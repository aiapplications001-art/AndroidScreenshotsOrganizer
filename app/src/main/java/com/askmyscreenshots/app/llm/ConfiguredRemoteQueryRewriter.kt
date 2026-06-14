package com.askmyscreenshots.app.llm

import com.askmyscreenshots.app.BuildConfig
import com.askmyscreenshots.app.debug.AppDebugLog
import com.askmyscreenshots.skill.api.AnswerSynthesisRequest
import com.askmyscreenshots.skill.api.AnswerSynthesisResult
import com.askmyscreenshots.skill.api.AskSubQuery
import com.askmyscreenshots.skill.api.ClusterLabelRequest
import com.askmyscreenshots.skill.api.ClusterLabelResult
import com.askmyscreenshots.skill.api.PlannedEvidenceChannel
import com.askmyscreenshots.skill.api.PlannerFilters
import com.askmyscreenshots.skill.api.PlannerGrouping
import com.askmyscreenshots.skill.api.PlannerRanking
import com.askmyscreenshots.skill.api.RedactedRewriteRequest
import com.askmyscreenshots.skill.api.RefineAction
import com.askmyscreenshots.skill.api.RemoteAnswerSynthesizer
import com.askmyscreenshots.skill.api.RemoteClusterLabeler
import com.askmyscreenshots.skill.api.RemoteQueryRewriter
import com.askmyscreenshots.skill.api.RewrittenQueryPlan
import com.askmyscreenshots.skill.api.SkillDateRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.Instant
import java.time.ZoneId

private const val GEMINI_HOST = "https://generativelanguage.googleapis.com"
private const val GEMINI_TIMEOUT_MS = 12_000
private const val MAX_LOG_ERROR_CHARS = 180
private val GEMINI_MODELS = listOf(
    "gemini-3.5-flash",
    "gemini-2.5-flash",
    "gemini-2.5-flash-lite",
    "gemini-3.1-flash-lite",
    "gemini-flash-latest",
    "gemini-2.0-flash",
    "gemini-2.0-flash-lite",
    "gemini-pro-latest",
)
private val GEMINI_API_VERSIONS = listOf("v1beta", "v1")

class ConfiguredRemoteQueryRewriter private constructor(
    private val endpoint: String,
    private val apiKey: String,
) : RemoteQueryRewriter {
    override suspend fun rewrite(request: RedactedRewriteRequest): RewrittenQueryPlan? {
        return withContext(Dispatchers.IO) {
            AppDebugLog.i(
                event = "remote_rewrite_start",
                message = "provider=generic queryLength=${request.query.length} " +
                    "capabilities=${request.retrievalCapabilities.size}",
            )
            runCatching {
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }

                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(request.toJson().toString())
                }

                val responseBody = if (connection.responseCode in 200..299) {
                    AppDebugLog.i(
                        event = "remote_rewrite_http",
                        message = "provider=generic status=${connection.responseCode}",
                    )
                    connection.inputStream.bufferedReader().use(BufferedReader::readText)
                } else {
                    AppDebugLog.w(
                        event = "remote_rewrite_http",
                        message = "provider=generic status=${connection.responseCode}",
                    )
                    connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                    return@withContext null
                }
                JSONObject(responseBody).toQueryPlan().also { plan ->
                    AppDebugLog.i(
                        event = "remote_rewrite_done",
                        message = "provider=generic categories=${plan.categories.size} " +
                            "entityTypes=${plan.entityTypes.size} hasDateRange=${plan.dateRange != null}",
                    )
                }
            }.onFailure { error ->
                AppDebugLog.e(
                    event = "remote_rewrite_failed",
                    message = "provider=generic",
                    throwable = error,
                )
            }.getOrNull()
        }
    }

    private fun RedactedRewriteRequest.toJson(): JSONObject {
        return JSONObject()
            .put("query", query)
            .put("schemaDescription", schemaDescription)
            .put("categoryVocabulary", JSONArray(categoryVocabulary))
            .put("entityTypeVocabulary", JSONArray(entityTypeVocabulary))
            .put("retrievalCapabilities", JSONArray(retrievalCapabilities))
            .put(
                "expectedResponse",
                JSONObject()
                    .put("planVersion", 2)
                    .put("taskType", "find|lookup_value|prove|summarize|compare|aggregate|cleanup|timeline|action_items")
                    .put("normalizedQuery", "string")
                    .put("searchTerms", "string[]")
                    .put(
                        "evidenceChannels",
                        JSONArray().put(
                            JSONObject()
                                .put("channel", "text|entity|app|category|visual|semantic|linked_entity|date")
                                .put("weight", "number 0.1..2.5")
                                .put("required", "boolean")
                                .put("terms", JSONArray())
                                .put("entityTypes", JSONArray())
                                .put("categories", JSONArray())
                                .put("appHints", JSONArray())
                                .put("visualLabels", JSONArray())
                                .put("visualObjectLabels", JSONArray())
                                .put("semanticQueries", JSONArray()),
                        ),
                    )
                    .put(
                        "filters",
                        JSONObject()
                            .put("dateRange", JSONObject.NULL)
                            .put("categories", JSONArray())
                            .put("entityTypes", JSONArray())
                            .put("appHints", JSONArray())
                            .put("visualLabels", JSONArray())
                            .put("visualObjectLabels", JSONArray()),
                    )
                    .put(
                        "grouping",
                        JSONObject()
                            .put("by", "none|entity|app|date_bucket|theme|sensitive_type|comparison_option|issue|analytics_category")
                            .put("entityTypes", JSONArray()),
                    )
                    .put(
                        "ranking",
                        JSONObject()
                            .put("sort", "relevance|coverage|chronological|newest_first|oldest_first")
                            .put("allowBroadFallback", "boolean"),
                    )
                    .put("answerShape", "direct_answer|evidence_list|grouped_summary|timeline|comparison|aggregate_summary")
                    .put(
                        "subQueries",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "subquery-id")
                                .put("label", "short label")
                                .put("query", "local search text")
                                .put("searchTerms", JSONArray())
                                .put("categories", JSONArray())
                                .put("entityTypes", JSONArray()),
                        ),
                    )
                    .put("retrievalPlan", "string[]"),
            )
    }

    companion object {
        private const val TIMEOUT_MS = 12_000

        fun fromBuildConfig(): RemoteQueryRewriter? {
            return when {
                BuildConfig.GEMINI_REWRITE_ENABLED -> {
                    AppDebugLog.i("remote_rewrite_config", "provider=gemini enabled=true")
                    GeminiQueryRewriter(
                        apiKey = BuildConfig.GEMINI_API_KEY,
                    )
                }

                BuildConfig.LLM_REWRITE_ENABLED -> {
                    AppDebugLog.i("remote_rewrite_config", "provider=generic enabled=true")
                    ConfiguredRemoteQueryRewriter(
                        endpoint = BuildConfig.LLM_REWRITE_ENDPOINT,
                        apiKey = BuildConfig.LLM_REWRITE_API_KEY,
                    )
                }

                else -> {
                    AppDebugLog.i("remote_rewrite_config", "enabled=false")
                    null
                }
            }
        }

        fun clusterLabelerFromBuildConfig(): RemoteClusterLabeler? {
            return if (BuildConfig.GEMINI_REWRITE_ENABLED) {
                AppDebugLog.i("cluster_label_config", "provider=gemini enabled=true")
                GeminiClusterLabeler(apiKey = BuildConfig.GEMINI_API_KEY)
            } else {
                AppDebugLog.i("cluster_label_config", "enabled=false")
                null
            }
        }

        fun answerSynthesizerFromBuildConfig(): RemoteAnswerSynthesizer? {
            return if (BuildConfig.GEMINI_REWRITE_ENABLED) {
                AppDebugLog.i("answer_synthesis_config", "provider=gemini enabled=true")
                GeminiAnswerSynthesizer(apiKey = BuildConfig.GEMINI_API_KEY)
            } else {
                AppDebugLog.i("answer_synthesis_config", "enabled=false")
                null
            }
        }
    }
}

