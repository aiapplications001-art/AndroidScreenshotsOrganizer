package com.askmyscreenshots.skill.categories

import com.askmyscreenshots.skill.api.CategoryBucketType
import com.askmyscreenshots.skill.api.CategoryOverviewRequest
import com.askmyscreenshots.skill.data.AppSourceCount
import com.askmyscreenshots.skill.data.CategoryCount
import com.askmyscreenshots.skill.data.EntityTypeBucketCount
import com.askmyscreenshots.skill.data.ScreenshotEntity
import com.askmyscreenshots.skill.data.ScreenshotSkillDao
import com.askmyscreenshots.skill.data.VisualBucketCount
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class CategoryBrowserTest {
    @Test
    fun overviewBuildsRequestedBucketSections() = runTest {
        val dao = fakeDao(
            categoryCounts = listOf(
                CategoryCount(category = "unknown", count = 4),
                CategoryCount(category = "fashion", count = 3),
            ),
            appSourceCounts = listOf(AppSourceCount(source = "whatsapp", count = 2)),
            visualBucketCounts = listOf(
                VisualBucketCount(label = "text", count = 6),
                VisualBucketCount(label = "plant", count = 2),
            ),
            entityTypeCounts = listOf(
                EntityTypeBucketCount(type = "phone", count = 2, sensitiveCount = 2),
                EntityTypeBucketCount(type = "topic", count = 9, sensitiveCount = 0),
            ),
        )

        val overview = CategoryBrowser(dao).overview(
            CategoryOverviewRequest(maxBucketsPerSection = 8, sampleSize = 1),
        )

        assertEquals(listOf("Fashion"), overview.dynamicCategories.map { it.title })
        assertEquals(listOf("Whatsapp"), overview.appSources.map { it.title })
        assertEquals(listOf("Plant"), overview.visualLabels.map { it.title })
        assertEquals(listOf("Phone Numbers"), overview.entityTypes.map { it.title })
        assertTrue(overview.entityTypes.single().isSensitive)
        assertFalse(CategoryBucketType.entries.any { it.name == "CORE_CATEGORY" })
    }

    private fun fakeDao(
        categoryCounts: List<CategoryCount>,
        appSourceCounts: List<AppSourceCount>,
        visualBucketCounts: List<VisualBucketCount>,
        entityTypeCounts: List<EntityTypeBucketCount>,
    ): ScreenshotSkillDao {
        val sample = listOf(sampleScreenshot())
        return Proxy.newProxyInstance(
            ScreenshotSkillDao::class.java.classLoader,
            arrayOf(ScreenshotSkillDao::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "toString" -> "FakeCategoryDao"
                "indexedScreenshotCount" -> 7
                "categoryCounts" -> categoryCounts
                "appSourceCounts" -> appSourceCounts
                "visualBucketCounts" -> visualBucketCounts
                "entityTypeBucketCounts" -> entityTypeCounts
                "screenshotsForCategory",
                "screenshotsForAppSource",
                "screenshotsForVisualBucket",
                "screenshotsForEntityTypeBucket",
                -> sample
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as ScreenshotSkillDao
    }

    private fun sampleScreenshot(): ScreenshotEntity {
        return ScreenshotEntity(
            id = 1L,
            mediaStoreId = 1L,
            uri = "content://screenshots/1",
            displayName = "Screenshot_1.png",
            relativePath = "Pictures/Screenshots",
            bucketName = "Screenshots",
            dateTakenMillis = 1_700_000_000_000L,
            sizeBytes = 100L,
            mimeType = "image/png",
            width = 1080,
            height = 2400,
            indexedAtMillis = 1_700_000_000_000L,
            indexStatus = "INDEXED",
            languageTag = "en",
            category = "fashion",
            appHint = "whatsapp",
            ocrText = "Sample screenshot text",
            errorMessage = null,
        )
    }
}
