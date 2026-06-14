package com.askmyscreenshots.skill.search

import com.askmyscreenshots.skill.ml.ScreenshotCategory
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class QueryPlannerTest {
    private val planner = QueryPlanner(ZoneId.of("UTC"))

    @Test
    fun mapsFindAadhaarToIdentityCategoryAndEntity() {
        val plan = planner.plan(
            com.askmyscreenshots.skill.api.SearchRequest("find my aadhaar"),
        )

        assertTrue(plan.categories.contains(ScreenshotCategory.IDENTITY_DOCS.value))
        assertTrue(plan.entityTypes.contains("aadhaar"))
    }

    @Test
    fun mapsWhomDidIPayLastWeekToPaymentEntitiesAndDateRange() {
        val plan = planner.plan(
            com.askmyscreenshots.skill.api.SearchRequest("whom all did I pay last week"),
        )

        assertTrue(plan.categories.contains(ScreenshotCategory.PAYMENTS.value))
        assertTrue(plan.entityTypes.contains("upi_id"))
        assertTrue(plan.entityTypes.contains("person_name"))
        assertTrue(plan.dateRange != null)
    }

    @Test
    fun createsSafeFtsPrefixQuery() {
        val fts = planner.toFtsQuery("show my insta screenshots!!")

        assertTrue(fts!!.contains("insta*"))
        assertTrue(fts.contains("screenshots*"))
    }
}
