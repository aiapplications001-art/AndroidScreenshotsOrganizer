package com.askmyscreenshots.skill.index

import android.os.SystemClock
import com.askmyscreenshots.skill.api.OrganizeProgress
import com.askmyscreenshots.skill.api.OrganizeRequest
import com.askmyscreenshots.skill.api.ReindexPolicy
import com.askmyscreenshots.skill.data.BarcodeEntity
import com.askmyscreenshots.skill.data.CategoryAssignmentEntity
import com.askmyscreenshots.skill.data.DetectedEntityEntity
import com.askmyscreenshots.skill.data.DetectedObjectEntity
import com.askmyscreenshots.skill.data.DetectedObjectLabelEntity
import com.askmyscreenshots.skill.data.DetectedObjectWithLabels
import com.askmyscreenshots.skill.data.EntityLinkEntity
import com.askmyscreenshots.skill.data.FaceEntity
import com.askmyscreenshots.skill.data.IndexFailureEntity
import com.askmyscreenshots.skill.data.IndexRunEntity
import com.askmyscreenshots.skill.data.IndexRunStatus
import com.askmyscreenshots.skill.data.IndexedScreenshotSnapshot
import com.askmyscreenshots.skill.data.OcrBlockEntity
import com.askmyscreenshots.skill.data.OcrLineEntity
import com.askmyscreenshots.skill.data.OcrTokenEntity
import com.askmyscreenshots.skill.data.ObjectLabelForScreenshot
import com.askmyscreenshots.skill.data.ScreenshotEmbeddingEntity
import com.askmyscreenshots.skill.data.ScreenshotEntity
import com.askmyscreenshots.skill.data.ScreenshotFtsEntity
import com.askmyscreenshots.skill.data.ScreenshotSkillDao
import com.askmyscreenshots.skill.data.VisualLabelEntity
import com.askmyscreenshots.skill.data.VisualDescriptionEntity
import com.askmyscreenshots.skill.debug.SkillDebugLog
import com.askmyscreenshots.skill.extract.CategoryClassifier
import com.askmyscreenshots.skill.extract.LocalEntityExtractor
import com.askmyscreenshots.skill.linking.EntityLinkDraft
import com.askmyscreenshots.skill.linking.EntityLinker
import com.askmyscreenshots.skill.media.ScreenshotCandidate
import com.askmyscreenshots.skill.media.ScreenshotMediaScanner
import com.askmyscreenshots.skill.ml.AnalyzedScreenshot
import com.askmyscreenshots.skill.ml.BoundingBox
import com.askmyscreenshots.skill.ml.CategoryAssignmentDraft
import com.askmyscreenshots.skill.ml.DetectedEntityDraft
import com.askmyscreenshots.skill.ml.DetectedObjectDraft
import com.askmyscreenshots.skill.ml.DetectedObjectLabelDraft
import com.askmyscreenshots.skill.ml.BarcodeDraft
import com.askmyscreenshots.skill.ml.FaceDraft
import com.askmyscreenshots.skill.ml.MlKitScreenshotAnalyzer
import com.askmyscreenshots.skill.ml.ScreenshotCategory
import com.askmyscreenshots.skill.ml.VisualLabelDraft
import com.askmyscreenshots.skill.semantic.HashingTextEmbedder
import com.askmyscreenshots.skill.semantic.SemanticInputBuilder
import com.askmyscreenshots.skill.semantic.TextEmbedder
import com.askmyscreenshots.skill.semantic.toEmbeddingBlob
import com.askmyscreenshots.skill.visual.HeuristicVisualCaptioner
import com.askmyscreenshots.skill.visual.VisualCaptioner
import com.askmyscreenshots.skill.visual.VisualDescriptionDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class IndexResult(
    val runId: Long,
    val totalCount: Int,
    val indexedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
)

