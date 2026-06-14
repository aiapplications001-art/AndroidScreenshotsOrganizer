package com.askmyscreenshots.skill.semantic

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sqrt

data class TextEmbedding(
    val modelName: String,
    val modelVersion: String,
    val inputHash: String,
    val dimension: Int,
    val vector: FloatArray,
)

interface TextEmbedder {
    val modelName: String
    val modelVersion: String
    val dimension: Int
    fun embed(text: String): TextEmbedding?
}

/**
 * Dependency-light local fallback used until a downloaded text embedding model is
 * available. It behaves like a semantic retrieval signal by normalizing common
 * screenshot-memory concepts before hashing tokens into a fixed vector.
 */
class HashingTextEmbedder(
    override val dimension: Int = DEFAULT_DIMENSION,
    private val name: String = "local-hashing-text-embedder",
    private val version: String = "2026-06-14",
) : TextEmbedder {
    override val modelName: String = name
    override val modelVersion: String = version

    override fun embed(text: String): TextEmbedding? {
        val tokens = normalizedTokens(text)
        if (tokens.isEmpty()) return null
        val vector = FloatArray(dimension)
        tokens.forEachIndexed { index, token ->
            val hash = token.stableHash()
            val bucket = (hash and Int.MAX_VALUE) % dimension
            val sign = if ((hash ushr 31) == 0) 1f else -1f
            val weight = when {
                token.contains(':') -> 1.35f
                index < 24 -> 1.15f
                else -> 1f
            }
            vector[bucket] += sign * weight
        }
        vector.normalizeInPlace()
        return TextEmbedding(
            modelName = modelName,
            modelVersion = modelVersion,
            inputHash = sha256(text),
            dimension = dimension,
            vector = vector,
        )
    }

    private fun normalizedTokens(text: String): List<String> {
        val raw = TOKEN.findAll(text.lowercase(Locale.US))
            .map { it.value.trim('_', '-', '.') }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .take(260)
            .toList()
        if (raw.isEmpty()) return emptyList()
        return buildList {
            raw.forEach { token ->
                add(token)
                CONCEPTS[token]?.forEach(::add)
            }
            raw.windowed(2).forEach { pair ->
                val phrase = pair.joinToString(" ")
                add(phrase)
                CONCEPTS[phrase]?.forEach(::add)
            }
            raw.windowed(3).forEach { triple ->
                val phrase = triple.joinToString(" ")
                CONCEPTS[phrase]?.forEach(::add)
            }
        }
    }

    companion object {
        const val DEFAULT_DIMENSION = 384

        private val TOKEN = Regex("""[\p{L}\p{Nd}@._+-]{2,}""")
        private val STOP_WORDS = setOf(
            "the", "and", "for", "with", "this", "that", "from", "your", "you", "are",
            "was", "were", "have", "has", "not", "but", "all", "can", "will", "just",
            "more", "view", "open", "done", "back", "next", "share", "send", "copy",
            "edit", "new", "old", "now", "get", "got", "image", "photo", "android",
            "screenshot", "screenshots",
        )
        private val CONCEPTS = mapOf(
            "pay" to listOf("payment", "money", "upi", "transaction"),
            "paid" to listOf("payment", "money", "upi", "transaction"),
            "sent" to listOf("payment", "money", "transfer"),
            "received" to listOf("payment", "money", "credit"),
            "gpay" to listOf("payment", "upi", "google pay"),
            "phonepe" to listOf("payment", "upi"),
            "paytm" to listOf("payment", "upi", "wallet"),
            "food" to listOf("restaurant", "swiggy", "zomato", "meal", "order"),
            "restaurant" to listOf("food", "meal"),
            "travel" to listOf("booking", "ticket", "hotel", "flight", "train"),
            "trip" to listOf("travel", "booking", "hotel", "flight"),
            "aadhaar" to listOf("identity", "id", "government"),
            "aadhar" to listOf("aadhaar", "identity", "id", "government"),
            "pan" to listOf("identity", "tax", "government"),
            "linkedin" to listOf("social", "professional", "post"),
            "whatsapp" to listOf("chat", "message", "contact"),
            "texted" to listOf("chat", "message", "contact"),
            "message" to listOf("chat", "conversation", "contact"),
            "humans" to listOf("people", "person", "contact"),
            "people" to listOf("person", "contact"),
            "doctor" to listOf("health", "appointment", "medical"),
            "hospital" to listOf("health", "appointment", "medical"),
            "medanta" to listOf("health", "hospital", "medical"),
            "refund" to listOf("pending", "payment", "return"),
            "due" to listOf("pending", "bill", "expiry"),
            "expiry" to listOf("pending", "renewal", "date"),
        )
    }
}

fun FloatArray.toEmbeddingBlob(): ByteArray {
    val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    forEach(buffer::putFloat)
    return buffer.array()
}

fun ByteArray.toFloatVector(): FloatArray {
    if (isEmpty() || size % Float.SIZE_BYTES != 0) return FloatArray(0)
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / Float.SIZE_BYTES) { buffer.float }
}

fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
    if (left.isEmpty() || right.isEmpty() || left.size != right.size) return 0f
    var dot = 0f
    var leftNorm = 0f
    var rightNorm = 0f
    for (index in left.indices) {
        dot += left[index] * right[index]
        leftNorm += left[index] * left[index]
        rightNorm += right[index] * right[index]
    }
    val denominator = sqrt(leftNorm) * sqrt(rightNorm)
    return if (denominator <= 0f) 0f else dot / denominator
}

private fun FloatArray.normalizeInPlace() {
    var norm = 0f
    forEach { norm += it * it }
    val denominator = sqrt(norm)
    if (denominator <= 0f) return
    indices.forEach { index -> this[index] = this[index] / denominator }
}

private fun String.stableHash(): Int {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return ByteBuffer.wrap(digest, 0, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int
}

private fun sha256(text: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