private class GeminiQueryRewriter(
    private val apiKey: String,
) : RemoteQueryRewriter {
    override suspend fun rewrite(request: RedactedRewriteRequest): RewrittenQueryPlan? {
        return withContext(Dispatchers.IO) {
            val prompt = request.toGeminiPrompt()
            var lastError: String? = null
            AppDebugLog.i(
                event = "remote_rewrite_start",
                message = "provider=gemini queryLength=${request.query.length} " +
                    "capabilities=${request.retrievalCapabilities.size}",
            )

            for (apiVersion in GEMINI_API_VERSIONS) {
                for (model in GEMINI_MODELS) {
                    AppDebugLog.i(
                        event = "gemini_model_attempt",
                        message = "apiVersion=$apiVersion model=$model",
                    )
                    val result = runCatching {
                        tryGeminiModel(
                            apiVersion = apiVersion,
                            model = model,
                            apiKey = apiKey,
                            prompt = prompt,
                        )
                    }
                    result.getOrNull()?.let { plan ->
                        AppDebugLog.i(
                            event = "gemini_model_success",
                            message = "apiVersion=$apiVersion model=$model " +
                                "version=${plan.planVersion} task=${plan.taskType} channels=${plan.evidenceChannels.size} " +
                                "askMode=${plan.askMode} groupBy=${plan.groupBy} " +
                                "categories=${plan.categories.size} entityTypes=${plan.entityTypes.size} " +
                                "appHints=${plan.appHints.size} visualLabels=${plan.visualLabels.size} " +
                                "visualObjects=${plan.visualObjectLabels.size} semanticQueries=${plan.semanticQueries.size} " +
                                "hasDateRange=${plan.dateRange != null}",
                        )
                        return@withContext plan
                    }
                    lastError = result.exceptionOrNull()?.message
                    AppDebugLog.w(
                        event = "gemini_model_failed",
                        message = "apiVersion=$apiVersion model=$model " +
                            "error=${result.exceptionOrNull()?.message.orEmpty().take(MAX_LOG_ERROR_CHARS)}",
                    )
                }
            }

            AppDebugLog.w(
                event = "remote_rewrite_failed",
                message = "provider=gemini lastError=${lastError.orEmpty().take(MAX_LOG_ERROR_CHARS)}",
            )
            null
        }
    }

    private fun RedactedRewriteRequest.toGeminiPrompt(): String {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        return """
You are the query planner for an Android app called Ask My Screenshots.

Task:
Convert the user's raw screenshot-memory question into a local retrieval plan. The phone will execute the plan against its local encrypted database.

Hard rules:
- Return ONLY raw JSON. No markdown. No explanations.
- Do not answer the user's question.
- Do not ask for screenshots.
- Do not invent entity types or retrieval tools outside the capability contract.
- For categories, prefer the indexed category vocabulary. Only add a query-specific category when it is a direct normalized form of the user's wording and is likely to exist as a dynamic category.
- For appHints, visualLabels, and visualObjectLabels, use short lower-case values that plausibly match indexed app/source names or ML Kit labels.
- Use the raw user query exactly as intent input; no local parser output or corpus frequency hints are provided.
- Prefer terms that map to OCR/FTS, exact entities, app hints, visual labels, detected object labels, semantic text, visual-signal embeddings, linked entities, and categories.
- If a date range is not implied by the raw user query, set dateRange to null.
- dateRange values must be epoch milliseconds in the user's local timezone.
- Choose task type, evidence channels, filters, grouping, ranking, subqueries, and retrieval hints that best fit the user's question.

Task type guide:
- find: find matching screenshots. Default for simple show/search/list questions.
- lookup_value: find or extract a specific value such as Aadhaar, PAN, UTR, PNR, order ID, booking ID, phone, address, coupon, link, or code.
- prove: find proof/evidence screenshots such as payment proof, receipt, approval, statement, invoice, ticket, pass, QR, or dispute evidence.
- summarize: summarize themes, patterns, memories, or what mattered across many screenshots.
- compare: compare options, prices, hotels, products, choices, or A vs B.
- aggregate: total, spend, amount, subscription, salary flow, biggest payment, or money pattern questions.
- cleanup: sensitive/private/delete/blur/mask/card/Aadhaar/PAN/passport/OTP cleanup.
- timeline: latest, oldest, before, after, around, history, timeline, or date sequencing.
- action_items: pending refunds, bills, replies, renewals, expiries, follow-ups, due soon.

Evidence channel guide:
- text: OCR/FTS terms in screenshot text, titles, entities, and categories.
- entity: extracted structured values such as amount, transaction_id, phone, email, person_name, aadhaar, order_id.
- app: normalized app/source names such as whatsapp, gpay, linkedin, amazon, teams.
- category: indexed category strings such as payments, shopping, identity_docs, receipt, travel.
- visual: ML Kit image labels, detected object labels, or visual descriptions.
- semantic: meaning-based text or visual-signal embedding search.
- linked_entity: expand from locally detected values through co-occurrence links.
- date: date ranges, recency, chronological order.

Planner rules:
- Use multiple evidenceChannels when the query has multiple clues.
- Use weight 1.0 for the main signal, 0.4-0.8 for supporting signals, and >1.0 only for very strong required evidence.
- Set required true only when missing that channel should strongly demote a screenshot.
- Put broad restrictions in filters; put per-channel terms inside the matching evidence channel.
- Use grouping.by = none for direct evidence lists; use entity/app/date_bucket/theme/sensitive_type/comparison_option/issue/analytics_category when grouping helps the answer.
- Set ranking.allowBroadFallback true for summarize, aggregate, compare, cleanup, action_items, and broad visual memory questions.

Current time:
- epochMillis: ${now.toEpochMilli()}
- timezone: ${zone.id}

Local index schema and behavior:
${schemaDescription}

Retrieval capabilities:
${retrievalCapabilities.joinToString("\n") { "- $it" }}

Indexed category vocabulary:
${categoryVocabulary.joinToString(", ")}

Allowed entityTypes:
${entityTypeVocabulary.joinToString(", ")}

Raw user query:
$query

Return this exact JSON shape:
{
  "planVersion": 2,
  "taskType": "find|lookup_value|prove|summarize|compare|aggregate|cleanup|timeline|action_items",
  "normalizedQuery": "short normalized search text",
  "searchTerms": ["global OCR/FTS terms"],
  "evidenceChannels": [
    {
      "channel": "text|entity|app|category|visual|semantic|linked_entity|date",
      "weight": 1.0,
      "required": false,
      "terms": ["channel-specific text terms"],
      "entityTypes": ["entity_type_from_allowed_list"],
      "categories": ["indexed_or_query_normalized_category"],
      "appHints": ["normalized app/source names"],
      "visualLabels": ["ML Kit image labels, lower-case"],
      "visualObjectLabels": ["ML Kit object labels, lower-case"],
      "semanticQueries": ["phrases that should be embedded"]
    }
  ],
  "filters": {
    "dateRange": {
      "startMillis": 0,
      "endMillisExclusive": 0
    },
    "categories": ["indexed_or_query_normalized_category"],
    "entityTypes": ["entity_type_from_allowed_list"],
    "appHints": ["normalized app/source names"],
    "visualLabels": ["ML Kit image labels, lower-case"],
    "visualObjectLabels": ["ML Kit object labels, lower-case"]
  },
  "grouping": {
    "by": "none|entity|app|date_bucket|theme|sensitive_type|analytics_category|comparison_option|issue",
    "entityTypes": ["entity_type_from_allowed_list"]
  },
  "ranking": {
    "sort": "relevance|coverage|chronological|newest_first|oldest_first",
    "allowBroadFallback": false
  },
  "answerShape": "direct_answer|evidence_list|grouped_summary|timeline|comparison|aggregate_summary",
  "subQueries": [
    {
      "id": "short-id",
      "label": "short label for one side/person/app/topic",
      "query": "local search query for this subtask",
      "searchTerms": ["terms"],
      "categories": ["indexed_or_query_normalized_category"],
      "entityTypes": ["entity_type_from_allowed_list"]
    }
  ],
  "retrievalPlan": [
    "short ordered retrieval step, e.g. entity:transaction_id required"
  ]
}

Use [] for empty arrays. Use null for filters.dateRange when no range is implied.
        """.trimIndent()
    }

    private fun tryGeminiModel(
        apiVersion: String,
        model: String,
        apiKey: String,
        prompt: String,
    ): RewrittenQueryPlan {
        val encodedKey = URLEncoder.encode(apiKey, Charsets.UTF_8.name())
        val url = URL("$GEMINI_HOST/$apiVersion/models/$model:generateContent?key=$encodedKey")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = GEMINI_TIMEOUT_MS
            readTimeout = GEMINI_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(geminiRequestBody(prompt).toString())
        }

        val responseBody = if (connection.responseCode in 200..299) {
            connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } else {
            val errorBody = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
            throw IllegalStateException(errorBody ?: "Gemini API error ${connection.responseCode}")
        }

        return JSONObject(responseBody)
            .extractGeminiText()
            .extractJsonObject()
            .let { JSONObject(it).toQueryPlan() }
    }

    private fun geminiRequestBody(prompt: String): JSONObject {
        return JSONObject()
            .put(
                "contents",
                JSONArray()
                    .put(
                        JSONObject()
                            .put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", prompt)),
                            ),
                    ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.1)
                    .put("candidateCount", 1),
            )
    }

    private fun JSONObject.extractGeminiText(): String {
        val candidates = optJSONArray("candidates")
            ?: throw IllegalStateException(optJSONObject("promptFeedback")?.optString("blockReason") ?: "No candidates returned")
        val content = candidates.optJSONObject(0)?.optJSONObject("content")
            ?: throw IllegalStateException("No content returned")
        val parts = content.optJSONArray("parts")
            ?: throw IllegalStateException("No content parts returned")
        return (0 until parts.length())
            .joinToString("\n") { index ->
                parts.optJSONObject(index)?.optString("text").orEmpty()
            }
            .trim()
            .takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No text returned")
    }
}