class ScreenshotIndexer(
    private val dao: ScreenshotSkillDao,
    private val scanner: ScreenshotMediaScanner,
    private val analyzer: MlKitScreenshotAnalyzer,
    private val entityExtractor: LocalEntityExtractor = LocalEntityExtractor(),
    private val categoryClassifier: CategoryClassifier = CategoryClassifier(),
    private val textEmbedder: TextEmbedder = HashingTextEmbedder(),
    private val imageSignalEmbedder: TextEmbedder = HashingTextEmbedder(
        name = "local-visual-signal-image-embedder",
        version = "2026-06-14",
    ),
    private val visualCaptioner: VisualCaptioner = HeuristicVisualCaptioner(),
    private val entityLinker: EntityLinker = EntityLinker(),
) {
    suspend fun index(
        request: OrganizeRequest,
        progress: suspend (OrganizeProgress) -> Unit,
    ): IndexResult {
        val startedAt = System.currentTimeMillis()
        SkillDebugLog.i(
            event = "index_start",
            message = "source=${request.source} reindex=${request.reindexPolicy} " +
                "start=${request.startMillis} end=${request.endMillisExclusive}",
        )
        val runId = dao.insertRun(
            IndexRunEntity(
                startMillis = request.startMillis,
                endMillisExclusive = request.endMillisExclusive,
                source = request.source.name,
                reindexPolicy = request.reindexPolicy.name,
                status = IndexRunStatus.STARTED.value,
                candidateCount = 0,
                indexedCount = 0,
                failedCount = 0,
                startedAtMillis = startedAt,
            ),
        )

        return runCatching {
            if (request.reindexPolicy == ReindexPolicy.REPLACE_RANGE) {
                SkillDebugLog.i("index_replace_range", "runId=$runId")
                dao.deleteScreenshotsInRange(request.startMillis, request.endMillisExclusive)
            }

            progress(OrganizeProgress.Scanning())
            SkillDebugLog.i("index_scan_start", "runId=$runId")
            val candidates = scanner.scan(request)
            SkillDebugLog.i("index_scan_done", "runId=$runId candidates=${candidates.size}")
            progress(OrganizeProgress.Scanning(candidates.size))

            val skippedUris = if (request.reindexPolicy == ReindexPolicy.INCREMENTAL) {
                findSkippableUris(candidates).also { skipped ->
                    SkillDebugLog.i(
                        event = "index_incremental_skip",
                        message = "runId=$runId total=${candidates.size} skipped=${skipped.size} " +
                            "toAnalyze=${candidates.size - skipped.size}",
                    )
                }
            } else {
                emptySet()
            }
            val skipped = skippedUris.size
            if (skipped > 0) {
                val refreshed = refreshSkippedCategories(
                    runId = runId,
                    skippedUris = skippedUris,
                    skippedCount = skipped,
                    candidateCount = candidates.size,
                    progress = progress,
                )
                SkillDebugLog.i(
                    event = "index_skipped_categories_refreshed",
                    message = "runId=$runId skipped=$skipped refreshed=$refreshed",
                )
            }
            val workItems = candidates.mapIndexedNotNull { index, candidate ->
                if (candidate.uri in skippedUris) null else CandidateWork(index, candidate)
            }
            if (skipped > 0) {
                progress(
                    OrganizeProgress.Indexing(
                        processedCount = skipped,
                        totalCount = candidates.size,
                        currentTitle = null,
                        skippedCount = skipped,
                    ),
                )
            }

            if (workItems.isNotEmpty()) {
                progress(OrganizeProgress.PreparingLocalOcr)
                SkillDebugLog.i("index_prepare_models_start", "runId=$runId")
                analyzer.prepareModels {
                    SkillDebugLog.i("index_models_download_required", "runId=$runId")
                    progress(OrganizeProgress.DownloadingLocalOcr)
                }
                SkillDebugLog.i("index_prepare_models_done", "runId=$runId")
            }

            var indexed = 0
            var failed = 0
            var processed = skipped
            val timingSamples = mutableListOf<IndexTimingSample>()
            val parallelism = analysisParallelism(workItems.size)
            SkillDebugLog.i(
                event = "index_parallelism",
                message = "runId=$runId parallelism=$parallelism candidates=${candidates.size} " +
                    "skipped=$skipped toAnalyze=${workItems.size}",
            )

            workItems.chunked(parallelism).forEach { chunk ->
                currentCoroutineContext().ensureActive()
                val analysisResults = coroutineScope {
                    chunk.map { work ->
                        async(Dispatchers.Default) {
                            analyzeCandidate(runId, work, candidates.size)
                        }
                    }.awaitAll()
                }

                analysisResults.forEach { result ->
                    currentCoroutineContext().ensureActive()
                    val work = result.work
                    val candidate = work.candidate
                    val analysis = result.analysis

                    if (analysis != null && result.error == null) {
                        val dbStartedAt = SystemClock.elapsedRealtime()
                        writeAnalysis(candidate, analysis)
                        val dbWriteMs = elapsedSince(dbStartedAt)
                        val sample = IndexTimingSample.from(analysis, dbWriteMs)
                        timingSamples += sample
                        SkillDebugLog.d(
                            event = "index_candidate_timing",
                            message = "runId=$runId index=${work.index} " +
                                "imageLoadMs=${sample.imageLoadMs} ocrMs=${sample.ocrMs} " +
                                "barcodeMs=${sample.barcodeMs} labelMs=${sample.imageLabelMs} " +
                                "objectMs=${sample.objectDetectionMs} faceMs=${sample.faceMs} languageMs=${sample.languageMs} " +
                                "entityMs=${sample.entityMs} categoryMs=${sample.categoryMs} " +
                                "dbWriteMs=${sample.dbWriteMs} totalMs=${sample.totalMs} " +
                                "textChars=${analysis.ocrText.length} " +
                                "entities=${analysis.entities.size} categories=${analysis.categories.size} " +
                                "labels=${analysis.visualLabels.size} objects=${analysis.detectedObjects.size} faces=${analysis.faces.size} " +
                                "barcodes=${analysis.barcodes.size}",
                        )
                        indexed += 1
                    } else {
                        failed += 1
                        SkillDebugLog.w(
                            event = "index_candidate_failed",
                            message = "runId=$runId index=${work.index} failed=$failed",
                            throwable = result.error,
                        )
                        dao.insertFailure(
                            IndexFailureEntity(
                                runId = runId,
                                uri = candidate.uri,
                                displayName = candidate.displayName,
                                errorMessage = result.error?.message ?: "Unknown indexing error",
                                failedAtMillis = System.currentTimeMillis(),
                            ),
                        )
                    }
                    processed += 1
                    progress(
                        OrganizeProgress.Indexing(
                            processedCount = processed,
                            totalCount = candidates.size,
                            currentTitle = candidate.displayName,
                            skippedCount = skipped,
                        ),
                    )
                }
            }

            dao.finishRun(
                runId = runId,
                status = IndexRunStatus.COMPLETED.value,
                candidateCount = candidates.size,
                indexedCount = indexed,
                failedCount = failed,
                completedAtMillis = System.currentTimeMillis(),
                errorMessage = null,
            )
            SkillDebugLog.i(
                event = "index_done",
                message = "runId=$runId total=${candidates.size} indexed=$indexed failed=$failed " +
                    "skipped=$skipped durationMs=${System.currentTimeMillis() - startedAt} " +
                    timingSamples.summaryForLog(),
            )
            IndexResult(
                runId = runId,
                totalCount = candidates.size,
                indexedCount = indexed,
                skippedCount = skipped,
                failedCount = failed,
            )
        }.getOrElse { error ->
            SkillDebugLog.e(
                event = "index_failed",
                message = "runId=$runId durationMs=${System.currentTimeMillis() - startedAt}",
                throwable = error,
            )
            dao.finishRun(
                runId = runId,
                status = IndexRunStatus.FAILED.value,
                candidateCount = 0,
                indexedCount = 0,
                failedCount = 0,
                completedAtMillis = System.currentTimeMillis(),
                errorMessage = error.message,
            )
            throw error
        }
    }

    private suspend fun findSkippableUris(candidates: List<ScreenshotCandidate>): Set<String> {
        if (candidates.isEmpty()) return emptySet()
        val snapshotsByUri = candidates
            .map { it.uri }
            .distinct()
            .chunked(MAX_SQL_BIND_ARGS)
            .flatMap { dao.indexedSnapshotsForUris(it) }
            .associateBy { it.uri }

        return candidates
            .asSequence()
            .filter { candidate -> snapshotsByUri[candidate.uri]?.matches(candidate) == true }
            .map { it.uri }
            .toSet()
    }

    private fun IndexedScreenshotSnapshot.matches(candidate: ScreenshotCandidate): Boolean {
        return mediaStoreId == candidate.mediaStoreId &&
            dateTakenMillis == candidate.dateTakenMillis &&
            sizeBytes == candidate.sizeBytes &&
            mimeType == candidate.mimeType &&
            width == candidate.width &&
            height == candidate.height
    }

    private suspend fun refreshSkippedCategories(
        runId: Long,
        skippedUris: Set<String>,
        skippedCount: Int,
        candidateCount: Int,
        progress: suspend (OrganizeProgress) -> Unit,
    ): Int {
        if (skippedUris.isEmpty()) return 0
        val screenshots = skippedUris
            .chunked(MAX_SQL_BIND_ARGS)
            .flatMap {
                dao.screenshotsMissingSearchSignalEmbeddingsForUris(
                    uris = it,
                    textModelName = textEmbedder.modelName,
                    textModelVersion = textEmbedder.modelVersion,
                    visualModelName = imageSignalEmbedder.modelName,
                    visualModelVersion = imageSignalEmbedder.modelVersion,
                )
            }
        if (screenshots.isEmpty()) {
            SkillDebugLog.i(
                event = "index_skipped_backfill_not_needed",
                message = "runId=$runId skipped=$skippedCount candidateCount=$candidateCount",
            )
            return 0
        }
        progress(
            OrganizeProgress.BackfillingLocalAi(
                processedCount = 0,
                totalCount = screenshots.size,
                stage = "semantic and visual features",
                skippedCount = skippedCount,
                candidateCount = candidateCount,
            ),
        )

        val screenshotIds = screenshots.map { it.id }
        val labelsByScreenshot = screenshotIds
            .chunked(MAX_SQL_BIND_ARGS)
            .flatMap { dao.visualLabelRowsForScreenshots(it) }
            .groupBy { it.screenshotId }
        val barcodesByScreenshot = screenshotIds
            .chunked(MAX_SQL_BIND_ARGS)
            .flatMap { dao.barcodeRowsForScreenshots(it) }
            .groupBy { it.screenshotId }
        val facesByScreenshot = screenshotIds
            .chunked(MAX_SQL_BIND_ARGS)
            .flatMap { dao.faceRowsForScreenshots(it) }
            .groupBy { it.screenshotId }
        val objectLabelsByScreenshot = screenshotIds
            .chunked(MAX_SQL_BIND_ARGS)
            .flatMap { dao.objectLabelRowsForScreenshots(it) }
            .groupBy { it.screenshotId }
        val descriptionsByScreenshot = screenshotIds
            .chunked(MAX_SQL_BIND_ARGS)
            .flatMap { dao.visualDescriptionsForScreenshots(it) }
            .groupBy { it.screenshotId }
        val missingObjectDetectionIds = screenshotIds
            .chunked(MAX_SQL_BIND_ARGS)
            .flatMap { dao.screenshotsMissingObjectDetections(it) }
            .map { it.id }
            .toSet()
        var objectBackfillCount = 0

        screenshots.forEachIndexed { index, screenshot ->
            val labels = labelsByScreenshot[screenshot.id].orEmpty()
            val labelDrafts = labels.map { it.toDraft() }
            val barcodes = barcodesByScreenshot[screenshot.id].orEmpty()
            val barcodeDrafts = barcodes.map { it.toDraft() }
            val faces = facesByScreenshot[screenshot.id].orEmpty()
            val faceDrafts = faces.map { it.toDraft() }
            val detectedObjects = if (screenshot.id in missingObjectDetectionIds) {
                val objects = analyzer.detectObjectsForUri(
                    uri = screenshot.uri,
                    width = screenshot.width,
                    height = screenshot.height,
                )
                if (objects.isNotEmpty()) {
                    dao.insertObjectDetectionsForScreenshot(
                        screenshotId = screenshot.id,
                        detectedObjects = objects.map {
                            it.toDetectedObjectWithLabels(screenshotId = screenshot.id, objectId = 0L)
                        },
                    )
                }
                objectBackfillCount += 1
                objects
            } else {
                objectLabelsByScreenshot[screenshot.id].orEmpty().toDetectedObjectDrafts()
            }
            val entities = entityExtractor.extract(
                ocrText = screenshot.ocrText,
                barcodes = barcodeDrafts,
                displayName = screenshot.displayName,
                relativePath = screenshot.relativePath,
                bucketName = screenshot.bucketName,
                visualLabels = labelDrafts,
                detectedObjects = detectedObjects,
                faces = faceDrafts,
            )
            val entityRows = entities.map { it.toEntity(screenshot.id) }
            val categories = categoryClassifier.classify(
                text = screenshot.ocrText,
                entities = entities,
                labels = labelDrafts,
            )
            val primaryCategory = categories.firstOrNull()?.category ?: ScreenshotCategory.UNKNOWN.value
            val appHint = entities.firstOrNull { it.type == "app" }?.normalizedValue ?: screenshot.appHint
            dao.replaceScreenshotDerivedSignals(
                screenshotId = screenshot.id,
                appHint = appHint,
                primaryCategory = primaryCategory,
                entities = entityRows,
                categories = categories.map { it.toEntity(screenshot.id) },
            )
            val visualDescription = storedVisualDescription(
                screenshot = screenshot.copy(category = primaryCategory, appHint = appHint),
                labels = labels,
                detectedObjects = detectedObjects,
                faces = faces,
                barcodes = barcodes,
            )
            visualDescription?.let { description ->
                dao.insertVisualDescription(description.toEntity(screenshot.id, nowMillis = System.currentTimeMillis()))
                dao.updateScreenshotFtsBody(
                    screenshotId = screenshot.id,
                    body = buildFtsBody(screenshot.ocrText, description.description),
                )
            }
            val semanticInput = SemanticInputBuilder.fromStored(
                screenshot = screenshot.copy(category = primaryCategory, appHint = appHint),
                entities = entityRows,
                labels = labels,
                categories = categories.map { it.toEntity(screenshot.id) },
                visualDescription = visualDescription?.description
                    ?: descriptionsByScreenshot[screenshot.id]?.firstOrNull()?.description,
                objectLabels = detectedObjects
                    .flatMap { it.labels }
                    .sortedByDescending { it.confidence }
                    .map { it.label },
            )
            textEmbedder.embed(semanticInput)?.let { embedding ->
                dao.insertEmbedding(embedding.toEntity(screenshot.id))
            }
            imageSignalEmbedder.embed(
                storedVisualEmbeddingInput(
                    screenshot = screenshot.copy(category = primaryCategory, appHint = appHint),
                    labels = labels,
                    detectedObjects = detectedObjects,
                    faces = faces,
                    barcodes = barcodes,
                    visualDescription = visualDescription?.description
                        ?: descriptionsByScreenshot[screenshot.id]?.firstOrNull()?.description,
                ),
            )?.let { embedding ->
                dao.insertEmbedding(embedding.toEntity(screenshot.id))
            }
            entityLinker.linksFor(entities).forEach { link ->
                dao.upsertEntityLink(
                    leftType = link.leftType,
                    leftValue = link.leftValue,
                    leftNormalizedValue = link.leftNormalizedValue,
                    rightType = link.rightType,
                    rightValue = link.rightValue,
                    rightNormalizedValue = link.rightNormalizedValue,
                    confidence = link.confidence,
                    seenAtMillis = System.currentTimeMillis(),
                    source = link.source,
                )
            }
            val processed = index + 1
            if (processed == screenshots.size || processed % 25 == 0) {
                progress(
                    OrganizeProgress.BackfillingLocalAi(
                        processedCount = processed,
                        totalCount = screenshots.size,
                        stage = "semantic and visual features",
                        skippedCount = skippedCount,
                        candidateCount = candidateCount,
                    ),
                )
            }
        }
        SkillDebugLog.d(
            event = "index_skipped_category_refresh_done",
            message = "runId=$runId screenshots=${screenshots.size} objectBackfilled=$objectBackfillCount",
        )
        return screenshots.size
    }

    private suspend fun analyzeCandidate(
        runId: Long,
        work: CandidateWork,
        totalCount: Int,
    ): CandidateAnalysisResult {
        if (work.index.shouldLogIndexingProgress(totalCount)) {
            SkillDebugLog.i(
                event = "index_candidate_start",
                message = "runId=$runId index=${work.index} total=$totalCount",
            )
        }
        return runCatching {
            analyzer.analyze(work.candidate)
        }.fold(
            onSuccess = { analysis -> CandidateAnalysisResult(work, analysis, null) },
            onFailure = { error -> CandidateAnalysisResult(work, null, error) },
        )
    }

    private suspend fun writeAnalysis(
        candidate: ScreenshotCandidate,
        analysis: AnalyzedScreenshot,
    ) {
        val visualDescription = visualCaptioner.describe(analysis)
        val semanticInput = SemanticInputBuilder.fromAnalysis(
            displayName = candidate.displayName,
            analysis = analysis,
            visualDescription = visualDescription?.description,
        )
        val embedding = textEmbedder.embed(semanticInput)
        val imageEmbedding = imageSignalEmbedder.embed(visualEmbeddingInput(analysis, visualDescription?.description))
        val now = System.currentTimeMillis()
        val entityLinks = entityLinker.linksFor(analysis.entities).map { it.toEntity(now) }
        dao.replaceScreenshotIndex(
            screenshot = candidate.toScreenshotEntity(analysis),
            ftsBody = analysis.toFtsEntity(candidate, visualDescription?.description),
            blocks = analysis.blocks.map { block ->
                OcrBlockEntity(
                    screenshotId = 0L,
                    blockIndex = block.blockIndex,
                    text = block.text,
                    left = block.box?.left,
                    top = block.box?.top,
                    right = block.box?.right,
                    bottom = block.box?.bottom,
                )
            },
            lines = analysis.lines.map { line ->
                OcrLineEntity(
                    screenshotId = 0L,
                    blockIndex = line.blockIndex,
                    lineIndex = line.lineIndex,
                    text = line.text,
                    left = line.box?.left,
                    top = line.box?.top,
                    right = line.box?.right,
                    bottom = line.box?.bottom,
                )
            },
            tokens = analysis.tokens.map { token ->
                OcrTokenEntity(
                    screenshotId = 0L,
                    lineIndex = token.lineIndex,
                    tokenIndex = token.tokenIndex,
                    text = token.text,
                    left = token.box?.left,
                    top = token.box?.top,
                    right = token.box?.right,
                    bottom = token.box?.bottom,
                )
            },
            labels = analysis.visualLabels.map { label ->
                VisualLabelEntity(
                    screenshotId = 0L,
                    label = label.label,
                    labelIndex = label.labelIndex,
                    confidence = label.confidence,
                )
            },
            detectedObjects = analysis.detectedObjects.map { detectedObject ->
                detectedObject.toDetectedObjectWithLabels(screenshotId = 0L, objectId = 0L)
            },
            barcodes = analysis.barcodes.map { barcode ->
                BarcodeEntity(
                    screenshotId = 0L,
                    rawValue = barcode.rawValue,
                    displayValue = barcode.displayValue,
                    format = barcode.format,
                    valueType = barcode.valueType,
                    left = barcode.box?.left,
                    top = barcode.box?.top,
                    right = barcode.box?.right,
                    bottom = barcode.box?.bottom,
                )
            },
            faces = analysis.faces.map { face ->
                FaceEntity(
                    screenshotId = 0L,
                    faceIndex = face.faceIndex,
                    left = face.box.left,
                    top = face.box.top,
                    right = face.box.right,
                    bottom = face.box.bottom,
                    smilingProbability = face.smilingProbability,
                    leftEyeOpenProbability = face.leftEyeOpenProbability,
                    rightEyeOpenProbability = face.rightEyeOpenProbability,
                    headEulerAngleX = face.headEulerAngleX,
                    headEulerAngleY = face.headEulerAngleY,
                    headEulerAngleZ = face.headEulerAngleZ,
                    landmarksJson = face.landmarksJson,
                )
            },
            entities = analysis.entities.map { entity ->
                DetectedEntityEntity(
                    screenshotId = 0L,
                    type = entity.type,
                    value = entity.value,
                    normalizedValue = entity.normalizedValue,
                    source = entity.source,
                    confidence = entity.confidence,
                    isSensitive = entity.isSensitive,
                    evidence = entity.evidence,
                )
            },
            categories = analysis.categories.map { category ->
                CategoryAssignmentEntity(
                    screenshotId = 0L,
                    category = category.category,
                    confidence = category.confidence,
                    reason = category.reason,
                )
            },
            embeddings = listOfNotNull(
                embedding?.toEntity(0L),
                imageEmbedding?.toEntity(0L),
            ),
            visualDescription = visualDescription?.toEntity(0L, now),
            entityLinks = entityLinks,
        )
    }

    private fun com.askmyscreenshots.skill.media.ScreenshotCandidate.toScreenshotEntity(
        analysis: AnalyzedScreenshot,
    ): ScreenshotEntity {
        return ScreenshotEntity(
            mediaStoreId = mediaStoreId,
            uri = uri,
            displayName = displayName,
            relativePath = relativePath,
            bucketName = bucketName,
            dateTakenMillis = dateTakenMillis,
            sizeBytes = sizeBytes,
            mimeType = mimeType,
            width = width,
            height = height,
            indexedAtMillis = System.currentTimeMillis(),
            indexStatus = "INDEXED",
            languageTag = analysis.languageTag,
            category = analysis.primaryCategory,
            appHint = analysis.appHint,
            ocrText = analysis.ocrText,
            errorMessage = null,
        )
    }

    private fun AnalyzedScreenshot.toFtsEntity(
        candidate: com.askmyscreenshots.skill.media.ScreenshotCandidate,
        visualDescription: String?,
    ): ScreenshotFtsEntity {
        return ScreenshotFtsEntity(
            rowId = 0L,
            title = candidate.displayName.orEmpty(),
            body = buildFtsBody(ocrText, visualDescription),
            entities = entities.joinToString(" ") {
                "${it.type} ${it.value} ${it.normalizedValue}"
            },
            categories = categories.joinToString(" ") { it.category },
        )
    }

    companion object {
        private const val MAX_SQL_BIND_ARGS = 500
    }
}

