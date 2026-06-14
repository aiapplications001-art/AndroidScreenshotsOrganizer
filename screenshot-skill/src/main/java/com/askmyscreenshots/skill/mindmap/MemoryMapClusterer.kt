package com.askmyscreenshots.skill.mindmap

import com.askmyscreenshots.skill.api.MemoryCluster
import com.askmyscreenshots.skill.api.MemoryMapSummary
import com.askmyscreenshots.skill.api.MemoryScreenshotPreview
import com.askmyscreenshots.skill.api.MemorySignal
import com.askmyscreenshots.skill.api.MindMapGraph
import com.askmyscreenshots.skill.api.MindMapRequest
import com.askmyscreenshots.skill.data.MindMapEntityFeature
import com.askmyscreenshots.skill.data.MindMapLineFeature
import com.askmyscreenshots.skill.data.MindMapVisualFeature
import com.askmyscreenshots.skill.data.ScreenshotEntity
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.min

internal data class MemoryMapScreenshotInput(
    val screenshot: ScreenshotEntity,
    val entities: List<MindMapEntityFeature>,
    val visualLabels: List<MindMapVisualFeature>,
    val lines: List<MindMapLineFeature>,
)

internal class MemoryMapClusterer {
    fun build(
        inputs: List<MemoryMapScreenshotInput>,
        request: MindMapRequest,
    ): MindMapGraph {
        val screenshots = inputs.take(request.maxScreenshots.coerceIn(1, 1_000))
        val featureSets = screenshots.associate { input ->
            input.screenshot.id to featuresFor(input)
        }
        val previews = screenshots.associate { input ->
            input.screenshot.id to previewFor(input)
        }
        val featureStats = featureSets.values
            .flatten()
            .groupBy { it.id }
            .mapValues { (_, features) ->
                FeatureStat(
                    feature = features.maxBy { it.weight },
                    screenshotIds = features.map { it.screenshotId }.distinct(),
                )
            }

        val topSignals = featureStats.values
            .filter { it.feature.canSurface }
            .sortedWith(compareByDescending<FeatureStat> { it.score(screenshots.size) }
                .thenBy { it.feature.label.lowercase() })
            .take(request.maxSignals.coerceIn(1, 80))
            .map { it.toSignal(previews) }

        val clusters = buildClusters(
            screenshots = screenshots,
            featureSets = featureSets,
            featureStats = featureStats,
            previews = previews,
            maxClusters = request.maxClusters.coerceIn(1, 24),
        )

        return MindMapGraph(
            summary = MemoryMapSummary(
                indexedScreenshotCount = screenshots.size,
                clusterCount = clusters.size,
                signalCount = topSignals.size,
                startMillis = request.dateRange?.startMillis,
                endMillisExclusive = request.dateRange?.endMillisExclusive,
            ),
            clusters = clusters,
            topSignals = topSignals,
            generatedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun buildClusters(
        screenshots: List<MemoryMapScreenshotInput>,
        featureSets: Map<Long, List<Feature>>,
        featureStats: Map<String, FeatureStat>,
        previews: Map<Long, MemoryScreenshotPreview>,
        maxClusters: Int,
    ): List<MemoryCluster> {
        val totalScreenshots = screenshots.size.coerceAtLeast(1)
        val seeds = featureStats.values
            .filter { stat ->
                stat.feature.canSeed &&
                    (stat.screenshotIds.size >= 2 || stat.feature.isHighIntent)
            }
            .sortedWith(compareByDescending<FeatureStat> { it.score(totalScreenshots) }
                .thenBy { it.feature.label.lowercase() })

        val clusters = mutableListOf<MemoryCluster>()
        for (seed in seeds) {
            val seedMembers = seed.screenshotIds.toSet()
            val seedFeatures = topFeaturesForMembers(featureSets, seedMembers, totalScreenshots)
            val expandedMembers = screenshots
                .map { it.screenshot.id }
                .filter { screenshotId ->
                    val features = featureSets[screenshotId].orEmpty()
                    screenshotId in seedMembers || similarity(features, seedFeatures) >= SIMILARITY_THRESHOLD
                }
                .toSet()
            val members = expandedMembers.ifEmpty { seedMembers }
            if (members.size < 2 && !seed.feature.isHighIntent) continue
            if (clusters.any { overlapRatio(it.screenshotIds, members) >= MAX_CLUSTER_OVERLAP }) continue

            clusters += clusterFor(
                members = members,
                featureSets = featureSets,
                previews = previews,
                totalScreenshots = totalScreenshots,
            )
            if (clusters.size >= maxClusters) break
        }

        if (clusters.isEmpty() && screenshots.isNotEmpty()) {
            val memberIds = screenshots.take(8).map { it.screenshot.id }.toSet()
            clusters += clusterFor(
                members = memberIds,
                featureSets = featureSets,
                previews = previews,
                totalScreenshots = totalScreenshots,
            )
        }

        return clusters
            .sortedWith(compareByDescending<MemoryCluster> { it.screenshotCount }
                .thenByDescending { it.confidence }
                .thenBy { it.title.lowercase() })
    }

    private fun clusterFor(
        members: Set<Long>,
        featureSets: Map<Long, List<Feature>>,
        previews: Map<Long, MemoryScreenshotPreview>,
        totalScreenshots: Int,
    ): MemoryCluster {
        val topFeatures = topFeaturesForMembers(featureSets, members, totalScreenshots)
            .filter { it.canSurface }
            .take(8)
        val signals = topFeatures.map { feature ->
            feature.toSignal(
                screenshotIds = members
                    .filter { screenshotId -> featureSets[screenshotId].orEmpty().any { it.id == feature.id } },
                previews = previews,
            )
        }
        val memberPreviews = members
            .mapNotNull { previews[it] }
            .sortedByDescending { it.takenAtMillis ?: 0L }
        val title = titleFor(signals, memberPreviews)
        val summary = summaryFor(signals, memberPreviews.size)
        val start = memberPreviews.mapNotNull { it.takenAtMillis }.minOrNull()
        val end = memberPreviews.mapNotNull { it.takenAtMillis }.maxOrNull()?.plus(1L)
        val confidence = (0.42f + min(0.43f, signals.take(4).sumOf { it.screenshotCount }.toFloat() / (totalScreenshots * 4f)))
            .coerceIn(0.42f, 0.95f)

        return MemoryCluster(
            id = stableId("cluster", members.sorted().joinToString(":") + ":" + signals.take(3).joinToString(":") { it.id }),
            title = title,
            summary = summary,
            screenshotCount = memberPreviews.size,
            topSignals = signals,
            representativeScreenshots = memberPreviews.take(24),
            screenshotIds = members.sorted(),
            startMillis = start,
            endMillisExclusive = end,
            confidence = confidence,
            askQuery = "Show me screenshots about ${signals.take(3).joinToString(" ") { it.label }.ifBlank { title }}".trim(),
        )
    }

    private fun topFeaturesForMembers(
        featureSets: Map<Long, List<Feature>>,
        members: Set<Long>,
        totalScreenshots: Int,
    ): List<Feature> {
        return members
            .flatMap { featureSets[it].orEmpty() }
            .groupBy { it.id }
            .map { (_, features) ->
                val feature = features.maxBy { it.weight }
                val coverage = features.map { it.screenshotId }.distinct().size
                feature.copy(weight = feature.weight + coverage)
            }
            .sortedWith(compareByDescending<Feature> { it.weight }.thenBy { it.label.lowercase() })
    }

    private fun featuresFor(input: MemoryMapScreenshotInput): List<Feature> {
        val screenshotId = input.screenshot.id
        val features = linkedMapOf<String, Feature>()

        fun add(feature: Feature) {
            if (feature.normalizedValue.isBlank() || feature.label.isBlank()) return
            if (feature.normalizedValue == feature.type) return
            features[feature.id] = feature
        }

        input.screenshot.appHint
            ?.takeIf { it.isNotBlank() }
            ?.let {
                add(
                    Feature(
                        screenshotId = screenshotId,
                        type = "app",
                        value = it.trim(),
                        normalizedValue = it.normalizeFeatureValue(),
                        label = it.toDisplayLabel(),
                        isSensitive = false,
                        weight = 4.2f,
                        canSeed = true,
                        canSurface = true,
                    ),
                )
            }

        input.entities.forEach { entity ->
            if (entity.type in LOW_VALUE_ENTITY_TYPES) return@forEach
            val value = entity.value.trim()
            val normalized = entity.normalizedValue.takeIf { it.isNotBlank() } ?: value
            if (value.isBlank() || normalized.normalizeFeatureValue() in LOW_VALUE_NORMALIZED_VALUES) return@forEach
            val type = if (entity.type == "flight_hint" || entity.type == "train_hint") "topic" else entity.type
            add(
                Feature(
                    screenshotId = screenshotId,
                    type = type,
                    value = value,
                    normalizedValue = normalized.normalizeFeatureValue(),
                    label = entityLabel(type, value),
                    isSensitive = entity.isSensitive,
                    weight = entityTypeWeight(type) + entity.confidence,
                    canSeed = type !in LOW_VALUE_ENTITY_TYPES,
                    canSurface = true,
                ),
            )
        }

        input.visualLabels
            .filter { it.confidence >= 0.68f }
            .forEach { label ->
                val normalized = label.label.normalizeFeatureValue()
                if (normalized in LOW_VALUE_NORMALIZED_VALUES || normalized.length < 3) return@forEach
                add(
                    Feature(
                        screenshotId = screenshotId,
                        type = "visual",
                        value = label.label,
                        normalizedValue = normalized,
                        label = label.label.toDisplayLabel(),
                        isSensitive = false,
                        weight = 1.2f + label.confidence,
                        canSeed = false,
                        canSurface = true,
                    ),
                )
            }

        keyPhrases(input.lines.map { it.text }.ifEmpty { input.screenshot.ocrText.lines() })
            .forEach { phrase ->
                add(
                    Feature(
                        screenshotId = screenshotId,
                        type = "topic",
                        value = phrase,
                        normalizedValue = phrase.normalizeFeatureValue(),
                        label = phrase.toDisplayLabel(),
                        isSensitive = false,
                        weight = 2.0f,
                        canSeed = true,
                        canSurface = true,
                    ),
                )
            }

        return features.values.toList()
    }

    private fun keyPhrases(lines: List<String>): List<String> {
        val tokens = lines
            .take(10)
            .flatMap { WORD.findAll(it.lowercase()).map { match -> match.value } }
            .map { it.trim('_', '-') }
            .filter { token ->
                token.length >= 3 &&
                    token !in STOP_WORDS &&
                    token !in LOW_VALUE_NORMALIZED_VALUES &&
                    !token.all(Char::isDigit)
            }
        val phrases = mutableListOf<String>()
        tokens.windowed(2).forEach { pair ->
            val phrase = pair.joinToString(" ")
            if (phrase.length in 7..42) phrases += phrase
        }
        tokens.windowed(3).forEach { trio ->
            val phrase = trio.joinToString(" ")
            if (phrase.length in 12..54) phrases += phrase
        }
        return phrases
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(5)
    }

    private fun previewFor(input: MemoryMapScreenshotInput): MemoryScreenshotPreview {
        val snippet = input.lines
            .sortedBy { it.lineIndex }
            .map { it.text.cleanWhitespace() }
            .firstOrNull { it.length >= 12 }
            ?: input.screenshot.ocrText.cleanWhitespace()
        return MemoryScreenshotPreview(
            id = input.screenshot.id,
            uri = input.screenshot.uri,
            title = input.screenshot.displayName?.takeIf { it.isNotBlank() }
                ?: input.screenshot.appHint?.toDisplayLabel()
                ?: "Screenshot ${input.screenshot.id}",
            takenAtMillis = input.screenshot.dateTakenMillis,
            snippet = snippet.take(MAX_SNIPPET_CHARS),
            appHint = input.screenshot.appHint,
            width = input.screenshot.width,
            height = input.screenshot.height,
        )
    }

    private fun similarity(features: List<Feature>, clusterFeatures: List<Feature>): Float {
        if (features.isEmpty() || clusterFeatures.isEmpty()) return 0f
        val featureIds = features.map { it.id }.toSet()
        val clusterIds = clusterFeatures.take(8).map { it.id }.toSet()
        val shared = featureIds.intersect(clusterIds).size
        if (shared < 2) return 0f
        return shared.toFloat() / min(featureIds.size, clusterIds.size).coerceAtLeast(1).toFloat()
    }

    private fun overlapRatio(existing: List<Long>, candidate: Set<Long>): Float {
        if (existing.isEmpty() || candidate.isEmpty()) return 0f
        val shared = existing.toSet().intersect(candidate).size
        return shared.toFloat() / min(existing.size, candidate.size).toFloat()
    }

    private fun titleFor(signals: List<MemorySignal>, previews: List<MemoryScreenshotPreview>): String {
        val chosen = signals
            .filter { it.type != "visual" }
            .take(3)
            .ifEmpty { signals.take(2) }
        if (chosen.isNotEmpty()) {
            return chosen.joinToString(" and ") { it.label }.take(MAX_TITLE_CHARS)
        }
        return previews.firstOrNull()?.appHint?.toDisplayLabel()
            ?: "Recent screenshot memory"
    }

    private fun summaryFor(signals: List<MemorySignal>, screenshotCount: Int): String {
        val signalText = signals.take(4).joinToString(", ") { it.label }
        return if (signalText.isBlank()) {
            "$screenshotCount screenshots from this date range."
        } else {
            "$screenshotCount screenshots connected by $signalText."
        }
    }

    private fun FeatureStat.score(totalScreenshots: Int): Float {
        val frequency = screenshotIds.size.toFloat() / totalScreenshots.coerceAtLeast(1).toFloat()
        val highFrequencyPenalty = if (frequency > 0.34f && feature.type in setOf("app", "topic", "visual")) {
            0.45f
        } else {
            1f
        }
        return feature.weight * screenshotIds.size * highFrequencyPenalty
    }

    private fun FeatureStat.toSignal(previews: Map<Long, MemoryScreenshotPreview>): MemorySignal {
        return feature.toSignal(screenshotIds, previews)
    }

    private fun Feature.toSignal(
        screenshotIds: List<Long>,
        previews: Map<Long, MemoryScreenshotPreview>,
    ): MemorySignal {
        val sortedIds = screenshotIds.distinct().sorted()
        return MemorySignal(
            id = id,
            type = type,
            label = label,
            value = value,
            screenshotCount = sortedIds.size,
            isSensitive = isSensitive,
            screenshotIds = sortedIds,
            representativeScreenshots = sortedIds
                .mapNotNull { previews[it] }
                .sortedByDescending { it.takenAtMillis ?: 0L }
                .take(16),
        )
    }

    private data class FeatureStat(
        val feature: Feature,
        val screenshotIds: List<Long>,
    )

    private data class Feature(
        val screenshotId: Long,
        val type: String,
        val value: String,
        val normalizedValue: String,
        val label: String,
        val isSensitive: Boolean,
        val weight: Float,
        val canSeed: Boolean,
        val canSurface: Boolean,
    ) {
        val id: String = stableId("signal", "$type:$normalizedValue")
        val isHighIntent: Boolean = type in HIGH_INTENT_TYPES
    }

    companion object {
        private const val SIMILARITY_THRESHOLD = 0.34f
        private const val MAX_CLUSTER_OVERLAP = 0.76f
        private const val MAX_SNIPPET_CHARS = 150
        private const val MAX_TITLE_CHARS = 64
        private val WORD = Regex("""[a-zA-Z][a-zA-Z0-9_+\-.]{2,}""")
        private val LOW_VALUE_ENTITY_TYPES = setOf("amount", "date")
        private val HIGH_INTENT_TYPES = setOf(
            "phone",
            "email",
            "upi_id",
            "person_name",
            "booking_id",
            "order_id",
            "pan",
            "aadhaar",
            "account_number",
            "card_number",
            "url",
            "topic",
        )
        private val LOW_VALUE_NORMALIZED_VALUES = setOf(
            "amount",
            "date",
            "time",
            "today",
            "tomorrow",
            "yesterday",
            "screenshot",
            "screenshots",
            "image",
            "photo",
            "null",
            "unknown",
            "android",
        )
        private val STOP_WORDS = setOf(
            "the",
            "and",
            "for",
            "with",
            "this",
            "that",
            "from",
            "your",
            "you",
            "are",
            "was",
            "were",
            "have",
            "has",
            "not",
            "but",
            "all",
            "can",
            "will",
            "just",
            "more",
            "view",
            "open",
            "done",
            "back",
            "next",
            "share",
            "send",
            "copy",
            "edit",
            "new",
            "old",
            "now",
            "get",
            "got",
        )
    }
}

private fun entityTypeWeight(type: String): Float {
    return when (type) {
        "person_name" -> 4.8f
        "phone", "email", "upi_id" -> 4.6f
        "booking_id", "order_id", "pan", "aadhaar", "account_number", "card_number" -> 4.4f
        "app" -> 4.0f
        "topic" -> 3.2f
        "url" -> 3.0f
        else -> 2.4f
    }
}

private fun entityLabel(type: String, value: String): String {
    return when (type) {
        "app", "topic", "visual" -> value.toDisplayLabel()
        "upi_id" -> value
        "person_name" -> value
        "booking_id" -> "Booking $value"
        "order_id" -> "Order $value"
        "account_number" -> "Account $value"
        "card_number" -> "Card $value"
        else -> value
    }.cleanWhitespace()
}

private fun String.normalizeFeatureValue(): String {
    return lowercase(Locale.US)
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trim('.', ',', ':', ';', '-', '_')
}

private fun String.toDisplayLabel(): String {
    return cleanWhitespace()
        .split(' ')
        .joinToString(" ") { word ->
            if (word.length <= 2 && word.all { it.isUpperCase() }) {
                word
            } else {
                word.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.US) else char.toString()
                }
            }
        }
}

private fun String.cleanWhitespace(): String {
    return replace(Regex("""\s+"""), " ").trim()
}

private fun stableId(prefix: String, value: String): String {
    val digest = MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "$prefix:$digest"
}