private class GeminiAnswerSynthesizer(
    private val apiKey: String,
) : RemoteAnswerSynthesizer {
    override suspend fun synthesize(request: AnswerSynthesisRequest): AnswerSynthesisResult? {
        if (request.evidence.isEmpty() && request.evidenceGroups.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            val prompt = request.toGeminiPrompt()
            var lastError: String? = null
            AppDebugLog.i(
                event = "answer_synthesis_start",
                message = "provider=gemini queryLength=${request.originalQuery.length} " +
                    "evidence=${request.evidence.size} groups=${request.evidenceGroups.size} " +
                    "entities=${request.matchedEntities.size}",
            )

            for (apiVersion in GEMINI_API_VERSIONS) {
                for (model in GEMINI_MODELS) {
                    AppDebugLog.i("answer_synthesis_attempt", "apiVersion=$apiVersion model=$model")
                    val result = runCatching {
                        tryGeminiAnswer(
                            apiVersion = apiVersion,
                            model = model,
                            apiKey = apiKey,
                            prompt = prompt,
                        )
                    }
                    result.getOrNull()?.let { answer ->
                        AppDebugLog.i(
                            event = "answer_synthesis_success",
                            message = "apiVersion=$apiVersion model=$model " +
                                "answerType=${answer.answerType} citedGroups=${answer.citedGroupIds.size} " +
                                "cited=${answer.citedScreenshotIds.size} " +
                                "refines=${answer.refineActions.size}",
                        )
                        return@withContext answer
                    }
                    lastError = result.exceptionOrNull()?.message
                    AppDebugLog.w(
                        event = "answer_synthesis_failed",
                        message = "apiVersion=$apiVersion model=$model " +
                            "error=${lastError.orEmpty().take(MAX_LOG_ERROR_CHARS)}",
                    )
                }
            }
            AppDebugLog.w(
                event = "answer_synthesis_failed",
                message = "provider=gemini lastError=${lastError.orEmpty().take(MAX_LOG_ERROR_CHARS)}",
            )
            null
        }
    }

    private fun AnswerSynthesisRequest.toGeminiPrompt(): String {
        val groupJson = JSONArray()
        evidenceGroups.take(10).forEach { group ->
            groupJson.put(
                JSONObject()
                    .put("id", group.id)
                    .put("type", group.type)
                    .put("title", group.title.take(120))
                    .put("summary", group.summary.take(400))
                    .put("screenshotCount", group.screenshotCount)
                    .put("confidence", group.confidence)
                    .put("sortReason", group.sortReason.take(180))
                    .put(
                        "topSignals",
                        JSONArray(
                            group.topSignals.take(12).map { signal ->
                                JSONObject()
                                    .put("type", signal.type)
                                    .put("label", signal.label.take(100))
                                    .put("value", signal.value.take(160))
                                    .put("count", signal.count)
                                    .put("isSensitive", signal.isSensitive)
                            },
                        ),
                    )
                    .put("allScreenshotIds", JSONArray(group.allScreenshotIds.take(80)))
                    .put(
                        "representativeScreenshots",
                        JSONArray(
                            group.representativeScreenshots.take(8).map { item ->
                                JSONObject()
                                    .put("screenshotId", item.id)
                                    .put("title", item.title.take(120))
                                    .put("takenAtMillis", item.takenAtMillis ?: JSONObject.NULL)
                                    .put("appHint", item.appHint ?: JSONObject.NULL)
                                    .put("category", item.category)
                                    .put("matchReason", item.matchReason.take(180))
                                    .put("relevanceScore", item.relevanceScore)
                                    .put("snippets", JSONArray(item.snippets.map { it.take(500) }))
                                    .put(
                                        "entities",
                                        JSONArray(
                                            item.entities.take(16).map { entity ->
                                                JSONObject()
                                                    .put("type", entity.type)
                                                    .put("value", entity.value.take(160))
                                                    .put("screenshotCount", entity.screenshotCount)
                                                    .put("isSensitive", entity.isSensitive)
                                            },
                                        ),
                                    )
                                    .put("visualLabels", JSONArray(item.visualLabels.take(10)))
                            },
                        ),
                    ),
            )
        }
        val evidenceJson = JSONArray()
        evidence.take(16).forEach { item ->
            evidenceJson.put(
                JSONObject()
                    .put("screenshotId", item.screenshotId)
                    .put("title", item.title.take(120))
                    .put("takenAtMillis", item.takenAtMillis ?: JSONObject.NULL)
                    .put("appHint", item.appHint ?: JSONObject.NULL)
                    .put("category", item.category)
                    .put("matchReason", item.matchReason)
                    .put("relevanceScore", item.relevanceScore)
                    .put("snippets", JSONArray(item.snippets.map { it.take(500) }))
                    .put(
                        "entities",
                        JSONArray(
                            item.entities.take(20).map { entity ->
                                JSONObject()
                                    .put("type", entity.type)
                                    .put("value", entity.value.take(160))
                                    .put("screenshotCount", entity.screenshotCount)
                                    .put("isSensitive", entity.isSensitive)
                            },
                        ),
                    )
                    .put("visualLabels", JSONArray(item.visualLabels.take(10))),
            )
        }
        val entityJson = JSONArray(
            matchedEntities.take(40).map { entity ->
                JSONObject()
                    .put("type", entity.type)
                    .put("value", entity.value.take(160))
                    .put("screenshotCount", entity.screenshotCount)
                    .put("isSensitive", entity.isSensitive)
            },
        )
        val facetJson = JSONArray(
            facets.take(12).map { facet ->
                JSONObject()
                    .put("name", facet.name)
                    .put("value", facet.value)
                    .put("count", facet.count)
            },
        )
        val planJson = JSONObject()
            .put("planVersion", plan.planVersion)
            .put("taskType", plan.taskType)
            .put("intent", plan.intent)
            .put("askMode", plan.askMode)
            .put("normalizedQuery", plan.normalizedQuery)
            .put("searchTerms", JSONArray(plan.searchTerms))
            .put("categories", JSONArray(plan.categories))
            .put("entityTypes", JSONArray(plan.entityTypes))
            .put("appHints", JSONArray(plan.appHints))
            .put("visualLabels", JSONArray(plan.visualLabels))
            .put("visualObjectLabels", JSONArray(plan.visualObjectLabels))
            .put("semanticQueries", JSONArray(plan.semanticQueries))
            .put("groupBy", plan.groupBy)
            .put("sort", plan.sort)
            .put("operation", plan.operation)
            .put("expectedAnswerShape", plan.expectedAnswerShape)
            .put("answerShape", plan.answerShape)
            .put("evidenceChannels", JSONArray(plan.evidenceChannels.map { it.toJson() }))
            .put("filters", plan.filters.toJson())
            .put("grouping", plan.grouping.toJson())
            .put("ranking", plan.ranking.toJson())
            .put("retrievalPlan", JSONArray(plan.retrievalPlan))
            .put(
                "subQueries",
                JSONArray(
                    plan.subQueries.map { subQuery ->
                        JSONObject()
                            .put("id", subQuery.id)
                            .put("label", subQuery.label)
                            .put("query", subQuery.query)
                            .put("searchTerms", JSONArray(subQuery.searchTerms))
                            .put("categories", JSONArray(subQuery.categories))
                            .put("entityTypes", JSONArray(subQuery.entityTypes))
                    },
                ),
            )
            .put(
                "dateRange",
                plan.dateRange?.let {
                    JSONObject()
                        .put("startMillis", it.startMillis)
                        .put("endMillisExclusive", it.endMillisExclusive)
                } ?: JSONObject.NULL,
            )
        return """
You answer questions for an Android app called Ask My Screenshots.

The local encrypted database has already retrieved the most relevant evidence using a planner v2 task/channel/filter/grouping/ranking plan. Your job is to phrase a helpful answer from ONLY the provided evidence and cite screenshot IDs.

Rules:
- Return ONLY raw JSON. No markdown. No explanation outside JSON.
- Do not claim facts that are not present in evidence snippets, entities, labels, dates, app hints, or categories.
- If evidence is weak, say so clearly and still cite the closest screenshot IDs.
- Prefer a direct answer first, then a short explanation.
- Use raw values when present; the user is asking about their own local screenshot memory.
- Do not mention internal table names to the user unless directly useful.
- Suggested refinements must be actionable follow-up queries for this app.
- For exact value, proof, and simple find questions, prioritize specific screenshot IDs from Top evidence. Cite group IDs only if they add structure.
- For theme, entity, app, timeline, pending, comparison, privacy, and analytics questions, organize the answer around the groups and still cite the exact screenshot IDs used for each claim.
- Never imply every screenshot in a cited group proves the answer; cite only the screenshot IDs that directly support the answer.

Database schema available to local search:
$databaseSchema

Structured local plan:
$planJson

User query:
$originalQuery

Top evidence:
$evidenceJson

Grouped evidence:
$groupJson

Matched entities across evidence:
$entityJson

Facets:
$facetJson

Return this exact JSON shape:
{
  "title": "short answer title",
  "body": "concise answer with any uncertainty",
  "confidence": 0.0,
  "answerType": "answer|find|aggregate|summarize|no_match",
  "citedGroupIds": ["group-id"],
  "citedScreenshotIds": [123],
  "refineActions": [
    {"label": "short chip label", "query": "follow up query", "type": "date|category|entity|app|topic"}
  ]
}
        """.trimIndent()
    }

    private fun tryGeminiAnswer(
        apiVersion: String,
        model: String,
        apiKey: String,
        prompt: String,
    ): AnswerSynthesisResult {
        val encodedKey = URLEncoder.encode(apiKey, Charsets.UTF_8.name())
        val url = URL("$GEMINI_HOST/$apiVersion/models/$model:generateContent?key=$encodedKey")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = GEMINI_TIMEOUT_MS
            readTimeout = GEMINI_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(geminiRequestBody(prompt).toString())
        }

        val responseBody = if (connection.responseCode in 200..299) {
            connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } else {
            val errorBody = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
            throw IllegalStateException(errorBody ?: "Gemini API error ${connection.responseCode}")
        }

        return JSONObject(responseBody)
            .extractGeminiText()
            .extractJsonObject()
            .let { JSONObject(it).toAnswerSynthesisResult() }
    }
}

