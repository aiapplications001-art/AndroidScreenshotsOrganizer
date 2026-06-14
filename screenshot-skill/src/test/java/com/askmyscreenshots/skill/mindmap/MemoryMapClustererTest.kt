package com.askmyscreenshots.skill.mindmap

import com.askmyscreenshots.skill.api.MindMapRequest
import com.askmyscreenshots.skill.data.MindMapEntityFeature
import com.askmyscreenshots.skill.data.MindMapLineFeature
import com.askmyscreenshots.skill.data.ScreenshotEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryMapClustererTest {
    @Test
    fun clustersScreenshotsByDynamicSharedSignals() {
        val graph = MemoryMapClusterer().build(
            inputs = listOf(
                input(1, app = "linkedin", lines = listOf("OpenAI startup funding market update")),
                input(2, app = "linkedin", lines = listOf("Startup funding and AI product launch")),
                input(3, app = "gmail", lines = listOf("Flight booking confirmation voucher")),
            ),
            request = MindMapRequest(maxClusters = 6),
        )

        assertTrue(graph.clusters.any { cluster ->
            cluster.screenshotCount >= 2 &&
                cluster.topSignals.any { it.label.contains("Startup", ignoreCase = true) || it.label.contains("Linkedin") }
        })
    }

    @Test
    fun filtersAmountOnlySignalsFromTopSignals() {
        val graph = MemoryMapClusterer().build(
            inputs = listOf(
                input(
                    id = 1,
                    entities = listOf(entity(1, type = "amount", value = "amount", normalized = "amount")),
                    lines = listOf("Paid amount successfully"),
                ),
                input(
                    id = 2,
                    entities = listOf(entity(2, type = "amount", value = "INR 500", normalized = "inr500")),
                    lines = listOf("Amount debited from account"),
                ),
            ),
            request = MindMapRequest(),
        )

        assertFalse(graph.topSignals.any { it.type == "amount" || it.label.equals("amount", ignoreCase = true) })
        assertFalse(graph.clusters.flatMap { it.topSignals }.any { it.type == "amount" })
    }

    @Test
    fun keepsSensitiveValuesVisibleInLocalOutput() {
        val phone = "9876543210"
        val graph = MemoryMapClusterer().build(
            inputs = listOf(
                input(
                    id = 1,
                    entities = listOf(entity(1, type = "phone", value = phone, normalized = phone, sensitive = true)),
                    lines = listOf("Call customer $phone"),
                ),
            ),
            request = MindMapRequest(),
        )

        assertTrue(graph.topSignals.any { it.isSensitive && it.label.contains(phone) })
    }

    @Test
    fun producesStableClusterIds() {
        val inputs = listOf(
            input(1, app = "whatsapp", lines = listOf("Visa appointment documents")),
            input(2, app = "whatsapp", lines = listOf("Visa appointment payment")),
        )

        val first = MemoryMapClusterer().build(inputs, MindMapRequest())
        val second = MemoryMapClusterer().build(inputs, MindMapRequest())

        assertEquals(first.clusters.map { it.id }, second.clusters.map { it.id })
        assertEquals(first.topSignals.map { it.id }, second.topSignals.map { it.id })
    }

    private fun input(
        id: Long,
        app: String? = null,
        lines: List<String> = emptyList(),
        entities: List<MindMapEntityFeature> = emptyList(),
    ): MemoryMapScreenshotInput {
        return MemoryMapScreenshotInput(
            screenshot = ScreenshotEntity(
                id = id,
                mediaStoreId = id,
                uri = "content://screenshots/$id",
                displayName = "Screenshot_$id.png",
                relativePath = "Pictures/Screenshots",
                bucketName = "Screenshots",
                dateTakenMillis = 1_700_000_000_000L + id,
                sizeBytes = 100L,
                mimeType = "image/png",
                width = 1080,
                height = 2400,
                indexedAtMillis = 1_700_000_000_000L,
                indexStatus = "INDEXED",
                languageTag = "en",
                category = "unknown",
                appHint = app,
                ocrText = lines.joinToString("\n"),
                errorMessage = null,
            ),
            entities = entities,
            visualLabels = emptyList(),
            lines = lines.mapIndexed { index, text ->
                MindMapLineFeature(
                    screenshotId = id,
                    text = text,
                    lineIndex = index,
                )
            },
        )
    }

    private fun entity(
        screenshotId: Long,
        type: String,
        value: String,
        normalized: String,
        sensitive: Boolean = false,
    ): MindMapEntityFeature {
        return MindMapEntityFeature(
            screenshotId = screenshotId,
            type = type,
            value = value,
            normalizedValue = normalized,
            confidence = 0.9f,
            isSensitive = sensitive,
        )
    }
}
