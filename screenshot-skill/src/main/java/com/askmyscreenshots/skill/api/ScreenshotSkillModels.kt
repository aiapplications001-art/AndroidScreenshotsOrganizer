package com.askmyscreenshots.skill.api

enum class ScreenshotAccessStatus {
    FULL,
    PARTIAL,
    PICKER_ONLY,
    DENIED,
}

data class ScreenshotAccessState(
    val status: ScreenshotAccessStatus,
    val canReadMediaStore: Boolean,
    val canUsePhotoPicker: Boolean,
    val missingPermissions: List<String>,
)

enum class ScreenshotSource {
    MEDIA_STORE,
    PICKED_IMAGES,
}

enum class ReindexPolicy {
    INCREMENTAL,
    REPLACE_RANGE,
}

data class OrganizeRequest(
    val startMillis: Long,
    val endMillisExclusive: Long,
    val source: ScreenshotSource = ScreenshotSource.MEDIA_STORE,
    val reindexPolicy: ReindexPolicy = ReindexPolicy.INCREMENTAL,
    val pickedImageUris: List<String> = emptyList(),
)

data class OrganizeWorkHandle(
    val workId: String,
    val workName: String,
)

sealed interface OrganizeProgress {
    data object Queued : OrganizeProgress
    data class Scanning(val candidateCount: Int = 0) : OrganizeProgress
    data object PreparingLocalOcr : OrganizeProgress
    data object DownloadingLocalOcr : OrganizeProgress
    data class BackfillingLocalAi(
        val processedCount: Int,
        val totalCount: Int,
        val stage: String,
        val skippedCount: Int = 0,
        val candidateCount: Int = 0,
    ) : OrganizeProgress

    data class Indexing(
        val processedCount: Int,
        val totalCount: Int,
        val currentTitle: String?,
        val skippedCount: Int = 0,
    ) : OrganizeProgress

    data class Completed(
        val runId: Long,
        val totalCount: Int,
        val indexedCount: Int,
        val failedCount: Int,
        val skippedCount: Int = 0,
    ) : OrganizeProgress

    data class Failed(val message: String) : OrganizeProgress
    data object Cancelled : OrganizeProgress
}

data class SkillDateRange(
    val startMillis: Long,
    val endMillisExclusive: Long,
)

data class SearchRequest(
    val query: String,
    val dateRange: SkillDateRange? = null,
    val maxResults: Int = 20,
    val allowRemoteRewrite: Boolean = true,
)

data class CategoryOverviewRequest(
    val dateRange: SkillDateRange? = null,
    val maxBucketsPerSection: Int = 24,
    val sampleSize: Int = 4,
)

data class CategoryBucketDetailRequest(
    val bucket: CategoryBucket,
    val dateRange: SkillDateRange? = null,
    val limit: Int = 80,
)

data class CategoryOverview(
    val dynamicCategories: List<CategoryBucket>,
    val appSources: List<CategoryBucket>,
    val visualLabels: List<CategoryBucket>,
    val entityTypes: List<CategoryBucket>,
    val totalScreenshotCount: Int,
    val generatedAtMillis: Long,
)

data class CategoryBucket(
    val id: String,
    val title: String,
    val type: CategoryBucketType,
    val queryValue: String,
    val count: Int,
    val sampleScreenshots: List<CategoryScreenshotPreview>,
    val isSensitive: Boolean,
    val description: String,
)

enum class CategoryBucketType {
    DYNAMIC_CATEGORY,
    APP_SOURCE,
    VISUAL_LABEL,
    ENTITY_TYPE,
}

data class CategoryBucketDetail(
    val bucket: CategoryBucket,
    val screenshots: List<CategoryScreenshotPreview>,
)

data class CategoryScreenshotPreview(
    val id: Long,
    val uri: String,
    val mediaStoreId: Long?,
    val title: String,
    val takenAtMillis: Long?,
    val appHint: String?,
    val category: String,
    val snippet: String,
    val width: Int?,
    val height: Int?,
)

enum class AskMode {
    EXACT_VALUE,
    PROOF,
    ENTITY_GROUP,
    THEME_SUMMARY,
    APP_SOURCE,
    TIMELINE,
    PENDING_ACTION,
    COMPARISON,
    PRIVACY_CLEANUP,
    ANALYTICS,
    FUZZY_VISUAL,
    FIND,
}

