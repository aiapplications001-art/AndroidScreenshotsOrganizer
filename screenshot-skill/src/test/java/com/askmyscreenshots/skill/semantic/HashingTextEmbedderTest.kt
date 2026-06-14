package com.askmyscreenshots.skill.semantic

import org.junit.Assert.assertTrue
import org.junit.Test

class HashingTextEmbedderTest {
    private val embedder = HashingTextEmbedder()

    @Test
    fun relatedPaymentLanguageHasPositiveSimilarity() {
        val query = embedder.embed("payments done through upi")!!
        val screenshot = embedder.embed("GPay transaction sent money to Rahul")!!

        assertTrue(cosineSimilarity(query.vector, screenshot.vector) > 0.05f)
    }

    @Test
    fun unrelatedLanguageScoresLowerThanRelatedLanguage() {
        val query = embedder.embed("find food orders")!!
        val related = embedder.embed("restaurant receipt from Zomato")!!
        val unrelated = embedder.embed("passport identity document")!!

        assertTrue(cosineSimilarity(query.vector, related.vector) > cosineSimilarity(query.vector, unrelated.vector))
    }
}
