package com.askmyscreenshots.skill.extract

import com.askmyscreenshots.skill.data.DetectedEntityEntity
import com.askmyscreenshots.skill.ml.DetectedEntityDraft

object PrivacyRedactor {
    private val redactionByType = mapOf(
        "aadhaar" to "<AADHAAR>",
        "pan" to "<PAN>",
        "phone" to "<PHONE>",
        "email" to "<EMAIL>",
        "upi_id" to "<UPI_ID>",
        "amount" to "<AMOUNT>",
        "account_number" to "<ACCOUNT_NUMBER>",
        "card_number" to "<CARD_NUMBER>",
        "booking_id" to "<BOOKING_ID>",
        "order_id" to "<ORDER_ID>",
        "person_name" to "<PERSON_NAME>",
    )

    fun redactText(text: String, entities: List<DetectedEntityEntity>): String {
        return entities.fold(text) { redacted, entity ->
            if (entity.isSensitive) {
                redacted.replace(entity.value, markerFor(entity.type))
            } else {
                redacted
            }
        }
    }

    fun redactDraftText(text: String, entities: List<DetectedEntityDraft>): String {
        return entities.fold(text) { redacted, entity ->
            if (entity.isSensitive) {
                redacted.replace(entity.value, markerFor(entity.type))
            } else {
                redacted
            }
        }
    }

    fun markerFor(type: String): String {
        return redactionByType[type] ?: "<SENSITIVE>"
    }
}