data class AskRequest(
    val query: String,
    val dateRange: SkillDateRange? = null,
    val maxResults: Int = 50,
    val allowRemoteRewrite: Boolean = true,
)

enum class AskProgressStep {
    UNDERSTANDING_QUERY,
    PLANNING_WITH_GEMINI,
    PLANNING_LOCAL_SEARCH,
    RETRIEVING_LOCAL_INDEX,
    RETRIEVING_SEMANTIC_INDEX,
    EXPANDING_LINKED_ENTITIES,
    RANKING_SCREENSHOTS,
    BUILDING_EVIDENCE,
    DEEP_ASK_WITH_GEMINI,
    COMPOSING_WITH_GEMINI,
    COMPOSING_LOCALLY,
    PREPARING_RESULTS,
}

data class AskProgress(
    val step: AskProgressStep,
)

data class AskPlan(
    val mode: AskMode,
    val normalizedQuery: String,
    val searchTerms: List<String>,
    val categories: List<String>,
    val entityTypes: List<String>,
    val appHints: List<String> = emptyList(),
    val visualLabels: List<String> = emptyList(),
    val visualObjectLabels: List<String> = emptyList(),
    val semanticQueries: List<String> = emptyList(),
    val dateRange: SkillDateRange?,
    val groupBy: String,
    val sort: String,
    val plannerSource: String,
    val operation: String = "find",
    val subQueries: List<AskSubQuery> = emptyList(),
    val retrievalPlan: List<String> = emptyList(),
    val expectedAnswerShape: String = "",
    val planVersion: Int = 1,
    val taskType: String = "find",
    val answerShape: String = expectedAnswerShape,
    val evidenceChannels: List<PlannedEvidenceChannel> = emptyList(),
    val filters: PlannerFilters = PlannerFilters(),
    val grouping: PlannerGrouping = PlannerGrouping(by = groupBy),
    val ranking: PlannerRanking = PlannerRanking(sort = sort),
)

data class AskSubQuery(
    val id: String,
    val label: String,
    val query: String,
    val searchTerms: List<String>,
    val categories: List<String>,
    val entityTypes: List<String>,
)

data class PlannedEvidenceChannel(
    val channel: String,
    val weight: Float = 1f,
    val required: Boolean = false,
    val terms: List<String> = emptyList(),
    val entityTypes: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val appHints: List<String> = emptyList(),
    val visualLabels: List<String> = emptyList(),
    val visualObjectLabels: List<String> = emptyList(),
    val semanticQueries: List<String> = emptyList(),
)

data class PlannerFilters(
    val dateRange: SkillDateRange? = null,
    val categories: List<String> = emptyList(),
    val entityTypes: List<String> = emptyList(),
    val appHints: List<String> = emptyList(),
    val visualLabels: List<String> = emptyList(),
    val visualObjectLabels: List<String> = emptyList(),
)

data class PlannerGrouping(
    val by: String = "none",
    val entityTypes: List<String> = emptyList(),
)

data class PlannerRanking(
    val sort: String = "relevance",
    val allowBroadFallback: Boolean = false,
)

data class AskResponse(
    val answerCard: AnswerCard,
    val usedEvidence: List<EvidenceScreenshot> = emptyList(),
    val evidenceGroups: List<EvidenceGroup>,
    val flatEvidence: List<EvidenceScreenshot>,
    val facets: List<SearchFacet>,
    val matchedEntities: List<MatchedEntity>,
    val refineActions: List<RefineAction>,
    val suggestedActions: List<SuggestedAction> = emptyList(),
    val privacyTrace: PrivacyTrace,
    val trace: AskTrace,
)

