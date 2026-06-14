package com.askmyscreenshots.skill.search

import com.askmyscreenshots.skill.api.AnswerCard
import com.askmyscreenshots.skill.api.AnswerEvidence
import com.askmyscreenshots.skill.api.AnswerSynthesisRequest
import com.askmyscreenshots.skill.api.AskProgress
import com.askmyscreenshots.skill.api.AskProgressStep
import com.askmyscreenshots.skill.api.AskMode
import com.askmyscreenshots.skill.api.AskPlan
import com.askmyscreenshots.skill.api.AskRequest
import com.askmyscreenshots.skill.api.AskResponse
import com.askmyscreenshots.skill.api.AskSubQuery
import com.askmyscreenshots.skill.api.AskTrace
import com.askmyscreenshots.skill.api.EvidenceGroup
import com.askmyscreenshots.skill.api.EvidenceScreenshot
import com.askmyscreenshots.skill.api.EvidenceSignal
import com.askmyscreenshots.skill.api.MatchedEntity
import com.askmyscreenshots.skill.api.PlannedEvidenceChannel
import com.askmyscreenshots.skill.api.PlannerFilters
import com.askmyscreenshots.skill.api.PlannerGrouping
import com.askmyscreenshots.skill.api.PlannerRanking
import com.askmyscreenshots.skill.api.PrivacyTrace
import com.askmyscreenshots.skill.api.RedactedRewriteRequest
import com.askmyscreenshots.skill.api.RefineAction
import com.askmyscreenshots.skill.api.RemoteAnswerSynthesizer
import com.askmyscreenshots.skill.api.RemoteQueryRewriter
import com.askmyscreenshots.skill.api.RewrittenQueryPlan
import com.askmyscreenshots.skill.api.SearchFacet
import com.askmyscreenshots.skill.api.SearchRequest
import com.askmyscreenshots.skill.api.SearchResponse
import com.askmyscreenshots.skill.api.ScreenshotRef
import com.askmyscreenshots.skill.api.SuggestedAction
import com.askmyscreenshots.skill.api.SuggestedActionType
import com.askmyscreenshots.skill.data.CategoryForScreenshot
import com.askmyscreenshots.skill.data.EntityForScreenshot
import com.askmyscreenshots.skill.data.ScreenshotEmbeddingEntity
import com.askmyscreenshots.skill.data.ScreenshotEntity
import com.askmyscreenshots.skill.data.ScreenshotSkillDao
import com.askmyscreenshots.skill.data.SearchHistoryEntity
import com.askmyscreenshots.skill.data.VisualLabelForScreenshot
import com.askmyscreenshots.skill.debug.SkillDebugLog
import com.askmyscreenshots.skill.extract.LocalEntityExtractor
import com.askmyscreenshots.skill.extract.PrivacyRedactor
import com.askmyscreenshots.skill.ml.ScreenshotCategory
import com.askmyscreenshots.skill.semantic.HashingTextEmbedder
import com.askmyscreenshots.skill.semantic.SemanticInputBuilder
import com.askmyscreenshots.skill.semantic.TextEmbedder
import com.askmyscreenshots.skill.semantic.cosineSimilarity
import com.askmyscreenshots.skill.semantic.toEmbeddingBlob
import com.askmyscreenshots.skill.semantic.toFloatVector
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

