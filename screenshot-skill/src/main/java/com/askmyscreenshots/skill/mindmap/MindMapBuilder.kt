package com.askmyscreenshots.skill.mindmap

import com.askmyscreenshots.skill.api.ClusterLabelCandidate
import com.askmyscreenshots.skill.api.ClusterLabelRequest
import com.askmyscreenshots.skill.api.MemoryCluster
import com.askmyscreenshots.skill.api.MindMapGraph
import com.askmyscreenshots.skill.api.MindMapRequest
import com.askmyscreenshots.skill.api.RemoteClusterLabeler
import com.askmyscreenshots.skill.data.ScreenshotSkillDao
import com.askmyscreenshots.skill.debug.SkillDebugLog
import com.askmyscreenshots.skill.extract.PrivacyRedactor

class MindMapBuilder(
    private val dao: ScreenshotSkillDao,
    private val remoteClusterLabeler: RemoteClusterLabeler? = null,
) {
    suspend fun build(request: MindMapRequest): MindMapGraph {
        val range = request.dateRange
        val screenshots = dao.mindMapScreenshots(
            startMillis = range?.startMillis,
            endMillisExclusive = range?.endMillisExclusive,
            limit = request.maxScreenshots.coerceIn(1, 1_000),
        )
        val screenshotIds = screenshots.map { it.id }
        if (screenshotIds.isEmpty()) {
            return MemoryMapClusterer().build(emptyList(), request)
        }

        val entities = dao.mindMapEntities(screenshotIds).groupBy { it.screenshotId }
        val visualLabels = dao.mindMapVisualLabels(
            screenshotIds = screenshotIds,
            minConfidence = 0.68f,
        ).groupBy { it.screenshotId }
        val lines = dao.mindMapLines(
            screenshotIds = screenshotIds,
            limit = screenshotIds.size.coerceAtLeast(1) * LINES_PER_SCREENSHOT,
        ).groupBy { it.screenshotId }

        val inputs = screenshots.map { screenshot ->
            MemoryMapScreenshotInput(
                screenshot = screenshot,
                entities = entities[screenshot.id].orEmpty(),
                visualLabels = visualLabels[screenshot.id].orEmpty(),
                lines = lines[screenshot.id].orEmpty(),
            )
        }

        SkillDebugLog.i(
            event = "memory_map_features",
            message = "screenshots=${inputs.size} entities=${entities.values.sumOf { it.size }} " +
                "labels=${visualLabels.values.sumOf { it.size }} lines=${lines.values.sumOf { it.size }}",
        )

        val localGraph = MemoryMapClusterer().build(inputs, request)
        SkillDebugLog.i(
            event = "memory_map_local",
            message = "clusters=${localGraph.clusters.size} signals=${localGraph.topSignals.size}",
        )
        return maybeApplyRemoteLabels(localGraph, request)
    }

    private suspend fun maybeApplyRemoteLabels(
        graph: MindMapGraph,
        request: MindMapRequest,
    ): MindMapGraph {
        val labeler = remoteClusterLabeler
        if (!request.allowRemoteLabeling || labeler == null || graph.clusters.isEmpty()) {
            SkillDebugLog.i(
                event = "memory_map_remote_labels",
                message = "enabled=${request.allowRemoteLabeling} available=${labeler != null} used=false",
            )
            return graph
        }

        val labelResults = runCatching {
            labeler.labelClusters(
                ClusterLabelRequest(
                    clusters = graph.clusters.take(MAX_REMOTE_LABEL_CLUSTERS).map { cluster ->
                        ClusterLabelCandidate(
                            id = cluster.id,
                            localTitle = cluster.title.redactForRemote(cluster),
                            localSummary = cluster.summary.redactForRemote(cluster),
                            screenshotCount = cluster.screenshotCount,
                            redactedSignals = cluster.topSignals.take(8).map { signal ->
                                if (signal.shouldRedactForRemote()) {
                                    "${signal.type}:${PrivacyRedactor.markerFor(signal.type)}:${signal.screenshotCount}"
                                } else {
                                    "${signal.type}:${signal.label}:${signal.screenshotCount}"
                                }
                            },
                        )
                    },
                ),
            )
        }.onFailure { error ->
            SkillDebugLog.e("memory_map_remote_labels", "result=failure", error)
        }.getOrNull().orEmpty()

        if (labelResults.isEmpty()) {
            SkillDebugLog.w("memory_map_remote_labels", "result=empty")
            return graph
        }

        val labelsById = labelResults.associateBy { it.id }
        SkillDebugLog.i("memory_map_remote_labels", "result=success count=${labelsById.size}")
        return graph.copy(
            clusters = graph.clusters.map { cluster ->
                val label = labelsById[cluster.id]
                if (label == null) {
                    cluster
                } else {
                    cluster.copy(
                        title = label.title.takeIf { it.isNotBlank() } ?: cluster.title,
                        summary = label.summary.takeIf { it.isNotBlank() } ?: cluster.summary,
                    )
                }
            },
        )
    }

    companion object {
        private const val LINES_PER_SCREENSHOT = 8
        private const val MAX_REMOTE_LABEL_CLUSTERS = 10
    }
}

private fun com.askmyscreenshots.skill.api.MemorySignal.shouldRedactForRemote(): Boolean {
    return isSensitive || type in REMOTE_REDACT_TYPES
}

private fun String.redactForRemote(cluster: MemoryCluster): String {
    return cluster.topSignals.fold(this) { redacted, signal ->
        if (signal.shouldRedactForRemote()) {
            redacted
                .replace(signal.value, PrivacyRedactor.markerFor(signal.type))
                .replace(signal.label, PrivacyRedactor.markerFor(signal.type))
        } else {
            redacted
        }
    }
}

private val REMOTE_REDACT_TYPES = setOf(
    "phone",
    "email",
    "upi_id",
    "person_name",
    "booking_id",
    "order_id",
    "pan",
    "aadhaar",
    "account_number",
    "card_number",
    "ifsc",
    "url",
)
