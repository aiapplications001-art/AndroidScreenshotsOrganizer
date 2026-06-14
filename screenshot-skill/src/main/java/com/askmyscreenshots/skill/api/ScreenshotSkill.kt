package com.askmyscreenshots.skill.api

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.askmyscreenshots.skill.access.ScreenshotAccessChecker
import com.askmyscreenshots.skill.categories.CategoryBrowser
import com.askmyscreenshots.skill.data.ScreenshotSkillDatabase
import com.askmyscreenshots.skill.debug.SkillDebugLog
import com.askmyscreenshots.skill.index.ScreenshotIndexWorker
import com.askmyscreenshots.skill.mindmap.MindMapBuilder
import com.askmyscreenshots.skill.search.LocalSearchEngine
import com.askmyscreenshots.skill.semantic.HashingTextEmbedder
import com.askmyscreenshots.skill.semantic.TextEmbedder
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.util.UUID

private const val INDEX_WORK_NAME = "screenshot-index-active"
private const val INDEX_WORK_TAG = "screenshot-index"
private const val WORK_CLEANUP_PREFS = "ask_my_screenshots_worker_cleanup"
private const val WORK_CLEANUP_VERSION_KEY = "cleanup_version"
private const val WORK_CLEANUP_VERSION = 1

interface ScreenshotSkill {
    fun checkScreenshotAccess(): ScreenshotAccessState
    fun organizeScreenshots(request: OrganizeRequest): Flow<OrganizeProgress>
    fun enqueueOrganizeScreenshots(request: OrganizeRequest): OrganizeWorkHandle
    fun observeOrganizeWork(workId: String): Flow<OrganizeProgress>
    suspend fun askScreenshots(
        request: AskRequest,
        onProgress: (AskProgress) -> Unit = {},
    ): AskResponse
    suspend fun searchScreenshots(request: SearchRequest): SearchResponse
    suspend fun categoryOverview(request: CategoryOverviewRequest = CategoryOverviewRequest()): CategoryOverview
    suspend fun categoryBucketDetail(request: CategoryBucketDetailRequest): CategoryBucketDetail
    suspend fun buildMindMap(request: MindMapRequest): MindMapGraph
    suspend fun deleteLocalIndex(scope: DeleteScope)

    companion object {
        fun create(
            context: Context,
            remoteQueryRewriter: RemoteQueryRewriter? = null,
            remoteClusterLabeler: RemoteClusterLabeler? = null,
            remoteAnswerSynthesizer: RemoteAnswerSynthesizer? = null,
        ): ScreenshotSkill {
            return AndroidScreenshotSkill(
                context = context.applicationContext,
                remoteQueryRewriter = remoteQueryRewriter,
                remoteClusterLabeler = remoteClusterLabeler,
                remoteAnswerSynthesizer = remoteAnswerSynthesizer,
            )
        }
    }
}