class LocalSearchEngine(
    private val dao: ScreenshotSkillDao,
    private val remoteQueryRewriter: RemoteQueryRewriter? = null,
    private val remoteAnswerSynthesizer: RemoteAnswerSynthesizer? = null,
    private val queryPlanner: QueryPlanner = QueryPlanner(),
    private val entityExtractor: LocalEntityExtractor = LocalEntityExtractor(),
    private val textEmbedder: TextEmbedder = HashingTextEmbedder(),
    private val imageSignalEmbedder: TextEmbedder = HashingTextEmbedder(
        name = "local-visual-signal-image-embedder",
        version = "2026-06-14",
    ),
) {
    suspend fun search(request: SearchRequest): SearchResponse {
        return ask(
            AskRequest(
                query = request.query,
                dateRange = request.dateRange,
                maxResults = request.maxResults,
                allowRemoteRewrite = request.allowRemoteRewrite,
            ),
        ).toSearchResponse()
    }

    suspend fun ask(
        request: AskRequest,
        onProgress: (AskProgress) -> Unit = {},
    ): AskResponse {
        onProgress(AskProgress(AskProgressStep.UNDERSTANDING_QUERY))
        val searchRequest = SearchRequest(
            query = request.query,
            dateRange = request.dateRange,
            maxResults = request.maxResults,
            allowRemoteRewrite = request.allowRemoteRewrite,
        )
        val shouldUseGeminiPlanner = request.allowRemoteRewrite && remoteQueryRewriter != null
        onProgress(
            AskProgress(
                if (shouldUseGeminiPlanner) {
                    AskProgressStep.PLANNING_WITH_GEMINI
                } else {
                    AskProgressStep.PLANNING_LOCAL_SEARCH
                },
            ),
        )
        val remotePlan = buildRemotePlan(searchRequest)
        val effectivePlan = remotePlan?.toExecutionPlan(searchRequest)
            ?: queryPlanner.plan(searchRequest)
        val askPlan = buildAskPlan(request.query, effectivePlan, remotePlan)
        onProgress(AskProgress(AskProgressStep.RETRIEVING_LOCAL_INDEX))
        val candidateResult = generateCandidates(askPlan, effectivePlan, request, onProgress)
        val candidates = candidateResult.candidates
        val screenshotIds = candidates.map { it.screenshot.id }
        val entities = if (screenshotIds.isNotEmpty()) dao.entitiesForScreenshots(screenshotIds) else emptyList()
        val categories = if (screenshotIds.isNotEmpty()) dao.categoriesForScreenshots(screenshotIds) else emptyList()
        val labels = if (screenshotIds.isNotEmpty()) {
            dao.labelsForScreenshots(screenshotIds, minConfidence = 0.55f) +
                dao.objectLabelsForScreenshots(screenshotIds, minConfidence = 0.45f)
        } else {
            emptyList()
        }
        val entityMap = entities.groupBy { it.screenshotId }
        val categoryMap = categories.groupBy { it.screenshotId }
        val labelMap = labels.groupBy { it.screenshotId }
        onProgress(AskProgress(AskProgressStep.RANKING_SCREENSHOTS))
        val scored = candidates
            .map { candidate ->
                scoreScreenshot(
                    candidate = candidate,
                    entities = entityMap[candidate.screenshot.id].orEmpty(),
                    categories = categoryMap[candidate.screenshot.id].orEmpty(),
                    labels = labelMap[candidate.screenshot.id].orEmpty(),
                    plan = effectivePlan,
                    askPlan = askPlan,
                )
            }
            .filter { it.score > 0f || askPlan.ranking.allowBroadFallback }
            .let { sortScored(it, askPlan) }

        onProgress(AskProgress(AskProgressStep.BUILDING_EVIDENCE))
        val allGroups = buildEvidenceGroups(
            request = request,
            plan = askPlan,
            scored = scored,
            entityMap = entityMap,
            labelMap = labelMap,
        )
        val flatEvidence = buildFlatEvidence(scored, entityMap, labelMap, request.maxResults.coerceIn(1, 80))
        val matchedEntities = entities
            .filter { entity -> scored.take(120).any { it.screenshot.id == entity.screenshotId } }
            .toMatchedEntities(limit = 60)
        val facets = facetsFor(scored.take(160).map { it.screenshot })

        SkillDebugLog.i(
            event = "ask_retrieval_done",
            message = "task=${askPlan.taskType} mode=${askPlan.mode} planner=${askPlan.plannerSource} " +
                "channels=${askPlan.evidenceChannels.joinToString(",") { it.channel }} broad=${askPlan.ranking.allowBroadFallback} " +
                "candidates=${candidates.size} semantic=${candidateResult.semanticCandidateCount} " +
                "linked=${candidateResult.linkedCandidateCount} subQueries=${candidateResult.subQueryCount} " +
                "scored=${scored.size} groups=${allGroups.size} " +
                "representatives=${allGroups.sumOf { it.representativeScreenshots.size }} " +
                "entities=${matchedEntities.size} facets=${facets.size}",
        )

        val deepAskUsed = shouldUseDeepAsk(askPlan, request, allGroups, flatEvidence)
        val groupsForSynthesis = if (shouldSendGroupsToAnswer(askPlan)) {
            allGroups.take(remoteGroupLimit(askPlan))
        } else {
            emptyList()
        }
        onProgress(
            AskProgress(
                if (request.allowRemoteRewrite && remoteAnswerSynthesizer != null && (flatEvidence.isNotEmpty() || groupsForSynthesis.isNotEmpty())) {
                    if (deepAskUsed) AskProgressStep.DEEP_ASK_WITH_GEMINI else AskProgressStep.COMPOSING_WITH_GEMINI
                } else {
                    AskProgressStep.COMPOSING_LOCALLY
                },
            ),
        )
        val synthesis = synthesizeAnswer(
            request = request,
            plan = askPlan,
            evidence = flatEvidence.take(20).map { it.toAnswerEvidence() },
            evidenceGroups = groupsForSynthesis,
            matchedEntities = matchedEntities,
            facets = facets,
        )
        val answerCard = composeAnswerCard(
            query = request.query,
            plan = askPlan,
            groups = allGroups,
            flatEvidence = flatEvidence,
            synthesis = synthesis,
        )
        onProgress(AskProgress(AskProgressStep.PREPARING_RESULTS))
        val citedScreenshotIds = answerCard.citedScreenshotIds.toSet()
        val scoredById = scored.associateBy { it.screenshot.id }
        val usedEvidence = buildEvidenceForScreenshotIds(
            ids = answerCard.citedScreenshotIds,
            scoredById = scoredById,
            entityMap = entityMap,
            labelMap = labelMap,
            limit = 12,
        ).map { it.copy(isCited = true) }
        val citedFlatEvidence = flatEvidence.map { it.copy(isCited = it.id in citedScreenshotIds) }
        val citedGroups = applyCitationsToGroups(
            groups = allGroups,
            answerCard = answerCard,
            scoredById = scoredById,
            entityMap = entityMap,
            labelMap = labelMap,
            plan = askPlan,
        )
        val displayedGroups = displayGroupsForPlan(askPlan, citedGroups, answerCard)
        val refineActions = (
            synthesis?.refineActions.orEmpty() +
                refineActionsFor(request.query, displayedGroups, facets, matchedEntities, askPlan)
            )
            .filter { it.label.isNotBlank() && it.query.isNotBlank() }
            .distinctBy { it.label.lowercase() to it.query.lowercase() }
            .take(12)
        val remoteAnswerUsed = synthesis != null
        val suggestedActions = suggestedActionsFor(answerCard, usedEvidence.ifEmpty { citedFlatEvidence })
        val response = AskResponse(
            answerCard = answerCard,
            usedEvidence = usedEvidence,
            evidenceGroups = displayedGroups,
            flatEvidence = citedFlatEvidence,
            facets = facets,
            matchedEntities = matchedEntities,
            refineActions = refineActions,
            suggestedActions = suggestedActions,
            privacyTrace = PrivacyTrace(
                remoteRewriteRequested = request.allowRemoteRewrite,
                remoteRewriteUsed = remotePlan != null,
                redactedContextSent = false,
                dataSentOffDevice = buildList {
                    if (remotePlan != null) {
                        addAll(
                            listOf(
                                "raw_query",
                                "planner_schema",
                                "category_vocabulary",
                                "entity_type_vocabulary",
                                "retrieval_capabilities",
                            ),
                        )
                    }
                    if (remoteAnswerUsed) {
                        addAll(listOf("grouped_evidence", "evidence_snippets", "detected_entities", "visual_labels", "screenshot_metadata"))
                        if (deepAskUsed) add("expanded_evidence_text")
                    }
                },
            ),
            trace = AskTrace(
                mode = askPlan.mode,
                planVersion = askPlan.planVersion,
                taskType = askPlan.taskType,
                answerShape = askPlan.answerShape,
                evidenceChannels = askPlan.evidenceChannels,
                grouping = askPlan.grouping,
                ranking = askPlan.ranking,
                plannerSource = askPlan.plannerSource,
                corpusScope = if (askPlan.ranking.allowBroadFallback) "broad_index" else "targeted_index",
                candidateCount = candidates.size,
                groupCount = displayedGroups.size,
                representativeCount = displayedGroups.sumOf { it.representativeScreenshots.size },
                usedEvidenceCount = usedEvidence.size,
                relatedEvidenceCount = citedFlatEvidence.count { !it.isCited },
                semanticCandidateCount = candidateResult.semanticCandidateCount,
                linkedCandidateCount = candidateResult.linkedCandidateCount,
                subQueryCount = candidateResult.subQueryCount,
                deepAskUsed = deepAskUsed && remoteAnswerUsed,
                suggestedActionCount = suggestedActions.size,
                remotePlanUsed = remotePlan != null,
                remoteAnswerUsed = remoteAnswerUsed,
            ),
        )

        dao.insertSearchHistory(
            SearchHistoryEntity(
                query = request.query,
                normalizedQuery = askPlan.normalizedQuery,
                resultCount = usedEvidence.ifEmpty { citedFlatEvidence }.size,
                remoteRewriteUsed = remotePlan != null || remoteAnswerUsed,
                searchedAtMillis = System.currentTimeMillis(),
            ),
        )
        SkillDebugLog.i(
            event = "ask_answer_ready",
            message = "task=${askPlan.taskType} mode=${askPlan.mode} remoteAnswer=$remoteAnswerUsed confidence=${answerCard.confidence} " +
                "groups=${response.evidenceGroups.size}/${allGroups.size} refs=${response.flatEvidence.size} " +
                "used=${response.usedEvidence.size} related=${response.trace.relatedEvidenceCount} " +
                "semantic=${response.trace.semanticCandidateCount} linked=${response.trace.linkedCandidateCount} " +
                "subQueries=${response.trace.subQueryCount} deepAsk=${response.trace.deepAskUsed} " +
                "citedGroups=${answerCard.citedGroupIds.size} citedScreens=${answerCard.citedScreenshotIds.size} " +
                "refines=${refineActions.size} actions=${suggestedActions.size}",
        )
        return response
    }

    private suspend fun buildRemotePlan(request: SearchRequest): RewrittenQueryPlan? {
        val rewriter = remoteQueryRewriter
        if (!request.allowRemoteRewrite || rewriter == null) return null
        val rewriteRequest = buildPlannerRewriteRequest(request.query)
        return runCatching { rewriter.rewrite(rewriteRequest) }
            .onFailure { error ->
                SkillDebugLog.w("ask_remote_plan_failed", error.message.orEmpty().take(180))
            }
            .getOrNull()
            ?.also { plan ->
                SkillDebugLog.i(
                    event = "ask_remote_plan_done",
                    message = "version=${plan.planVersion} task=${plan.taskType.ifBlank { plan.operation }} " +
                        "channels=${plan.evidenceChannels.map { it.channel }} intent=${plan.intent} askMode=${plan.askMode} groupBy=${plan.groupBy} " +
                        "terms=${plan.searchTerms.size} categories=${plan.categories.size} " +
                        "entityTypes=${plan.entityTypes.size} appHints=${plan.appHints.size} " +
                        "visualLabels=${plan.visualLabels.size} visualObjects=${plan.visualObjectLabels.size} " +
                        "semanticQueries=${plan.semanticQueries.size} hasDateRange=${plan.dateRange != null}",
                )
            }
    }

    private fun RewrittenQueryPlan.toExecutionPlan(request: SearchRequest): LocalQueryPlan {
        val normalized = normalizedQuery.ifBlank { request.query }.trim()
        val channelTerms = evidenceChannels.flatMap { it.terms }
        val channelCategories = evidenceChannels.flatMap { it.categories }
        val channelEntityTypes = evidenceChannels.flatMap { it.entityTypes }
        val channelAppHints = evidenceChannels.flatMap { it.appHints }
        val channelVisualLabels = evidenceChannels.flatMap { it.visualLabels }
        val channelVisualObjectLabels = evidenceChannels.flatMap { it.visualObjectLabels }
        val channelSemanticQueries = evidenceChannels.flatMap { it.semanticQueries }
        val remoteSearchText = (
            listOf(normalized) +
                searchTerms +
                channelTerms +
                appHints +
                filters.appHints +
                channelAppHints +
                visualLabels +
                filters.visualLabels +
                channelVisualLabels +
                visualObjectLabels +
                filters.visualObjectLabels +
                channelVisualObjectLabels +
                semanticQueries +
                channelSemanticQueries +
                subQueries.flatMap { listOf(it.query) + it.searchTerms }
            )
            .joinToString(" ")
            .ifBlank { request.query }
        return LocalQueryPlan(
            normalizedQuery = normalized.lowercase(Locale.US),
            ftsQuery = queryPlanner.toFtsQuery(remoteSearchText.lowercase(Locale.US)),
            intent = intent.ifBlank { "find" },
            searchTerms = (searchTerms + channelTerms).normalizedPlannerValues(limit = 48),
            categories = (categories + filters.categories + channelCategories).normalizedPlannerValues(limit = 28),
            entityTypes = (entityTypes + filters.entityTypes + channelEntityTypes).normalizedPlannerValues(limit = 32),
            appHints = (appHints + filters.appHints + channelAppHints).normalizedPlannerValues(limit = 20),
            dateRange = filters.dateRange ?: dateRange ?: request.dateRange,
        )
    }

    private fun buildAskPlan(
        query: String,
        plan: LocalQueryPlan,
        remotePlan: RewrittenQueryPlan?,
    ): AskPlan {
        val remoteMode = remotePlan?.askMode?.toAskModeOrNull()
        val localMode = inferAskMode(query, plan)
        val explicitTaskType = remotePlan?.taskType
            ?.takeIf { it.isNotBlank() }
            ?.let { normalizeTaskType(it) }
        val legacyModeTaskType = remoteMode
            ?.takeIf { it != AskMode.FIND }
            ?.let { taskTypeForMode(it) }
        val operationTaskType = remotePlan
            ?.takeIf { it.planVersion >= 2 }
            ?.operation
            ?.takeIf { it.isNotBlank() }
            ?.let { normalizeTaskType(it) }
            ?.takeIf { it != TASK_FIND }
        val taskType = explicitTaskType
            ?: legacyModeTaskType
            ?: operationTaskType
            ?: taskTypeForMode(remoteMode ?: localMode)
        val mode = displayModeForTask(taskType, remoteMode ?: localMode, remotePlan)
        val filters = buildPlannerFilters(remotePlan, plan)
        val channels = buildEvidenceChannels(
            query = query,
            localPlan = plan,
            remotePlan = remotePlan,
            taskType = taskType,
            mode = mode,
            filters = filters,
        )
        val grouping = buildPlannerGrouping(remotePlan, mode, taskType)
        val ranking = buildPlannerRanking(remotePlan, mode, taskType, query, grouping)
        val answerShape = remotePlan?.answerShape
            ?.takeIf { it.isNotBlank() }
            ?: remotePlan?.expectedAnswerShape?.takeIf { it.isNotBlank() }
            ?: answerShapeForTask(taskType, grouping)
        return AskPlan(
            mode = mode,
            normalizedQuery = plan.normalizedQuery,
            searchTerms = (plan.searchTerms + channels.flatMap { it.terms }).normalizedPlannerValues(limit = 48),
            categories = (plan.categories + filters.categories + channels.flatMap { it.categories }).normalizedPlannerValues(limit = 28),
            entityTypes = (plan.entityTypes + filters.entityTypes + channels.flatMap { it.entityTypes }).normalizedPlannerValues(limit = 32),
            appHints = (plan.appHints + filters.appHints + channels.flatMap { it.appHints }).normalizedPlannerValues(limit = 20),
            visualLabels = (remotePlan?.visualLabels.orEmpty() + filters.visualLabels + channels.flatMap { it.visualLabels })
                .normalizedPlannerValues(limit = 36),
            visualObjectLabels = (remotePlan?.visualObjectLabels.orEmpty() + filters.visualObjectLabels + channels.flatMap { it.visualObjectLabels })
                .normalizedPlannerValues(limit = 36),
            semanticQueries = (remotePlan?.semanticQueries.orEmpty() + channels.flatMap { it.semanticQueries }).normalizedPlannerValues(limit = 16),
            dateRange = filters.dateRange ?: plan.dateRange,
            groupBy = grouping.by,
            sort = ranking.sort,
            plannerSource = if (remotePlan == null) "local" else "gemini",
            operation = operationForTask(taskType),
            subQueries = remotePlan?.subQueries?.takeIf { it.isNotEmpty() } ?: localSubQueries(query, plan, taskType),
            retrievalPlan = remotePlan?.retrievalPlan.orEmpty(),
            expectedAnswerShape = remotePlan?.expectedAnswerShape.orEmpty(),
            planVersion = remotePlan?.planVersion ?: 2,
            taskType = taskType,
            answerShape = answerShape,
            evidenceChannels = channels,
            filters = filters,
            grouping = grouping,
            ranking = ranking,
        )
    }

    private fun normalizeTaskType(raw: String?): String {
        return when (raw.orEmpty().trim().lowercase(Locale.US).replace('-', '_').replace(' ', '_')) {
            TASK_LOOKUP_VALUE, "lookup", "exact_value", "value" -> TASK_LOOKUP_VALUE
            TASK_PROVE, "proof", "evidence" -> TASK_PROVE
            TASK_SUMMARIZE, "summary", "theme_summary", "themes" -> TASK_SUMMARIZE
            TASK_COMPARE, "comparison" -> TASK_COMPARE
            TASK_AGGREGATE, "analytics", "total", "totals" -> TASK_AGGREGATE
            TASK_CLEANUP, "privacy_cleanup", "privacy" -> TASK_CLEANUP
            TASK_TIMELINE, "history" -> TASK_TIMELINE
            TASK_ACTION_ITEMS, "pending_action", "pending", "actions" -> TASK_ACTION_ITEMS
            else -> TASK_FIND
        }
    }

    private fun taskTypeForMode(mode: AskMode): String {
        return when (mode) {
            AskMode.EXACT_VALUE -> TASK_LOOKUP_VALUE
            AskMode.PROOF -> TASK_PROVE
            AskMode.THEME_SUMMARY -> TASK_SUMMARIZE
            AskMode.COMPARISON -> TASK_COMPARE
            AskMode.ANALYTICS -> TASK_AGGREGATE
            AskMode.PRIVACY_CLEANUP -> TASK_CLEANUP
            AskMode.TIMELINE -> TASK_TIMELINE
            AskMode.PENDING_ACTION -> TASK_ACTION_ITEMS
            AskMode.ENTITY_GROUP,
            AskMode.APP_SOURCE,
            AskMode.FUZZY_VISUAL,
            AskMode.FIND,
            -> TASK_FIND
        }
    }

    private fun displayModeForTask(
        taskType: String,
        fallbackMode: AskMode,
        remotePlan: RewrittenQueryPlan?,
    ): AskMode {
        return when (taskType) {
            TASK_LOOKUP_VALUE -> AskMode.EXACT_VALUE
            TASK_PROVE -> AskMode.PROOF
            TASK_SUMMARIZE -> AskMode.THEME_SUMMARY
            TASK_COMPARE -> AskMode.COMPARISON
            TASK_AGGREGATE -> AskMode.ANALYTICS
            TASK_CLEANUP -> AskMode.PRIVACY_CLEANUP
            TASK_TIMELINE -> AskMode.TIMELINE
            TASK_ACTION_ITEMS -> AskMode.PENDING_ACTION
            else -> remotePlan?.askMode?.toAskModeOrNull() ?: fallbackMode
        }
    }

    private fun buildPlannerFilters(
        remotePlan: RewrittenQueryPlan?,
        localPlan: LocalQueryPlan,
    ): PlannerFilters {
        return PlannerFilters(
            dateRange = remotePlan?.filters?.dateRange ?: remotePlan?.dateRange ?: localPlan.dateRange,
            categories = (remotePlan?.filters?.categories.orEmpty() + remotePlan?.categories.orEmpty() + localPlan.categories)
                .normalizedPlannerValues(limit = 28),
            entityTypes = (remotePlan?.filters?.entityTypes.orEmpty() + remotePlan?.entityTypes.orEmpty() + localPlan.entityTypes)
                .normalizedPlannerValues(limit = 32),
            appHints = (remotePlan?.filters?.appHints.orEmpty() + remotePlan?.appHints.orEmpty() + localPlan.appHints)
                .normalizedPlannerValues(limit = 20),
            visualLabels = (remotePlan?.filters?.visualLabels.orEmpty() + remotePlan?.visualLabels.orEmpty())
                .normalizedPlannerValues(limit = 36),
            visualObjectLabels = (remotePlan?.filters?.visualObjectLabels.orEmpty() + remotePlan?.visualObjectLabels.orEmpty())
                .normalizedPlannerValues(limit = 36),
        )
    }

    private fun buildPlannerGrouping(
        remotePlan: RewrittenQueryPlan?,
        mode: AskMode,
        taskType: String,
    ): PlannerGrouping {
        val remoteBy = remotePlan?.grouping?.by?.takeIf { it.isNotBlank() && it != "none" }
            ?: remotePlan?.groupBy?.takeIf { it.isNotBlank() && it != "best_match" }
        val by = normalizeGrouping(remoteBy ?: defaultGroupingForTask(taskType, mode))
        return PlannerGrouping(
            by = by,
            entityTypes = remotePlan?.grouping?.entityTypes.normalizedPlannerValues(limit = 12),
        )
    }

    private fun buildPlannerRanking(
        remotePlan: RewrittenQueryPlan?,
        mode: AskMode,
        taskType: String,
        query: String,
        grouping: PlannerGrouping,
    ): PlannerRanking {
        val sort = remotePlan?.ranking?.sort?.takeIf { it.isNotBlank() }
            ?: remotePlan?.sort?.takeIf { it.isNotBlank() }
            ?: defaultSortForTask(taskType, mode, query)
        val broadFallback = (remotePlan?.ranking?.allowBroadFallback == true) ||
            defaultAllowBroadFallback(taskType, mode, grouping.by)
        return PlannerRanking(
            sort = normalizeSort(sort),
            allowBroadFallback = broadFallback,
        )
    }

    private fun buildEvidenceChannels(
        query: String,
        localPlan: LocalQueryPlan,
        remotePlan: RewrittenQueryPlan?,
        taskType: String,
        mode: AskMode,
        filters: PlannerFilters,
    ): List<PlannedEvidenceChannel> {
        val inferredFromFields = buildList {
            if (remotePlan?.searchTerms.orEmpty().isNotEmpty()) {
                add(PlannedEvidenceChannel(CHANNEL_TEXT, terms = remotePlan?.searchTerms.orEmpty()))
            }
            if ((filters.entityTypes + remotePlan?.entityTypes.orEmpty()).isNotEmpty()) {
                add(PlannedEvidenceChannel(CHANNEL_ENTITY, entityTypes = filters.entityTypes + remotePlan?.entityTypes.orEmpty()))
            }
            if ((filters.appHints + remotePlan?.appHints.orEmpty()).isNotEmpty()) {
                add(PlannedEvidenceChannel(CHANNEL_APP, appHints = filters.appHints + remotePlan?.appHints.orEmpty()))
            }
            if ((filters.categories + remotePlan?.categories.orEmpty()).isNotEmpty()) {
                add(PlannedEvidenceChannel(CHANNEL_CATEGORY, categories = filters.categories + remotePlan?.categories.orEmpty()))
            }
            if ((filters.visualLabels + filters.visualObjectLabels + remotePlan?.visualLabels.orEmpty() + remotePlan?.visualObjectLabels.orEmpty()).isNotEmpty()) {
                add(
                    PlannedEvidenceChannel(
                        channel = CHANNEL_VISUAL,
                        visualLabels = filters.visualLabels + remotePlan?.visualLabels.orEmpty(),
                        visualObjectLabels = filters.visualObjectLabels + remotePlan?.visualObjectLabels.orEmpty(),
                    ),
                )
            }
            if (remotePlan?.semanticQueries.orEmpty().isNotEmpty()) {
                add(PlannedEvidenceChannel(CHANNEL_SEMANTIC, semanticQueries = remotePlan?.semanticQueries.orEmpty()))
            }
            if (filters.dateRange != null) {
                add(PlannedEvidenceChannel(CHANNEL_DATE, weight = 0.45f))
            }
        }
        val rawChannels = remotePlan?.evidenceChannels.orEmpty() + inferredFromFields
        val baseChannels = rawChannels.ifEmpty {
            defaultEvidenceChannels(query, localPlan, taskType, mode, filters)
        }
        return mergeEvidenceChannels(baseChannels.map { channel ->
            enrichEvidenceChannel(channel, localPlan, remotePlan, taskType, filters)
        }).ifEmpty {
            defaultEvidenceChannels(query, localPlan, taskType, mode, filters)
        }.take(12)
    }

    private fun defaultEvidenceChannels(
        query: String,
        localPlan: LocalQueryPlan,
        taskType: String,
        mode: AskMode,
        filters: PlannerFilters,
    ): List<PlannedEvidenceChannel> {
        val channels = mutableListOf<PlannedEvidenceChannel>()
        fun add(
            channel: String,
            weight: Float,
            required: Boolean = false,
            terms: List<String> = emptyList(),
            entityTypes: List<String> = emptyList(),
            categories: List<String> = emptyList(),
            appHints: List<String> = emptyList(),
            visualLabels: List<String> = emptyList(),
            visualObjectLabels: List<String> = emptyList(),
            semanticQueries: List<String> = emptyList(),
        ) {
            channels += PlannedEvidenceChannel(
                channel = channel,
                weight = weight,
                required = required,
                terms = terms,
                entityTypes = entityTypes,
                categories = categories,
                appHints = appHints,
                visualLabels = visualLabels,
                visualObjectLabels = visualObjectLabels,
                semanticQueries = semanticQueries,
            )
        }

        val terms = localPlan.searchTerms
        val entityTypes = (localPlan.entityTypes + filters.entityTypes).distinct()
        val categories = (localPlan.categories + filters.categories).distinct()
        val appHints = (localPlan.appHints + filters.appHints).distinct()
        when (taskType) {
            TASK_LOOKUP_VALUE -> {
                add(CHANNEL_ENTITY, weight = 1.35f, required = entityTypes.isNotEmpty(), entityTypes = entityTypes)
                add(CHANNEL_TEXT, weight = 0.65f, terms = terms)
            }

            TASK_PROVE -> {
                add(CHANNEL_TEXT, weight = 0.95f, terms = terms)
                add(CHANNEL_ENTITY, weight = 0.9f, entityTypes = entityTypes)
                add(CHANNEL_CATEGORY, weight = 0.85f, categories = categories)
            }

            TASK_SUMMARIZE -> {
                add(CHANNEL_SEMANTIC, weight = 0.95f, semanticQueries = listOf(query))
                add(CHANNEL_TEXT, weight = 0.55f, terms = terms)
                add(CHANNEL_VISUAL, weight = 0.55f, visualLabels = filters.visualLabels, visualObjectLabels = filters.visualObjectLabels)
                add(CHANNEL_CATEGORY, weight = 0.45f, categories = categories)
            }

            TASK_COMPARE -> {
                add(CHANNEL_SEMANTIC, weight = 0.85f, semanticQueries = listOf(query))
                add(CHANNEL_TEXT, weight = 0.85f, terms = terms)
                add(CHANNEL_CATEGORY, weight = 0.55f, categories = categories)
                add(CHANNEL_ENTITY, weight = 0.55f, entityTypes = entityTypes)
            }

            TASK_AGGREGATE -> {
                add(CHANNEL_ENTITY, weight = 1.05f, entityTypes = entityTypes.ifEmpty { listOf("amount", "upi_id", "person_name") })
                add(CHANNEL_CATEGORY, weight = 0.8f, categories = categories)
                add(CHANNEL_TEXT, weight = 0.7f, terms = terms)
                add(CHANNEL_SEMANTIC, weight = 0.55f, semanticQueries = listOf(query))
            }

            TASK_CLEANUP -> {
                add(CHANNEL_ENTITY, weight = 1.15f, required = true, entityTypes = entityTypes.ifEmpty { SENSITIVE_ENTITY_TYPES.toList() })
                add(CHANNEL_CATEGORY, weight = 0.55f, categories = categories)
                add(CHANNEL_TEXT, weight = 0.45f, terms = terms)
            }

            TASK_TIMELINE -> {
                add(CHANNEL_DATE, weight = 0.8f)
                add(CHANNEL_TEXT, weight = 0.75f, terms = terms)
                add(CHANNEL_SEMANTIC, weight = 0.55f, semanticQueries = listOf(query))
            }

            TASK_ACTION_ITEMS -> {
                add(CHANNEL_TEXT, weight = 0.95f, terms = terms + listOf("pending", "due", "refund", "reply", "renewal"))
                add(CHANNEL_CATEGORY, weight = 0.65f, categories = categories)
                add(CHANNEL_SEMANTIC, weight = 0.65f, semanticQueries = listOf(query))
                add(CHANNEL_DATE, weight = 0.35f)
            }

            else -> {
                if (terms.isNotEmpty()) add(CHANNEL_TEXT, weight = 0.9f, terms = terms)
                if (entityTypes.isNotEmpty()) add(CHANNEL_ENTITY, weight = 0.85f, entityTypes = entityTypes)
                if (categories.isNotEmpty()) add(CHANNEL_CATEGORY, weight = 0.6f, categories = categories)
                add(CHANNEL_SEMANTIC, weight = if (mode == AskMode.FUZZY_VISUAL) 0.85f else 0.55f, semanticQueries = listOf(query))
            }
        }
        if (appHints.isNotEmpty()) add(CHANNEL_APP, weight = 0.85f, appHints = appHints)
        if (filters.visualLabels.isNotEmpty() || filters.visualObjectLabels.isNotEmpty() || mode == AskMode.FUZZY_VISUAL) {
            add(
                CHANNEL_VISUAL,
                weight = if (mode == AskMode.FUZZY_VISUAL) 1.15f else 0.75f,
                visualLabels = filters.visualLabels,
                visualObjectLabels = filters.visualObjectLabels,
            )
        }
        if (entityTypes.isNotEmpty() && taskType != TASK_LOOKUP_VALUE) {
            add(CHANNEL_LINKED_ENTITY, weight = 0.45f, entityTypes = entityTypes)
        }
        if (filters.dateRange != null && channels.none { normalizeChannel(it.channel) == CHANNEL_DATE }) {
            add(CHANNEL_DATE, weight = 0.4f)
        }
        return mergeEvidenceChannels(channels)
    }

    private fun enrichEvidenceChannel(
        channel: PlannedEvidenceChannel,
        localPlan: LocalQueryPlan,
        remotePlan: RewrittenQueryPlan?,
        taskType: String,
        filters: PlannerFilters,
    ): PlannedEvidenceChannel {
        val normalized = normalizeChannel(channel.channel)
        return when (normalized) {
            CHANNEL_TEXT -> channel.copy(
                channel = CHANNEL_TEXT,
                terms = (channel.terms + remotePlan?.searchTerms.orEmpty() + localPlan.searchTerms)
                    .normalizedPlannerValues(limit = 48),
            )

            CHANNEL_ENTITY -> channel.copy(
                channel = CHANNEL_ENTITY,
                required = channel.required || (taskType == TASK_LOOKUP_VALUE && (channel.entityTypes + localPlan.entityTypes + filters.entityTypes).isNotEmpty()),
                entityTypes = (channel.entityTypes + remotePlan?.entityTypes.orEmpty() + filters.entityTypes + localPlan.entityTypes)
                    .normalizedPlannerValues(limit = 32),
            )

            CHANNEL_APP -> channel.copy(
                channel = CHANNEL_APP,
                appHints = (channel.appHints + remotePlan?.appHints.orEmpty() + filters.appHints + localPlan.appHints)
                    .normalizedPlannerValues(limit = 20),
            )

            CHANNEL_CATEGORY -> channel.copy(
                channel = CHANNEL_CATEGORY,
                categories = (channel.categories + remotePlan?.categories.orEmpty() + filters.categories + localPlan.categories)
                    .normalizedPlannerValues(limit = 28),
            )

            CHANNEL_VISUAL -> channel.copy(
                channel = CHANNEL_VISUAL,
                visualLabels = (channel.visualLabels + remotePlan?.visualLabels.orEmpty() + filters.visualLabels)
                    .normalizedPlannerValues(limit = 36),
                visualObjectLabels = (channel.visualObjectLabels + remotePlan?.visualObjectLabels.orEmpty() + filters.visualObjectLabels)
                    .normalizedPlannerValues(limit = 36),
            )

            CHANNEL_SEMANTIC -> channel.copy(
                channel = CHANNEL_SEMANTIC,
                semanticQueries = (channel.semanticQueries + remotePlan?.semanticQueries.orEmpty() + localPlan.normalizedQuery)
                    .normalizedPlannerValues(limit = 16),
            )

            CHANNEL_LINKED_ENTITY -> channel.copy(
                channel = CHANNEL_LINKED_ENTITY,
                entityTypes = (channel.entityTypes + remotePlan?.entityTypes.orEmpty() + filters.entityTypes + localPlan.entityTypes)
                    .normalizedPlannerValues(limit = 32),
            )

            CHANNEL_DATE -> channel.copy(channel = CHANNEL_DATE)
            else -> channel.copy(channel = normalized)
        }
    }

    private fun mergeEvidenceChannels(channels: List<PlannedEvidenceChannel>): List<PlannedEvidenceChannel> {
        return channels.mapNotNull { channel ->
            val normalized = normalizeChannel(channel.channel)
            channel.copy(channel = normalized).takeIf { normalized in KNOWN_CHANNELS }
        }.groupBy { it.channel }
            .map { (channel, grouped) ->
                PlannedEvidenceChannel(
                    channel = channel,
                    weight = grouped.maxOf { it.weight }.coerceIn(0.1f, 2.5f),
                    required = grouped.any { it.required },
                    terms = grouped.flatMap { it.terms }.normalizedPlannerValues(limit = 48),
                    entityTypes = grouped.flatMap { it.entityTypes }.normalizedPlannerValues(limit = 32),
                    categories = grouped.flatMap { it.categories }.normalizedPlannerValues(limit = 28),
                    appHints = grouped.flatMap { it.appHints }.normalizedPlannerValues(limit = 20),
                    visualLabels = grouped.flatMap { it.visualLabels }.normalizedPlannerValues(limit = 36),
                    visualObjectLabels = grouped.flatMap { it.visualObjectLabels }.normalizedPlannerValues(limit = 36),
                    semanticQueries = grouped.flatMap { it.semanticQueries }.normalizedPlannerValues(limit = 16),
                )
            }
            .sortedBy { KNOWN_CHANNELS.indexOf(it.channel).takeIf { index -> index >= 0 } ?: 99 }
    }

    private fun normalizeChannel(raw: String): String {
        return when (raw.trim().lowercase(Locale.US).replace('-', '_').replace(' ', '_')) {
            "ocr", "fts", "full_text", "full_text_search" -> CHANNEL_TEXT
            "exact_entity", "entity_type", "entities" -> CHANNEL_ENTITY
            "source", "app_hint", "application" -> CHANNEL_APP
            "categories" -> CHANNEL_CATEGORY
            "visual_label", "visual_object", "object", "objects", "image", "image_label" -> CHANNEL_VISUAL
            "semantic_text", "semantic_visual", "embedding", "embeddings" -> CHANNEL_SEMANTIC
            "linked", "link", "entity_link", "entity_links" -> CHANNEL_LINKED_ENTITY
            "time", "recency", "chronology" -> CHANNEL_DATE
            else -> raw.trim().lowercase(Locale.US).replace('-', '_').replace(' ', '_')
        }
    }

    private fun normalizeGrouping(raw: String): String {
        return when (raw.trim().lowercase(Locale.US).replace('-', '_').replace(' ', '_')) {
            "", "best_match", "none" -> "none"
            "person", "people", "contact", "contacts" -> "entity"
            "date", "timeline" -> "date_bucket"
            "privacy", "sensitive" -> "sensitive_type"
            "analytics", "category_analytics" -> "analytics_category"
            "comparison", "option" -> "comparison_option"
            "pending", "action", "action_items" -> "issue"
            else -> raw.trim().lowercase(Locale.US).replace('-', '_').replace(' ', '_')
        }
    }

    private fun normalizeSort(raw: String): String {
        return when (raw.trim().lowercase(Locale.US).replace('-', '_').replace(' ', '_')) {
            "newest", "latest", "recent" -> "newest_first"
            "oldest" -> "oldest_first"
            "date", "timeline" -> "chronological"
            "coverage" -> "coverage"
            else -> "relevance"
        }
    }

    private fun defaultGroupingForTask(taskType: String, mode: AskMode): String {
        return when (taskType) {
            TASK_SUMMARIZE -> "theme"
            TASK_COMPARE -> "comparison_option"
            TASK_AGGREGATE -> "analytics_category"
            TASK_CLEANUP -> "sensitive_type"
            TASK_TIMELINE -> "date_bucket"
            TASK_ACTION_ITEMS -> "issue"
            else -> when (mode) {
                AskMode.ENTITY_GROUP -> "entity"
                AskMode.APP_SOURCE -> "app"
                AskMode.FUZZY_VISUAL -> "theme"
                else -> "none"
            }
        }
    }

    private fun defaultSortForTask(taskType: String, mode: AskMode, query: String): String {
        val q = query.lowercase(Locale.US)
        return when {
            "oldest" in q -> "oldest_first"
            "latest" in q || "newest" in q || "recent" in q -> "newest_first"
            taskType == TASK_TIMELINE || mode == AskMode.TIMELINE -> "chronological"
            taskType in setOf(TASK_SUMMARIZE, TASK_AGGREGATE, TASK_CLEANUP) -> "coverage"
            else -> "relevance"
        }
    }

    private fun defaultAllowBroadFallback(taskType: String, mode: AskMode, grouping: String): Boolean {
        return taskType in BROAD_TASK_TYPES ||
            mode in setOf(AskMode.FUZZY_VISUAL, AskMode.THEME_SUMMARY) ||
            grouping in setOf("theme", "analytics_category", "comparison_option", "issue", "sensitive_type")
    }

    private fun answerShapeForTask(taskType: String, grouping: PlannerGrouping): String {
        return when (taskType) {
            TASK_LOOKUP_VALUE -> "direct_answer"
            TASK_PROVE -> "evidence_list"
            TASK_SUMMARIZE -> "grouped_summary"
            TASK_COMPARE -> "comparison"
            TASK_AGGREGATE -> "aggregate_summary"
            TASK_TIMELINE -> "timeline"
            TASK_ACTION_ITEMS -> "grouped_summary"
            TASK_CLEANUP -> "grouped_summary"
            else -> if (grouping.by == "none") "evidence_list" else "grouped_summary"
        }
    }

    private fun operationForTask(taskType: String): String {
        return when (taskType) {
            TASK_LOOKUP_VALUE -> "lookup_value"
            TASK_PROVE -> "prove"
            TASK_SUMMARIZE -> "summarize"
            TASK_COMPARE -> "compare"
            TASK_AGGREGATE -> "aggregate"
            TASK_CLEANUP -> "cleanup"
            TASK_TIMELINE -> "timeline"
            TASK_ACTION_ITEMS -> "pending_action"
            else -> "find"
        }
    }

    private suspend fun generateCandidates(
        askPlan: AskPlan,
        localPlan: LocalQueryPlan,
        request: AskRequest,
        onProgress: (AskProgress) -> Unit,
    ): CandidateGenerationResult {
        val range = askPlan.dateRange ?: request.dateRange
        val targetLimit = if (askPlan.ranking.allowBroadFallback) 1_000 else (request.maxResults * 8).coerceIn(120, 500)
        val results = linkedMapOf<Long, CandidateScreenshot>()
        fun add(
            screenshots: List<ScreenshotEntity>,
            source: String,
            baseScore: Float,
            semanticScore: Float = 0f,
            linkedScore: Float = 0f,
            visualLabelScore: Float = 0f,
            visualObjectScore: Float = 0f,
            subQueryLabel: String? = null,
            channelScores: Map<String, Float> = emptyMap(),
        ) {
            screenshots.forEach { screenshot ->
                val existing = results[screenshot.id]
                results[screenshot.id] = if (existing == null) {
                    CandidateScreenshot(
                        screenshot = screenshot,
                        source = source,
                        baseScore = baseScore,
                        semanticScore = semanticScore,
                        linkedScore = linkedScore,
                        visualLabelScore = visualLabelScore,
                        visualObjectScore = visualObjectScore,
                        subQueryLabels = subQueryLabel?.let { listOf(it) }.orEmpty(),
                        channelScores = channelScores,
                    )
                } else {
                    existing.copy(
                        baseScore = maxOf(existing.baseScore, baseScore),
                        semanticScore = maxOf(existing.semanticScore, semanticScore),
                        linkedScore = maxOf(existing.linkedScore, linkedScore),
                        visualLabelScore = maxOf(existing.visualLabelScore, visualLabelScore),
                        visualObjectScore = maxOf(existing.visualObjectScore, visualObjectScore),
                        source = (existing.source.split("+") + source).distinct().joinToString("+"),
                        subQueryLabels = (existing.subQueryLabels + listOfNotNull(subQueryLabel)).distinct(),
                        channelScores = mergeChannelScores(existing.channelScores, channelScores),
                    )
                }
            }
        }
        val exactEntityCandidates = if (askPlan.hasChannel(CHANNEL_ENTITY) && shouldSearchExactEntities(askPlan)) {
            searchExactEntityCandidates(askPlan, request, targetLimit)
        } else {
            emptyList()
        }

        add(
            screenshots = exactEntityCandidates,
            source = "exact_entity",
            baseScore = 2.2f,
            channelScores = mapOf(CHANNEL_ENTITY to 1.8f),
        )
        if (askPlan.hasChannel(CHANNEL_TEXT)) {
            add(
                screenshots = searchTextCandidates(localPlan, targetLimit),
                source = "text",
                baseScore = 0.75f,
                channelScores = mapOf(CHANNEL_TEXT to 1.0f),
            )
        }
        if (askPlan.hasChannel(CHANNEL_APP)) {
            add(
                screenshots = searchAppHintCandidates(askPlan, request, targetLimit),
                source = "app",
                baseScore = 0.8f,
                channelScores = mapOf(CHANNEL_APP to 1.0f),
            )
        }
        if (askPlan.hasChannel(CHANNEL_CATEGORY)) {
            add(
                screenshots = searchCategoryCandidates(askPlan, request, targetLimit),
                source = "category",
                baseScore = 0.65f,
                channelScores = mapOf(CHANNEL_CATEGORY to 1.0f),
            )
        }
        if (askPlan.hasChannel(CHANNEL_ENTITY)) {
            add(
                screenshots = searchEntityTypeCandidates(askPlan, request, targetLimit),
                source = "entity",
                baseScore = 0.95f,
                channelScores = mapOf(CHANNEL_ENTITY to 1.0f),
            )
        }
        val subQueryCandidates = searchSubQueries(askPlan, localPlan, targetLimit)
        subQueryCandidates.forEach { (label, screenshots) ->
            add(
                screenshots = screenshots,
                source = "sub_query",
                baseScore = 1.0f,
                subQueryLabel = label,
                channelScores = mapOf(CHANNEL_TEXT to 0.8f),
            )
        }
        val visualLabelCandidates = if (askPlan.hasChannel(CHANNEL_VISUAL)) {
            searchVisualLabelCandidates(askPlan, request, targetLimit)
        } else {
            emptyList()
        }
        visualLabelCandidates.forEach { visual ->
            add(
                screenshots = listOf(visual.screenshot),
                source = "visual_label",
                baseScore = visualSourceBase(askPlan),
                visualLabelScore = visual.score,
                channelScores = mapOf(CHANNEL_VISUAL to visual.score),
            )
        }
        val visualObjectCandidates = if (askPlan.hasChannel(CHANNEL_VISUAL)) {
            searchVisualObjectCandidates(askPlan, request, targetLimit)
        } else {
            emptyList()
        }
        visualObjectCandidates.forEach { visual ->
            add(
                screenshots = listOf(visual.screenshot),
                source = "visual_object",
                baseScore = visualSourceBase(askPlan) * 0.9f,
                visualObjectScore = visual.score,
                channelScores = mapOf(CHANNEL_VISUAL to visual.score),
            )
        }
        onProgress(AskProgress(AskProgressStep.RETRIEVING_SEMANTIC_INDEX))
        val semanticCandidates = if (askPlan.hasChannel(CHANNEL_SEMANTIC)) {
            searchSemanticCandidates(askPlan, request, targetLimit)
        } else {
            emptyList()
        }
        semanticCandidates.forEach { semantic ->
            add(
                screenshots = listOf(semantic.screenshot),
                source = "semantic",
                baseScore = 0.7f,
                semanticScore = semantic.similarity,
                channelScores = mapOf(CHANNEL_SEMANTIC to semantic.similarity),
            )
        }
        onProgress(AskProgress(AskProgressStep.EXPANDING_LINKED_ENTITIES))
        val linkedCandidates = if (askPlan.hasChannel(CHANNEL_LINKED_ENTITY)) {
            searchLinkedEntityCandidates(askPlan, request, targetLimit)
        } else {
            emptyList()
        }
        add(
            screenshots = linkedCandidates,
            source = "linked_entity",
            baseScore = 0.6f,
            linkedScore = 1.3f,
            channelScores = mapOf(CHANNEL_LINKED_ENTITY to 1.0f),
        )
        val broadFallbackUsed = askPlan.ranking.allowBroadFallback || results.size < min(80, targetLimit)
        if (broadFallbackUsed) {
            add(
                screenshots = dao.mindMapScreenshots(
                    startMillis = range?.startMillis,
                    endMillisExclusive = range?.endMillisExclusive,
                    limit = targetLimit,
                ),
                source = "broad_coverage",
                baseScore = if (askPlan.ranking.allowBroadFallback) 0.45f else 0.12f,
                channelScores = mapOf(CHANNEL_BROAD to if (askPlan.ranking.allowBroadFallback) 0.8f else 0.25f),
            )
        }
        SkillDebugLog.i(
            event = "ask_candidates",
            message = "task=${askPlan.taskType} channels=${askPlan.evidenceChannels.joinToString(",") { it.channel }} " +
                "target=$targetLimit total=${results.size} " +
                "exactEntities=${exactEntityCandidates.size} semantic=${semanticCandidates.size} " +
                "visualLabels=${visualLabelCandidates.size} visualObjects=${visualObjectCandidates.size} " +
                "linked=${linkedCandidates.size} broad=$broadFallbackUsed subQueries=${subQueryCandidates.size} terms=${askPlan.searchTerms.size} " +
                "categories=${askPlan.categories.size} entityTypes=${askPlan.entityTypes.size}",
        )
        return CandidateGenerationResult(
            candidates = results.values.take(targetLimit),
            semanticCandidateCount = semanticCandidates.size,
            linkedCandidateCount = linkedCandidates.size,
            subQueryCount = subQueryCandidates.size,
        )
    }

    private fun mergeChannelScores(
        left: Map<String, Float>,
        right: Map<String, Float>,
    ): Map<String, Float> {
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        return (left.keys + right.keys).associateWith { key ->
            maxOf(left[key] ?: 0f, right[key] ?: 0f)
        }
    }

    private fun shouldSearchExactEntities(askPlan: AskPlan): Boolean {
        return askPlan.taskType == TASK_LOOKUP_VALUE ||
            askPlan.requiredChannels().contains(CHANNEL_ENTITY) ||
            askPlan.entityTypes.any { it in EXACT_ENTITY_TYPES }
    }

    private suspend fun searchTextCandidates(
        plan: LocalQueryPlan,
        limit: Int,
    ): List<ScreenshotEntity> {
        val ftsQuery = plan.ftsQuery ?: return emptyList()
        val range = plan.dateRange
        return runCatching {
            dao.searchFts(
                matchQuery = ftsQuery,
                startMillis = range?.startMillis,
                endMillisExclusive = range?.endMillisExclusive,
                limit = limit,
            )
        }.getOrElse { emptyList() }
    }

    private suspend fun searchCategoryCandidates(
        askPlan: AskPlan,
        request: AskRequest,
        limit: Int,
    ): List<ScreenshotEntity> {
        if (askPlan.categories.isEmpty()) return emptyList()
        val range = askPlan.dateRange ?: request.dateRange
        return dao.searchCategories(
            categories = askPlan.categories,
            startMillis = range?.startMillis,
            endMillisExclusive = range?.endMillisExclusive,
            limit = limit,
        )
    }

    private suspend fun searchEntityTypeCandidates(
        askPlan: AskPlan,
        request: AskRequest,
        limit: Int,
    ): List<ScreenshotEntity> {
        if (askPlan.entityTypes.isEmpty()) return emptyList()
        val range = askPlan.dateRange ?: request.dateRange
        return dao.searchEntityTypes(
            entityTypes = askPlan.entityTypes,
            startMillis = range?.startMillis,
            endMillisExclusive = range?.endMillisExclusive,
            limit = limit,
        )
    }

    private suspend fun searchAppHintCandidates(
        askPlan: AskPlan,
        request: AskRequest,
        limit: Int,
    ): List<ScreenshotEntity> {
        if (askPlan.appHints.isEmpty()) return emptyList()
        val range = askPlan.dateRange ?: request.dateRange
        return dao.searchAppHints(
            appHints = askPlan.appHints,
            startMillis = range?.startMillis,
            endMillisExclusive = range?.endMillisExclusive,
            limit = limit,
        )
    }

    private suspend fun searchExactEntityCandidates(
        askPlan: AskPlan,
        request: AskRequest,
        limit: Int,
    ): List<ScreenshotEntity> {
        val queryEntities = entityExtractor.extract(request.query)
        val exactTypes = (askPlan.entityTypes + queryEntities.map { it.type })
            .filter { it in EXACT_ENTITY_TYPES }
            .distinct()
        if (exactTypes.isEmpty()) return emptyList()

        val range = askPlan.dateRange ?: request.dateRange
        val results = linkedMapOf<Long, ScreenshotEntity>()
        val exactValues = queryEntities
            .filter { it.type in exactTypes }
            .map { it.normalizedValue }
            .filter { it.isNotBlank() }
            .distinct()

        if (exactValues.isNotEmpty()) {
            dao.searchExactEntityValues(
                entityTypes = exactTypes,
                normalizedValues = exactValues,
                startMillis = range?.startMillis,
                endMillisExclusive = range?.endMillisExclusive,
                limit = limit,
            ).forEach { results[it.id] = it }
        }
        if (results.size < limit) {
            dao.searchExactEntityTypes(
                entityTypes = exactTypes,
                startMillis = range?.startMillis,
                endMillisExclusive = range?.endMillisExclusive,
                limit = limit,
            ).forEach { results[it.id] = it }
        }
        SkillDebugLog.i(
            event = "ask_exact_entity_candidates",
            message = "types=${exactTypes.size} values=${exactValues.size} results=${results.size}",
        )
        return results.values.take(limit)
    }

    private suspend fun searchWithPlan(plan: LocalQueryPlan, limit: Int): List<ScreenshotEntity> {
        val range = plan.dateRange
        val results = linkedMapOf<Long, ScreenshotEntity>()
        if (plan.appHints.isNotEmpty()) {
            dao.searchAppHints(
                appHints = plan.appHints,
                startMillis = range?.startMillis,
                endMillisExclusive = range?.endMillisExclusive,
                limit = limit,
            ).forEach { results[it.id] = it }
        }
        if (plan.ftsQuery != null) {
            runCatching {
                dao.searchFts(
                    matchQuery = plan.ftsQuery,
                    startMillis = range?.startMillis,
                    endMillisExclusive = range?.endMillisExclusive,
                    limit = limit,
                )
            }.getOrElse { emptyList() }.forEach { results[it.id] = it }
        }
        if (plan.categories.isNotEmpty() && results.size < limit) {
            dao.searchCategories(
                categories = plan.categories,
                startMillis = range?.startMillis,
                endMillisExclusive = range?.endMillisExclusive,
                limit = limit,
            ).forEach { results[it.id] = it }
        }
        if (plan.entityTypes.isNotEmpty() && results.size < limit) {
            dao.searchEntityTypes(
                entityTypes = plan.entityTypes,
                startMillis = range?.startMillis,
                endMillisExclusive = range?.endMillisExclusive,
                limit = limit,
            ).forEach { results[it.id] = it }
        }
        return results.values.take(limit)
    }

    private suspend fun searchSubQueries(
        askPlan: AskPlan,
        localPlan: LocalQueryPlan,
        limit: Int,
    ): List<Pair<String, List<ScreenshotEntity>>> {
        if (askPlan.subQueries.isEmpty()) return emptyList()
        val perQueryLimit = (limit / askPlan.subQueries.size.coerceAtLeast(1)).coerceIn(40, 160)
        return askPlan.subQueries.take(6).mapNotNull { subQuery ->
            val subPlan = localPlan.copy(
                normalizedQuery = subQuery.query.lowercase(Locale.US),
                ftsQuery = queryPlanner.toFtsQuery(
                    (subQuery.query + " " + subQuery.searchTerms.joinToString(" ")).lowercase(Locale.US),
                ),
                searchTerms = (subQuery.searchTerms + queryPlanner.searchTermsForInternal(subQuery.query))
                    .distinct()
                    .take(16),
                categories = (localPlan.categories + subQuery.categories).distinct(),
                entityTypes = (localPlan.entityTypes + subQuery.entityTypes).distinct(),
            )
            val screenshots = searchWithPlan(subPlan, perQueryLimit)
            if (screenshots.isEmpty()) null else subQuery.label.ifBlank { subQuery.query } to screenshots
        }
    }

    private suspend fun searchVisualLabelCandidates(
        askPlan: AskPlan,
        request: AskRequest,
        targetLimit: Int,
    ): List<VisualCandidate> {
        val labels = visualLabelsForQuery(askPlan, request.query)
        if (labels.isEmpty()) return emptyList()
        val range = askPlan.dateRange ?: request.dateRange
        val rows = dao.searchVisualLabelCandidates(
            labels = labels,
            minConfidence = visualLabelMinConfidence(askPlan),
            startMillis = range?.startMillis,
            endMillisExclusive = range?.endMillisExclusive,
            limit = (targetLimit / 2).coerceIn(40, 260),
        )
        return rows.toVisualCandidates("ask_visual_label_candidates", askPlan)
    }

    private suspend fun searchVisualObjectCandidates(
        askPlan: AskPlan,
        request: AskRequest,
        targetLimit: Int,
    ): List<VisualCandidate> {
        val labels = visualObjectLabelsForQuery(askPlan, request.query)
        if (labels.isEmpty()) return emptyList()
        val range = askPlan.dateRange ?: request.dateRange
        val rows = dao.searchVisualObjectCandidates(
            labels = labels,
            minConfidence = visualObjectMinConfidence(askPlan),
            minAreaRatio = 0.015f,
            startMillis = range?.startMillis,
            endMillisExclusive = range?.endMillisExclusive,
            limit = (targetLimit / 2).coerceIn(40, 260),
        )
        return rows.toVisualCandidates("ask_visual_object_candidates", askPlan)
    }

    private suspend fun List<com.askmyscreenshots.skill.data.VisualCandidateRow>.toVisualCandidates(
        event: String,
        askPlan: AskPlan,
    ): List<VisualCandidate> {
        if (isEmpty()) {
            SkillDebugLog.i(event, "mode=${askPlan.mode} rows=0")
            return emptyList()
        }
        val screenshotsById = map { it.screenshotId }
            .chunked(MAX_SQL_BIND_ARGS)
            .flatMap { dao.screenshotsByIds(it) }
            .associateBy { it.id }
        val candidates = mapNotNull { row ->
            screenshotsById[row.screenshotId]?.let { screenshot ->
                VisualCandidate(
                    screenshot = screenshot,
                    score = row.confidence * 2.4f + row.matchCount.coerceAtMost(4) * 0.35f,
                )
            }
        }
        SkillDebugLog.i(
            event = event,
            message = "mode=${askPlan.mode} rows=$size selected=${candidates.size} " +
                "best=${maxOfOrNull { it.confidence } ?: 0f}",
        )
        return candidates
    }

    private suspend fun searchSemanticCandidates(
        askPlan: AskPlan,
        request: AskRequest,
        targetLimit: Int,
    ): List<SemanticCandidate> {
        val queryText = buildString {
            append(request.query)
            append(' ')
            append(askPlan.searchTerms.joinToString(" "))
            append(' ')
            append(askPlan.categories.joinToString(" "))
            append(' ')
            append(askPlan.entityTypes.joinToString(" "))
            append(' ')
            append(askPlan.appHints.joinToString(" "))
            append(' ')
            append(askPlan.semanticQueries.joinToString(" "))
        }
        val range = askPlan.dateRange ?: request.dateRange
        val textCandidates = semanticCandidatesForEmbedder(
            embedder = textEmbedder,
            queryText = queryText,
            range = range,
            askPlan = askPlan,
            targetLimit = targetLimit,
            visualOnly = false,
        )
        val visualCandidates = if (askPlan.hasChannel(CHANNEL_VISUAL) || askPlan.taskType in setOf(TASK_SUMMARIZE, TASK_COMPARE)) {
            semanticCandidatesForEmbedder(
                embedder = imageSignalEmbedder,
                queryText = queryText,
                range = range,
                askPlan = askPlan,
                targetLimit = targetLimit,
                visualOnly = true,
            )
        } else {
            emptyList()
        }
        val merged = (textCandidates + visualCandidates)
            .groupBy { it.screenshot.id }
            .map { (_, candidates) -> candidates.maxBy { it.similarity } }
            .sortedByDescending { it.similarity }
            .take(if (askPlan.ranking.allowBroadFallback) 260 else 140)
        SkillDebugLog.i(
            event = "ask_semantic_candidates",
            message = "text=${textCandidates.size} visual=${visualCandidates.size} merged=${merged.size}",
        )
        return merged
    }

    private suspend fun semanticCandidatesForEmbedder(
        embedder: TextEmbedder,
        queryText: String,
        range: com.askmyscreenshots.skill.api.SkillDateRange?,
        askPlan: AskPlan,
        targetLimit: Int,
        visualOnly: Boolean,
    ): List<SemanticCandidate> {
        val queryEmbedding = embedder.embed(queryText) ?: return emptyList()
        val backfilled = backfillEmbeddingsForRange(
            range = range,
            embedder = embedder,
            limit = if (askPlan.ranking.allowBroadFallback) 260 else 120,
            visualOnly = visualOnly,
        )
        val rows = dao.embeddingsForSearch(
            modelName = embedder.modelName,
            modelVersion = embedder.modelVersion,
            startMillis = range?.startMillis,
            endMillisExclusive = range?.endMillisExclusive,
            limit = if (askPlan.ranking.allowBroadFallback) targetLimit else targetLimit.coerceAtMost(600),
        )
        if (rows.isEmpty()) {
            SkillDebugLog.i(
                event = "ask_semantic_candidates",
                message = "model=${embedder.modelName} rows=0 backfilled=$backfilled",
            )
            return emptyList()
        }
        val ranked = rows
            .mapNotNull { row ->
                val similarity = cosineSimilarity(queryEmbedding.vector, row.vectorBlob.toFloatVector())
                SemanticCandidateId(row.screenshotId, similarity).takeIf {
                    similarity >= semanticThreshold(askPlan)
                }
            }
            .sortedByDescending { it.similarity }
            .take(if (askPlan.ranking.allowBroadFallback) 220 else 120)
        if (ranked.isEmpty()) return emptyList()
        val screenshotsById = ranked
            .map { it.screenshotId }
            .chunked(MAX_SQL_BIND_ARGS)
            .flatMap { dao.screenshotsByIds(it) }
            .associateBy { it.id }
        val candidates = ranked.mapNotNull { item ->
            screenshotsById[item.screenshotId]?.let { SemanticCandidate(it, item.similarity) }
        }
        SkillDebugLog.i(
            event = "ask_semantic_candidates",
            message = "model=${embedder.modelName} rows=${rows.size} backfilled=$backfilled " +
                "selected=${candidates.size} best=${ranked.firstOrNull()?.similarity ?: 0f}",
        )
        return candidates
    }

    private suspend fun backfillEmbeddingsForRange(
        range: com.askmyscreenshots.skill.api.SkillDateRange?,
        embedder: TextEmbedder,
        limit: Int,
        visualOnly: Boolean,
    ): Int {
        val missing = dao.screenshotsMissingEmbeddings(
            modelName = embedder.modelName,
            modelVersion = embedder.modelVersion,
            startMillis = range?.startMillis,
            endMillisExclusive = range?.endMillisExclusive,
            limit = limit,
        )
        if (missing.isEmpty()) return 0
        val ids = missing.map { it.id }
        val entitiesByScreenshot = ids
            .chunked(MAX_SQL_BIND_ARGS)
            .flatMap { dao.detectedEntityRowsForScreenshots(it) }
            .groupBy { it.screenshotId }
        val labelsByScreenshot = ids
            .chunked(MAX_SQL_BIND_ARGS)
            .flatMap { dao.visualLabelRowsForScreenshots(it) }
            .groupBy { it.screenshotId }
        val objectLabelsByScreenshot = ids
            .chunked(MAX_SQL_BIND_ARGS)
            .flatMap { dao.objectLabelsForScreenshots(it, minConfidence = 0.35f) }
            .groupBy { it.screenshotId }
        val descriptionsByScreenshot = ids
            .chunked(MAX_SQL_BIND_ARGS)
            .flatMap { dao.visualDescriptionsForScreenshots(it) }
            .groupBy { it.screenshotId }
        missing.forEach { screenshot ->
            val visualDescription = descriptionsByScreenshot[screenshot.id]?.firstOrNull()?.description
            val input = if (visualOnly) {
                buildString {
                    appendLine("app:${screenshot.appHint.orEmpty()}")
                    appendLine("category:${screenshot.category}")
                    appendLine(labelsByScreenshot[screenshot.id].orEmpty().joinToString(" ") { "visual:${it.label}" })
                    appendLine(objectLabelsByScreenshot[screenshot.id].orEmpty().joinToString(" ") { "object:${it.label}" })
                    visualDescription?.let { appendLine("caption:$it") }
                }.trim()
            } else {
                SemanticInputBuilder.fromStored(
                    screenshot = screenshot,
                    entities = entitiesByScreenshot[screenshot.id].orEmpty(),
                    labels = labelsByScreenshot[screenshot.id].orEmpty(),
                    categories = emptyList(),
                    visualDescription = visualDescription,
                    objectLabels = objectLabelsByScreenshot[screenshot.id].orEmpty()
                        .sortedByDescending { it.confidence }
                        .map { it.label },
                )
            }
            embedder.embed(input)?.let { embedding ->
                dao.insertEmbedding(
                    ScreenshotEmbeddingEntity(
                        screenshotId = screenshot.id,
                        modelName = embedding.modelName,
                        modelVersion = embedding.modelVersion,
                        inputHash = embedding.inputHash,
                        dimension = embedding.dimension,
                        vectorBlob = embedding.vector.toEmbeddingBlob(),
                        createdAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
        return missing.size
    }

    private suspend fun searchLinkedEntityCandidates(
        askPlan: AskPlan,
        request: AskRequest,
        targetLimit: Int,
    ): List<ScreenshotEntity> {
        val queryEntities = entityExtractor.extract(request.query)
        val types = (askPlan.entityTypes + queryEntities.map { it.type })
            .filter { it in LINK_EXPANSION_ENTITY_TYPES }
            .distinct()
        val values = queryEntities
            .filter { it.type in LINK_EXPANSION_ENTITY_TYPES }
            .map { it.normalizedValue }
            .filter { it.isNotBlank() }
            .distinct()
        if (types.isEmpty() || values.isEmpty()) return emptyList()
        val linkedTargets = dao.linkedEntityTargets(
            types = types,
            normalizedValues = values,
            limit = 24,
        ).filter { it.type in LINK_EXPANSION_ENTITY_TYPES }
        if (linkedTargets.isEmpty()) return emptyList()
        val range = askPlan.dateRange ?: request.dateRange
        val results = dao.searchExactEntityValues(
            entityTypes = linkedTargets.map { it.type }.distinct(),
            normalizedValues = linkedTargets.map { it.normalizedValue }.distinct(),
            startMillis = range?.startMillis,
            endMillisExclusive = range?.endMillisExclusive,
            limit = (targetLimit / 3).coerceIn(40, 180),
        )
        SkillDebugLog.i(
            event = "ask_linked_candidates",
            message = "types=${types.size} values=${values.size} linkedTargets=${linkedTargets.size} results=${results.size}",
        )
        return results
    }

    private fun semanticThreshold(plan: AskPlan): Float {
        return when (plan.taskType) {
            TASK_LOOKUP_VALUE -> 0.14f
            TASK_SUMMARIZE, TASK_COMPARE -> 0.08f
            TASK_AGGREGATE, TASK_ACTION_ITEMS, TASK_CLEANUP -> 0.07f
            TASK_FIND -> if (plan.hasChannel(CHANNEL_VISUAL)) 0.08f else 0.1f
            else -> 0.1f
        }
    }

    private fun visualLabelsForQuery(plan: AskPlan, query: String): List<String> {
        if (plan.taskType == TASK_LOOKUP_VALUE) return emptyList()
        val localFallbackLabels = if (plan.plannerSource == "local") {
            visualLabelsFrom(VISUAL_LABEL_SYNONYMS, plan, query)
        } else {
            emptyList()
        }
        return (plan.visualLabels.normalizedPlannerValues(limit = 32) + localFallbackLabels)
            .filter { it !in LOW_VALUE_VISUAL_LABELS }
            .distinct()
            .take(32)
    }

    private fun visualObjectLabelsForQuery(plan: AskPlan, query: String): List<String> {
        if (plan.taskType == TASK_LOOKUP_VALUE) return emptyList()
        val localFallbackLabels = if (plan.plannerSource == "local") {
            visualLabelsFrom(VISUAL_OBJECT_SYNONYMS, plan, query)
        } else {
            emptyList()
        }
        return (plan.visualObjectLabels.normalizedPlannerValues(limit = 32) + localFallbackLabels)
            .filter { it !in LOW_VALUE_VISUAL_LABELS }
            .distinct()
            .take(32)
    }

    private fun visualLabelsFrom(
        synonyms: Map<String, List<String>>,
        plan: AskPlan,
        query: String,
    ): List<String> {
        val q = query.lowercase(Locale.US)
        val plannedTerms = (plan.searchTerms + plan.categories + plan.entityTypes)
            .map { it.replace('_', ' ').lowercase(Locale.US) }
        val matched = synonyms.flatMap { (key, labels) ->
            val keyText = key.lowercase(Locale.US)
            if (q.contains(keyText) || plannedTerms.any { it == keyText || it.contains(keyText) }) {
                labels
            } else {
                emptyList()
            }
        }
        return matched
            .map { it.lowercase(Locale.US).trim() }
            .filter { it.isNotBlank() && it !in LOW_VALUE_VISUAL_LABELS }
            .distinct()
            .take(32)
    }

    private fun visualSourceBase(plan: AskPlan): Float {
        return when (plan.taskType) {
            TASK_LOOKUP_VALUE -> 0.25f
            TASK_PROVE -> 0.95f
            TASK_SUMMARIZE, TASK_COMPARE -> 1.15f
            TASK_FIND -> if (plan.channelWeight(CHANNEL_VISUAL) >= 1f) 1.8f else 1.45f
            else -> 0.8f
        }
    }

    private fun visualLabelMinConfidence(plan: AskPlan): Float {
        return when {
            plan.taskType == TASK_FIND -> 0.45f
            plan.taskType in setOf(TASK_SUMMARIZE, TASK_COMPARE) -> 0.5f
            else -> 0.55f
        }
    }

    private fun visualObjectMinConfidence(plan: AskPlan): Float {
        return when {
            plan.taskType == TASK_FIND -> 0.35f
            plan.taskType in setOf(TASK_SUMMARIZE, TASK_COMPARE) -> 0.4f
            else -> 0.45f
        }
    }

    private suspend fun buildEvidenceGroups(
        request: AskRequest,
        plan: AskPlan,
        scored: List<ScoredScreenshot>,
        entityMap: Map<Long, List<EntityForScreenshot>>,
        labelMap: Map<Long, List<VisualLabelForScreenshot>>,
    ): List<EvidenceGroup> {
        if (scored.isEmpty()) return emptyList()
        val groups = when (plan.grouping.by) {
            "theme" -> themeGroups(scored, entityMap, labelMap, plan)
            "entity" -> entityGroups(scored, entityMap, labelMap, plan)
            "app" -> appGroups(scored, entityMap, labelMap, plan)
            "date_bucket" -> timelineGroups(scored, entityMap, labelMap, plan)
            "sensitive_type" -> sensitiveGroups(scored, entityMap, labelMap, plan)
            "analytics_category" -> analyticsGroups(scored, entityMap, labelMap, plan)
            "comparison_option" -> comparisonGroups(scored, entityMap, labelMap, plan)
            "issue" -> pendingGroups(scored, entityMap, labelMap, plan)
            else -> bestEvidenceGroups(scored, entityMap, labelMap, plan)
        }
        val limit = groupLimit(plan)
        return groups
            .filter { it.screenshotCount > 0 }
            .sortedWith(compareByDescending<EvidenceGroup> { it.confidence }
                .thenByDescending { it.screenshotCount }
                .thenBy { it.title.lowercase() })
            .let { if (limit <= 0) emptyList() else it.take(limit) }
            .ifEmpty { bestEvidenceGroups(scored, entityMap, labelMap, plan).take(1) }
            .also {
                SkillDebugLog.i(
                    event = "ask_groups_built",
                    message = "mode=${plan.mode} requested=${request.query.length} groups=${it.size} " +
                        "types=${it.groupingBy { group -> group.type }.eachCount()}",
                )
            }
    }

    private suspend fun themeGroups(
        scored: List<ScoredScreenshot>,
        entityMap: Map<Long, List<EntityForScreenshot>>,
        labelMap: Map<Long, List<VisualLabelForScreenshot>>,
        plan: AskPlan,
    ): List<EvidenceGroup> {
        val signals = scored.flatMap { scoredScreenshot ->
            signalsFor(scoredScreenshot, entityMap[scoredScreenshot.screenshot.id].orEmpty(), labelMap[scoredScreenshot.screenshot.id].orEmpty())
        }
        val bySignal = signals.groupBy { it.key }
        return bySignal.values
            .filter { signalHits -> signalHits.map { it.screenshot.screenshot.id }.distinct().size >= 2 }
            .map { signalHits ->
                val members = signalHits
                    .map { it.screenshot }
                    .distinctBy { it.screenshot.id }
                    .sortedByDescending { it.score }
                groupFromMembers(
                    type = "theme",
                    title = signalHits.first().label,
                    members = members,
                    entityMap = entityMap,
                    labelMap = labelMap,
                    plan = plan,
                    sortReason = "recurring signal across ${members.size} screenshots",
                )
            }
    }

    private suspend fun entityGroups(
        scored: List<ScoredScreenshot>,
        entityMap: Map<Long, List<EntityForScreenshot>>,
        labelMap: Map<Long, List<VisualLabelForScreenshot>>,
        plan: AskPlan,
    ): List<EvidenceGroup> {
        if (plan.grouping.by == "entity") {
            val contactGroups = contactEntityGroups(scored, entityMap, labelMap, plan)
            if (contactGroups.isNotEmpty()) return contactGroups
        }
        val preferredTypes = preferredEntityTypes(plan)
        val hits = scored.flatMap { scoredScreenshot ->
            entityMap[scoredScreenshot.screenshot.id].orEmpty()
                .filter { entity -> preferredTypes.isEmpty() || entity.type in preferredTypes }
                .filter { entity -> entity.type !in LOW_VALUE_GROUP_ENTITY_TYPES }
                .map { entity -> GroupHit("${entity.type}:${entity.value.normalizeGroupKey()}", entity.value, entity.type, scoredScreenshot) }
        }
        return hits.groupBy { it.key }
            .map { (_, groupHits) ->
                groupFromMembers(
                    type = groupHits.first().type,
                    title = groupHits.first().label,
                    members = groupHits.map { it.screenshot }.distinctBy { it.screenshot.id },
                    entityMap = entityMap,
                    labelMap = labelMap,
                    plan = plan,
                    sortReason = "grouped by ${groupHits.first().type}",
                )
            }
    }

    private suspend fun contactEntityGroups(
        scored: List<ScoredScreenshot>,
        entityMap: Map<Long, List<EntityForScreenshot>>,
        labelMap: Map<Long, List<VisualLabelForScreenshot>>,
        plan: AskPlan,
    ): List<EvidenceGroup> {
        val preferredTypes = preferredEntityTypes(plan).ifEmpty { CONTACT_ENTITY_TYPES }
            .filter { it in CONTACT_ENTITY_TYPES }
            .toSet()
        if (preferredTypes.isEmpty()) return emptyList()
        val parent = mutableMapOf<String, String>()
        fun find(node: String): String {
            val current = parent.getOrPut(node) { node }
            if (current == node) return node
            val root = find(current)
            parent[node] = root
            return root
        }
        fun union(left: String, right: String) {
            val leftRoot = find(left)
            val rightRoot = find(right)
            if (leftRoot != rightRoot) parent[rightRoot] = leftRoot
        }
        fun nodeFor(entity: EntityForScreenshot): String = "${entity.type}:${entity.value.normalizeGroupKey()}"

        val hits = scored.flatMap { scoredScreenshot ->
            val contactEntities = entityMap[scoredScreenshot.screenshot.id].orEmpty()
                .filter { entity -> entity.type in preferredTypes }
                .filter { entity -> entity.type !in LOW_VALUE_GROUP_ENTITY_TYPES }
                .distinctBy { entity -> nodeFor(entity) }
            val nodes = contactEntities.map { nodeFor(it) }
            nodes.forEach { parent.getOrPut(it) { it } }
            if (nodes.size in 2..4) {
                nodes.drop(1).forEach { node -> union(nodes.first(), node) }
            }
            contactEntities.map { entity ->
                GroupHit(nodeFor(entity), entity.value.cleanTitle(), entity.type, scoredScreenshot)
            }
        }
        return hits.groupBy { find(it.key) }
            .map { (_, groupHits) ->
                val labelHit = groupHits.minWith(
                    compareBy<GroupHit> { contactTypeRank(it.type) }
                        .thenBy { it.label.length }
                        .thenBy { it.label.lowercase() },
                )
                groupFromMembers(
                    type = "contact",
                    title = labelHit.label,
                    members = groupHits.map { it.screenshot }.distinctBy { it.screenshot.id },
                    entityMap = entityMap,
                    labelMap = labelMap,
                    plan = plan,
                    sortReason = "grouped by contact clues",
                )
            }
    }

    private suspend fun appGroups(
        scored: List<ScoredScreenshot>,
        entityMap: Map<Long, List<EntityForScreenshot>>,
        labelMap: Map<Long, List<VisualLabelForScreenshot>>,
        plan: AskPlan,
    ): List<EvidenceGroup> {
        return scored.groupBy { it.screenshot.appHint?.takeIf(String::isNotBlank) ?: it.screenshot.category }
            .map { (app, members) ->
                groupFromMembers(
                    type = "app",
                    title = app.toDisplayLabel(),
                    members = members,
                    entityMap = entityMap,
                    labelMap = labelMap,
                    plan = plan,
                    sortReason = "grouped by app/source",
                )
            }
    }

    private suspend fun timelineGroups(
        scored: List<ScoredScreenshot>,
        entityMap: Map<Long, List<EntityForScreenshot>>,
        labelMap: Map<Long, List<VisualLabelForScreenshot>>,
        plan: AskPlan,
    ): List<EvidenceGroup> {
        val sorted = if (plan.sort == "oldest_first") {
            scored.sortedBy { it.screenshot.dateTakenMillis ?: Long.MAX_VALUE }
        } else {
            scored.sortedByDescending { it.screenshot.dateTakenMillis ?: 0L }
        }
        return sorted.groupBy { dateBucket(it.screenshot.dateTakenMillis) }
            .map { (bucket, members) ->
                groupFromMembers(
                    type = "date_bucket",
                    title = bucket,
                    members = members,
                    entityMap = entityMap,
                    labelMap = labelMap,
                    plan = plan,
                    sortReason = "chronological bucket",
                )
            }
    }

    private suspend fun sensitiveGroups(
        scored: List<ScoredScreenshot>,
        entityMap: Map<Long, List<EntityForScreenshot>>,
        labelMap: Map<Long, List<VisualLabelForScreenshot>>,
        plan: AskPlan,
    ): List<EvidenceGroup> {
        val hits = scored.flatMap { scoredScreenshot ->
            entityMap[scoredScreenshot.screenshot.id].orEmpty()
                .filter { it.isSensitive }
                .map { entity -> GroupHit(entity.type, entity.type.replace('_', ' ').toDisplayLabel(), "sensitive_type", scoredScreenshot) }
        }
        return hits.groupBy { it.key }
            .map { (_, groupHits) ->
                groupFromMembers(
                    type = "sensitive_type",
                    title = groupHits.first().label,
                    members = groupHits.map { it.screenshot }.distinctBy { it.screenshot.id },
                    entityMap = entityMap,
                    labelMap = labelMap,
                    plan = plan,
                    sortReason = "contains sensitive ${groupHits.first().label}",
                )
            }
    }

    private suspend fun analyticsGroups(
        scored: List<ScoredScreenshot>,
        entityMap: Map<Long, List<EntityForScreenshot>>,
        labelMap: Map<Long, List<VisualLabelForScreenshot>>,
        plan: AskPlan,
    ): List<EvidenceGroup> {
        val amountHeavy = scored.filter { entityMap[it.screenshot.id].orEmpty().any { entity -> entity.type == "amount" } }
        val base = amountHeavy.ifEmpty { scored }
        return base.groupBy { it.screenshot.category }
            .map { (category, members) ->
                groupFromMembers(
                    type = "analytics_category",
                    title = category.replace('_', ' ').toDisplayLabel(),
                    members = members,
                    entityMap = entityMap,
                    labelMap = labelMap,
                    plan = plan,
                    sortReason = "grouped for spending/pattern analysis",
                )
            }
    }

    private suspend fun comparisonGroups(
        scored: List<ScoredScreenshot>,
        entityMap: Map<Long, List<EntityForScreenshot>>,
        labelMap: Map<Long, List<VisualLabelForScreenshot>>,
        plan: AskPlan,
    ): List<EvidenceGroup> {
        val optionGroups = themeGroups(scored.take(300), entityMap, labelMap, plan)
            .map { it.copy(type = "comparison_option", sortReason = "possible saved option") }
        return optionGroups.ifEmpty { appGroups(scored, entityMap, labelMap, plan) }
    }

    private suspend fun pendingGroups(
        scored: List<ScoredScreenshot>,
        entityMap: Map<Long, List<EntityForScreenshot>>,
        labelMap: Map<Long, List<VisualLabelForScreenshot>>,
        plan: AskPlan,
    ): List<EvidenceGroup> {
        val pendingTerms = setOf("pending", "due", "expiry", "expires", "refund", "reply", "follow", "submit", "renewal", "appointment")
        val filtered = scored.filter { scoredScreenshot ->
            val haystack = scoredScreenshot.screenshot.ocrText.lowercase()
            pendingTerms.any { haystack.contains(it) }
        }.ifEmpty { scored }
        return filtered.groupBy { it.screenshot.category }
            .map { (category, members) ->
                groupFromMembers(
                    type = "issue",
                    title = category.replace('_', ' ').toDisplayLabel(),
                    members = members,
                    entityMap = entityMap,
                    labelMap = labelMap,
                    plan = plan,
                    sortReason = "possible pending/action signal",
                )
            }
    }

    private suspend fun bestEvidenceGroups(
        scored: List<ScoredScreenshot>,
        entityMap: Map<Long, List<EntityForScreenshot>>,
        labelMap: Map<Long, List<VisualLabelForScreenshot>>,
        plan: AskPlan,
    ): List<EvidenceGroup> {
        val members = scored.take(12)
        return listOf(
            groupFromMembers(
                type = when (plan.taskType) {
                    TASK_LOOKUP_VALUE -> "exact_match"
                    TASK_PROVE -> "proof"
                    else -> "best_match"
                },
                title = when (plan.taskType) {
                    TASK_LOOKUP_VALUE -> "Best value matches"
                    TASK_PROVE -> "Best proof screenshots"
                    else -> "Best matches"
                },
                members = members,
                entityMap = entityMap,
                labelMap = labelMap,
                plan = plan,
                sortReason = "ranked by query relevance",
            ),
        )
    }

    private suspend fun groupFromMembers(
        type: String,
        title: String,
        members: List<ScoredScreenshot>,
        entityMap: Map<Long, List<EntityForScreenshot>>,
        labelMap: Map<Long, List<VisualLabelForScreenshot>>,
        plan: AskPlan,
        sortReason: String,
    ): EvidenceGroup {
        val uniqueMembers = sortScored(members.distinctBy { it.screenshot.id }, plan)
        val representativeLimit = representativeLimit(plan)
        val representativeScreenshots = buildFlatEvidence(uniqueMembers, entityMap, labelMap, representativeLimit)
        val signals = groupSignals(uniqueMembers, entityMap, labelMap)
        val confidence = (
            0.35f +
                min(0.35f, uniqueMembers.size / 20f) +
                min(0.22f, uniqueMembers.sumOf { it.score.toDouble() }.toFloat() / 80f)
            ).coerceIn(0.2f, 0.96f)
        val cleanTitle = title.cleanTitle()
        return EvidenceGroup(
            id = stableId("group", "$type:$cleanTitle:${uniqueMembers.map { it.screenshot.id }.sorted().take(20).joinToString(":")}"),
            type = type,
            title = cleanTitle,
            summary = "${uniqueMembers.size} screenshots connected by ${signals.take(3).joinToString(", ") { it.label }.ifBlank { sortReason }}.",
            screenshotCount = uniqueMembers.size,
            confidence = confidence,
            topSignals = signals.take(8),
            representativeScreenshots = representativeScreenshots,
            allScreenshotIds = uniqueMembers.map { it.screenshot.id }.distinct(),
            sortReason = sortReason,
        )
    }

    private suspend fun buildFlatEvidence(
        scored: List<ScoredScreenshot>,
        entityMap: Map<Long, List<EntityForScreenshot>>,
        labelMap: Map<Long, List<VisualLabelForScreenshot>>,
        limit: Int,
    ): List<EvidenceScreenshot> {
        return scored.take(limit).map { scoredScreenshot ->
            val screenshot = scoredScreenshot.screenshot
            EvidenceScreenshot(
                id = screenshot.id,
                uri = screenshot.uri,
                mediaStoreId = screenshot.mediaStoreId,
                title = screenshot.displayName ?: screenshot.appHint?.toDisplayLabel() ?: "Screenshot ${screenshot.id}",
                takenAtMillis = screenshot.dateTakenMillis,
                appHint = screenshot.appHint,
                category = screenshot.category,
                snippets = snippetsFor(screenshot),
                entities = entityMap[screenshot.id].orEmpty().toMatchedEntities(limit = 12),
                visualLabels = labelMap[screenshot.id].orEmpty()
                    .sortedByDescending { it.confidence }
                    .map { it.label }
                    .distinct()
                    .take(8),
                matchReason = scoredScreenshot.reason,
                relevanceScore = scoredScreenshot.score,
            )
        }
    }

    private suspend fun buildEvidenceForScreenshotIds(
        ids: List<Long>,
        scoredById: Map<Long, ScoredScreenshot>,
        entityMap: Map<Long, List<EntityForScreenshot>>,
        labelMap: Map<Long, List<VisualLabelForScreenshot>>,
        limit: Int,
    ): List<EvidenceScreenshot> {
        val ordered = ids.distinct().mapNotNull { scoredById[it] }.take(limit)
        return buildFlatEvidence(ordered, entityMap, labelMap, limit)
    }

    private suspend fun applyCitationsToGroups(
        groups: List<EvidenceGroup>,
        answerCard: AnswerCard,
        scoredById: Map<Long, ScoredScreenshot>,
        entityMap: Map<Long, List<EntityForScreenshot>>,
        labelMap: Map<Long, List<VisualLabelForScreenshot>>,
        plan: AskPlan,
    ): List<EvidenceGroup> {
        val citedScreenshotIds = answerCard.citedScreenshotIds.toSet()
        return groups.map { group ->
            val groupCitedIds = group.allScreenshotIds.filter { it in citedScreenshotIds }
            val previewIds = (
                groupCitedIds +
                    group.representativeScreenshots.map { it.id } +
                    group.allScreenshotIds
                )
                .distinct()
            val previews = buildEvidenceForScreenshotIds(
                ids = previewIds,
                scoredById = scoredById,
                entityMap = entityMap,
                labelMap = labelMap,
                limit = representativeLimit(plan),
            ).map { screenshot -> screenshot.copy(isCited = screenshot.id in citedScreenshotIds) }
            group.copy(
                citedScreenshotIds = groupCitedIds,
                representativeScreenshots = previews,
            )
        }
    }

    private fun displayGroupsForPlan(
        plan: AskPlan,
        groups: List<EvidenceGroup>,
        answerCard: AnswerCard,
    ): List<EvidenceGroup> {
        if (!shouldDisplayEvidenceGroups(plan)) return emptyList()
        val citedGroupIds = answerCard.citedGroupIds.toSet()
        val primaryGroups = groups.filter { group ->
            group.citedScreenshotIds.isNotEmpty() ||
                (shouldCiteGroups(plan) && group.id in citedGroupIds)
        }
        val selected = primaryGroups.ifEmpty { groups.take(fallbackDisplayGroupLimit(plan)) }
        return selected
            .sortedWith(
                compareByDescending<EvidenceGroup> { it.citedScreenshotIds.size }
                    .thenByDescending { it.confidence }
                    .thenByDescending { it.screenshotCount },
            )
            .take(groupLimit(plan))
    }

    private suspend fun synthesizeAnswer(
        request: AskRequest,
        plan: AskPlan,
        evidence: List<AnswerEvidence>,
        evidenceGroups: List<EvidenceGroup>,
        matchedEntities: List<MatchedEntity>,
        facets: List<SearchFacet>,
    ): com.askmyscreenshots.skill.api.AnswerSynthesisResult? {
        val synthesizer = remoteAnswerSynthesizer
        if (!request.allowRemoteRewrite || synthesizer == null || (evidence.isEmpty() && evidenceGroups.isEmpty())) {
            return null
        }
        return runCatching {
            synthesizer.synthesize(
                AnswerSynthesisRequest(
                    originalQuery = request.query,
                    plan = plan.toRemotePlan(),
                    databaseSchema = databaseSchemaDescription(),
                    evidence = evidence,
                    evidenceGroups = evidenceGroups,
                    matchedEntities = matchedEntities,
                    facets = facets,
                ),
            )
        }.onFailure { error ->
            SkillDebugLog.w(
                event = "ask_answer_synthesis_failed",
                message = error.message.orEmpty().take(180),
            )
        }.getOrNull()
    }

    private fun composeAnswerCard(
        query: String,
        plan: AskPlan,
        groups: List<EvidenceGroup>,
        flatEvidence: List<EvidenceScreenshot>,
        synthesis: com.askmyscreenshots.skill.api.AnswerSynthesisResult?,
    ): AnswerCard {
        val local = localAnswerCard(query, plan, groups, flatEvidence)
        if (synthesis == null) return local
        val validGroupIds = groups.map { it.id }.toSet()
        val validScreenshotIds = flatEvidence.map { it.id }.toSet() + groups.flatMap { it.allScreenshotIds }.toSet()
        return AnswerCard(
            title = synthesis.title.ifBlank { local.title },
            body = synthesis.body.ifBlank { local.body },
            confidence = synthesis.confidence.coerceIn(0f, 1f),
            answerType = synthesis.answerType.ifBlank { plan.taskType },
            citedScreenshotIds = synthesis.citedScreenshotIds.filter { it in validScreenshotIds }
                .ifEmpty { local.citedScreenshotIds },
            citedGroupIds = synthesis.citedGroupIds.filter { it in validGroupIds }
                .ifEmpty { local.citedGroupIds },
        )
    }

    private fun localAnswerCard(
        query: String,
        plan: AskPlan,
        groups: List<EvidenceGroup>,
        flatEvidence: List<EvidenceScreenshot>,
    ): AnswerCard {
        if (groups.isEmpty() && flatEvidence.isEmpty()) {
            return AnswerCard(
                title = "No confident answer",
                body = "I could not find matching indexed screenshots for \"$query\".",
                confidence = 0f,
                answerType = "no_match",
                citedScreenshotIds = emptyList(),
            )
        }
        val groupText = groups.take(4).joinToString(", ") { it.title }
        val body = when (plan.taskType) {
            TASK_SUMMARIZE -> "I found ${groups.size} major themes: $groupText."
            TASK_TIMELINE -> "I arranged matching screenshots into ${groups.size} timeline buckets."
            TASK_CLEANUP -> "I found sensitive screenshot groups: $groupText."
            TASK_AGGREGATE -> "I grouped likely money-pattern screenshots into ${groups.size} evidence groups."
            TASK_COMPARE -> "I found ${groups.size} possible saved options to compare."
            TASK_ACTION_ITEMS -> "I found ${groups.size} possible pending/action groups."
            TASK_LOOKUP_VALUE -> "I found ${flatEvidence.size} likely screenshots containing the requested value."
            TASK_PROVE -> "I found ${flatEvidence.size} likely proof screenshots."
            else -> when (plan.grouping.by) {
                "entity" -> "I grouped matching screenshots by people or identifiers: $groupText."
                "app" -> "I grouped matching screenshots by app/source: $groupText."
                "theme" -> "I found ${groups.size} evidence themes: $groupText."
                else -> "I found ${flatEvidence.size} matching screenshots across your organized memory."
            }
        }
        val localCitedScreenshotIds = groups.flatMap { it.representativeScreenshots.take(2).map { screenshot -> screenshot.id } }
            .ifEmpty { flatEvidence.take(3).map { it.id } }
            .distinct()
        val localCitedGroupIds = if (shouldDisplayEvidenceGroups(plan)) {
            groups.take(4).map { it.id }
        } else {
            emptyList()
        }
        return AnswerCard(
            title = when (plan.taskType) {
                TASK_SUMMARIZE -> "Themes in your screenshots"
                TASK_LOOKUP_VALUE -> "Best value matches"
                TASK_PROVE -> "Proof screenshots"
                TASK_TIMELINE -> "Timeline from screenshots"
                TASK_CLEANUP -> "Sensitive screenshot groups"
                TASK_AGGREGATE -> "Screenshot pattern summary"
                TASK_COMPARE -> "Comparison evidence"
                TASK_ACTION_ITEMS -> "Pending action evidence"
                else -> "Answer from screenshots"
            },
            body = body,
            confidence = groups.firstOrNull()?.confidence ?: flatEvidence.firstOrNull()?.relevanceScore?.let { (it / 12f).coerceIn(0.2f, 0.88f) } ?: 0.2f,
            answerType = plan.taskType,
            citedScreenshotIds = localCitedScreenshotIds,
            citedGroupIds = localCitedGroupIds,
        )
    }

    private suspend fun buildPlannerRewriteRequest(query: String): RedactedRewriteRequest {
        return RedactedRewriteRequest(
            query = query,
            schemaDescription = databaseSchemaDescription(),
            categoryVocabulary = plannerCategoryVocabulary(),
            entityTypeVocabulary = PLANNER_ENTITY_TYPES.toList(),
            retrievalCapabilities = plannerRetrievalCapabilities(),
        )
    }

    private suspend fun plannerCategoryVocabulary(): List<String> {
        val stored = runCatching { dao.categoryCounts().map { it.category } }
            .getOrDefault(emptyList())
            .filter { it.isNotBlank() && it != ScreenshotCategory.UNKNOWN.value }
        return (PLANNER_SEED_CATEGORIES + stored)
            .distinct()
            .take(120)
    }

    private suspend fun snippetsFor(screenshot: ScreenshotEntity): List<String> {
        val lines = dao.linesForScreenshot(screenshot.id, 8).map { it.text }
        val visualDescription = dao.visualDescriptionsForScreenshots(listOf(screenshot.id))
            .firstOrNull()
            ?.description
            ?.takeIf { it.isNotBlank() }
        val raw = if (lines.isNotEmpty()) lines else screenshot.ocrText.lines().take(4)
        return (listOfNotNull(visualDescription?.let { "Visual: $it" }) + raw)
            .filter { it.isNotBlank() }
            .take(5)
    }

    private fun scoreScreenshot(
        candidate: CandidateScreenshot,
        entities: List<EntityForScreenshot>,
        categories: List<CategoryForScreenshot>,
        labels: List<VisualLabelForScreenshot>,
        plan: LocalQueryPlan,
        askPlan: AskPlan,
    ): ScoredScreenshot {
        val screenshot = candidate.screenshot
        val haystack = buildString {
            append(screenshot.displayName.orEmpty().lowercase())
            append(' ')
            append(screenshot.ocrText.lowercase())
            append(' ')
            append(screenshot.category.lowercase())
            append(' ')
            append(categories.joinToString(" ") { it.category.lowercase() })
            append(' ')
            append(screenshot.appHint.orEmpty().lowercase())
            append(' ')
            append(entities.joinToString(" ") { "${it.type} ${it.value} ${it.value.lowercase()}" })
            append(' ')
            append(labels.joinToString(" ") { it.label.lowercase() })
        }
        var score = candidate.baseScore + if (askPlan.ranking.allowBroadFallback) 0.6f else 0f
        val reasons = mutableListOf<String>()
        if (candidate.source.isNotBlank()) {
            reasons += candidate.source.replace('_', ' ').replace('+', '/')
        }
        candidate.channelScores.forEach { (channel, strength) ->
            val weighted = strength * askPlan.channelWeight(channel) * 2.6f
            score += weighted
            if (strength > 0f && reasons.size < 4 && channel != CHANNEL_BROAD) {
                reasons += "${channel.replace('_', ' ')} signal"
            }
        }
        if (candidate.semanticScore > 0f) {
            score += candidate.semanticScore * askPlan.channelWeight(CHANNEL_SEMANTIC) * if (askPlan.taskType == TASK_LOOKUP_VALUE) 2.0f else 5.5f
            reasons += "semantic match"
        }
        if (candidate.linkedScore > 0f) {
            score += candidate.linkedScore * askPlan.channelWeight(CHANNEL_LINKED_ENTITY)
            reasons += "linked entity"
        }
        if (candidate.visualLabelScore > 0f) {
            val weight = when (askPlan.taskType) {
                TASK_LOOKUP_VALUE -> 0.15f
                TASK_FIND -> 1.0f
                TASK_SUMMARIZE, TASK_COMPARE -> 0.9f
                else -> 0.65f
            } * askPlan.channelWeight(CHANNEL_VISUAL)
            score += candidate.visualLabelScore * weight
            reasons += "visual label match"
        }
        if (candidate.visualObjectScore > 0f) {
            val weight = when (askPlan.taskType) {
                TASK_LOOKUP_VALUE -> 0.12f
                TASK_FIND -> 0.95f
                TASK_SUMMARIZE, TASK_COMPARE -> 0.8f
                else -> 0.55f
            } * askPlan.channelWeight(CHANNEL_VISUAL)
            score += candidate.visualObjectScore * weight
            reasons += "detected object match"
        }
        if (candidate.subQueryLabels.isNotEmpty()) {
            score += candidate.subQueryLabels.size.coerceAtMost(3) * 0.8f
            reasons += "matched ${candidate.subQueryLabels.take(2).joinToString(", ")}"
        }
        val entityTypes = entities.map { it.type }.toSet()
        val matchedTypes = plan.entityTypes.filter { it in entityTypes }
        val requestedExactTypes = plan.entityTypes.filter { it in EXACT_ENTITY_TYPES }
        val exactEntityRequired = askPlan.taskType == TASK_LOOKUP_VALUE && requestedExactTypes.isNotEmpty()
        val hasRequestedExactEntity = requestedExactTypes.any { it in entityTypes }

        plan.searchTerms.forEach { term ->
            if (term.length >= 2 && haystack.contains(term.lowercase())) {
                val termBoost = when {
                    askPlan.taskType != TASK_LOOKUP_VALUE -> 2.2f
                    hasRequestedExactEntity -> 3.4f
                    exactEntityRequired -> 0.8f
                    else -> 3.4f
                } * askPlan.channelWeight(CHANNEL_TEXT)
                score += termBoost
                if (reasons.size < 3) reasons += "matched \"$term\""
            }
        }
        val requestedCategories = plan.categories.map { it.lowercase(Locale.US) }.toSet()
        val matchingCategory = categories
            .filter { it.category.lowercase(Locale.US) in requestedCategories }
            .maxByOrNull { categoryAssignmentScore(it, plan) }
        if (matchingCategory != null) {
            val categoryBoost = categoryAssignmentScore(matchingCategory, plan) * askPlan.channelWeight(CHANNEL_CATEGORY)
            score += categoryBoost
            reasons += "category ${matchingCategory.category} (${matchingCategory.reason.take(36)})"
        }
        if (matchedTypes.isNotEmpty()) {
            score += (if (askPlan.taskType == TASK_LOOKUP_VALUE) 10f + matchedTypes.size * 2f else 3f + matchedTypes.size) *
                askPlan.channelWeight(CHANNEL_ENTITY)
            reasons += "detected ${matchedTypes.joinToString(", ")}"
        } else if (exactEntityRequired) {
            score -= 4f
            reasons += "no exact entity"
        }
        if (askPlan.taskType == TASK_CLEANUP && entities.any { it.isSensitive }) {
            score += 4.2f
            reasons += "sensitive entity"
        }
        if (screenshot.appHint != null && plan.searchTerms.any { screenshot.appHint.lowercase().contains(it.lowercase()) }) {
            score += 2f * askPlan.channelWeight(CHANNEL_APP)
            reasons += "app ${screenshot.appHint}"
        }
        val labelHits = labels.count { label -> plan.searchTerms.any { label.label.lowercase().contains(it.lowercase()) } }
        if (labelHits > 0) {
            score += labelHits.coerceAtMost(3) * 1.2f * askPlan.channelWeight(CHANNEL_VISUAL)
            reasons += "visual labels"
        }
        if (askPlan.grouping.by == "entity" && isChatContactQuery(askPlan)) {
            val app = screenshot.appHint.orEmpty().lowercase(Locale.US)
            if (screenshot.category in setOf(ScreenshotCategory.CHAT.value, ScreenshotCategory.SOCIAL.value, ScreenshotCategory.EMAILS.value) ||
                hasAny(app, "whatsapp", "message", "messages", "sms", "telegram", "signal")
            ) {
                score += 4.5f
                reasons += "chat/contact source"
            }
            if (screenshot.category in setOf(ScreenshotCategory.IDENTITY_DOCS.value, ScreenshotCategory.PAYMENTS.value, ScreenshotCategory.FINANCE.value)) {
                score -= 2.8f
            }
        }
        if (askPlan.grouping.by == "entity" && isPaymentCounterpartyQuery(askPlan)) {
            val app = screenshot.appHint.orEmpty().lowercase(Locale.US)
            if (screenshot.category in setOf(ScreenshotCategory.PAYMENTS.value, ScreenshotCategory.FINANCE.value) ||
                hasAny(app, "gpay", "google pay", "phonepe", "paytm", "upi", "bank")
            ) {
                score += 4.2f
                reasons += "payment source"
            }
            if (screenshot.category == ScreenshotCategory.IDENTITY_DOCS.value) {
                score -= 2.4f
            }
        }
        val missingRequiredChannels = askPlan.requiredChannels().filterNot { it in candidate.channelScores.keys }
        if (missingRequiredChannels.isNotEmpty()) {
            score -= missingRequiredChannels.size * 5f
            reasons += "missing ${missingRequiredChannels.joinToString(", ")}"
        }
        score += ((screenshot.dateTakenMillis ?: 0L) / 86_400_000L % 30L).toFloat() / 300f
        return ScoredScreenshot(
            screenshot = screenshot,
            score = score,
            reason = reasons.distinct().take(4).joinToString(" · ").ifBlank { "included for broad coverage" },
        )
    }

    private fun categoryAssignmentScore(
        category: CategoryForScreenshot,
        plan: LocalQueryPlan,
    ): Float {
        val confidence = category.confidence.coerceIn(0f, 1f)
        val reason = category.reason.lowercase(Locale.US)
        val categoryLabel = category.category.replace('_', ' ')
        val reasonMatchesTerm = plan.searchTerms.any { term ->
            term.length >= 3 && (reason.contains(term.lowercase(Locale.US)) || categoryLabel.contains(term.lowercase(Locale.US)))
        }
        val reasonMatchesEntity = plan.entityTypes.any { type ->
            val label = type.replace('_', ' ')
            reason.contains(type) || reason.contains(label)
        }
        return (
            0.8f +
                confidence * 2.4f +
                if (reasonMatchesTerm) 0.45f else 0f +
                if (reasonMatchesEntity) 0.35f else 0f
            ).coerceIn(0.6f, 4.0f)
    }

    private fun sortScored(scored: List<ScoredScreenshot>, plan: AskPlan): List<ScoredScreenshot> {
        return when (plan.sort) {
            "oldest_first" -> scored.sortedBy { it.screenshot.dateTakenMillis ?: Long.MAX_VALUE }
            "chronological" -> scored.sortedBy { it.screenshot.dateTakenMillis ?: Long.MAX_VALUE }
            "newest_first" -> scored.sortedByDescending { it.screenshot.dateTakenMillis ?: 0L }
            "coverage" -> scored.sortedWith(
                compareByDescending<ScoredScreenshot> { it.score }
                    .thenByDescending { it.screenshot.ocrText.length }
                    .thenByDescending { it.screenshot.dateTakenMillis ?: 0L },
            )
            else -> scored.sortedWith(compareByDescending<ScoredScreenshot> { it.score }
                .thenByDescending { it.screenshot.dateTakenMillis ?: 0L })
        }
    }

    private fun signalsFor(
        scored: ScoredScreenshot,
        entities: List<EntityForScreenshot>,
        labels: List<VisualLabelForScreenshot>,
    ): List<GroupHit> {
        val hits = mutableListOf<GroupHit>()
        scored.screenshot.appHint?.takeIf { it.isNotBlank() }?.let {
            hits += GroupHit("app:${it.normalizeGroupKey()}", it.toDisplayLabel(), "app", scored)
        }
        if (scored.screenshot.category.isNotBlank() && scored.screenshot.category != ScreenshotCategory.UNKNOWN.value) {
            hits += GroupHit("category:${scored.screenshot.category}", scored.screenshot.category.replace('_', ' ').toDisplayLabel(), "category", scored)
        }
        entities
            .filter { it.type !in LOW_VALUE_GROUP_ENTITY_TYPES }
            .forEach { entity ->
                val type = if (entity.type in setOf("flight_hint", "train_hint")) "topic" else entity.type
                hits += GroupHit("$type:${entity.value.normalizeGroupKey()}", entityLabel(type, entity.value), type, scored)
            }
        labels
            .filter { it.confidence >= 0.68f }
            .take(8)
            .forEach { label ->
                hits += GroupHit("visual:${label.label.normalizeGroupKey()}", label.label.toDisplayLabel(), "visual", scored)
            }
        keyPhrases(scored.screenshot.ocrText).forEach { phrase ->
            hits += GroupHit("topic:${phrase.normalizeGroupKey()}", phrase.toDisplayLabel(), "theme", scored)
        }
        return hits.distinctBy { it.key }
    }

    private fun groupSignals(
        members: List<ScoredScreenshot>,
        entityMap: Map<Long, List<EntityForScreenshot>>,
        labelMap: Map<Long, List<VisualLabelForScreenshot>>,
    ): List<EvidenceSignal> {
        return members
            .flatMap { signalsFor(it, entityMap[it.screenshot.id].orEmpty(), labelMap[it.screenshot.id].orEmpty()) }
            .groupBy { it.key }
            .map { (_, hits) ->
                EvidenceSignal(
                    type = hits.first().type,
                    label = hits.first().label,
                    value = hits.first().label,
                    count = hits.map { it.screenshot.screenshot.id }.distinct().size,
                    isSensitive = hits.first().type in SENSITIVE_ENTITY_TYPES,
                )
            }
            .filterNot { signal -> signal.label.lowercase(Locale.US) in NOISY_SIGNAL_LABELS }
            .sortedWith(compareByDescending<EvidenceSignal> { it.count }.thenBy { it.label.lowercase() })
            .take(12)
    }

    private fun isChatContactQuery(plan: AskPlan): Boolean {
        val text = (plan.normalizedQuery + " " + plan.searchTerms.joinToString(" ")).lowercase(Locale.US)
        return hasAny(text, "text", "texted", "message", "chat", "whatsapp", "sms", "contact")
    }

    private fun isPaymentCounterpartyQuery(plan: AskPlan): Boolean {
        val text = (plan.normalizedQuery + " " + plan.searchTerms.joinToString(" ")).lowercase(Locale.US)
        return hasAny(text, "pay", "paid", "payment", "upi", "sent money", "transferred")
    }

    private fun contactTypeRank(type: String): Int {
        return when (type) {
            "person_name" -> 0
            "phone" -> 1
            "email" -> 2
            "upi_id" -> 3
            else -> 9
        }
    }

    private fun refineActionsFor(
        query: String,
        groups: List<EvidenceGroup>,
        facets: List<SearchFacet>,
        matchedEntities: List<MatchedEntity>,
        plan: AskPlan,
    ): List<RefineAction> {
        val modeActions = when (plan.taskType) {
            TASK_LOOKUP_VALUE -> listOf(
                RefineAction("Show all matching docs", "$query matching documents", "category"),
                RefineAction("Only identity docs", "$query identity documents", "category"),
            )
            TASK_PROVE -> listOf(
                RefineAction("Best proof screenshots", "$query proof screenshots", "topic"),
                RefineAction("Only payments", "$query payments", "category"),
                RefineAction("Only documents", "$query documents", "category"),
            )
            TASK_SUMMARIZE -> listOf(
                RefineAction("Themes by app", "$query by app", "app"),
                RefineAction("Last 30 days", "$query last 30 days", "date"),
                RefineAction("Common people/topics", "$query people and topics", "topic"),
            )
            TASK_COMPARE -> listOf(
                RefineAction("Compare options", "$query compare options", "topic"),
                RefineAction("Cheapest first", "$query cheaper", "topic"),
                RefineAction("Recent options", "$query latest", "date"),
            )
            TASK_AGGREGATE -> listOf(
                RefineAction("Payment totals", "$query payment totals", "topic"),
                RefineAction("Subscriptions", "$query subscriptions", "topic"),
                RefineAction("Biggest amounts", "$query biggest amounts", "topic"),
            )
            TASK_CLEANUP -> listOf(
                RefineAction("Identity docs", "$query identity documents", "category"),
                RefineAction("Payment info", "$query payment sensitive info", "category"),
                RefineAction("Shared screenshots", "$query safe to share", "topic"),
            )
            TASK_TIMELINE -> listOf(
                RefineAction("Oldest first", "$query oldest first", "date"),
                RefineAction("Newest first", "$query latest", "date"),
                RefineAction("Last 30 days", "$query last 30 days", "date"),
            )
            TASK_ACTION_ITEMS -> listOf(
                RefineAction("Due soon", "$query due soon", "topic"),
                RefineAction("Refunds only", "$query refunds", "topic"),
                RefineAction("Replies only", "$query replies", "topic"),
            )
            else -> when (plan.grouping.by) {
                "entity" -> listOf(
                    RefineAction("Only WhatsApp chats", "$query WhatsApp chats", "app"),
                    RefineAction("Last 30 days", "$query last 30 days", "date"),
                    RefineAction("Group by app", "$query by app", "app"),
                    RefineAction("Show more contacts", "$query contacts", "entity"),
                )
                "app" -> listOf(
                    RefineAction("Last 30 days", "$query last 30 days", "date"),
                    RefineAction("Most recent", "$query latest", "date"),
                    RefineAction("Group by app", "$query by app", "app"),
                )
                "theme" -> listOf(
                    RefineAction("Themes by app", "$query by app", "app"),
                    RefineAction("Last 30 days", "$query last 30 days", "date"),
                    RefineAction("Common people/topics", "$query people and topics", "topic"),
                )
                else -> listOf(
                    RefineAction("Last 30 days", "$query last 30 days", "date"),
                    RefineAction("Most recent", "$query latest", "date"),
                )
            }
        }
        val groupActions = groups
            .filter { it.citedScreenshotIds.isNotEmpty() }
            .take(2)
            .map {
                RefineAction(
                    label = "Ask about ${it.title.take(18)}",
                    query = "tell me more about ${it.title}",
                    type = it.type,
                )
            }
        val categoryActions = facets
            .filter { it.value !in setOf(ScreenshotCategory.UNKNOWN.value) }
            .take(1)
            .map {
                RefineAction("Only ${it.value.replace('_', ' ')}", "$query ${it.value.replace('_', ' ')}", "category")
            }
        val entityActions = if (plan.grouping.by == "entity") {
            matchedEntities
                .filter { it.type in CONTACT_ENTITY_TYPES }
                .take(1)
                .map { RefineAction("This contact", "$query ${it.value}", it.type) }
        } else {
            emptyList()
        }
        return modeActions + groupActions + categoryActions + entityActions
    }

    private fun suggestedActionsFor(
        answerCard: AnswerCard,
        evidence: List<EvidenceScreenshot>,
    ): List<SuggestedAction> {
        val citedIds = answerCard.citedScreenshotIds.toSet()
        val entities = evidence
            .sortedWith(compareByDescending<EvidenceScreenshot> { it.id in citedIds }.thenByDescending { it.relevanceScore })
            .flatMap { it.entities }
        return entities.mapNotNull { entity ->
            when (entity.type) {
                "url" -> SuggestedAction(
                    type = SuggestedActionType.OPEN_URL,
                    label = "Open link",
                    value = entity.value,
                    displayValue = entity.value,
                )

                "phone" -> SuggestedAction(
                    type = SuggestedActionType.DIAL_PHONE,
                    label = "Dial phone",
                    value = entity.value,
                    displayValue = entity.value,
                )

                "email" -> SuggestedAction(
                    type = SuggestedActionType.EMAIL,
                    label = "Email",
                    value = entity.value,
                    displayValue = entity.value,
                )

                "aadhaar",
                "pan",
                "upi_id",
                "account_number",
                "card_number",
                "booking_id",
                "order_id",
                "ifsc",
                -> SuggestedAction(
                    type = SuggestedActionType.COPY_TEXT,
                    label = "Copy ${entity.type.replace('_', ' ')}",
                    value = entity.value,
                    displayValue = entity.value,
                )

                else -> null
            }
        }
            .distinctBy { it.type to it.value.lowercase(Locale.US) }
            .take(5)
            .also {
                SkillDebugLog.i(
                    event = "ask_suggested_actions",
                    message = "count=${it.size} types=${it.joinToString(",") { action -> action.type.name }}",
                )
            }
    }

    private fun facetsFor(results: List<ScreenshotEntity>): List<SearchFacet> {
        return results
            .groupingBy { it.category }
            .eachCount()
            .map { (category, count) -> SearchFacet(name = "category", value = category, count = count) }
            .sortedByDescending { it.count }
            .take(20)
    }

    private fun List<EntityForScreenshot>.toMatchedEntities(limit: Int): List<MatchedEntity> {
        return groupBy { it.type to it.value }
            .map { (key, grouped) ->
                MatchedEntity(
                    type = key.first,
                    value = key.second,
                    screenshotCount = grouped.map { it.screenshotId }.distinct().size,
                    isSensitive = grouped.any { it.isSensitive },
                )
            }
            .sortedWith(compareByDescending<MatchedEntity> { it.screenshotCount }.thenBy { it.type })
            .take(limit)
    }

    private fun EvidenceScreenshot.toAnswerEvidence(): AnswerEvidence {
        return AnswerEvidence(
            screenshotId = id,
            title = title,
            takenAtMillis = takenAtMillis,
            appHint = appHint,
            category = category,
            snippets = snippets,
            entities = entities,
            visualLabels = visualLabels,
            matchReason = matchReason,
            relevanceScore = relevanceScore,
        )
    }

    private fun AskPlan.toRemotePlan(): RewrittenQueryPlan {
        return RewrittenQueryPlan(
            planVersion = planVersion,
            normalizedQuery = normalizedQuery,
            intent = taskType,
            searchTerms = searchTerms,
            categories = categories,
            entityTypes = entityTypes,
            appHints = appHints,
            visualLabels = visualLabels,
            visualObjectLabels = visualObjectLabels,
            semanticQueries = semanticQueries,
            dateRange = dateRange,
            askMode = mode.name,
            groupBy = groupBy,
            sort = sort,
            operation = operation,
            subQueries = subQueries,
            retrievalPlan = retrievalPlan,
            expectedAnswerShape = expectedAnswerShape,
            taskType = taskType,
            answerShape = answerShape,
            evidenceChannels = evidenceChannels,
            filters = filters,
            grouping = grouping,
            ranking = ranking,
        )
    }

    private fun AskResponse.toSearchResponse(): SearchResponse {
        val citedScreenshotIds = answerCard.citedScreenshotIds.toSet()
        val refs = flatEvidence.map { evidence ->
            ScreenshotRef(
                id = evidence.id,
                uri = evidence.uri,
                mediaStoreId = evidence.mediaStoreId,
                takenAtMillis = evidence.takenAtMillis,
                title = evidence.title,
                matchedSnippets = evidence.snippets,
                sensitiveEntityBadges = evidence.entities
                    .filter { it.isSensitive }
                    .map { PrivacyRedactor.markerFor(it.type) }
                    .distinct(),
                appHint = evidence.appHint,
                category = evidence.category,
                matchReason = evidence.matchReason,
                relevanceScore = evidence.relevanceScore,
                isCited = evidence.id in citedScreenshotIds,
            )
        }
        return SearchResponse(
            answer = answerCard.body,
            screenshotRefs = refs,
            facets = facets,
            matchedEntities = matchedEntities,
            privacyTrace = privacyTrace,
            answerCard = answerCard,
            refineActions = refineActions,
            totalCandidateCount = trace.candidateCount,
            evidenceGroups = evidenceGroups,
            askTrace = trace,
        )
    }

    private fun inferAskMode(query: String, plan: LocalQueryPlan): AskMode {
        return AskModeClassifier.infer(query, plan)
    }

    private fun String.toAskModeOrNull(): AskMode? {
        return trim().uppercase().replace('-', '_').replace(' ', '_')
            .let { value -> AskMode.entries.firstOrNull { it.name == value } }
    }

    private fun localSubQueries(
        query: String,
        plan: LocalQueryPlan,
        taskType: String,
    ): List<AskSubQuery> {
        val q = query.trim()
        val lowered = q.lowercase(Locale.US)
        val parts = when {
            taskType == TASK_COMPARE && lowered.contains(" vs ") ->
                q.split(Regex("""\s+vs\s+""", RegexOption.IGNORE_CASE))
            taskType == TASK_COMPARE && lowered.contains(" versus ") ->
                q.split(Regex("""\s+versus\s+""", RegexOption.IGNORE_CASE))
            taskType == TASK_COMPARE && lowered.contains(" between ") && lowered.contains(" and ") ->
                q.substringAfter(" between ", q).split(Regex("""\s+and\s+""", RegexOption.IGNORE_CASE))
            taskType in setOf(TASK_AGGREGATE, TASK_FIND) &&
                plan.searchTerms.count { it.length >= 3 } >= 2 ->
                plan.searchTerms.take(4)
            else -> emptyList()
        }
        return parts
            .map { it.trim(' ', ',', '.', '?') }
            .filter { it.length >= 3 }
            .distinctBy { it.lowercase(Locale.US) }
            .take(6)
            .mapIndexed { index, part ->
                AskSubQuery(
                    id = "local-${index + 1}",
                    label = part.take(42),
                    query = part,
                    searchTerms = queryPlanner.searchTermsForInternal(part),
                    categories = emptyList(),
                    entityTypes = emptyList(),
                )
            }
    }

    private fun shouldDisplayEvidenceGroups(plan: AskPlan): Boolean = plan.grouping.by != "none" && groupLimit(plan) > 0

    private fun shouldSendGroupsToAnswer(plan: AskPlan): Boolean = shouldDisplayEvidenceGroups(plan)

    private fun shouldCiteGroups(plan: AskPlan): Boolean {
        return plan.grouping.by in setOf("theme", "entity", "app", "date_bucket", "sensitive_type", "analytics_category", "comparison_option", "issue")
    }

    private fun shouldUseDeepAsk(
        plan: AskPlan,
        request: AskRequest,
        groups: List<EvidenceGroup>,
        flatEvidence: List<EvidenceScreenshot>,
    ): Boolean {
        if (!request.allowRemoteRewrite || remoteAnswerSynthesizer == null) return false
        if (groups.isEmpty() && flatEvidence.isEmpty()) return false
        return plan.taskType in DEEP_ASK_TASK_TYPES ||
            plan.grouping.by != "none" ||
            plan.subQueries.isNotEmpty() ||
            (plan.taskType == TASK_FIND && (flatEvidence.firstOrNull()?.relevanceScore ?: 0f) < 4f)
    }

    private fun fallbackDisplayGroupLimit(plan: AskPlan): Int = when (plan.grouping.by) {
        "entity" -> 4
        "theme", "date_bucket" -> 6
        else -> 3
    }

    private fun preferredEntityTypes(plan: AskPlan): Set<String> {
        if (plan.grouping.entityTypes.isNotEmpty()) return plan.grouping.entityTypes.toSet()
        if (plan.entityTypes.isNotEmpty()) return plan.entityTypes.toSet()
        return when {
            plan.grouping.by == "entity" -> setOf("person_name", "phone", "email", "upi_id")
            plan.taskType == TASK_AGGREGATE -> setOf("amount", "upi_id", "person_name")
            plan.taskType == TASK_LOOKUP_VALUE -> EXACT_ENTITY_TYPES
            else -> emptySet()
        }
    }

    private fun groupLimit(plan: AskPlan): Int = when (plan.grouping.by) {
        "none" -> 0
        "date_bucket" -> 10
        else -> 8
    }.let { limit -> if (plan.taskType in setOf(TASK_LOOKUP_VALUE, TASK_PROVE) && plan.grouping.by == "none") 0 else limit }

    private fun remoteGroupLimit(plan: AskPlan): Int = when (plan.grouping.by) {
        "none" -> 0
        "theme", "analytics_category", "comparison_option" -> 8
        else -> 6
    }

    private fun representativeLimit(plan: AskPlan): Int = when {
        plan.taskType in setOf(TASK_LOOKUP_VALUE, TASK_PROVE) -> 8
        plan.grouping.by == "date_bucket" -> 6
        else -> 5
    }

    private fun AskPlan.hasChannel(channel: String): Boolean {
        val normalized = normalizeChannel(channel)
        return evidenceChannels.any { it.channel == normalized }
    }

    private fun AskPlan.channelWeight(channel: String): Float {
        val normalized = normalizeChannel(channel)
        return evidenceChannels.firstOrNull { it.channel == normalized }?.weight ?: when (normalized) {
            CHANNEL_BROAD -> if (ranking.allowBroadFallback) 0.5f else 0.15f
            else -> 0.5f
        }
    }

    private fun AskPlan.requiredChannels(): Set<String> {
        return evidenceChannels.filter { it.required }.map { it.channel }.toSet()
    }

    private fun dateBucket(millis: Long?): String {
        if (millis == null || millis <= 0L) return "Unknown date"
        val formatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
        return Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .format(formatter)
    }

    private fun keyPhrases(text: String): List<String> {
        val tokens = WORD.findAll(text.lowercase())
            .map { it.value.trim('_', '-') }
            .filter { token -> token.length >= 3 && token !in STOP_WORDS && !token.all(Char::isDigit) }
            .take(80)
            .toList()
        return (tokens.windowed(2) + tokens.windowed(3))
            .map { it.joinToString(" ") }
            .filter { it.length in 7..54 }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(4)
    }

    private fun entityLabel(type: String, value: String): String {
        return when (type) {
            "booking_id" -> "Booking $value"
            "order_id" -> "Order $value"
            "account_number" -> "Account $value"
            "card_number" -> "Card $value"
            else -> value
        }.cleanTitle()
    }

    private fun databaseSchemaDescription(): String {
        return """
Local encrypted Room schema:
- screenshots(id, mediaStoreId, uri, displayName, relativePath, bucketName, dateTakenMillis, sizeBytes, mimeType, width, height, indexedAtMillis, indexStatus, languageTag, category, appHint, ocrText)
- screenshot_fts(rowid, title, body, entities, categories) where body contains OCR text plus local visual descriptions, entities contains detected entity type/value tokens, and categories contains assigned category tokens
	- detected_entities(id, screenshotId, type, value, normalizedValue, source, confidence, isSensitive, evidence) from OCR, filename/path, barcode payloads/metadata, ML Kit image labels, ML Kit object labels, and face presence
	- category_assignments(id, screenshotId, category, confidence, reason) with both seed and dynamically derived category strings from OCR/entities/visual signals
- ocr_lines(id, screenshotId, blockIndex, lineIndex, text, bounding box)
- visual_labels(id, screenshotId, label, confidence)
- detected_objects(id, screenshotId, objectIndex, trackingId, bounding box, areaRatio)
- detected_object_labels(id, objectId, screenshotId, label, labelIndex, confidence)
- visual_descriptions(id, screenshotId, modelName, modelVersion, description, confidence, status)
- screenshot_embeddings(id, screenshotId, modelName, modelVersion, inputHash, dimension, vectorBlob)
- entity_links(id, leftType, leftValue, leftNormalizedValue, rightType, rightValue, rightNormalizedValue, coOccurrenceCount, confidence, firstSeenAtMillis, lastSeenAtMillis, source)
- barcodes(id, screenshotId, rawValue, displayValue, format, valueType, bounding box)
- faces(id, screenshotId, faceIndex, bounding box, smile/eye/head probabilities)
	Supported planner v2 task types: find, lookup_value, prove, summarize, compare, aggregate, cleanup, timeline, action_items.
	Supported evidence channels: text, entity, app, category, visual, semantic, linked_entity, date.
	Supported group types: none, theme, entity, app, date_bucket, issue, sensitive_type, analytics_category, comparison_option.
	Search tools available to the local executor: OCR/FTS search, exact entity value search, entity type search, category search, appHint search, visual label search, detected object label search, semantic text embedding search, visual-signal semantic search, linked entity expansion, broad in-range fallback, evidence grouping.
The local database is the source of truth; remote models plan retrieval or phrase answers from provided grouped evidence. Screenshot images and embeddings are not sent to Gemini.
        """.trimIndent()
    }

    private fun plannerRetrievalCapabilities(): List<String> {
        return listOf(
            "ocr_fts: prefix full-text search over screenshot_fts title/body/entities/categories",
            "exact_entity: lookup detected_entities by type and normalizedValue",
            "entity_type: lookup screenshots containing requested detected entity types",
            "category: lookup category_assignments by seed or dynamically indexed category strings",
            "app_hint: lookup screenshots by normalized screenshots.appHint",
            "visual_label: lookup visual_labels from ML Kit image labels",
            "visual_object: lookup detected_object_labels with object area filtering",
            "semantic_text: embed query text and compare with screenshot text embeddings",
            "semantic_visual_signal: embed visual/app/category/object/caption signals and compare with visual-signal embeddings",
            "linked_entity: expand through entity_links co-occurrence graph",
            "broad_fallback: pull recent or date-filtered screenshots when targeted retrieval is sparse",
            "grouping: build evidence groups by theme, entity, app, date bucket, sensitive type, issue, analytics category, or comparison option",
        )
    }

    private data class ScoredScreenshot(
        val screenshot: ScreenshotEntity,
        val score: Float,
        val reason: String,
    )

    private data class CandidateGenerationResult(
        val candidates: List<CandidateScreenshot>,
        val semanticCandidateCount: Int,
        val linkedCandidateCount: Int,
        val subQueryCount: Int,
    )

    private data class CandidateScreenshot(
        val screenshot: ScreenshotEntity,
        val source: String,
        val baseScore: Float,
        val semanticScore: Float = 0f,
        val linkedScore: Float = 0f,
        val visualLabelScore: Float = 0f,
        val visualObjectScore: Float = 0f,
        val subQueryLabels: List<String> = emptyList(),
        val channelScores: Map<String, Float> = emptyMap(),
    )

    private data class SemanticCandidate(
        val screenshot: ScreenshotEntity,
        val similarity: Float,
    )

    private data class VisualCandidate(
        val screenshot: ScreenshotEntity,
        val score: Float,
    )

    private data class SemanticCandidateId(
        val screenshotId: Long,
        val similarity: Float,
    )

    private data class GroupHit(
        val key: String,
        val label: String,
        val type: String,
        val screenshot: ScoredScreenshot,
    )

    companion object {
        private const val TASK_FIND = "find"
        private const val TASK_LOOKUP_VALUE = "lookup_value"
        private const val TASK_PROVE = "prove"
        private const val TASK_SUMMARIZE = "summarize"
        private const val TASK_COMPARE = "compare"
        private const val TASK_AGGREGATE = "aggregate"
        private const val TASK_CLEANUP = "cleanup"
        private const val TASK_TIMELINE = "timeline"
        private const val TASK_ACTION_ITEMS = "action_items"

        private const val CHANNEL_TEXT = "text"
        private const val CHANNEL_ENTITY = "entity"
        private const val CHANNEL_APP = "app"
        private const val CHANNEL_CATEGORY = "category"
        private const val CHANNEL_VISUAL = "visual"
        private const val CHANNEL_SEMANTIC = "semantic"
        private const val CHANNEL_LINKED_ENTITY = "linked_entity"
        private const val CHANNEL_DATE = "date"
        private const val CHANNEL_BROAD = "broad"

        private val KNOWN_CHANNELS = listOf(
            CHANNEL_TEXT,
            CHANNEL_ENTITY,
            CHANNEL_APP,
            CHANNEL_CATEGORY,
            CHANNEL_VISUAL,
            CHANNEL_SEMANTIC,
            CHANNEL_LINKED_ENTITY,
            CHANNEL_DATE,
        )
        private val BROAD_TASK_TYPES = setOf(
            TASK_SUMMARIZE,
            TASK_COMPARE,
            TASK_AGGREGATE,
            TASK_CLEANUP,
            TASK_ACTION_ITEMS,
        )
        private val DEEP_ASK_TASK_TYPES = setOf(
            TASK_SUMMARIZE,
            TASK_COMPARE,
            TASK_AGGREGATE,
            TASK_TIMELINE,
            TASK_ACTION_ITEMS,
        )
        private val LOW_VALUE_VISUAL_LABELS = setOf("screenshot", "image", "photograph")
        private val VISUAL_LABEL_SYNONYMS = mapOf(
            "receipt" to listOf("receipt", "money", "document"),
            "bill" to listOf("receipt", "document", "money"),
            "invoice" to listOf("receipt", "document", "money"),
            "payment slip" to listOf("receipt", "money"),
            "money" to listOf("money", "cash", "coin", "receipt"),
            "cash" to listOf("money", "cash", "coin"),
            "food" to listOf("food", "meal", "restaurant", "menu"),
            "restaurant" to listOf("food", "restaurant", "meal", "menu"),
            "menu" to listOf("menu", "food", "restaurant"),
            "sofa" to listOf("couch", "furniture"),
            "couch" to listOf("couch", "furniture"),
            "chair" to listOf("chair", "furniture"),
            "furniture" to listOf("furniture", "couch", "chair"),
            "plant" to listOf("plant", "flower", "tree"),
            "plants" to listOf("plant", "flower", "tree"),
            "flower" to listOf("flower", "plant"),
            "phone" to listOf("mobile phone", "telephone", "electronic device"),
            "mobile" to listOf("mobile phone", "telephone", "electronic device"),
            "laptop" to listOf("computer", "electronic device"),
            "computer" to listOf("computer", "electronic device"),
            "website" to listOf("web page", "website", "computer"),
            "web page" to listOf("web page", "website", "computer"),
            "browser" to listOf("web page", "website", "computer"),
            "document" to listOf("document", "paper", "text"),
            "pdf" to listOf("document", "paper", "text"),
            "certificate" to listOf("document", "paper", "text"),
            "car" to listOf("car", "vehicle"),
            "vehicle" to listOf("vehicle", "car"),
            "dress" to listOf("clothing", "dress", "fashion accessory"),
            "shirt" to listOf("clothing", "fashion accessory"),
            "shoe" to listOf("footwear", "shoe", "fashion accessory"),
            "hotel" to listOf("room", "building", "furniture"),
            "room" to listOf("room", "furniture", "building"),
            "map" to listOf("map", "road", "landscape"),
            "place" to listOf("place", "landmark", "building"),
            "person" to listOf("person", "face", "portrait"),
            "people" to listOf("person", "face", "portrait"),
            "human" to listOf("person", "face", "portrait"),
            "humans" to listOf("person", "face", "portrait"),
            "face" to listOf("person", "face", "portrait"),
            "selfie" to listOf("person", "face", "portrait"),
            "video call" to listOf("person", "face", "portrait", "mobile phone"),
        )
        private val VISUAL_OBJECT_SYNONYMS = mapOf(
            "food" to listOf("food"),
            "restaurant" to listOf("food", "place"),
            "meal" to listOf("food"),
            "plant" to listOf("plant"),
            "plants" to listOf("plant"),
            "flower" to listOf("plant"),
            "sofa" to listOf("home good"),
            "couch" to listOf("home good"),
            "chair" to listOf("home good"),
            "furniture" to listOf("home good"),
            "home" to listOf("home good"),
            "interior" to listOf("home good"),
            "dress" to listOf("fashion good"),
            "shirt" to listOf("fashion good"),
            "shoe" to listOf("fashion good"),
            "clothing" to listOf("fashion good"),
            "fashion" to listOf("fashion good"),
            "place" to listOf("place"),
            "hotel" to listOf("place", "home good"),
            "room" to listOf("place", "home good"),
            "travel" to listOf("place"),
            "location" to listOf("place"),
        )
        private val CONTACT_ENTITY_TYPES = setOf("person_name", "phone", "email", "upi_id")
        private val LINK_EXPANSION_ENTITY_TYPES = setOf(
            "person_name", "phone", "email", "upi_id", "url", "domain", "booking_id", "order_id",
            "invoice_id", "tracking_id", "transaction_id", "counterparty", "aadhaar", "pan", "passport", "app",
        )
        private val EXACT_ENTITY_TYPES = setOf(
            "aadhaar", "pan", "phone", "email", "upi_id", "account_number", "card_number",
            "booking_id", "order_id", "invoice_id", "tracking_id", "transaction_id", "otp",
            "gstin", "passport", "ifsc", "url", "domain", "flight_hint", "train_hint",
        )
        private val PLANNER_SEED_CATEGORIES = listOf(
            ScreenshotCategory.CHAT.value,
            ScreenshotCategory.SOCIAL.value,
            ScreenshotCategory.PAYMENTS.value,
            ScreenshotCategory.FINANCE.value,
            ScreenshotCategory.IDENTITY_DOCS.value,
            ScreenshotCategory.BOOKING_TRAVEL.value,
            ScreenshotCategory.SHOPPING.value,
            ScreenshotCategory.FOOD.value,
            ScreenshotCategory.MAPS.value,
            ScreenshotCategory.AI_NEWS.value,
            ScreenshotCategory.CODE_ERRORS.value,
            ScreenshotCategory.EMAILS.value,
            ScreenshotCategory.MEDIA.value,
            ScreenshotCategory.DOCUMENTS.value,
            "health",
            "news",
            "receipt",
            "delivery",
            "subscription",
            "jobs",
            "education",
            "event",
            "real_estate",
            "people",
            "qr_code",
            "qr_link",
            "wifi",
            "fashion",
            "home",
            "places",
            "plants",
            "transport",
            "web",
            "security",
            "tax",
            "startup",
            "funding",
            "investing",
            "crypto",
        )
        private val PLANNER_ENTITY_TYPES = setOf(
            "aadhaar",
            "pan",
            "passport",
            "phone",
            "email",
            "upi_id",
            "amount",
            "account_number",
            "card_number",
            "booking_id",
            "order_id",
            "invoice_id",
            "tracking_id",
            "transaction_id",
            "counterparty",
            "otp",
            "gstin",
            "pincode",
            "person_name",
            "ifsc",
            "url",
            "domain",
            "date",
            "time",
            "flight_hint",
            "train_hint",
            "topic",
            "app",
            "barcode_signal",
            "barcode_format",
            "barcode_value_type",
            "visual_label",
            "visual_object",
            "face_count",
            "people_presence",
            "social_handle",
            "hashtag",
            "source_bucket",
            "path_hint",
        )
        private const val MAX_SQL_BIND_ARGS = 500
        private val SENSITIVE_ENTITY_TYPES = setOf(
            "aadhaar", "pan", "phone", "email", "upi_id", "account_number", "card_number",
            "booking_id", "order_id", "invoice_id", "tracking_id", "transaction_id", "otp",
            "gstin", "passport", "ifsc", "amount", "person_name", "counterparty", "face_count", "people_presence",
        )
        private val LOW_VALUE_GROUP_ENTITY_TYPES = setOf("amount", "date")
        private val NOISY_SIGNAL_LABELS = setOf(
            "amount",
            "image",
            "photo",
            "poster",
            "screenshot",
            "screenshots",
        )
        private val WORD = Regex("""[a-zA-Z][a-zA-Z0-9_+\-.]{2,}""")
        private val STOP_WORDS = setOf(
            "the", "and", "for", "with", "this", "that", "from", "your", "you", "are",
            "was", "were", "have", "has", "not", "but", "all", "can", "will", "just",
            "more", "view", "open", "done", "back", "next", "share", "send", "copy",
            "edit", "new", "old", "now", "get", "got", "screenshot", "screenshots",
            "image", "photo", "null", "unknown", "android",
        )
    }
}

private fun stableId(prefix: String, value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it) }
    return "$prefix-$digest"
}

private fun hasAny(text: String, vararg needles: String): Boolean {
    return needles.any { text.contains(it) }
}

private fun List<String>?.normalizedPlannerValues(limit: Int): List<String> {
    return this.orEmpty()
        .map { it.trim().lowercase(Locale.US).replace(Regex("""\s+"""), " ") }
        .filter { it.isNotBlank() }
        .distinct()
        .take(limit)
}

internal object AskModeClassifier {
    private val exactEntityTypes = setOf(
        "aadhaar", "pan", "phone", "email", "upi_id", "account_number", "card_number",
        "booking_id", "order_id", "ifsc", "url", "flight_hint", "train_hint",
    )

    fun infer(query: String, plan: LocalQueryPlan): AskMode {
        val q = query.lowercase()
        return when {
            hasAny(q, "common theme", "themes", "patterns", "summary", "summarize", "digital memory", "what mattered", "what was i excited", "what was stressing") ->
                AskMode.THEME_SUMMARY
            hasAny(q, "how much", "total", "spend", "spent", "where did salary", "subscriptions total", "biggest payments", "money patterns") ->
                AskMode.ANALYTICS
            hasAny(q, "best", "compare", " vs ", "cheaper", "better", "shortlist", "options", "decide") ->
                AskMode.COMPARISON
            hasAny(q, "pending", "follow up", "need to reply", "owe", "owes", "expires", "due soon", "what should i", "remind") ->
                AskMode.PENDING_ACTION
            hasAny(q, "who", "whom", "person", "paid ", "where did i pay", "who got", "texted", "message from", "which email") ->
                AskMode.ENTITY_GROUP
            hasAny(q, "latest", "oldest", "timeline", "history", "before ", "after ", "around ", "today", "yesterday", "last week", "last month", "this month") ->
                AskMode.TIMELINE
            hasAny(q, "sensitive", "private", "hide", "delete", "cleanup", "safe to share", "blur", "mask", "aadhaar", "pan", "passport", "card number", "otp") &&
                hasAny(q, "screenshot", "screenshots", "delete", "cleanup", "hide", "blur", "mask", "safe") ->
                AskMode.PRIVACY_CLEANUP
            hasAny(q, "proof", "receipt", "approval", "statement", "bill", "invoice", "ticket", "pass", "qr") ->
                AskMode.PROOF
            plan.entityTypes.any { it in exactEntityTypes } || hasAny(q, "extract", "number", "code", "link", "address", "utr", "pnr", "order id", "booking id", "coupon") ->
                AskMode.EXACT_VALUE
            hasAny(q, "whatsapp", "gpay", "phonepe", "paytm", "linkedin", "amazon", "flipkart", "swiggy", "zomato", "slack", "teams", "jira", "github") ->
                AskMode.APP_SOURCE
            hasAny(q, "that ", "blue ", "green ", "black ", "cafe", "plants", "sofa", "pool", "balcony", "rooftop", "sushi", "sneakers", "dress", "perfume") ->
                AskMode.FUZZY_VISUAL
            else -> AskMode.FIND
        }
    }
}

private fun String.normalizeGroupKey(): String {
    return lowercase(Locale.US)
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun String.toDisplayLabel(): String {
    return replace('_', ' ')
        .split(Regex("""\s+"""))
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() } }
        .ifBlank { this }
}

private fun String.cleanTitle(): String {
    return replace(Regex("""\s+"""), " ")
        .trim()
        .ifBlank { "Screenshot group" }
        .take(72)
}
