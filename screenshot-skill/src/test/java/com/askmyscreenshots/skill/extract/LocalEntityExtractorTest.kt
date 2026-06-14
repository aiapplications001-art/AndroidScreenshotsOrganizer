package com.askmyscreenshots.skill.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEntityExtractorTest {
    private val extractor = LocalEntityExtractor()

    @Test
    fun extractsSensitiveIndianPaymentAndIdentityEntities() {
        val entities = extractor.extract(
            ocrText = """
                Paid ₹1,250.00 to Ravi Kumar
                UPI ravi.kumar@okhdfcbank
                Aadhaar 2345 6789 1234
                PAN ABCDE1234F
                IFSC HDFC0001234
            """.trimIndent(),
        )

        assertTrue(entities.any { it.type == "amount" && it.normalizedValue == "₹1250.00" })
        assertTrue(entities.any { it.type == "upi_id" && it.normalizedValue == "ravi.kumar@okhdfcbank" })
        assertTrue(entities.any { it.type == "aadhaar" && it.normalizedValue == "234567891234" })
        assertTrue(entities.any { it.type == "pan" && it.normalizedValue == "ABCDE1234F" })
        assertTrue(entities.any { it.type == "ifsc" && it.normalizedValue == "HDFC0001234" })
        assertTrue(entities.filter { it.type in setOf("upi_id", "aadhaar", "pan", "ifsc") }.all { it.isSensitive })
    }

    @Test
    fun validatesCardsWithLuhnBeforeStoringCardNumber() {
        val entities = extractor.extract(
            ocrText = "Card 4111 1111 1111 1111 failed. Ref 4111 1111 1111 1112 ignored.",
        )

        assertTrue(entities.any { it.type == "card_number" && it.normalizedValue == "4111111111111111" })
        assertFalse(entities.any { it.type == "card_number" && it.normalizedValue == "4111111111111112" })
    }

    @Test
    fun redactsDraftSensitiveEntities() {
        val text = "UPI ravi.kumar@okhdfcbank paid ₹1,250.00"
        val entities = extractor.extract(text)

        val redacted = PrivacyRedactor.redactDraftText(text, entities)

        assertEquals("UPI <UPI_ID> paid <AMOUNT>", redacted)
    }
}