private class GeminiClusterLabeler(
    private val apiKey: String,
) : RemoteClusterLabeler {
    override suspend fun labelClusters(request: ClusterLabelRequest): List<ClusterLabelResult> {
        if (request.clusters.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val prompt = request.toGeminiPrompt()
            var lastError: String? = null

            AppDebugLog.i(
                event = "cluster_label_start",
                message = "provider=gemini clusters=${request.clusters.size}",
            )
            for (apiVersion in GEMINI_API_VERSIONS) {
                for (model in GEMINI_MODELS) {
                    AppDebugLog.i("cluster_label_attempt", "apiVersion=$apiVersion model=$model")
                    val result = runCatching {
                        tryGeminiClusterLabels(
                            apiVersion = apiVersion,
                            model = model,
                            apiKey = apiKey,
                            prompt = prompt,
                        )
                    }
                    result.getOrNull()?.let { labels ->
                        AppDebugLog.i(
                            event = "cluster_label_success",
                            message = "apiVersion=$apiVersion model=$model labels=${labels.size}",
                        )
                        return@withContext labels
                    }
                    lastError = result.exceptionOrNull()?.message
                    AppDebugLog.w(
                        event = "cluster_label_failed",
                        message = "apiVersion=$apiVersion model=$model " +
                            "error=${lastError.orEmpty().take(MAX_LOG_ERROR_CHARS)}",
                    )
                }
            }
            AppDebugLog.w(
                event = "cluster_label_failed",
                message = "provider=gemini lastError=${lastError.orEmpty().take(MAX_LOG_ERROR_CHARS)}",
            )
            emptyList()
        }
    }

    private fun ClusterLabelRequest.toGeminiPrompt(): String {
        val clustersJson = JSONArray()
        clusters.forEach { cluster ->
            clustersJson.put(
                JSONObject()
                    .put("id", cluster.id)
                    .put("localTitle", cluster.localTitle)
                    .put("localSummary", cluster.localSummary)
                    .put("screenshotCount", cluster.screenshotCount)
                    .put("redactedSignals", JSONArray(cluster.redactedSignals)),
            )
        }
        return """
You label screenshot-memory clusters for an Android app called Ask My Screenshots.

Rules:
- Return ONLY raw JSON. No markdown. No explanation.
- Do not invent private values, people, apps, or facts.
- Use only the provided redacted signals and counts.
- Keep titles short, human, and useful on a phone.
- Summaries must say why screenshots belong together.
- If signals are weak, preserve the local title/summary.

Input clusters:
$clustersJson

Return this exact JSON shape:
{
  "clusters": [
    {
      "id": "same id",
      "title": "short human title",
      "summary": "one sentence cluster summary"
    }
  ]
}
        """.trimIndent()
    }

    private fun tryGeminiClusterLabels(
        apiVersion: String,
        model: String,
        apiKey: String,
        prompt: String,
    ): List<ClusterLabelResult> {
        val encodedKey = URLEncoder.encode(apiKey, Charsets.UTF_8.name())
        val url = URL("$GEMINI_HOST/$apiVersion/models/$model:generateContent?key=$encodedKey")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = GEMINI_TIMEOUT_MS
            readTimeout = GEMINI_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(geminiRequestBody(prompt).toString())
        }

        val responseBody = if (connection.responseCode in 200..299) {
            connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } else {
            val errorBody = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
            throw IllegalStateException(errorBody ?: "Gemini API error ${connection.responseCode}")
        }

        val clusters = JSONObject(responseBody)
            .extractGeminiText()
            .extractJsonObject()
            .let { JSONObject(it).optJSONArray("clusters") }
            ?: return emptyList()
        return (0 until clusters.length()).mapNotNull { index ->
            val item = clusters.optJSONObject(index) ?: return@mapNotNull null
            ClusterLabelResult(
                id = item.optString("id").trim(),
                title = item.optString("title").trim(),
                summary = item.optString("summary").trim(),
            ).takeIf { it.id.isNotBlank() && (it.title.isNotBlank() || it.summary.isNotBlank()) }
        }
    }
}

