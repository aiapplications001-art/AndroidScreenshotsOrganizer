package com.askmyscreenshots.skill.semantic

import com.askmyscreenshots.skill.data.CategoryAssignmentEntity
import com.askmyscreenshots.skill.data.DetectedEntityEntity
import com.askmyscreenshots.skill.data.ScreenshotEntity
import com.askmyscreenshots.skill.data.VisualLabelEntity
import com.askmyscreenshots.skill.ml.AnalyzedScreenshot

object SemanticInputBuilder {
    fun fromAnalysis(
        displayName: String?,
        analysis: AnalyzedScreenshot,
        visualDescription: String?,
    ): String {
        return buildString {
            appendLine(displayName.orEmpty())
            appendLine("app:${analysis.appHint.orEmpty()}")
            appendLine("category:${analysis.primaryCategory}")
            appendLine(analysis.categories.joinToString(" ") { "category:${it.category}:${it.reason}" })
            appendLine(analysis.entities.joinToString(" ") { "${it.type}:${it.value} ${it.normalizedValue}" })
            appendLine(analysis.visualLabels.sortedByDescending { it.confidence }.take(12).joinToString(" ") { "visual:${it.label}" })
            appendLine(
                analysis.detectedObjects
                    .flatMap { it.labels }
                    .sortedByDescending { it.confidence }
                    .take(12)
                    .joinToString(" ") { "object:${it.label}" },
            )
            visualDescription?.takeIf { it.isNotBlank() }?.let { appendLine("caption:$it") }
            appendLine(analysis.ocrText.take(MAX_OCR_CHARS))
        }.trim()
    }

    fun fromStored(
        screenshot: ScreenshotEntity,
        entities: List<DetectedEntityEntity>,
        labels: List<VisualLabelEntity>,
        categories: List<CategoryAssignmentEntity>,
        visualDescription: String?,
        objectLabels: List<String> = emptyList(),
    ): String {
        return buildString {
            appendLine(screenshot.displayName.orEmpty())
            appendLine("app:${screenshot.appHint.orEmpty()}")
            appendLine("category:${screenshot.category}")
            appendLine(categories.joinToString(" ") { "category:${it.category}:${it.reason}" })
            appendLine(entities.joinToString(" ") { "${it.type}:${it.value} ${it.normalizedValue}" })
            appendLine(labels.sortedByDescending { it.confidence }.take(12).joinToString(" ") { "visual:${it.label}" })
            appendLine(objectLabels.take(12).joinToString(" ") { "object:$it" })
            visualDescription?.takeIf { it.isNotBlank() }?.let { appendLine("caption:$it") }
            appendLine(screenshot.ocrText.take(MAX_OCR_CHARS))
        }.trim()
    }

    private const val MAX_OCR_CHARS = 3_200
}