private fun storedVisualDescription(
    screenshot: ScreenshotEntity,
    labels: List<VisualLabelEntity>,
    detectedObjects: List<DetectedObjectDraft> = emptyList(),
    faces: List<FaceEntity> = emptyList(),
    barcodes: List<BarcodeEntity> = emptyList(),
): VisualDescriptionDraft? {
    val labelText = labels
        .filter { it.confidence >= 0.55f }
        .sortedByDescending { it.confidence }
        .map { it.label.lowercase() }
        .distinct()
        .take(8)
        .joinToString(", ")
    val objectText = detectedObjects
        .flatMap { it.labels }
        .filter { it.confidence >= 0.45f }
        .sortedByDescending { it.confidence }
        .map { it.label.lowercase() }
        .distinct()
        .take(8)
        .joinToString(", ")
    val pieces = buildList {
        screenshot.appHint?.takeIf { it.isNotBlank() }?.let { add("app ${it.lowercase()}") }
        screenshot.category.takeIf { it.isNotBlank() && it != ScreenshotCategory.UNKNOWN.value }
            ?.let { add(it.replace('_', ' ')) }
        if (labelText.isNotBlank()) add("visual $labelText")
        if (objectText.isNotBlank()) add("objects $objectText")
        if (faces.isNotEmpty()) add("${faces.size} face${if (faces.size == 1) "" else "s"}")
        if (barcodes.isNotEmpty()) add("${barcodes.size} QR or barcode signal")
    }
    val description = pieces.joinToString(". ")
    if (description.isBlank()) return null
    return VisualDescriptionDraft(
        modelName = "mlkit-visual-summary",
        modelVersion = "2026-06-14",
        description = description,
        confidence = (
            0.35f +
                labels.size.coerceAtMost(6) * 0.07f +
                detectedObjects.flatMap { it.labels }.size.coerceAtMost(5) * 0.05f +
                if (faces.isNotEmpty()) 0.08f else 0f +
                if (barcodes.isNotEmpty()) 0.08f else 0f
            ).coerceIn(0.35f, 0.86f),
        status = "BACKFILLED_LABEL_SUMMARY",
    )
}

