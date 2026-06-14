package com.askmyscreenshots.skill.index

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.askmyscreenshots.skill.api.OrganizeProgress
import com.askmyscreenshots.skill.api.OrganizeRequest
import com.askmyscreenshots.skill.api.ReindexPolicy
import com.askmyscreenshots.skill.api.ScreenshotSource
import com.askmyscreenshots.skill.data.ScreenshotSkillDatabase
import com.askmyscreenshots.skill.debug.SkillDebugLog
import com.askmyscreenshots.skill.media.ScreenshotMediaScanner
import com.askmyscreenshots.skill.ml.MlKitScreenshotAnalyzer
import com.askmyscreenshots.skill.semantic.HashingTextEmbedder
import com.askmyscreenshots.skill.visual.HeuristicVisualCaptioner

class ScreenshotIndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val request = inputData.toOrganizeRequest()
        SkillDebugLog.i(
            event = "worker_start",
            message = "id=$id attempt=$runAttemptCount source=${request.source} " +
                "start=${request.startMillis} end=${request.endMillisExclusive}",
        )
        val dao = ScreenshotSkillDatabase.get(applicationContext).screenshotDao()
        return runCatching {
            SkillDebugLog.i(
                event = "worker_local_models_ready",
                message = "text_embedder=local_hashing image_embedder=removed visual_captioner=heuristic",
            )
            val indexer = ScreenshotIndexer(
                dao = dao,
                scanner = ScreenshotMediaScanner(applicationContext),
                analyzer = MlKitScreenshotAnalyzer(applicationContext),
                textEmbedder = HashingTextEmbedder(),
                visualCaptioner = HeuristicVisualCaptioner(),
            )
            val result = indexer.index(request) { progress ->
                setProgress(progress.toData())
            }
            SkillDebugLog.i(
                event = "worker_success",
                message = "id=$id runId=${result.runId} total=${result.totalCount} " +
                    "indexed=${result.indexedCount} skipped=${result.skippedCount} " +
                    "failed=${result.failedCount}",
            )
            Result.success(
                workDataOf(
                    KEY_RUN_ID to result.runId,
                    KEY_TOTAL to result.totalCount,
                    KEY_INDEXED to result.indexedCount,
                    KEY_SKIPPED to result.skippedCount,
                    KEY_FAILED to result.failedCount,
                ),
            )
        }.getOrElse { error ->
            SkillDebugLog.e(
                event = "worker_failure",
                message = "id=$id attempt=$runAttemptCount",
                throwable = error,
            )
            Result.failure(
                workDataOf(
                    KEY_ERROR to (error.message ?: "Indexing failed"),
                ),
            )
        }
    }

    private fun Data.toOrganizeRequest(): OrganizeRequest {
        return OrganizeRequest(
            startMillis = getLong(KEY_START, 0L),
            endMillisExclusive = getLong(KEY_END, Long.MAX_VALUE),
            source = getString(KEY_SOURCE)?.let { ScreenshotSource.valueOf(it) }
                ?: ScreenshotSource.MEDIA_STORE,
            reindexPolicy = getString(KEY_REINDEX_POLICY)?.let { ReindexPolicy.valueOf(it) }
                ?: ReindexPolicy.INCREMENTAL,
            pickedImageUris = getStringArray(KEY_PICKED_URIS)?.toList().orEmpty(),
        )
    }

    private fun OrganizeProgress.toData(): Data {
        return when (this) {
            OrganizeProgress.Queued -> workDataOf(KEY_STAGE to STAGE_QUEUED)
            is OrganizeProgress.Scanning -> workDataOf(
                KEY_STAGE to STAGE_SCANNING,
                KEY_TOTAL to candidateCount,
            )

            OrganizeProgress.PreparingLocalOcr -> workDataOf(KEY_STAGE to STAGE_PREPARING_LOCAL_OCR)
            OrganizeProgress.DownloadingLocalOcr -> workDataOf(KEY_STAGE to STAGE_DOWNLOADING_LOCAL_OCR)
            is OrganizeProgress.BackfillingLocalAi -> workDataOf(
                KEY_STAGE to STAGE_BACKFILLING_LOCAL_AI,
                KEY_PROCESSED to processedCount,
                KEY_TOTAL to totalCount,
                KEY_BACKFILL_STAGE to stage,
                KEY_SKIPPED to skippedCount,
                KEY_CANDIDATE_TOTAL to candidateCount,
            )

            is OrganizeProgress.Indexing -> workDataOf(
                KEY_STAGE to STAGE_INDEXING,
                KEY_PROCESSED to processedCount,
                KEY_TOTAL to totalCount,
                KEY_SKIPPED to skippedCount,
                KEY_TITLE to currentTitle,
            )

            is OrganizeProgress.Completed -> workDataOf(
                KEY_STAGE to STAGE_COMPLETED,
                KEY_RUN_ID to runId,
                KEY_TOTAL to totalCount,
                KEY_INDEXED to indexedCount,
                KEY_SKIPPED to skippedCount,
                KEY_FAILED to failedCount,
            )

            is OrganizeProgress.Failed -> workDataOf(
                KEY_STAGE to STAGE_FAILED,
                KEY_ERROR to message,
            )

            OrganizeProgress.Cancelled -> workDataOf(KEY_STAGE to STAGE_CANCELLED)
        }
    }

    companion object {
        const val KEY_START = "startMillis"
        const val KEY_END = "endMillisExclusive"
        const val KEY_SOURCE = "source"
        const val KEY_REINDEX_POLICY = "reindexPolicy"
        const val KEY_PICKED_URIS = "pickedImageUris"
        const val KEY_STAGE = "stage"
        const val KEY_PROCESSED = "processedCount"
        const val KEY_TOTAL = "totalCount"
        const val KEY_CANDIDATE_TOTAL = "candidateTotalCount"
        const val KEY_TITLE = "currentTitle"
        const val KEY_BACKFILL_STAGE = "backfillStage"
        const val KEY_RUN_ID = "runId"
        const val KEY_INDEXED = "indexedCount"
        const val KEY_SKIPPED = "skippedCount"
        const val KEY_FAILED = "failedCount"
        const val KEY_ERROR = "error"

        const val STAGE_QUEUED = "QUEUED"
        const val STAGE_SCANNING = "SCANNING"
        const val STAGE_PREPARING_LOCAL_OCR = "PREPARING_LOCAL_OCR"
        const val STAGE_DOWNLOADING_LOCAL_OCR = "DOWNLOADING_LOCAL_OCR"
        const val STAGE_BACKFILLING_LOCAL_AI = "BACKFILLING_LOCAL_AI"
        const val STAGE_INDEXING = "INDEXING"
        const val STAGE_COMPLETED = "COMPLETED"
        const val STAGE_FAILED = "FAILED"
        const val STAGE_CANCELLED = "CANCELLED"

        fun inputData(request: OrganizeRequest): Data {
            return workDataOf(
                KEY_START to request.startMillis,
                KEY_END to request.endMillisExclusive,
                KEY_SOURCE to request.source.name,
                KEY_REINDEX_POLICY to request.reindexPolicy.name,
                KEY_PICKED_URIS to request.pickedImageUris.toTypedArray(),
            )
        }
    }
}
