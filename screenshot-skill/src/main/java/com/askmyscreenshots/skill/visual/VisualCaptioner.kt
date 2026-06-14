package com.askmyscreenshots.skill.visual

import com.askmyscreenshots.skill.ml.AnalyzedScreenshot
import com.askmyscreenshots.skill.ml.ScreenshotCategory
import java.util.Locale

data class VisualDescriptionDraft(
    val modelName: String,
    val modelVersion: String,
    val description: String,
    val confidence: Float,
    val status: String,
)

interface VisualCaptioner {
    val modelName: String
    val modelVersion: String
    fun describe(analysis: AnalyzedScreenshot): VisualDescriptionDraft?
}

/**
 * Lightweight local fallback that turns existing on-device visual labels, faces,
 * barcodes, app hints, and category signals into a searchable description.
 */
class HeuristicVisualCaptioner : VisualCaptioner {
    override val modelName: String = "mlkit-visual-summary"
    override val modelVersion: String = "2026-06-14"

    override fun describe(analysis: AnalyzedScreenshot): VisualDescriptionDraft? {
        val labels = analysis.visualLabels
            .filter { it.confidence >= 0.55f }
            .sortedByDescending { it.confidence }
            .map { it.label.cleanLabel() }
            .distinct()
            .take(8)
        val objectLabels = analysis.detectedObjects
            .flatMap { it.labels }
            .filter { it.confidence >= 0.45f }
            .sortedByDescending { it.confidence }
            .map { it.label.cleanLabel() }
            .distinct()
            .take(8)
        val pieces = buildList {
            analysis.appHint?.takeIf { it.isNotBlank() }?.let { add("app ${it.cleanLabel()}") }
            analysis.primaryCategory.takeUnless { it == ScreenshotCategory.UNKNOWN.value }?.let {
                add(it.replace('_', ' ').cleanLabel())
            }
            if (labels.isNotEmpty()) add("visual ${labels.joinToString(", ")}")
            if (objectLabels.isNotEmpty()) add("objects ${objectLabels.joinToString(", ")}")
            if (analysis.faces.isNotEmpty()) add("${analysis.faces.size} face${if (analysis.faces.size == 1) "" else "s"}")
            if (analysis.barcodes.isNotEmpty()) add("${analysis.barcodes.size} QR or barcode signal")
        }
        val description = pieces.joinToString(". ").trim()
        if (description.isBlank()) return null
        val confidence = (
            0.35f +
                labels.size.coerceAtMost(6) * 0.07f +
                objectLabels.size.coerceAtMost(5) * 0.05f +
                if (analysis.faces.isNotEmpty()) 0.08f else 0f +
                if (analysis.barcodes.isNotEmpty()) 0.08f else 0f
            ).coerceIn(0.35f, 0.86f)
        return VisualDescriptionDraft(
            modelName = modelName,
            modelVersion = modelVersion,
            description = description,
            confidence = confidence,
            status = "FALLBACK_LABEL_SUMMARY",
        )
    }
}

private fun String.cleanLabel(): String {
    return lowercase(Locale.US)
        .replace('_', ' ')
        .replace(Regex("""\s+"""), " ")
        .trim()
}