private fun buildFtsBody(ocrText: String, visualDescription: String?): String {
    return listOf(ocrText, visualDescription?.let { "visual description $it" })
        .filterNotNull()
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

private fun visualEmbeddingInput(
    analysis: AnalyzedScreenshot,
    visualDescription: String?,
): String {
    return buildString {
        appendLine("app:${analysis.appHint.orEmpty()}")
        appendLine("category:${analysis.primaryCategory}")
        appendLine(analysis.visualLabels.sortedByDescending { it.confidence }.take(16).joinToString(" ") { "visual:${it.label}" })
        appendLine(
            analysis.detectedObjects
                .flatMap { it.labels }
                .sortedByDescending { it.confidence }
                .take(16)
                .joinToString(" ") { "object:${it.label}" },
        )
        appendLine("objects:${analysis.detectedObjects.size}")
        appendLine("faces:${analysis.faces.size}")
        appendLine("barcodes:${analysis.barcodes.size}")
        visualDescription?.let { appendLine("caption:$it") }
    }.trim()
}

private fun storedVisualEmbeddingInput(
    screenshot: ScreenshotEntity,
    labels: List<VisualLabelEntity>,
    detectedObjects: List<DetectedObjectDraft> = emptyList(),
    faces: List<FaceEntity> = emptyList(),
    barcodes: List<BarcodeEntity> = emptyList(),
    visualDescription: String?,
): String {
    return buildString {
        appendLine("app:${screenshot.appHint.orEmpty()}")
        appendLine("category:${screenshot.category}")
        appendLine(labels.sortedByDescending { it.confidence }.take(16).joinToString(" ") { "visual:${it.label}" })
        appendLine(
            detectedObjects
                .flatMap { it.labels }
                .sortedByDescending { it.confidence }
                .take(16)
                .joinToString(" ") { "object:${it.label}" },
        )
        appendLine("objects:${detectedObjects.size}")
        appendLine("faces:${faces.size}")
        appendLine("barcodes:${barcodes.size}")
        visualDescription?.let { appendLine("caption:$it") }
    }.trim()
}

private fun com.askmyscreenshots.skill.semantic.TextEmbedding.toEntity(screenshotId: Long): ScreenshotEmbeddingEntity {
    return ScreenshotEmbeddingEntity(
        screenshotId = screenshotId,
        modelName = modelName,
        modelVersion = modelVersion,
        inputHash = inputHash,
        dimension = dimension,
        vectorBlob = vector.toEmbeddingBlob(),
        createdAtMillis = System.currentTimeMillis(),
    )
}

private fun VisualDescriptionDraft.toEntity(screenshotId: Long, nowMillis: Long): VisualDescriptionEntity {
    return VisualDescriptionEntity(
        screenshotId = screenshotId,
        modelName = modelName,
        modelVersion = modelVersion,
        description = description,
        confidence = confidence,
        status = status,
        createdAtMillis = nowMillis,
    )
}

private fun EntityLinkDraft.toEntity(nowMillis: Long): EntityLinkEntity {
    return EntityLinkEntity(
        leftType = leftType,
        leftValue = leftValue,
        leftNormalizedValue = leftNormalizedValue,
        rightType = rightType,
        rightValue = rightValue,
        rightNormalizedValue = rightNormalizedValue,
        coOccurrenceCount = 1,
        confidence = confidence,
        firstSeenAtMillis = nowMillis,
        lastSeenAtMillis = nowMillis,
        source = source,
    )
}

private fun DetectedEntityEntity.toDraft(): DetectedEntityDraft {
    return DetectedEntityDraft(
        type = type,
        value = value,
        normalizedValue = normalizedValue,
        source = source,
        confidence = confidence,
        isSensitive = isSensitive,
        evidence = evidence,
    )
}

private fun DetectedEntityDraft.toEntity(screenshotId: Long): DetectedEntityEntity {
    return DetectedEntityEntity(
        screenshotId = screenshotId,
        type = type,
        value = value,
        normalizedValue = normalizedValue,
        source = source,
        confidence = confidence,
        isSensitive = isSensitive,
        evidence = evidence,
    )
}

private fun VisualLabelEntity.toDraft(): VisualLabelDraft {
    return VisualLabelDraft(
        label = label,
        confidence = confidence,
        labelIndex = labelIndex,
    )
}

private fun BarcodeEntity.toDraft(): BarcodeDraft {
    return BarcodeDraft(
        rawValue = rawValue,
        displayValue = displayValue,
        format = format,
        valueType = valueType,
        box = if (left != null && top != null && right != null && bottom != null) {
            BoundingBox(left, top, right, bottom)
        } else {
            null
        },
    )
}

private fun FaceEntity.toDraft(): FaceDraft {
    return FaceDraft(
        faceIndex = faceIndex,
        box = BoundingBox(left, top, right, bottom),
        smilingProbability = smilingProbability,
        leftEyeOpenProbability = leftEyeOpenProbability,
        rightEyeOpenProbability = rightEyeOpenProbability,
        headEulerAngleX = headEulerAngleX,
        headEulerAngleY = headEulerAngleY,
        headEulerAngleZ = headEulerAngleZ,
        landmarksJson = landmarksJson,
    )
}

private fun List<ObjectLabelForScreenshot>.toDetectedObjectDrafts(): List<DetectedObjectDraft> {
    return groupBy { it.objectId }
        .map { (_, rows) ->
            val first = rows.first()
            DetectedObjectDraft(
                objectIndex = first.objectIndex,
                trackingId = first.trackingId,
                box = BoundingBox(
                    left = first.objectLeft,
                    top = first.objectTop,
                    right = first.objectRight,
                    bottom = first.objectBottom,
                ),
                areaRatio = first.areaRatio,
                labels = rows.map { row ->
                    DetectedObjectLabelDraft(
                        label = row.label,
                        labelIndex = row.labelIndex,
                        confidence = row.confidence,
                    )
                },
            )
        }
}

private fun DetectedObjectDraft.toDetectedObjectWithLabels(
    screenshotId: Long,
    objectId: Long,
): DetectedObjectWithLabels {
    return DetectedObjectWithLabels(
        objectRow = DetectedObjectEntity(
            screenshotId = screenshotId,
            objectIndex = objectIndex,
            trackingId = trackingId,
            left = box.left,
            top = box.top,
            right = box.right,
            bottom = box.bottom,
            areaRatio = areaRatio,
        ),
        labels = labels.map { label ->
            DetectedObjectLabelEntity(
                objectId = objectId,
                screenshotId = screenshotId,
                label = label.label,
                labelIndex = label.labelIndex,
                confidence = label.confidence,
            )
        },
    )
}

private fun CategoryAssignmentDraft.toEntity(screenshotId: Long): CategoryAssignmentEntity {
    return CategoryAssignmentEntity(
        screenshotId = screenshotId,
        category = category,
        confidence = confidence,
        reason = reason,
    )
}

private data class CandidateWork(
    val index: Int,
    val candidate: ScreenshotCandidate,
)

private data class CandidateAnalysisResult(
    val work: CandidateWork,
    val analysis: AnalyzedScreenshot?,
    val error: Throwable?,
)

private data class IndexTimingSample(
    val imageLoadMs: Long,
    val ocrMs: Long,
    val barcodeMs: Long,
    val imageLabelMs: Long,
    val objectDetectionMs: Long,
    val faceMs: Long,
    val languageMs: Long,
    val entityMs: Long,
    val categoryMs: Long,
    val dbWriteMs: Long,
    val totalMs: Long,
) {
    companion object {
        fun from(analysis: AnalyzedScreenshot, dbWriteMs: Long): IndexTimingSample {
            val timing = analysis.timing
            return IndexTimingSample(
                imageLoadMs = timing.imageLoadMs,
                ocrMs = timing.ocrMs,
                barcodeMs = timing.barcodeMs,
                imageLabelMs = timing.imageLabelMs,
                objectDetectionMs = timing.objectDetectionMs,
                faceMs = timing.faceMs,
                languageMs = timing.languageMs,
                entityMs = timing.entityMs,
                categoryMs = timing.categoryMs,
                dbWriteMs = dbWriteMs,
                totalMs = timing.totalMs + dbWriteMs,
            )
        }
    }
}

private fun analysisParallelism(workCount: Int): Int {
    if (workCount <= 1) return 1
    return (Runtime.getRuntime().availableProcessors() / 3)
        .coerceIn(2, 3)
        .coerceAtMost(workCount)
}

private fun List<IndexTimingSample>.summaryForLog(): String {
    if (isEmpty()) return "timingSamples=0"
    return listOf(
        "timingSamples=$size",
        "avgTotalMs=${map { it.totalMs }.averageLong()}",
        "p50TotalMs=${map { it.totalMs }.percentile(50)}",
        "p90TotalMs=${map { it.totalMs }.percentile(90)}",
        "avgOcrMs=${map { it.ocrMs }.averageLong()}",
        "avgFaceMs=${map { it.faceMs }.averageLong()}",
        "avgLabelMs=${map { it.imageLabelMs }.averageLong()}",
        "avgObjectMs=${map { it.objectDetectionMs }.averageLong()}",
        "avgBarcodeMs=${map { it.barcodeMs }.averageLong()}",
        "avgDbMs=${map { it.dbWriteMs }.averageLong()}",
    ).joinToString(" ")
}

private fun List<Long>.averageLong(): Long {
    if (isEmpty()) return 0L
    return (sum().toDouble() / size).toLong()
}

private fun List<Long>.percentile(percentile: Int): Long {
    if (isEmpty()) return 0L
    val sorted = sorted()
    val index = ((percentile / 100.0) * (sorted.size - 1)).toInt()
    return sorted[index.coerceIn(0, sorted.lastIndex)]
}

private fun elapsedSince(startedAt: Long): Long {
    return SystemClock.elapsedRealtime() - startedAt
}

private fun Int.shouldLogIndexingProgress(totalCount: Int): Boolean {
    return this == 0 ||
        this == totalCount - 1 ||
        totalCount <= 20 ||
        this % 25 == 0
}
