package com.askmyscreenshots.skill.ml

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import com.askmyscreenshots.skill.extract.CategoryClassifier
import com.askmyscreenshots.skill.extract.LocalEntityExtractor
import com.askmyscreenshots.skill.media.ScreenshotCandidate
import com.google.android.gms.common.api.OptionalModuleApi
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

class MlKitScreenshotAnalyzer(
    private val context: Context,
    private val entityExtractor: LocalEntityExtractor = LocalEntityExtractor(),
    private val categoryClassifier: CategoryClassifier = CategoryClassifier(),
) : ScreenshotAnalyzer {
    private val recognizers: List<TextRecognizer> by lazy {
        listOf(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()),
        )
    }
    private val languageIdentifier by lazy { LanguageIdentification.getClient() }
    private val barcodeScanner by lazy { BarcodeScanning.getClient() }
    private val imageLabeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
    }
    private val objectDetector by lazy {
        ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build(),
        )
    }
    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .enableTracking()
                .build(),
        )
    }
    private val modelDownloader by lazy {
        MlKitModelDownloader(context) {
            buildList {
                recognizers.mapNotNullTo(this) { it as? OptionalModuleApi }
                (languageIdentifier as? OptionalModuleApi)?.let(::add)
                (barcodeScanner as? OptionalModuleApi)?.let(::add)
                (imageLabeler as? OptionalModuleApi)?.let(::add)
                (objectDetector as? OptionalModuleApi)?.let(::add)
                (faceDetector as? OptionalModuleApi)?.let(::add)
            }
        }
    }

    suspend fun prepareModels(onDownloadRequired: suspend () -> Unit = {}) {
        modelDownloader.requestInstallIfNeeded(onDownloadRequired)
    }

    override suspend fun analyze(candidate: ScreenshotCandidate): AnalyzedScreenshot {
        val totalStartedAt = SystemClock.elapsedRealtime()
        val imageResult = timed {
            InputImage.fromFilePath(context, Uri.parse(candidate.uri))
        }
        val image = imageResult.value

        return coroutineScope {
            val textDeferred = async { timedSuspend { recognizeText(image) } }
            val barcodeDeferred = async { timedSuspend { detectBarcodes(image) } }
            val labelDeferred = async { timedSuspend { labelImage(image) } }
            val objectDeferred = async { timedSuspend { detectObjects(image, candidate.width, candidate.height) } }
            val faceDeferred = async { timedSuspend { detectFaces(image) } }

            val textResult = textDeferred.await()
            val ocrPostStartedAt = SystemClock.elapsedRealtime()
            val textResults = textResult.value
            val mergedText = mergeText(textResults)
            val blocks = textResults.flatMapIndexed { scriptIndex, text ->
                text.textBlocks.mapIndexed { blockIndex, block ->
                    OcrBlockDraft(
                        blockIndex = scriptIndex * SCRIPT_INDEX_OFFSET + blockIndex,
                        text = block.text,
                        box = block.boundingBox.toBox(),
                    )
                }
            }
            val lines = textResults.flatMapIndexed { scriptIndex, text ->
                text.textBlocks.flatMapIndexed { blockIndex, block ->
                    block.lines.mapIndexed { lineIndex, line ->
                        OcrLineDraft(
                            blockIndex = scriptIndex * SCRIPT_INDEX_OFFSET + blockIndex,
                            lineIndex = scriptIndex * SCRIPT_INDEX_OFFSET + lineIndex,
                            text = line.text,
                            box = line.boundingBox.toBox(),
                        )
                    }
                }
            }.distinctBy { it.text.lowercase() to it.box }
            val tokens = textResults.flatMapIndexed { scriptIndex, text ->
                text.textBlocks.flatMap { block ->
                    block.lines.flatMapIndexed { lineIndex, line ->
                        line.elements.mapIndexed { tokenIndex, element ->
                            OcrTokenDraft(
                                lineIndex = scriptIndex * SCRIPT_INDEX_OFFSET + lineIndex,
                                tokenIndex = tokenIndex,
                                text = element.text,
                                box = element.boundingBox.toBox(),
                            )
                        }
                    }
                }
            }.distinctBy { it.text.lowercase() to it.box }
            val ocrMs = textResult.durationMs + elapsedSince(ocrPostStartedAt)

            val barcodeResult = barcodeDeferred.await()
            val labelResult = labelDeferred.await()
            val objectResult = objectDeferred.await()
            val faceResult = faceDeferred.await()
            val languageResult = timedSuspend { identifyLanguage(mergedText) }
            val entityResult = timed {
                entityExtractor.extract(
                    ocrText = mergedText,
                    barcodes = barcodeResult.value,
                    displayName = candidate.displayName,
                    relativePath = candidate.relativePath,
                    bucketName = candidate.bucketName,
                    visualLabels = labelResult.value,
                    detectedObjects = objectResult.value,
                    faces = faceResult.value,
                )
            }
            val categoryResult = timed {
                categoryClassifier.classify(
                    text = mergedText,
                    entities = entityResult.value,
                    labels = labelResult.value,
                )
            }
            val primaryCategory = categoryResult.value.firstOrNull()?.category ?: ScreenshotCategory.UNKNOWN.value
            val appHint = entityResult.value.firstOrNull { it.type == "app" }?.normalizedValue

            AnalyzedScreenshot(
                ocrText = mergedText,
                languageTag = languageResult.value,
                blocks = blocks,
                lines = lines,
                tokens = tokens,
                visualLabels = labelResult.value,
                detectedObjects = objectResult.value,
                barcodes = barcodeResult.value,
                faces = faceResult.value,
                entities = entityResult.value,
                categories = categoryResult.value,
                primaryCategory = primaryCategory,
                appHint = appHint,
                timing = ScreenshotAnalysisTiming(
                    imageLoadMs = imageResult.durationMs,
                    ocrMs = ocrMs,
                    barcodeMs = barcodeResult.durationMs,
                    imageLabelMs = labelResult.durationMs,
                    objectDetectionMs = objectResult.durationMs,
                    faceMs = faceResult.durationMs,
                    languageMs = languageResult.durationMs,
                    entityMs = entityResult.durationMs,
                    categoryMs = categoryResult.durationMs,
                    totalMs = elapsedSince(totalStartedAt),
                ),
            )
        }
    }

    private suspend fun recognizeText(image: InputImage): List<Text> {
        return coroutineScope {
            recognizers.map { recognizer ->
                async {
                    runCatching { recognizer.process(image).await() }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun mergeText(results: List<Text>): String {
        return results
            .flatMap { it.textBlocks }
            .map { it.text.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .joinToString("\n")
    }

    private suspend fun detectBarcodes(image: InputImage): List<BarcodeDraft> {
        return runCatching {
            barcodeScanner.process(image).await().map { barcode ->
                BarcodeDraft(
                    rawValue = barcode.rawValue,
                    displayValue = barcode.displayValue,
                    format = barcode.format,
                    valueType = barcode.valueType,
                    box = barcode.boundingBox.toBox(),
                )
            }
        }.getOrElse { emptyList() }
    }

    private suspend fun labelImage(image: InputImage): List<VisualLabelDraft> {
        return runCatching {
            imageLabeler.process(image).await().map { label ->
                VisualLabelDraft(
                    label = label.text,
                    labelIndex = label.index,
                    confidence = label.confidence,
                )
            }
        }.getOrElse { emptyList() }
    }

    suspend fun detectObjectsForUri(
        uri: String,
        width: Int?,
        height: Int?,
    ): List<DetectedObjectDraft> {
        return runCatching {
            val image = InputImage.fromFilePath(context, Uri.parse(uri))
            detectObjects(image, width, height)
        }.getOrElse { emptyList() }
    }

    private suspend fun detectObjects(
        image: InputImage,
        width: Int?,
        height: Int?,
    ): List<DetectedObjectDraft> {
        return runCatching {
            objectDetector.process(image).await().mapIndexed { index, detectedObject ->
                val box = detectedObject.boundingBox
                DetectedObjectDraft(
                    objectIndex = index,
                    trackingId = detectedObject.trackingId,
                    box = BoundingBox(
                        left = box.left,
                        top = box.top,
                        right = box.right,
                        bottom = box.bottom,
                    ),
                    areaRatio = areaRatio(box, width, height),
                    labels = detectedObject.labels.map { label ->
                        DetectedObjectLabelDraft(
                            label = label.text,
                            labelIndex = label.index,
                            confidence = label.confidence,
                        )
                    },
                )
            }
        }.getOrElse { emptyList() }
    }

    private suspend fun detectFaces(image: InputImage): List<FaceDraft> {
        return runCatching {
            faceDetector.process(image).await().mapIndexed { index, face ->
                val box = face.boundingBox
                FaceDraft(
                    faceIndex = index,
                    box = BoundingBox(
                        left = box.left,
                        top = box.top,
                        right = box.right,
                        bottom = box.bottom,
                    ),
                    smilingProbability = face.smilingProbability,
                    leftEyeOpenProbability = face.leftEyeOpenProbability,
                    rightEyeOpenProbability = face.rightEyeOpenProbability,
                    headEulerAngleX = face.headEulerAngleX,
                    headEulerAngleY = face.headEulerAngleY,
                    headEulerAngleZ = face.headEulerAngleZ,
                    landmarksJson = face.allLandmarks.joinToString(
                        prefix = "[",
                        postfix = "]",
                    ) { landmark ->
                        val position = landmark.position
                        """{"type":${landmark.landmarkType},"x":${position.x},"y":${position.y}}"""
                    },
                )
            }
        }.getOrElse { emptyList() }
    }

    private suspend fun identifyLanguage(text: String): String? {
        if (text.isBlank()) return null
        return runCatching { languageIdentifier.identifyLanguage(text).await() }
            .getOrNull()
            ?.takeUnless { it == "und" }
    }

    private fun Rect?.toBox(): BoundingBox? {
        return this?.let {
            BoundingBox(
                left = it.left,
                top = it.top,
                right = it.right,
                bottom = it.bottom,
            )
        }
    }

    private fun areaRatio(box: Rect, width: Int?, height: Int?): Float {
        val imageArea = ((width ?: 0).coerceAtLeast(1) * (height ?: 0).coerceAtLeast(1)).toFloat()
        val objectArea = box.width().coerceAtLeast(0) * box.height().coerceAtLeast(0)
        return (objectArea / imageArea).coerceIn(0f, 1f)
    }

    companion object {
        private const val SCRIPT_INDEX_OFFSET = 10_000
    }
}

private data class TimedResult<T>(
    val value: T,
    val durationMs: Long,
)

private inline fun <T> timed(block: () -> T): TimedResult<T> {
    val startedAt = SystemClock.elapsedRealtime()
    return TimedResult(block(), elapsedSince(startedAt))
}

private suspend inline fun <T> timedSuspend(crossinline block: suspend () -> T): TimedResult<T> {
    val startedAt = SystemClock.elapsedRealtime()
    return TimedResult(block(), elapsedSince(startedAt))
}

private fun elapsedSince(startedAt: Long): Long {
    return SystemClock.elapsedRealtime() - startedAt
}
