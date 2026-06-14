package com.askmyscreenshots.skill.extract

import com.askmyscreenshots.skill.ml.DetectedEntityDraft
import com.askmyscreenshots.skill.ml.ScreenshotCategory
import com.askmyscreenshots.skill.ml.VisualLabelDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryClassifierTest {
    private val classifier = CategoryClassifier()

    @Test
    fun classifiesPaymentScreenshotsFromEntities() {
        val categories = classifier.classify(
            text = "Paid successfully to merchant",
            entities = listOf(entity("upi_id"), entity("amount")),
        )

        assertEquals(ScreenshotCategory.PAYMENTS.value, categories.first().category)
    }

    @Test
    fun classifiesBookingVoucherScreenshots() {
        val categories = classifier.classify(
            text = "Hotel booking voucher confirmation PNR AB123CD",
            entities = emptyList(),
        )

        assertTrue(categories.any { it.category == ScreenshotCategory.BOOKING_TRAVEL.value })
    }

    @Test
    fun doesNotClassifyAmountOnlySocialPostAsPayment() {
        val categories = classifier.classify(
            text = """
                LinkedIn post: Lightspeed partners said nearly 60 percent of India investments
                went into AI-native startups. They are focused on applied AI and infrastructure.
            """.trimIndent(),
            entities = listOf(
                entity(type = "amount", value = "$300", isSensitive = true),
                entity(type = "app", value = "linkedin", isSensitive = false),
            ),
        )

        assertFalse(categories.any { it.category == ScreenshotCategory.PAYMENTS.value })
        assertTrue(categories.any { it.category == ScreenshotCategory.SOCIAL.value })
        assertTrue(categories.any { it.category == ScreenshotCategory.AI_NEWS.value })
    }

    @Test
    fun doesNotClassifyLoosePanWordAsIdentityDocument() {
        val categories = classifier.classify(
            text = "Pan India marketing plan and expansion notes",
            entities = emptyList(),
        )

        assertFalse(categories.any { it.category == ScreenshotCategory.IDENTITY_DOCS.value })
    }

    @Test
    fun classifiesRealPanEntityAsIdentityDocument() {
        val categories = classifier.classify(
            text = "PAN card details",
            entities = listOf(entity(type = "pan", value = "ABCDE1234F")),
        )

        assertEquals(ScreenshotCategory.IDENTITY_DOCS.value, categories.first().category)
    }

    @Test
    fun doesNotMatchAiInsidePaid() {
        val categories = classifier.classify(
            text = "Paid successfully to merchant",
            entities = listOf(entity("amount")),
        )

        assertFalse(categories.any { it.category == ScreenshotCategory.AI_NEWS.value })
    }

    @Test
    fun amountAloneDoesNotCreatePaymentCategory() {
        val categories = classifier.classify(
            text = "Startup funding round valued at $1 trillion",
            entities = listOf(entity(type = "amount", value = "$1")),
        )

        assertFalse(categories.any { it.category == ScreenshotCategory.PAYMENTS.value })
    }

    @Test
    fun createsDynamicHealthCategoryFromOcrTopic() {
        val categories = classifier.classify(
            text = "Doctor appointment at Medanta hospital, prescription attached",
            entities = listOf(entity(type = "topic", value = "health", isSensitive = false)),
        )

        assertTrue(categories.any { it.category == "health" })
    }

    @Test
    fun createsDynamicFashionCategoryFromVisualLabel() {
        val categories = classifier.classify(
            text = "",
            entities = listOf(entity(type = "visual_object", value = "Fashion good", isSensitive = false)),
            labels = listOf(VisualLabelDraft(label = "Fashion good", confidence = 0.76f, labelIndex = 2)),
        )

        assertTrue(categories.any { it.category == "fashion" })
    }

    private fun entity(
        type: String,
        value: String = "value",
        isSensitive: Boolean = true,
    ): DetectedEntityDraft {
        return DetectedEntityDraft(
            type = type,
            value = value,
            normalizedValue = value.lowercase(),
            source = "OCR",
            confidence = 0.9f,
            isSensitive = isSensitive,
            evidence = value,
        )
    }
}