private class AndroidScreenshotSkill(
    private val context: Context,
    private val remoteQueryRewriter: RemoteQueryRewriter?,
    private val remoteClusterLabeler: RemoteClusterLabeler?,
    private val remoteAnswerSynthesizer: RemoteAnswerSynthesizer?,
) : ScreenshotSkill {
    private val database by lazy { ScreenshotSkillDatabase.get(context) }
    private val workManager by lazy { WorkManager.getInstance(context) }

    init {
        deleteObsoleteLocalAiModelFiles()
        cancelObsoleteIndexWorkersIfNeeded()
    }

    override fun checkScreenshotAccess(): ScreenshotAccessState {
        return ScreenshotAccessChecker(context).check().also { state ->
            SkillDebugLog.i(
                event = "access_check",
                message = "status=${state.status} canReadMediaStore=${state.canReadMediaStore} " +
                    "picker=${state.canUsePhotoPicker} missing=${state.missingPermissions.size}",
            )
        }
    }

    override fun organizeScreenshots(request: OrganizeRequest): Flow<OrganizeProgress> {
        val handle = enqueueOrganizeScreenshots(request)
        return observeOrganizeWork(handle.workId)
    }

    override fun enqueueOrganizeScreenshots(request: OrganizeRequest): OrganizeWorkHandle {
        SkillDebugLog.i(
            event = "organize_enqueue",
            message = "source=${request.source} reindex=${request.reindexPolicy} " +
                "start=${request.startMillis} end=${request.endMillisExclusive} " +
                "pickedCount=${request.pickedImageUris.size}",
        )
        val workRequest = OneTimeWorkRequestBuilder<ScreenshotIndexWorker>()
            .setInputData(ScreenshotIndexWorker.inputData(request))
            .addTag(INDEX_WORK_TAG)
            .build()
        val workName = request.organizeWorkName()
        SkillDebugLog.d("organize_enqueue", "workName=$workName workId=${workRequest.id}")
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, workRequest)
        return OrganizeWorkHandle(
            workId = workRequest.id.toString(),
            workName = workName,
        )
    }

    override fun observeOrganizeWork(workId: String): Flow<OrganizeProgress> {
        val uuid = UUID.fromString(workId)
        return workManager.getWorkInfoByIdFlow(uuid)
            .map { info -> info?.toOrganizeProgress() ?: OrganizeProgress.Queued }
            .onStart { emit(OrganizeProgress.Queued) }
            .distinctUntilChanged()
    }

    override suspend fun askScreenshots(
        request: AskRequest,
        onProgress: (AskProgress) -> Unit,
    ): AskResponse {
        SkillDebugLog.i(
            event = "ask_start",
            message = "queryLength=${request.query.length} maxResults=${request.maxResults} " +
                "remoteRewrite=${request.allowRemoteRewrite} hasDateRange=${request.dateRange != null}",
        )
        return LocalSearchEngine(
            dao = database.screenshotDao(),
            remoteQueryRewriter = remoteQueryRewriter,
            remoteAnswerSynthesizer = remoteAnswerSynthesizer,
            textEmbedder = textEmbedderForSearch(),
        ).ask(request, onProgress = onProgress).also { response ->
            SkillDebugLog.i(
                event = "ask_done",
                message = "task=${response.trace.taskType} mode=${response.trace.mode} " +
                    "channels=${response.trace.evidenceChannels.map { it.channel }} groups=${response.evidenceGroups.size} " +
                    "refs=${response.flatEvidence.size} facets=${response.facets.size} " +
                    "entities=${response.matchedEntities.size} " +
                    "remoteUsed=${response.privacyTrace.remoteRewriteUsed} " +
                    "remoteAnswer=${response.trace.remoteAnswerUsed} " +
                    "answerType=${response.answerCard.answerType}",
            )
        }
    }

    override suspend fun searchScreenshots(request: SearchRequest): SearchResponse {
        SkillDebugLog.i(
            event = "search_start",
            message = "queryLength=${request.query.length} maxResults=${request.maxResults} " +
                "remoteRewrite=${request.allowRemoteRewrite} hasDateRange=${request.dateRange != null}",
        )
        return LocalSearchEngine(
            dao = database.screenshotDao(),
            remoteQueryRewriter = remoteQueryRewriter,
            remoteAnswerSynthesizer = remoteAnswerSynthesizer,
            textEmbedder = textEmbedderForSearch(),
        ).search(request).also { response ->
            SkillDebugLog.i(
                event = "search_done",
                message = "refs=${response.screenshotRefs.size} groups=${response.evidenceGroups.size} " +
                    "facets=${response.facets.size} entities=${response.matchedEntities.size} " +
                    "remoteUsed=${response.privacyTrace.remoteRewriteUsed} " +
                    "answerType=${response.answerCard?.answerType.orEmpty()}",
            )
        }
    }

    override suspend fun categoryOverview(request: CategoryOverviewRequest): CategoryOverview {
        SkillDebugLog.i(
            event = "category_overview_start",
            message = "maxBuckets=${request.maxBucketsPerSection} sampleSize=${request.sampleSize} " +
                "hasDateRange=${request.dateRange != null}",
        )
        return CategoryBrowser(database.screenshotDao()).overview(request).also { overview ->
            SkillDebugLog.i(
                event = "category_overview_done",
                message = "screenshots=${overview.totalScreenshotCount} dynamic=${overview.dynamicCategories.size} " +
                    "apps=${overview.appSources.size} visual=${overview.visualLabels.size} entities=${overview.entityTypes.size}",
            )
        }
    }

    override suspend fun categoryBucketDetail(request: CategoryBucketDetailRequest): CategoryBucketDetail {
        SkillDebugLog.i(
            event = "category_detail_start",
            message = "type=${request.bucket.type} query=${request.bucket.queryValue} limit=${request.limit}",
        )
        return CategoryBrowser(database.screenshotDao()).detail(request).also { detail ->
            SkillDebugLog.i(
                event = "category_detail_done",
                message = "type=${detail.bucket.type} query=${detail.bucket.queryValue} screenshots=${detail.screenshots.size}",
            )
        }
    }

    override suspend fun buildMindMap(request: MindMapRequest): MindMapGraph {
        SkillDebugLog.i(
            event = "mindmap_start",
            message = "maxScreenshots=${request.maxScreenshots} maxClusters=${request.maxClusters} " +
                "remoteLabels=${request.allowRemoteLabeling} hasDateRange=${request.dateRange != null}",
        )
        return MindMapBuilder(
            dao = database.screenshotDao(),
            remoteClusterLabeler = remoteClusterLabeler,
        ).build(request).also { graph ->
            SkillDebugLog.i(
                event = "mindmap_done",
                message = "clusters=${graph.clusters.size} signals=${graph.topSignals.size} " +
                    "screenshots=${graph.summary.indexedScreenshotCount}",
            )
        }
    }

    override suspend fun deleteLocalIndex(scope: DeleteScope) {
        SkillDebugLog.i("delete_index", "scope=${scope::class.simpleName}")
        when (scope) {
            DeleteScope.All -> database.screenshotDao().deleteAllIndexData()
            is DeleteScope.DateRange -> database.screenshotDao().deleteScreenshotsInRange(
                startMillis = scope.range.startMillis,
                endMillisExclusive = scope.range.endMillisExclusive,
            )
        }
    }

    private fun textEmbedderForSearch(): TextEmbedder = HashingTextEmbedder()

    private fun deleteObsoleteLocalAiModelFiles() {
        val modelDir = File(context.filesDir, "local-ai-models")
        if (!modelDir.exists()) return
        val bytes = modelDir.walkBottomUp()
            .filter { it.isFile }
            .sumOf { it.length() }
        val deleted = runCatching { modelDir.deleteRecursively() }.getOrDefault(false)
        SkillDebugLog.i(
            event = "local_model_cleanup",
            message = "deleted=$deleted bytes=$bytes",
        )
    }

    private fun cancelObsoleteIndexWorkersIfNeeded() {
        val prefs = context.getSharedPreferences(WORK_CLEANUP_PREFS, Context.MODE_PRIVATE)
        val cleanupVersion = prefs.getInt(WORK_CLEANUP_VERSION_KEY, 0)
        if (cleanupVersion >= WORK_CLEANUP_VERSION) return
        workManager.cancelAllWork()
        prefs.edit().putInt(WORK_CLEANUP_VERSION_KEY, WORK_CLEANUP_VERSION).apply()
        SkillDebugLog.i(
            event = "index_work_cleanup",
            message = "cancelled=true version=$WORK_CLEANUP_VERSION",
        )
    }

    private fun WorkInfo.toOrganizeProgress(): OrganizeProgress {
        return when (state) {
            WorkInfo.State.ENQUEUED -> OrganizeProgress.Queued
            WorkInfo.State.RUNNING -> {
                val stage = progress.getString(ScreenshotIndexWorker.KEY_STAGE).orEmpty()
                when (stage) {
                    ScreenshotIndexWorker.STAGE_SCANNING -> OrganizeProgress.Scanning(
                        candidateCount = progress.getInt(ScreenshotIndexWorker.KEY_TOTAL, 0),
                    )

                    ScreenshotIndexWorker.STAGE_PREPARING_LOCAL_OCR -> OrganizeProgress.PreparingLocalOcr
                    ScreenshotIndexWorker.STAGE_DOWNLOADING_LOCAL_OCR -> OrganizeProgress.DownloadingLocalOcr
                    ScreenshotIndexWorker.STAGE_BACKFILLING_LOCAL_AI -> OrganizeProgress.BackfillingLocalAi(
                        processedCount = progress.getInt(ScreenshotIndexWorker.KEY_PROCESSED, 0),
                        totalCount = progress.getInt(ScreenshotIndexWorker.KEY_TOTAL, 0),
                        stage = progress.getString(ScreenshotIndexWorker.KEY_BACKFILL_STAGE).orEmpty(),
                        skippedCount = progress.getInt(ScreenshotIndexWorker.KEY_SKIPPED, 0),
                        candidateCount = progress.getInt(ScreenshotIndexWorker.KEY_CANDIDATE_TOTAL, 0),
                    )

                    else -> OrganizeProgress.Indexing(
                        processedCount = progress.getInt(ScreenshotIndexWorker.KEY_PROCESSED, 0),
                        totalCount = progress.getInt(ScreenshotIndexWorker.KEY_TOTAL, 0),
                        currentTitle = progress.getString(ScreenshotIndexWorker.KEY_TITLE),
                        skippedCount = progress.getInt(ScreenshotIndexWorker.KEY_SKIPPED, 0),
                    )
                }
            }

            WorkInfo.State.SUCCEEDED -> OrganizeProgress.Completed(
                runId = outputData.getLong(ScreenshotIndexWorker.KEY_RUN_ID, 0L),
                totalCount = outputData.getInt(ScreenshotIndexWorker.KEY_TOTAL, 0),
                indexedCount = outputData.getInt(ScreenshotIndexWorker.KEY_INDEXED, 0),
                failedCount = outputData.getInt(ScreenshotIndexWorker.KEY_FAILED, 0),
                skippedCount = outputData.getInt(ScreenshotIndexWorker.KEY_SKIPPED, 0),
            )

            WorkInfo.State.FAILED -> OrganizeProgress.Failed(
                outputData.getString(ScreenshotIndexWorker.KEY_ERROR) ?: "Indexing failed",
            )

            WorkInfo.State.CANCELLED -> OrganizeProgress.Cancelled
            WorkInfo.State.BLOCKED -> OrganizeProgress.Queued
        }
    }
}

private fun OrganizeRequest.organizeWorkName(): String {
    return INDEX_WORK_NAME
}
