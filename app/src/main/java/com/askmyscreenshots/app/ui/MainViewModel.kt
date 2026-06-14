package com.askmyscreenshots.app.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.askmyscreenshots.app.data.AccessMode
import com.askmyscreenshots.app.data.AppDatabase
import com.askmyscreenshots.app.data.OrganizationError
import com.askmyscreenshots.app.data.OrganizationRunEntity
import com.askmyscreenshots.app.data.RunStatus
import com.askmyscreenshots.app.debug.AppDebugLog
import com.askmyscreenshots.app.domain.DateRange
import com.askmyscreenshots.app.domain.DateRangeCalculator
import com.askmyscreenshots.app.domain.DateRangePreset
import com.askmyscreenshots.app.domain.toDisplayDate
import com.askmyscreenshots.app.llm.ConfiguredRemoteQueryRewriter
import com.askmyscreenshots.skill.api.AskProgress
import com.askmyscreenshots.skill.api.AskProgressStep
import com.askmyscreenshots.skill.api.AskRequest
import com.askmyscreenshots.skill.api.AskResponse
import com.askmyscreenshots.skill.api.CategoryBucket
import com.askmyscreenshots.skill.api.CategoryBucketDetailRequest
import com.askmyscreenshots.skill.api.CategoryOverviewRequest
import com.askmyscreenshots.skill.api.DeleteScope
import com.askmyscreenshots.skill.api.MindMapRequest
import com.askmyscreenshots.skill.api.MindMapGraph
import com.askmyscreenshots.skill.api.OrganizeProgress
import com.askmyscreenshots.skill.api.OrganizeRequest
import com.askmyscreenshots.skill.api.ReindexPolicy
import com.askmyscreenshots.skill.api.ScreenshotSkill
import com.askmyscreenshots.skill.api.ScreenshotAccessStatus
import com.askmyscreenshots.skill.api.ScreenshotSource
import com.askmyscreenshots.skill.api.SkillDateRange
import com.askmyscreenshots.skill.api.SuggestedAction
import com.askmyscreenshots.skill.api.SuggestedActionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val remoteQueryRewriter = ConfiguredRemoteQueryRewriter.fromBuildConfig()
    private val remoteClusterLabeler = ConfiguredRemoteQueryRewriter.clusterLabelerFromBuildConfig()
    private val remoteAnswerSynthesizer = ConfiguredRemoteQueryRewriter.answerSynthesizerFromBuildConfig()
    private val screenshotSkill = ScreenshotSkill.create(
        context = application,
        remoteQueryRewriter = remoteQueryRewriter,
        remoteClusterLabeler = remoteClusterLabeler,
        remoteAnswerSynthesizer = remoteAnswerSynthesizer,
    )
    private val organizationDao = AppDatabase.get(application).organizationDao()
    private val preferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val latestRun = MutableStateFlow<RunSummary?>(null)
    private val selectedPreset = MutableStateFlow(DateRangePreset.LAST_7_DAYS)
    private val customStart = MutableStateFlow(LocalDate.now())
    private val customEnd = MutableStateFlow(LocalDate.now())
    private val operationState = MutableStateFlow(OperationState())
    private val searchQuery = MutableStateFlow("")
    private val searchResponse = MutableStateFlow<AskResponse?>(null)
    private val mindMap = MutableStateFlow<MindMapGraph?>(null)
    private val categoriesState = MutableStateFlow(CategoriesUiState())
    private var activeOrganizationJob: Job? = null
    private var activeOrganizationWorkId: String? = null
    private val onlineQueryHelpEnabled = MutableStateFlow(remoteQueryRewriter != null)

    val uiState = combine(
        latestRun,
        selectedPreset,
        customStart,
        customEnd,
        operationState,
        searchQuery,
        searchResponse,
        mindMap,
        categoriesState,
        onlineQueryHelpEnabled,
    ) { values ->
        val preset = values[1] as DateRangePreset
        val start = values[2] as LocalDate
        val end = values[3] as LocalDate
        val operation = values[4] as OperationState
        MainUiState(
            latestRun = values[0] as RunSummary?,
            selectedPreset = preset,
            customStart = start,
            customEnd = end,
            currentRange = dateRangeFor(preset, start, end),
            isWorking = operation.isWorking,
            isAskWorking = operation.kind == OperationKind.ASK,
            askProgressStep = operation.askProgressStep,
            notice = operation.notice,
            searchQuery = values[5] as String,
            searchResponse = values[6] as AskResponse?,
            mindMap = values[7] as MindMapGraph?,
            categories = values[8] as CategoriesUiState,
            onlineQueryHelpEnabled = values[9] as Boolean,
            onlineQueryHelpAvailable = remoteQueryRewriter != null,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    init {
        AppDebugLog.i(
            event = "viewmodel_init",
            message = "remoteQueryHelpAvailable=${remoteQueryRewriter != null} " +
                "remoteClusterLabelsAvailable=${remoteClusterLabeler != null} " +
                "remoteAnswerSynthesisAvailable=${remoteAnswerSynthesizer != null} " +
                "onlineQueryHelpEnabled=${onlineQueryHelpEnabled.value}",
        )
        if (remoteQueryRewriter != null) {
            preferences.edit().putBoolean(KEY_ONLINE_QUERY_HELP, true).apply()
            AppDebugLog.i("online_query_help_locked", "enabled=true")
        }
        viewModelScope.launch {
            organizationDao.observeLatestRun().collect { run ->
                latestRun.value = run?.toRunSummary()
                AppDebugLog.i(
                    event = "latest_run_observed",
                    message = if (run == null) {
                        "exists=false"
                    } else {
                        "exists=true status=${run.status} preset=${run.dateRangePreset} " +
                            "candidates=${run.candidateCount} indexed=${run.indexedCount} " +
                            "new=${run.newlyIndexedCount} skipped=${run.skippedCount} failed=${run.failedCount}"
                    },
                )
                run?.let(::attachToActiveOrganizationRunIfNeeded)
            }
        }
    }

    fun onPresetSelected(preset: DateRangePreset) {
        AppDebugLog.i("preset_selected", "preset=${preset.name}")
        selectedPreset.value = preset
        clearNotice()
    }

    fun onCustomStartSelected(utcMillis: Long) {
        customStart.value = DateRangeCalculator.utcPickerMillisToLocalDate(utcMillis)
        selectedPreset.value = DateRangePreset.CUSTOM
        AppDebugLog.i("custom_start_selected", "utcMillis=$utcMillis")
        clearNotice()
    }

    fun onCustomEndSelected(utcMillis: Long) {
        customEnd.value = DateRangeCalculator.utcPickerMillisToLocalDate(utcMillis)
        selectedPreset.value = DateRangePreset.CUSTOM
        AppDebugLog.i("custom_end_selected", "utcMillis=$utcMillis")
        clearNotice()
    }

    fun organizeFromMediaStore() {
        val accessState = screenshotSkill.checkScreenshotAccess()
        if (accessState.status == ScreenshotAccessStatus.PARTIAL) {
            onPartialPhotoAccess()
            return
        }
        if (!accessState.canReadMediaStore) {
            onPermissionDenied(permanently = false)
            return
        }
        val range = currentDateRange()
        AppDebugLog.i(
            event = "organize_media_store",
            message = range.toLogMessage(ScreenshotSource.MEDIA_STORE, pickedCount = 0) +
                " access=${accessState.status}",
        )
        activeOrganizationJob?.cancel()
        activeOrganizationJob = viewModelScope.launch {
            runOrganization(
                OrganizeRequest(
                    startMillis = range.startMillis,
                    endMillisExclusive = range.endMillisExclusive,
                    source = ScreenshotSource.MEDIA_STORE,
                    reindexPolicy = ReindexPolicy.INCREMENTAL,
                ),
                range,
            )
        }
    }

    fun organizeFromPickedImages(uris: List<Uri>) {
        AppDebugLog.i("organize_picked_images", "pickedCount=${uris.size}")
        if (uris.isEmpty()) {
            operationState.value = OperationState(
                isWorking = false,
                notice = OperationNotice(
                    type = NoticeType.EMPTY_PICKER,
                    title = "No images selected",
                    message = "Pick screenshots or try another date range.",
                ),
            )
            return
        }

        val range = currentDateRange()
        AppDebugLog.i(
            event = "organize_picked_images_range",
            message = range.toLogMessage(ScreenshotSource.PICKED_IMAGES, pickedCount = uris.size),
        )
        activeOrganizationJob?.cancel()
        activeOrganizationJob = viewModelScope.launch {
            runOrganization(
                OrganizeRequest(
                    startMillis = range.startMillis,
                    endMillisExclusive = range.endMillisExclusive,
                    source = ScreenshotSource.PICKED_IMAGES,
                    reindexPolicy = ReindexPolicy.INCREMENTAL,
                    pickedImageUris = uris.map(Uri::toString),
                ),
                range,
            )
        }
    }

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
        clearNotice()
    }

    fun searchScreenshots() {
        searchScreenshots(queryOverride = null)
    }

    fun askAboutMindMap(query: String) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return
        AppDebugLog.i("mindmap_ask_query", "queryLength=${cleanQuery.length}")
        searchQuery.value = cleanQuery
        searchScreenshots(queryOverride = cleanQuery)
    }

    fun fillMindMapSearch(query: String) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return
        AppDebugLog.i("mindmap_fill_query", "queryLength=${cleanQuery.length}")
        searchQuery.value = cleanQuery
        clearNotice()
    }

    fun loadCategories() {
        AppDebugLog.i("categories_load_requested", "existing=${categoriesState.value.overview != null}")
        viewModelScope.launch {
            refreshCategoriesFromIndex(clearSelection = false)
        }
    }

    fun selectCategoryBucket(bucket: CategoryBucket) {
        AppDebugLog.i(
            event = "category_bucket_selected",
            message = "type=${bucket.type} query=${bucket.queryValue} count=${bucket.count}",
        )
        viewModelScope.launch {
            categoriesState.update {
                it.copy(
                    selectedBucket = bucket,
                    detail = null,
                    isLoading = true,
                    errorMessage = null,
                )
            }
            val result = runCatching {
                screenshotSkill.categoryBucketDetail(
                    CategoryBucketDetailRequest(
                        bucket = bucket,
                        limit = CATEGORY_DETAIL_LIMIT,
                    ),
                )
            }
            categoriesState.update { current ->
                if (result.isSuccess) {
                    current.copy(
                        selectedBucket = bucket,
                        detail = result.getOrNull(),
                        isLoading = false,
                        errorMessage = null,
                    )
                } else {
                    AppDebugLog.e(
                        event = "category_detail_failed",
                        message = "type=${bucket.type} query=${bucket.queryValue}",
                        throwable = result.exceptionOrNull(),
                    )
                    current.copy(
                        selectedBucket = bucket,
                        detail = null,
                        isLoading = false,
                        errorMessage = "Could not load this bucket.",
                    )
                }
            }
        }
    }

    fun clearSelectedCategoryBucket() {
        AppDebugLog.i("category_bucket_cleared", "hadSelection=${categoriesState.value.selectedBucket != null}")
        categoriesState.update {
            it.copy(
                selectedBucket = null,
                detail = null,
                isLoading = false,
                errorMessage = null,
            )
        }
    }

    private fun searchScreenshots(queryOverride: String?) {
        val query = (queryOverride ?: searchQuery.value).trim()
        AppDebugLog.i(
            event = "search_requested",
            message = "queryLength=${query.length} onlineQueryHelp=${onlineQueryHelpEnabled.value}",
        )
        if (query.isBlank()) {
            operationState.value = OperationState(
                isWorking = false,
                notice = OperationNotice(
                    type = NoticeType.SEARCH_EMPTY,
                    title = "Ask something",
                    message = "Type what you want to find in your indexed screenshots.",
                    destination = NoticeDestination.ASK,
                ),
            )
            return
        }

        viewModelScope.launch {
            operationState.value = OperationState(
                isWorking = true,
                kind = OperationKind.ASK,
                askProgressStep = AskProgressStep.UNDERSTANDING_QUERY,
                notice = null,
            )
            val result = runCatching {
                screenshotSkill.askScreenshots(
                    AskRequest(
                        query = query,
                        dateRange = null,
                        maxResults = 50,
                        allowRemoteRewrite = remoteQueryRewriter != null,
                    ),
                    onProgress = ::onAskProgress,
                )
            }
            searchResponse.value = result.getOrNull()
            operationState.value = if (result.isSuccess) {
                result.getOrNull()?.let { response ->
                    AppDebugLog.i(
                        event = "search_succeeded",
                        message = "task=${response.trace.taskType} mode=${response.trace.mode} " +
                            "channels=${response.trace.evidenceChannels.map { it.channel }} groups=${response.evidenceGroups.size} " +
                            "used=${response.usedEvidence.size} related=${response.trace.relatedEvidenceCount} " +
                            "refs=${response.flatEvidence.size} facets=${response.facets.size} " +
                            "entities=${response.matchedEntities.size} " +
                            "remoteRequested=${response.privacyTrace.remoteRewriteRequested} " +
                            "remoteUsed=${response.privacyTrace.remoteRewriteUsed} " +
                            "remoteAnswer=${response.trace.remoteAnswerUsed} " +
                            "dataSent=${response.privacyTrace.dataSentOffDevice.size} " +
                            "answerType=${response.answerCard.answerType}",
                    )
                }
                OperationState(isWorking = false, notice = null)
            } else {
                AppDebugLog.e(
                    event = "search_failed",
                    message = "queryLength=${query.length}",
                    throwable = result.exceptionOrNull(),
                )
                OperationState(
                    isWorking = false,
                    notice = OperationNotice(
                        type = NoticeType.ERROR,
                        title = "Search failed",
                        message = "The query router or local screenshot index could not answer that yet.",
                        destination = NoticeDestination.ASK,
                    ),
                )
            }
        }
    }

    fun onOnlineQueryHelpChanged(enabled: Boolean) {
        AppDebugLog.i(
            event = "online_query_help_changed",
            message = "requested=$enabled available=${remoteQueryRewriter != null} locked=true",
        )
        if (remoteQueryRewriter == null) {
            operationState.value = OperationState(
                isWorking = false,
                notice = OperationNotice(
                    type = NoticeType.INFO,
                    title = "Online query help unavailable",
                    message = "This APK was built without a Gemini query planner. Local search still works.",
                    destination = NoticeDestination.ASK,
                ),
            )
            onlineQueryHelpEnabled.value = false
            preferences.edit().putBoolean(KEY_ONLINE_QUERY_HELP, false).apply()
            return
        }

        onlineQueryHelpEnabled.value = true
        preferences.edit().putBoolean(KEY_ONLINE_QUERY_HELP, true).apply()
        operationState.value = OperationState(
            isWorking = false,
            notice = OperationNotice(
                type = NoticeType.INFO,
                title = "Online query help on",
                message = "Gemini plans screenshot searches and may phrase answers from compact evidence text. Screenshot images stay on this phone.",
                destination = NoticeDestination.ASK,
            ),
        )
    }

    fun executeSuggestedAction(action: SuggestedAction) {
        val context = getApplication<Application>().applicationContext
        AppDebugLog.i(
            event = "suggested_action_clicked",
            message = "type=${action.type} valueLength=${action.value.length}",
        )
        when (action.type) {
            SuggestedActionType.COPY_TEXT -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(action.label, action.value))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }

            SuggestedActionType.OPEN_URL -> {
                openIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse(action.value.withUrlScheme())))
            }

            SuggestedActionType.DIAL_PHONE -> {
                openIntent(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:${action.value}")))
            }

            SuggestedActionType.EMAIL -> {
                openIntent(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${action.value}")))
            }

            SuggestedActionType.OPEN_MAPS -> {
                openIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(action.value)}")))
            }

            SuggestedActionType.ASK_FOLLOW_UP,
            SuggestedActionType.FILTER_THIS,
            -> {
                searchQuery.value = action.value
                searchScreenshots(queryOverride = action.value)
            }

            SuggestedActionType.SHARE_SCREENSHOT -> {
                openIntent(
                    context,
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, action.value)
                    },
                )
            }
        }
    }

    fun deleteLocalMemory() {
        AppDebugLog.i("delete_local_memory", "requested=true")
        viewModelScope.launch {
            operationState.value = OperationState(isWorking = true, notice = null)
            val result = runCatching {
                screenshotSkill.deleteLocalIndex(DeleteScope.All)
                organizationDao.deleteCandidates()
                organizationDao.deleteRuns()
            }

            if (result.isSuccess) {
                AppDebugLog.i("delete_local_memory", "result=success")
                latestRun.value = null
                searchResponse.value = null
                mindMap.value = null
                categoriesState.value = CategoriesUiState()
                operationState.value = OperationState(
                    isWorking = false,
                    notice = OperationNotice(
                        type = NoticeType.INFO,
                        title = "Local memory deleted",
                        message = "The encrypted screenshot index was cleared. Your original screenshots were not deleted.",
                    ),
                )
            } else {
                AppDebugLog.e(
                    event = "delete_local_memory",
                    message = "result=failure",
                    throwable = result.exceptionOrNull(),
                )
                operationState.value = OperationState(
                    isWorking = false,
                    notice = OperationNotice(
                        type = NoticeType.ERROR,
                        title = "Delete failed",
                        message = "The local screenshot index could not be cleared.",
                    ),
                )
            }
        }
    }

    fun buildMindMap() {
        val range = currentDateRange()
        AppDebugLog.i(
            event = "mindmap_requested",
            message = "start=${range.startMillis} end=${range.endMillisExclusive}",
        )
        viewModelScope.launch {
            operationState.value = OperationState(
                isWorking = true,
                notice = OperationNotice(
                    type = NoticeType.INFO,
                    title = "Building memory map",
                    message = "Finding clusters, topics, people, apps, and screenshots in this date range.",
                    destination = NoticeDestination.ASK,
                ),
            )
            val result = runCatching {
                screenshotSkill.buildMindMap(
                    MindMapRequest(
                        dateRange = SkillDateRange(
                            startMillis = range.startMillis,
                            endMillisExclusive = range.endMillisExclusive,
                        ),
                        maxScreenshots = 600,
                        maxClusters = 12,
                        maxSignals = 30,
                        allowRemoteLabeling = remoteClusterLabeler != null,
                    ),
                )
            }
            mindMap.value = result.getOrNull()
            operationState.value = if (result.isSuccess) {
                result.getOrNull()?.let { graph ->
                    AppDebugLog.i(
                        event = "mindmap_succeeded",
                        message = "clusters=${graph.clusters.size} signals=${graph.topSignals.size} " +
                            "screenshots=${graph.summary.indexedScreenshotCount}",
                    )
                }
                OperationState(isWorking = false, notice = null)
            } else {
                AppDebugLog.e(
                    event = "mindmap_failed",
                    message = "rangeStart=${range.startMillis}",
                    throwable = result.exceptionOrNull(),
                )
                OperationState(
                    isWorking = false,
                    notice = OperationNotice(
                        type = NoticeType.ERROR,
                        title = "Mind map failed",
                        message = "The local index could not build a graph yet.",
                        destination = NoticeDestination.ASK,
                    ),
                )
            }
        }
    }

    private suspend fun refreshCategoriesFromIndex(clearSelection: Boolean) {
        if (categoriesState.value.isLoading && !clearSelection) return
        categoriesState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                selectedBucket = if (clearSelection) null else it.selectedBucket,
                detail = if (clearSelection) null else it.detail,
            )
        }
        val result = runCatching {
            screenshotSkill.categoryOverview(
                CategoryOverviewRequest(
                    maxBucketsPerSection = CATEGORY_SECTION_LIMIT,
                    sampleSize = CATEGORY_SAMPLE_SIZE,
                ),
            )
        }
        categoriesState.update { current ->
            if (result.isSuccess) {
                val overview = result.getOrNull()
                AppDebugLog.i(
                    event = "categories_loaded",
                    message = "screenshots=${overview?.totalScreenshotCount ?: 0} " +
                        "dynamic=${overview?.dynamicCategories?.size ?: 0} " +
                        "apps=${overview?.appSources?.size ?: 0} " +
                        "visual=${overview?.visualLabels?.size ?: 0} " +
                        "entities=${overview?.entityTypes?.size ?: 0}",
                )
                current.copy(
                    overview = overview,
                    selectedBucket = if (clearSelection) null else current.selectedBucket,
                    detail = if (clearSelection) null else current.detail,
                    isLoading = false,
                    errorMessage = null,
                )
            } else {
                AppDebugLog.e(
                    event = "categories_load_failed",
                    message = "clearSelection=$clearSelection",
                    throwable = result.exceptionOrNull(),
                )
                current.copy(
                    isLoading = false,
                    errorMessage = "Could not load categories.",
                )
            }
        }
    }

    fun onPermissionDenied(permanently: Boolean) {
        AppDebugLog.w(
            event = "permission_denied",
            message = "permanently=$permanently",
        )
        operationState.value = OperationState(
            isWorking = false,
            notice = OperationNotice(
                type = if (permanently) {
                    NoticeType.PERMISSION_PERMANENTLY_DENIED
                } else {
                    NoticeType.PERMISSION_DENIED
                },
                title = "Screenshot access needed",
                message = if (permanently) {
                    "Allow photo access in Android settings, then return here."
                } else {
                    "Allow photo access to detect screenshots in the selected range."
                },
            ),
        )
    }

    fun onFullPhotoAccessGranted() {
        AppDebugLog.i("permission_access", "status=FULL")
        organizeFromMediaStore()
    }

    fun onPartialPhotoAccess() {
        AppDebugLog.w("permission_access", "status=PARTIAL")
        operationState.value = OperationState(
            isWorking = false,
            notice = OperationNotice(
                type = NoticeType.PARTIAL_PHOTO_ACCESS,
                title = "Selected photos access",
                message = "Android only gave access to selected images. Automatic date-range updates need full photo access; otherwise select screenshots manually.",
            ),
        )
    }

    fun onPickerOnlyAccess() {
        AppDebugLog.i("permission_access", "status=PICKER_ONLY")
        operationState.value = OperationState(
            isWorking = false,
            notice = OperationNotice(
                type = NoticeType.INFO,
                title = "Select screenshots",
                message = "This Android version needs selected images. Pick screenshots to organize them.",
            ),
        )
    }

    fun clearNotice() {
        operationState.update { it.copy(notice = null) }
    }

    private fun attachToActiveOrganizationRunIfNeeded(run: OrganizationRunEntity) {
        val isInProgress = run.status == RunStatus.IN_PROGRESS.value ||
            (run.status == RunStatus.STARTED.value && run.completedAtMillis == null)
        val workId = run.workId
        if (!isInProgress || workId.isNullOrBlank()) return
        if (activeOrganizationWorkId == workId && activeOrganizationJob?.isActive == true) return

        activeOrganizationJob?.cancel()
        activeOrganizationWorkId = workId
        activeOrganizationJob = viewModelScope.launch {
            val range = run.toDateRange()
            AppDebugLog.i(
                event = "organize_reattach",
                message = "runId=${run.id} workId=$workId workName=${run.workName.orEmpty()} " +
                    "processed=${run.processedCount} total=${run.candidateCount}",
            )
            operationState.value = OperationState(
                isWorking = true,
                notice = OperationNotice(
                    type = NoticeType.INFO,
                    title = "Organization in progress",
                    message = "Reconnected to the local indexing job.",
                ),
            )
            runCatching {
                collectOrganizationWork(
                    workId = workId,
                    range = range,
                    appRunId = run.id,
                    startedAtMillis = run.startedAtMillis,
                )
            }.onFailure {
                if (it is CancellationException) throw it
                AppDebugLog.e(
                    event = "organize_reattach_failed",
                    message = "runId=${run.id} workId=$workId",
                    throwable = it,
                )
                markRunFailed(
                    runId = run.id,
                    range = range,
                    startedAtMillis = run.startedAtMillis,
                    errorCode = "ORGANIZE_REATTACH_FAILED",
                )
            }
        }
    }

    private suspend fun runOrganization(request: OrganizeRequest, range: DateRange) {
        AppDebugLog.i(
            event = "organize_started",
            message = range.toLogMessage(request.source, request.pickedImageUris.size),
        )
        operationState.value = OperationState(isWorking = true, notice = null)
        val startedAtMillis = System.currentTimeMillis()
        val accessMode = when (request.source) {
            ScreenshotSource.MEDIA_STORE -> AccessMode.MEDIA_STORE.value
            ScreenshotSource.PICKED_IMAGES -> AccessMode.PHOTO_PICKER.value
        }
        val appRunId = runCatching {
            organizationDao.insertRun(
                OrganizationRunEntity(
                    dateRangePreset = range.preset.name,
                    startMillis = range.startMillis,
                    endMillis = range.endMillisExclusive,
                    status = RunStatus.IN_PROGRESS.value,
                    candidateCount = 0,
                    indexedCount = 0,
                    newlyIndexedCount = 0,
                    skippedCount = 0,
                    failedCount = 0,
                    accessMode = accessMode,
                    startedAtMillis = startedAtMillis,
                    completedAtMillis = null,
                    errorCode = null,
                ),
            )
        }.getOrElse {
            AppDebugLog.e(
                event = "organize_run_create_failed",
                message = range.toLogMessage(request.source, request.pickedImageUris.size),
                throwable = it,
            )
            operationState.value = OperationState(
                isWorking = false,
                notice = OperationNotice(
                    type = NoticeType.ERROR,
                    title = "Could not start",
                    message = "The local organization run could not be recorded. Try again.",
                ),
            )
            return
        }
        latestRun.value = runSummary(
            id = appRunId,
            state = OrganizationRunState.IN_PROGRESS,
            totalCount = 0,
            processedCount = 0,
            organizedCount = 0,
            newlyIndexedCount = 0,
            skippedCount = 0,
            failedCount = 0,
            preset = range.preset,
            startMillis = range.startMillis,
            endMillisExclusive = range.endMillisExclusive,
            startedAtMillis = startedAtMillis,
            completedAtMillis = null,
        )
        val workHandle = runCatching {
            screenshotSkill.enqueueOrganizeScreenshots(request)
        }.onFailure {
            AppDebugLog.e(
                event = "organize_enqueue_failed",
                message = range.toLogMessage(request.source, request.pickedImageUris.size),
                throwable = it,
            )
            markRunFailed(
                runId = appRunId,
                range = range,
                startedAtMillis = startedAtMillis,
                errorCode = "ORGANIZE_ENQUEUE_FAILED",
            )
            operationState.value = OperationState(
                isWorking = false,
                notice = OperationNotice(
                    type = NoticeType.ERROR,
                    title = "Could not organize",
                    message = "Check photo access and try again.",
                ),
            )
            return
        }.getOrThrow()

        activeOrganizationWorkId = workHandle.workId
        organizationDao.updateRunWork(
            runId = appRunId,
            workId = workHandle.workId,
            workName = workHandle.workName,
            updatedAtMillis = System.currentTimeMillis(),
        )
        runCatching {
            collectOrganizationWork(
                workId = workHandle.workId,
                range = range,
                appRunId = appRunId,
                startedAtMillis = startedAtMillis,
            )
        }.onFailure {
            if (it is CancellationException) throw it
            AppDebugLog.e(
                event = "organize_collect_failed",
                message = range.toLogMessage(request.source, request.pickedImageUris.size),
                throwable = it,
            )
            markRunFailed(
                runId = appRunId,
                range = range,
                startedAtMillis = startedAtMillis,
                errorCode = "ORGANIZE_COLLECT_FAILED",
            )
            operationState.value = OperationState(
                isWorking = false,
                notice = OperationNotice(
                    type = NoticeType.ERROR,
                    title = "Could not organize",
                    message = "Check photo access and try again.",
                ),
            )
        }
    }

    private suspend fun collectOrganizationWork(
        workId: String,
        range: DateRange,
        appRunId: Long,
        startedAtMillis: Long,
    ) {
        screenshotSkill.observeOrganizeWork(workId).first { progress ->
            val terminal = handleOrganizeProgress(
                progress = progress,
                range = range,
                appRunId = appRunId,
                startedAtMillis = startedAtMillis,
            )
            if (terminal && activeOrganizationWorkId == workId) {
                activeOrganizationWorkId = null
                activeOrganizationJob = null
            }
            terminal
        }
    }

    private suspend fun handleOrganizeProgress(
        progress: OrganizeProgress,
        range: DateRange,
        appRunId: Long,
        startedAtMillis: Long,
    ): Boolean {
        operationState.value = when (progress) {
            OrganizeProgress.Queued -> {
                AppDebugLog.d("organize_progress", "stage=QUEUED")
                OperationState(
                    isWorking = true,
                    notice = OperationNotice(
                        type = NoticeType.INFO,
                        title = "Queued",
                        message = "Local screenshot organization is queued.",
                    ),
                )
            }

            is OrganizeProgress.Scanning -> {
                AppDebugLog.i(
                    event = "organize_progress",
                    message = "stage=SCANNING candidates=${progress.candidateCount}",
                )
                persistRunProgress(
                    id = appRunId,
                    totalCount = progress.candidateCount,
                    processedCount = 0,
                    organizedCount = 0,
                    newlyIndexedCount = 0,
                    skippedCount = 0,
                    failedCount = 0,
                    preset = range.preset,
                    startMillis = range.startMillis,
                    endMillisExclusive = range.endMillisExclusive,
                    startedAtMillis = startedAtMillis,
                )
                OperationState(
                    isWorking = true,
                    notice = OperationNotice(
                        type = NoticeType.INFO,
                        title = "Scanning screenshots",
                        message = if (progress.candidateCount == 0) {
                            "Looking for screenshots in the selected range."
                        } else {
                            "${progress.candidateCount} screenshots found. Starting local ML Kit indexing."
                        },
                    ),
                )
            }

            OrganizeProgress.PreparingLocalOcr -> {
                AppDebugLog.i("organize_progress", "stage=PREPARING_LOCAL_OCR")
                OperationState(
                    isWorking = true,
                    notice = OperationNotice(
                        type = NoticeType.MODEL_PREPARING,
                        title = "Preparing local vision models",
                        message = "Checking on-device OCR, visual labels, objects, faces, and barcode components before indexing starts.",
                    ),
                )
            }

            OrganizeProgress.DownloadingLocalOcr -> {
                AppDebugLog.i("organize_progress", "stage=DOWNLOADING_LOCAL_OCR")
                OperationState(
                    isWorking = true,
                    notice = OperationNotice(
                        type = NoticeType.MODEL_DOWNLOADING,
                        title = "Downloading local vision components",
                        message = "Google Play Services is preparing on-device OCR and visual models. Keep internet on; screenshots stay on this phone.",
                    ),
                )
            }

            is OrganizeProgress.BackfillingLocalAi -> {
                if (progress.processedCount == 0 || progress.processedCount == progress.totalCount || progress.processedCount % 25 == 0) {
                    AppDebugLog.i(
                        event = "organize_progress",
                        message = "stage=BACKFILLING_LOCAL_AI kind=${progress.stage} processed=${progress.processedCount} total=${progress.totalCount}",
                    )
                }
                val currentRun = latestRun.value
                val candidateTotal = progress.candidateCount
                    .takeIf { it > 0 }
                    ?: currentRun?.totalCount?.takeIf { it > 0 }
                    ?: progress.totalCount
                val skippedCount = progress.skippedCount
                    .takeIf { it > 0 }
                    ?: currentRun?.skippedCount?.takeIf { it > 0 }
                    ?: 0
                val checkedCount = skippedCount.takeIf { it > 0 } ?: progress.processedCount
                persistRunProgress(
                    id = appRunId,
                    totalCount = candidateTotal,
                    processedCount = checkedCount.coerceAtMost(candidateTotal),
                    organizedCount = skippedCount,
                    newlyIndexedCount = 0,
                    skippedCount = skippedCount,
                    failedCount = 0,
                    preset = range.preset,
                    startMillis = range.startMillis,
                    endMillisExclusive = range.endMillisExclusive,
                    startedAtMillis = startedAtMillis,
                )
                OperationState(
                    isWorking = true,
                    notice = OperationNotice(
                        type = NoticeType.INFO,
                        title = "Upgrading existing memory",
                        message = "${progress.processedCount}/${progress.totalCount} screenshots upgraded for ${progress.stage.ifBlank { "local AI search" }}.",
                    ),
                )
            }

            is OrganizeProgress.Indexing -> {
                if (progress.shouldLogIndexingProgress()) {
                    AppDebugLog.i(
                        event = "organize_progress",
                        message = "stage=INDEXING processed=${progress.processedCount} " +
                        "total=${progress.totalCount} skipped=${progress.skippedCount}",
                    )
                }
                persistRunProgress(
                    id = appRunId,
                    totalCount = progress.totalCount,
                    processedCount = progress.processedCount,
                    organizedCount = progress.skippedCount,
                    newlyIndexedCount = 0,
                    skippedCount = progress.skippedCount,
                    failedCount = 0,
                    preset = range.preset,
                    startMillis = range.startMillis,
                    endMillisExclusive = range.endMillisExclusive,
                    startedAtMillis = startedAtMillis,
                )
                val skipText = if (progress.skippedCount > 0) {
                    " · ${progress.skippedCount} already indexed"
                } else {
                    ""
                }
                OperationState(
                    isWorking = true,
                    notice = OperationNotice(
                        type = NoticeType.INFO,
                        title = "Organizing locally",
                        message = "${progress.processedCount}/${progress.totalCount} checked$skipText" +
                            (progress.currentTitle?.let { " · $it" } ?: ""),
                    ),
                )
            }

            is OrganizeProgress.Completed -> {
                val availableCount = progress.indexedCount + progress.skippedCount
                val completedState = if (
                    progress.totalCount > 0 &&
                    progress.indexedCount == 0 &&
                    progress.skippedCount == progress.totalCount
                ) {
                    OrganizationRunState.UP_TO_DATE
                } else {
                    OrganizationRunState.DONE
                }
                AppDebugLog.i(
                    event = "organize_completed",
                    message = "skillRunId=${progress.runId} appRunId=$appRunId total=${progress.totalCount} " +
                        "indexed=${progress.indexedCount} skipped=${progress.skippedCount} " +
                        "failed=${progress.failedCount} state=$completedState",
                )
                if (availableCount == 0) {
                    markRunFailed(
                        runId = appRunId,
                        range = range,
                        startedAtMillis = startedAtMillis,
                        errorCode = OrganizationError.NO_SCREENSHOTS_FOUND.value,
                    )
                    OperationState(
                        isWorking = false,
                        notice = OperationNotice(
                            type = NoticeType.NO_SCREENSHOTS,
                            title = "No readable screenshots indexed",
                            message = "Try another date range, select images manually, or retry after local vision setup finishes.",
                        ),
                    )
                } else {
                    recordCompletedRun(
                        runId = appRunId,
                        range = range,
                        progress = progress,
                        state = completedState,
                        startedAtMillis = startedAtMillis,
                    )
                    refreshCategoriesFromIndex(clearSelection = true)
                    OperationState(isWorking = false, notice = null)
                }
            }

            is OrganizeProgress.Failed -> {
                AppDebugLog.e(
                    event = "organize_failed",
                    message = progress.message,
                )
                markRunFailed(
                    runId = appRunId,
                    range = range,
                    startedAtMillis = startedAtMillis,
                    errorCode = "ORGANIZE_FAILED",
                )
                OperationState(
                    isWorking = false,
                    notice = OperationNotice(
                        type = NoticeType.ERROR,
                        title = "Organization failed",
                        message = progress.message,
                    ),
                )
            }

            OrganizeProgress.Cancelled -> {
                AppDebugLog.w("organize_cancelled", "rangeStart=${range.startMillis}")
                markRunFailed(
                    runId = appRunId,
                    range = range,
                    startedAtMillis = startedAtMillis,
                    errorCode = "ORGANIZE_CANCELLED",
                )
                OperationState(
                    isWorking = false,
                    notice = OperationNotice(
                        type = NoticeType.ERROR,
                        title = "Organization cancelled",
                        message = "The local indexing job was cancelled.",
                    ),
                )
            }
        }
        return progress is OrganizeProgress.Completed ||
            progress is OrganizeProgress.Failed ||
            progress is OrganizeProgress.Cancelled
    }

    private suspend fun persistRunProgress(
        id: Long,
        totalCount: Int,
        processedCount: Int,
        organizedCount: Int,
        newlyIndexedCount: Int,
        skippedCount: Int,
        failedCount: Int,
        preset: DateRangePreset,
        startMillis: Long,
        endMillisExclusive: Long,
        startedAtMillis: Long,
    ) {
        latestRun.value = runSummary(
            id = id,
            state = OrganizationRunState.IN_PROGRESS,
            totalCount = totalCount,
            processedCount = processedCount,
            organizedCount = organizedCount,
            newlyIndexedCount = newlyIndexedCount,
            skippedCount = skippedCount,
            failedCount = failedCount,
            preset = preset,
            startMillis = startMillis,
            endMillisExclusive = endMillisExclusive,
            startedAtMillis = startedAtMillis,
            completedAtMillis = null,
        )
        organizationDao.updateRunProgress(
            runId = id,
            status = RunStatus.IN_PROGRESS.value,
            candidateCount = totalCount,
            processedCount = processedCount,
            indexedCount = organizedCount,
            newlyIndexedCount = newlyIndexedCount,
            skippedCount = skippedCount,
            failedCount = failedCount,
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun recordCompletedRun(
        runId: Long,
        range: DateRange,
        progress: OrganizeProgress.Completed,
        state: OrganizationRunState,
        startedAtMillis: Long,
    ) {
        val now = System.currentTimeMillis()
        AppDebugLog.i(
            event = "record_completed_run",
            message = "runId=$runId state=$state total=${progress.totalCount} " +
                "indexed=${progress.indexedCount} skipped=${progress.skippedCount} " +
                "failed=${progress.failedCount}",
        )
        val availableCount = progress.indexedCount + progress.skippedCount
        val persistedStatus = when (state) {
            OrganizationRunState.UP_TO_DATE -> RunStatus.UP_TO_DATE
            else -> RunStatus.DONE
        }
        organizationDao.updateRunCompletion(
            runId = runId,
            status = persistedStatus.value,
            candidateCount = progress.totalCount,
            processedCount = progress.totalCount,
            indexedCount = availableCount,
            newlyIndexedCount = progress.indexedCount,
            skippedCount = progress.skippedCount,
            failedCount = progress.failedCount,
            completedAtMillis = now,
            errorCode = null,
        )
        latestRun.value = runSummary(
            id = runId,
            state = state,
            totalCount = progress.totalCount,
            processedCount = progress.totalCount,
            organizedCount = availableCount,
            newlyIndexedCount = progress.indexedCount,
            skippedCount = progress.skippedCount,
            failedCount = progress.failedCount,
            preset = range.preset,
            startMillis = range.startMillis,
            endMillisExclusive = range.endMillisExclusive,
            startedAtMillis = startedAtMillis,
            completedAtMillis = now,
        )
    }

    private suspend fun markRunFailed(
        runId: Long,
        range: DateRange,
        startedAtMillis: Long,
        errorCode: String,
    ) {
        val now = System.currentTimeMillis()
        val current = latestRun.value?.takeIf { it.id == runId }
        organizationDao.updateRunCompletion(
            runId = runId,
            status = RunStatus.FAILED.value,
            candidateCount = current?.totalCount ?: 0,
            processedCount = current?.processedCount ?: 0,
            indexedCount = current?.organizedCount ?: 0,
            newlyIndexedCount = current?.newlyIndexedCount ?: 0,
            skippedCount = current?.skippedCount ?: 0,
            failedCount = current?.failedCount ?: 0,
            completedAtMillis = now,
            errorCode = errorCode,
        )
        latestRun.value = runSummary(
            id = runId,
            state = OrganizationRunState.FAILED,
            totalCount = current?.totalCount ?: 0,
            processedCount = current?.processedCount ?: 0,
            organizedCount = current?.organizedCount ?: 0,
            newlyIndexedCount = current?.newlyIndexedCount ?: 0,
            skippedCount = current?.skippedCount ?: 0,
            failedCount = current?.failedCount ?: 0,
            preset = range.preset,
            startMillis = range.startMillis,
            endMillisExclusive = range.endMillisExclusive,
            startedAtMillis = startedAtMillis,
            completedAtMillis = now,
        )
    }

    private fun OrganizationRunEntity.toRunSummary(): RunSummary {
        val state = when (status) {
            RunStatus.IN_PROGRESS.value -> OrganizationRunState.IN_PROGRESS
            RunStatus.DONE.value -> OrganizationRunState.DONE
            RunStatus.UP_TO_DATE.value -> OrganizationRunState.UP_TO_DATE
            RunStatus.FAILED.value -> OrganizationRunState.FAILED
            RunStatus.STARTED.value -> if (completedAtMillis != null || indexedCount > 0) {
                OrganizationRunState.DONE
            } else {
                OrganizationRunState.IN_PROGRESS
            }
            else -> OrganizationRunState.FAILED
        }
        return runSummary(
            id = id,
            state = state,
            totalCount = candidateCount,
            processedCount = if (state == OrganizationRunState.IN_PROGRESS) {
                processedCount
            } else {
                candidateCount
            },
            organizedCount = indexedCount,
            newlyIndexedCount = newlyIndexedCount,
            skippedCount = skippedCount,
            failedCount = failedCount,
            preset = runCatching { DateRangePreset.valueOf(dateRangePreset) }
                .getOrDefault(DateRangePreset.LAST_7_DAYS),
            startMillis = startMillis,
            endMillisExclusive = endMillis,
            startedAtMillis = startedAtMillis,
            completedAtMillis = completedAtMillis,
        )
    }

    private fun OrganizationRunEntity.toDateRange(): DateRange {
        val preset = runCatching { DateRangePreset.valueOf(dateRangePreset) }
            .getOrDefault(DateRangePreset.LAST_7_DAYS)
        return DateRange(
            preset = preset,
            startMillis = startMillis,
            endMillisExclusive = endMillis,
            displayLabel = preset.label,
        )
    }

    private fun runSummary(
        id: Long,
        state: OrganizationRunState,
        totalCount: Int,
        processedCount: Int,
        organizedCount: Int,
        newlyIndexedCount: Int,
        skippedCount: Int,
        failedCount: Int,
        preset: DateRangePreset,
        startMillis: Long,
        endMillisExclusive: Long,
        startedAtMillis: Long,
        completedAtMillis: Long?,
    ): RunSummary {
        val zone = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
        val endDate = Instant.ofEpochMilli(endMillisExclusive).atZone(zone).toLocalDate().minusDays(1)
        return RunSummary(
            id = id,
            state = state,
            totalCount = totalCount,
            processedCount = processedCount,
            organizedCount = organizedCount,
            newlyIndexedCount = newlyIndexedCount,
            skippedCount = skippedCount,
            failedCount = failedCount,
            dateRangeName = preset.label,
            dateRangeDates = if (startDate == endDate) {
                startDate.toDisplayDate()
            } else {
                "${startDate.toDisplayDate()} - ${endDate.toDisplayDate()}"
            },
            startedAtText = startedAtMillis.toDisplayDateTime(zone),
            completedAtText = completedAtMillis?.toDisplayDateTime(zone),
        )
    }

    private fun currentDateRange(): DateRange {
        return dateRangeFor(
            preset = selectedPreset.value,
            start = customStart.value,
            end = customEnd.value,
        )
    }

    private fun dateRangeFor(
        preset: DateRangePreset,
        start: LocalDate,
        end: LocalDate,
    ): DateRange {
        return if (preset == DateRangePreset.CUSTOM) {
            DateRangeCalculator.custom(start, end, ZoneId.systemDefault())
        } else {
            DateRangeCalculator.calculate(preset)
        }
    }

    private fun onAskProgress(progress: AskProgress) {
        AppDebugLog.i("ask_progress", "step=${progress.step}")
        operationState.update { state ->
            if (state.kind == OperationKind.ASK) {
                state.copy(askProgressStep = progress.step)
            } else {
                state
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "ask_my_screenshots_settings"
        private const val KEY_ONLINE_QUERY_HELP = "online_query_help"
        private const val CATEGORY_SECTION_LIMIT = 24
        private const val CATEGORY_SAMPLE_SIZE = 4
        private const val CATEGORY_DETAIL_LIMIT = 120
    }
}

private data class OperationState(
    val isWorking: Boolean = false,
    val kind: OperationKind = OperationKind.NONE,
    val askProgressStep: AskProgressStep? = null,
    val notice: OperationNotice? = null,
)

private enum class OperationKind {
    NONE,
    ASK,
}

private fun DateRange.toLogMessage(source: ScreenshotSource, pickedCount: Int): String {
    return "preset=${preset.name} start=$startMillis end=$endMillisExclusive " +
        "source=${source.name} pickedCount=$pickedCount"
}

private fun OrganizeProgress.Indexing.shouldLogIndexingProgress(): Boolean {
    return processedCount == 0 ||
        processedCount == totalCount ||
        totalCount <= 20 ||
        processedCount % 25 == 0
}

private fun Long.toDisplayDateTime(zone: ZoneId): String {
    val formatter = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault())
    return Instant.ofEpochMilli(this).atZone(zone).format(formatter)
}

private fun openIntent(context: Context, intent: Intent) {
    runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "No app found for this action", Toast.LENGTH_SHORT).show()
    }
}

private fun String.withUrlScheme(): String {
    val trimmed = trim()
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}