private fun String.extractJsonObject(): String {
    return replace("```json", "")
        .replace("```", "")
        .trim()
        .let { text ->
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start >= 0 && end > start) text.substring(start, end + 1) else text
        }
}

private fun geminiRequestBody(prompt: String): JSONObject {
    return JSONObject()
        .put(
            "contents",
            JSONArray()
                .put(
                    JSONObject()
                        .put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt)),
                        ),
                ),
        )
        .put(
            "generationConfig",
            JSONObject()
                .put("temperature", 0.1)
                .put("candidateCount", 1),
        )
}

private fun JSONObject.extractGeminiText(): String {
    val candidates = optJSONArray("candidates")
        ?: throw IllegalStateException(optJSONObject("promptFeedback")?.optString("blockReason") ?: "No candidates returned")
    val content = candidates.optJSONObject(0)?.optJSONObject("content")
        ?: throw IllegalStateException("No content returned")
    val parts = content.optJSONArray("parts")
        ?: throw IllegalStateException("No content parts returned")
    return (0 until parts.length())
        .joinToString("\n") { index ->
            parts.optJSONObject(index)?.optString("text").orEmpty()
        }
        .trim()
        .takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("No text returned")
}

private fun JSONObject.toQueryPlan(): RewrittenQueryPlan {
    val rootDateRange = optDateRange("dateRange")
    val evidenceChannels = optJSONArray("evidenceChannels").toEvidenceChannels()
    val filters = optJSONObject("filters").toPlannerFilters(rootDateRange)
    val grouping = optJSONObject("grouping").toPlannerGrouping(optString("groupBy").trim())
    val ranking = optJSONObject("ranking").toPlannerRanking(optString("sort").trim())
    val channelSearchTerms = evidenceChannels.flatMap { it.terms }
    val channelCategories = evidenceChannels.flatMap { it.categories }
    val channelEntityTypes = evidenceChannels.flatMap { it.entityTypes }
    val channelAppHints = evidenceChannels.flatMap { it.appHints }
    val channelVisualLabels = evidenceChannels.flatMap { it.visualLabels }
    val channelVisualObjectLabels = evidenceChannels.flatMap { it.visualObjectLabels }
    val channelSemanticQueries = evidenceChannels.flatMap { it.semanticQueries }
    return RewrittenQueryPlan(
        planVersion = optInt("planVersion", if (evidenceChannels.isNotEmpty() || optString("taskType").isNotBlank()) 2 else 1),
        normalizedQuery = optString("normalizedQuery").trim(),
        intent = optString("intent", "find").trim().ifBlank { "find" },
        searchTerms = (optJSONArray("searchTerms").toStringList() + channelSearchTerms).distinct(),
        categories = (optJSONArray("categories").toStringList() + filters.categories + channelCategories).distinct(),
        entityTypes = (optJSONArray("entityTypes").toStringList() + filters.entityTypes + channelEntityTypes).distinct(),
        appHints = (optJSONArray("appHints").toStringList() + filters.appHints + channelAppHints).distinct(),
        visualLabels = (optJSONArray("visualLabels").toStringList() + filters.visualLabels + channelVisualLabels).distinct(),
        visualObjectLabels = (optJSONArray("visualObjectLabels").toStringList() + filters.visualObjectLabels + channelVisualObjectLabels).distinct(),
        semanticQueries = (optJSONArray("semanticQueries").toStringList() + channelSemanticQueries).distinct(),
        dateRange = filters.dateRange ?: rootDateRange,
        askMode = optString("askMode").trim(),
        groupBy = grouping.by,
        sort = ranking.sort,
        operation = optString("operation").trim(),
        subQueries = optJSONArray("subQueries").toSubQueries(),
        retrievalPlan = optJSONArray("retrievalPlan").toJsonishStringList(),
        expectedAnswerShape = optString("expectedAnswerShape").trim(),
        taskType = optString("taskType").trim(),
        answerShape = optString("answerShape").trim(),
        evidenceChannels = evidenceChannels,
        filters = filters,
        grouping = grouping,
        ranking = ranking,
    )
}

