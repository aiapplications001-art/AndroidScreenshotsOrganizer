package com.askmyscreenshots.skill.search

import com.askmyscreenshots.skill.api.AskMode
import com.askmyscreenshots.skill.api.SearchRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class AskModeClassifierTest {
    private val planner = QueryPlanner(ZoneId.of("UTC"))

    @Test
    fun mapsRepresentativeQuestionTaxonomyToAskModes() {
        val examples = listOf(
            "UTR number" to AskMode.EXACT_VALUE,
            "rent payment proof" to AskMode.PROOF,
            "whom all did I pay last week" to AskMode.ENTITY_GROUP,
            "one common theme do you see in my Indian screenshots" to AskMode.THEME_SUMMARY,
            "show whatsapp chats" to AskMode.APP_SOURCE,
            "latest refund screenshot" to AskMode.TIMELINE,
            "pending bills and renewals" to AskMode.PENDING_ACTION,
            "which hotel was cheaper" to AskMode.COMPARISON,
            "screenshots with Aadhaar that I should hide" to AskMode.PRIVACY_CLEANUP,
            "how much did I spend on subscriptions" to AskMode.ANALYTICS,
            "that cafe with plants" to AskMode.FUZZY_VISUAL,
            "show my Medanta history" to AskMode.TIMELINE,
        )

        examples.forEach { (query, expectedMode) ->
            val plan = planner.plan(SearchRequest(query))

            assertEquals(query, expectedMode, AskModeClassifier.infer(query, plan))
        }
    }

    @Test
    fun mapsAmountCounterpartyRecallToEntityGrouping() {
        val query = "where did I pay 1200"
        val plan = planner.plan(SearchRequest(query))

        assertEquals(AskMode.ENTITY_GROUP, AskModeClassifier.infer(query, plan))
    }
}