data class AskTrace(
    val mode: AskMode,
    val planVersion: Int = 1,
    val taskType: String = "find",
    val answerShape: String = "",
    val evidenceChannels: List<PlannedEvidenceChannel> = emptyList(),
    val grouping: PlannerGrouping = PlannerGrouping(),
    val ranking: PlannerRanking = PlannerRanking(),
    val plannerSource: String,
    val corpusScope: String,
    val candidateCount: Int,
    val groupCount: Int,
    val representativeCount: Int,
    val usedEvidenceCount: Int = 0,
    val relatedEvidenceCount: Int = 0,
    val semanticCandidateCount: Int = 0,
    val linkedCandidateCount: Int = 0,
    val subQueryCount: Int = 0,
    val deepAskUsed: Boolean = false,
    val suggestedActionCount: Int = 0,
    val remotePlanUsed: Boolean,
    val remoteAnswerUsed: Boolean,
)

data class EvidenceGroup(
    val id: String,
    val type: String,
    val title: String,
    val summary: String,
    val screenshotCount: Int,
    val confidence: Float,
    val topSignals: List<EvidenceSignal>,
    val representativeScreenshots: List<EvidenceScreenshot>,
    val allScreenshotIds: List<Long>,
    val sortReason: String,
    val groupAnswer: String? = null,
    val citedScreenshotIds: List<Long> = emptyList(),
)

data class EvidenceSignal(
    val type: String,
    val label: String,
    val value: String,
    val count: Int,
    val isSensitive: Boolean,
)

data class EvidenceScreenshot(
    val id: Long,
    val uri: String,
    val mediaStoreId: Long?,
    val title: String,
    val takenAtMillis: Long?,
    val appHint: String?,
    val category: String,
    val snippets: List<String>,
    val entities: List<MatchedEntity>,
    val visualLabels: List<String>,
    val matchReason: String,
    val relevanceScore: Float,
    val isCited: Boolean = false,
)

data class SearchResponse(
    val answer: String,
    val screenshotRefs: List<ScreenshotRef>,
    val facets: List<SearchFacet>,
    val matchedEntities: List<MatchedEntity>,
    val privacyTrace: PrivacyTrace,
    val answerCard: AnswerCard? = null,
    val refineActions: List<RefineAction> = emptyList(),
    val totalCandidateCount: Int = screenshotRefs.size,
    val evidenceGroups: List<EvidenceGroup> = emptyList(),
    val askTrace: AskTrace? = null,
)

data class ScreenshotRef(
    val uri: String,
    val mediaStoreId: Long?,
    val takenAtMillis: Long?,
    val title: String,
    val matchedSnippets: List<String>,
    val sensitiveEntityBadges: List<String>,
    val id: Long = 0L,
    val appHint: String? = null,
    val category: String? = null,
    val matchReason: String = "",
    val relevanceScore: Float = 0f,
    val isCited: Boolean = false,
)

data class AnswerCard(
    val title: String,
    val body: String,
    val confidence: Float,
    val answerType: String,
    val citedScreenshotIds: List<Long>,
    val citedGroupIds: List<String> = emptyList(),
)

data class RefineAction(
    val label: String,
    val query: String,
    val type: String,
)

enum class SuggestedActionType {
    COPY_TEXT,
    OPEN_URL,
    DIAL_PHONE,
    EMAIL,
    OPEN_MAPS,
    ASK_FOLLOW_UP,
    FILTER_THIS,
    SHARE_SCREENSHOT,
}

data class SuggestedAction(
    val type: SuggestedActionType,
    val label: String,
    val value: String,
    val displayValue: String,
)

data class SearchFacet(
    val name: String,
    val value: String,
    val count: Int,
)

data class MatchedEntity(
    val type: String,
    val value: String,
    val screenshotCount: Int,
    val isSensitive: Boolean,
)

data class PrivacyTrace(
    val remoteRewriteRequested: Boolean,
    val remoteRewriteUsed: Boolean,
    val redactedContextSent: Boolean,
    val dataSentOffDevice: List<String>,
)

data class MindMapRequest(
    val dateRange: SkillDateRange? = null,
    val maxScreenshots: Int = 600,
    val maxClusters: Int = 12,
    val maxSignals: Int = 30,
    val allowRemoteLabeling: Boolean = false,
)

data class MindMapGraph(
    val summary: MemoryMapSummary,
    val clusters: List<MemoryCluster>,
    val topSignals: List<MemorySignal>,
    val generatedAtMillis: Long,
)

data class MemoryMapSummary(
    val indexedScreenshotCount: Int,
    val clusterCount: Int,
    val signalCount: Int,
    val startMillis: Long?,
    val endMillisExclusive: Long?,
)

