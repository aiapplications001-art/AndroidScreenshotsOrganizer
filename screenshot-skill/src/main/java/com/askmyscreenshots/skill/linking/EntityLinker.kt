package com.askmyscreenshots.skill.linking

import com.askmyscreenshots.skill.ml.DetectedEntityDraft

data class EntityLinkDraft(
    val leftType: String,
    val leftValue: String,
    val leftNormalizedValue: String,
    val rightType: String,
    val rightValue: String,
    val rightNormalizedValue: String,
    val confidence: Float,
    val source: String,
)

class EntityLinker {
    fun linksFor(entities: List<DetectedEntityDraft>): List<EntityLinkDraft> {
        val highValue = entities
            .filter { it.type in LINKABLE_ENTITY_TYPES }
            .filter { it.normalizedValue.isNotBlank() }
            .distinctBy { it.type to it.normalizedValue }
            .take(8)
        if (highValue.size < 2) return emptyList()

        return highValue.flatMapIndexed { index, left ->
            highValue.drop(index + 1).mapNotNull { right ->
                if (!shouldLink(left, right)) return@mapNotNull null
                val confidence = ((left.confidence + right.confidence) / 2f).coerceIn(0.25f, 0.95f)
                EntityLinkDraft(
                    leftType = left.type,
                    leftValue = left.value,
                    leftNormalizedValue = left.normalizedValue,
                    rightType = right.type,
                    rightValue = right.value,
                    rightNormalizedValue = right.normalizedValue,
                    confidence = confidence,
                    source = "co_occurrence",
                )
            }
        }
    }

    private fun shouldLink(left: DetectedEntityDraft, right: DetectedEntityDraft): Boolean {
        if (left.type == right.type && left.normalizedValue == right.normalizedValue) return false
        if (left.type == "amount" || right.type == "amount") return false
        val types = setOf(left.type, right.type)
        if ("app" in types && types.none { it in CONTACT_OR_ID_TYPES }) return false
        return true
    }

    companion object {
        val LINKABLE_ENTITY_TYPES = setOf(
            "person_name",
            "phone",
            "email",
            "upi_id",
            "url",
            "domain",
            "order_id",
            "booking_id",
            "invoice_id",
            "tracking_id",
            "transaction_id",
            "counterparty",
            "aadhaar",
            "pan",
            "passport",
            "social_handle",
            "hashtag",
            "topic",
            "app",
        )
        private val CONTACT_OR_ID_TYPES = setOf(
            "person_name",
            "phone",
            "email",
            "upi_id",
            "order_id",
            "booking_id",
            "invoice_id",
            "tracking_id",
            "transaction_id",
            "counterparty",
            "aadhaar",
            "pan",
            "passport",
        )
    }
}