private fun JSONObject.optDateRange(key: String): SkillDateRange? {
    val dateRangeJson = optJSONObject(key) ?: return null
    return dateRangeJson.toDateRange()
}

private fun JSONObject.toDateRange(): SkillDateRange? {
    return SkillDateRange(
        startMillis = optLong("startMillis"),
        endMillisExclusive = optLong("endMillisExclusive"),
    ).takeIf { it.startMillis > 0L && it.endMillisExclusive > it.startMillis }
}

private fun JSONObject?.toPlannerFilters(fallbackDateRange: SkillDateRange?): PlannerFilters {
    if (this == null) return PlannerFilters(dateRange = fallbackDateRange)
    return PlannerFilters(
        dateRange = optDateRange("dateRange") ?: fallbackDateRange,
        categories = optJSONArray("categories").toStringList(),
        entityTypes = optJSONArray("entityTypes").toStringList(),
        appHints = optJSONArray("appHints").toStringList(),
        visualLabels = optJSONArray("visualLabels").toStringList(),
        visualObjectLabels = optJSONArray("visualObjectLabels").toStringList(),
    )
}

private fun JSONObject?.toPlannerGrouping(fallbackBy: String): PlannerGrouping {
    if (this == null) {
        return PlannerGrouping(by = fallbackBy.ifBlank { "none" })
    }
    return PlannerGrouping(
        by = optString("by").trim().ifBlank { fallbackBy.ifBlank { "none" } },
        entityTypes = optJSONArray("entityTypes").toStringList(),
    )
}