data class MemoryCluster(
    val id: String,
    val title: String,
    val summary: String,
    val screenshotCount: Int,
    val topSignals: List<MemorySignal>,
    val representativeScreenshots: List<MemoryScreenshotPreview>,
    val screenshotIds: List<Long>,
    val startMillis: Long?,
    val endMillisExclusive: Long?,
    val confidence: Float,
    val askQuery: String,
)

data class MemorySignal(
    val id: String,
    val type: String,
    val label: String,
    val value: String,
    val screenshotCount: Int,
    val isSensitive: Boolean,
    val screenshotIds: List<Long>,
    val representativeScreenshots: List<MemoryScreenshotPreview> = emptyList(),
)

data class MemoryScreenshotPreview(
    val id: Long,
    val uri: String,
    val title: String,
    val takenAtMillis: Long?,
    val snippet: String,
    val appHint: String?,
    val width: Int?,
    val height: Int?,
)

data class ClusterLabelRequest(
    val clusters: List<ClusterLabelCandidate>,
)

data class ClusterLabelCandidate(
    val id: String,
    val localTitle: String,
    val localSummary: String,
    val screenshotCount: Int,
    val redactedSignals: List<String>,
)

data class ClusterLabelResult(
    val id: String,
    val title: String,
    val summary: String,
)

fun interface RemoteClusterLabeler {
    suspend fun labelClusters(request: ClusterLabelRequest): List<ClusterLabelResult>
}

sealed interface DeleteScope {
    data object All : DeleteScope
    data class DateRange(val range: SkillDateRange) : DeleteScope
}

data class RedactedRewriteRequest(
    val query: String,
    val schemaDescription: String,
    val categoryVocabulary: List<String>,
    val entityTypeVocabulary: List<String>,
    val retrievalCapabilities: List<String>,
)

data class RewrittenQueryPlan(
    val planVersion: Int = 1,
    val normalizedQuery: String,
    val intent: String = "find",
    val searchTerms: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val entityTypes: List<String> = emptyList(),
    val appHints: List<String> = emptyList(),
    val visualLabels: List<String> = emptyList(),
    val visualObjectLabels: List<String> = emptyList(),
    val semanticQueries: List<String> = emptyList(),
    val dateRange: SkillDateRange? = null,
    val askMode: String = "",
    val groupBy: String = "",
    val sort: String = "",
    val operation: String = "",
    val subQueries: List<AskSubQuery> = emptyList(),
    val retrievalPlan: List<String> = emptyList(),
    val expectedAnswerShape: String = "",
    val taskType: String = "",
    val answerShape: String = "",
    val evidenceChannels: List<PlannedEvidenceChannel> = emptyList(),
    val filters: PlannerFilters = PlannerFilters(),
    val grouping: PlannerGrouping = PlannerGrouping(),
    val ranking: PlannerRanking = PlannerRanking(),
)

fun interface RemoteQueryRewriter {
    suspend fun rewrite(request: RedactedRewriteRequest): RewrittenQueryPlan?
}

data class AnswerSynthesisRequest(
    val originalQuery: String,
    val plan: RewrittenQueryPlan,
    val databaseSchema: String,
    val evidence: List<AnswerEvidence>,
    val evidenceGroups: List<EvidenceGroup> = emptyList(),
    val matchedEntities: List<MatchedEntity>,
    val facets: List<SearchFacet>,
)

data class AnswerEvidence(
    val screenshotId: Long,
    val title: String,
    val takenAtMillis: Long?,
    val appHint: String?,
    val category: String,
    val snippets: List<String>,
    val entities: List<MatchedEntity>,
    val visualLabels: List<String>,
    val matchReason: String,
    val relevanceScore: Float,
)

data class AnswerSynthesisResult(
    val title: String,
    val body: String,
    val confidence: Float,
    val answerType: String,
    val citedScreenshotIds: List<Long>,
    val citedGroupIds: List<String> = emptyList(),
    val refineActions: List<RefineAction> = emptyList(),
)

fun interface RemoteAnswerSynthesizer {
    suspend fun synthesize(request: AnswerSynthesisRequest): AnswerSynthesisResult?
}
