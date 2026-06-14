package com.askmyscreenshots.skill.ml

import com.askmyscreenshots.skill.media.ScreenshotCandidate

interface ScreenshotAnalyzer {
    suspend fun analyze(candidate: ScreenshotCandidate): AnalyzedScreenshot
}

data class AnalyzedScreenshot(
    val ocrText: String,
    val languageTag: String?,
    val blocks: List<OcrBlockDraft>,
    val lines: List<OcrLineDraft>,
    val tokens: List<OcrTokenDraft>,
    val visualLabels: List<VisualLabelDraft>,
    val detectedObjects: List<DetectedObjectDraft>,
    val barcodes: List<BarcodeDraft>,
    val faces: List<FaceDraft>,
    val entities: List<DetectedEntityDraft>,
    val categories: List<CategoryAssignmentDraft>,
    val primaryCategory: String,
    val appHint: String?,
    val timing: ScreenshotAnalysisTiming = ScreenshotAnalysisTiming(),
)

data class ScreenshotAnalysisTiming(
    val imageLoadMs: Long = 0L,
    val ocrMs: Long = 0L,
    val barcodeMs: Long = 0L,
    val imageLabelMs: Long = 0L,
    val objectDetectionMs: Long = 0L,
    val faceMs: Long = 0L,
    val languageMs: Long = 0L,
    val entityMs: Long = 0L,
    val categoryMs: Long = 0L,
    val totalMs: Long = 0L,
)

data class OcrBlockDraft(
    val blockIndex: Int,
    val text: String,
    val box: BoundingBox?,
)

data class OcrLineDraft(
    val blockIndex: Int,
    val lineIndex: Int,
    val text: String,
    val box: BoundingBox?,
)

data class OcrTokenDraft(
    val lineIndex: Int,
    val tokenIndex: Int,
    val text: String,
    val box: BoundingBox?,
)

data class VisualLabelDraft(
    val label: String,
    val confidence: Float,
    val labelIndex: Int? = null,
)

data class DetectedObjectDraft(
    val objectIndex: Int,
    val trackingId: Int?,
    val box: BoundingBox,
    val areaRatio: Float,
    val labels: List<DetectedObjectLabelDraft>,
)

data class DetectedObjectLabelDraft(
    val label: String,
    val labelIndex: Int?,
    val confidence: Float,
)

data class BarcodeDraft(
    val rawValue: String?,
    val displayValue: String?,
    val format: Int,
    val valueType: Int,
    val box: BoundingBox?,
)

data class FaceDraft(
    val faceIndex: Int,
    val box: BoundingBox,
    val smilingProbability: Float?,
    val leftEyeOpenProbability: Float?,
    val rightEyeOpenProbability: Float?,
    val headEulerAngleX: Float,
    val headEulerAngleY: Float,
    val headEulerAngleZ: Float,
    val landmarksJson: String,
)

data class DetectedEntityDraft(
    val type: String,
    val value: String,
    val normalizedValue: String,
    val source: String,
    val confidence: Float,
    val isSensitive: Boolean,
    val evidence: String,
)

data class CategoryAssignmentDraft(
    val category: String,
    val confidence: Float,
    val reason: String,
)

data class BoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

enum class ScreenshotCategory(val value: String) {
    CHAT("chat"),
    SOCIAL("social"),
    PAYMENTS("payments"),
    FINANCE("finance"),
    IDENTITY_DOCS("identity_docs"),
    BOOKING_TRAVEL("booking_travel"),
    SHOPPING("shopping"),
    FOOD("food"),
    MAPS("maps"),
    AI_NEWS("ai_news"),
    CODE_ERRORS("code_errors"),
    EMAILS("emails"),
    MEDIA("media"),
    DOCUMENTS("documents"),
    UNKNOWN("unknown"),
}