private fun JSONObject?.toPlannerRanking(fallbackSort: String): PlannerRanking {
    if (this == null) {
        return PlannerRanking(sort = fallbackSort.ifBlank { "relevance" })
    }
    return PlannerRanking(
        sort = optString("sort").trim().ifBlank { fallbackSort.ifBlank { "relevance" } },
        allowBroadFallback = optBoolean("allowBroadFallback", false),
    )
}

private fun JSONArray?.toEvidenceChannels(): List<PlannedEvidenceChannel> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        when (val value = opt(index)) {
            is JSONObject -> {
                val channel = value.optString("channel").trim()
                PlannedEvidenceChannel(
                    channel = channel,
                    weight = value.optDouble("weight", 1.0).toFloat().coerceIn(0.1f, 2.5f),
                    required = value.optBoolean("required", false),
                    terms = value.optJSONArray("terms").toStringList(),
                    entityTypes = value.optJSONArray("entityTypes").toStringList(),
                    categories = value.optJSONArray("categories").toStringList(),
                    appHints = value.optJSONArray("appHints").toStringList(),
                    visualLabels = value.optJSONArray("visualLabels").toStringList(),
                    visualObjectLabels = value.optJSONArray("visualObjectLabels").toStringList(),
                    semanticQueries = value.optJSONArray("semanticQueries").toStringList(),
                ).takeIf { it.channel.isNotBlank() }
            }

            is String -> PlannedEvidenceChannel(channel = value.trim()).takeIf { it.channel.isNotBlank() }
            else -> null
        }
    }.distinctBy { it.channel.lowercase() }.take(12)
}

private fun PlannedEvidenceChannel.toJson(): JSONObject {
    return JSONObject()
        .put("channel", channel)
        .put("weight", weight)
        .put("required", required)
        .put("terms", JSONArray(terms))
        .put("entityTypes", JSONArray(entityTypes))
        .put("categories", JSONArray(categories))
        .put("appHints", JSONArray(appHints))
        .put("visualLabels", JSONArray(visualLabels))
        .put("visualObjectLabels", JSONArray(visualObjectLabels))
        .put("semanticQueries", JSONArray(semanticQueries))
}

private fun PlannerFilters.toJson(): JSONObject {
    return JSONObject()
        .put(
            "dateRange",
            dateRange?.toJson() ?: JSONObject.NULL,
        )
        .put("categories", JSONArray(categories))
        .put("entityTypes", JSONArray(entityTypes))
        .put("appHints", JSONArray(appHints))
        .put("visualLabels", JSONArray(visualLabels))
        .put("visualObjectLabels", JSONArray(visualObjectLabels))
}

private fun PlannerGrouping.toJson(): JSONObject {
    return JSONObject()
        .put("by", by)
        .put("entityTypes", JSONArray(entityTypes))
}

private fun PlannerRanking.toJson(): JSONObject {
    return JSONObject()
        .put("sort", sort)
        .put("allowBroadFallback", allowBroadFallback)
}

private fun SkillDateRange.toJson(): JSONObject {
    return JSONObject()
        .put("startMillis", startMillis)
        .put("endMillisExclusive", endMillisExclusive)
}

private fun JSONObject.toAnswerSynthesisResult(): AnswerSynthesisResult {
    return AnswerSynthesisResult(
        title = optString("title").trim(),
        body = optString("body").trim(),
        confidence = optDouble("confidence", 0.4).toFloat().coerceIn(0f, 1f),
        answerType = optString("answerType", "answer").trim().ifBlank { "answer" },
        citedScreenshotIds = optJSONArray("citedScreenshotIds").toLongList(),
        citedGroupIds = optJSONArray("citedGroupIds").toStringList(),
        refineActions = optJSONArray("refineActions").toRefineActions(),
    )
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length())
        .mapNotNull { index -> optString(index).trim().takeIf(String::isNotBlank) }
        .distinct()
}

private fun JSONArray?.toJsonishStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        val raw = when (val value = opt(index)) {
            is JSONObject -> value.toString()
            is JSONArray -> value.toString()
            else -> value?.toString()
        }
        raw?.trim()?.takeIf(String::isNotBlank)
    }.distinct()
}

private fun JSONArray?.toLongList(): List<Long> {
    if (this == null) return emptyList()
    return (0 until length())
        .mapNotNull { index -> optLong(index).takeIf { it > 0L } }
        .distinct()
}

private fun JSONArray?.toRefineActions(): List<RefineAction> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        val item = optJSONObject(index) ?: return@mapNotNull null
        RefineAction(
            label = item.optString("label").trim(),
            query = item.optString("query").trim(),
            type = item.optString("type").trim().ifBlank { "topic" },
        ).takeIf { it.label.isNotBlank() && it.query.isNotBlank() }
    }.distinctBy { it.label.lowercase() to it.query.lowercase() }
}

private fun JSONArray?.toSubQueries(): List<AskSubQuery> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        val item = optJSONObject(index) ?: return@mapNotNull null
        val query = item.optString("query").trim()
        AskSubQuery(
            id = item.optString("id").trim().ifBlank { "remote-${index + 1}" },
            label = item.optString("label").trim().ifBlank { query.take(42) },
            query = query,
            searchTerms = item.optJSONArray("searchTerms").toStringList(),
            categories = item.optJSONArray("categories").toStringList(),
            entityTypes = item.optJSONArray("entityTypes").toStringList(),
        ).takeIf { it.query.isNotBlank() }
    }.take(8)
}
