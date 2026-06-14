package com.askmyscreenshots.app.domain

data class MediaImageRow(
    val mediaStoreId: Long?,
    val uri: String,
    val displayName: String?,
    val relativePath: String?,
    val bucketName: String?,
    val dateTakenMillis: Long?,
    val dateAddedSeconds: Long?,
    val sizeBytes: Long?,
    val mimeType: String?,
)

data class ScreenshotCandidateDraft(
    val mediaStoreId: Long?,
    val uri: String,
    val displayName: String?,
    val relativePath: String?,
    val bucketName: String?,
    val dateTakenMillis: Long?,
    val sizeBytes: Long?,
    val mimeType: String?,
)

object ScreenshotDetector {
    private val folderSignals = listOf(
        "pictures/screenshots",
        "dcim/screenshots",
        "/screenshots/",
        "screenshot/",
        "/screen shots/",
        "screen shots/",
    )

    private val nameSignals = listOf(
        "screenshot",
        "screen_shot",
        "screen-shot",
        "screen shot",
    )

    fun normalizedTakenMillis(row: MediaImageRow): Long? {
        row.dateTakenMillis?.takeIf { it > 0L }?.let { return it }
        row.dateAddedSeconds?.takeIf { it > 0L }?.let { return it * 1_000L }
        return null
    }

    fun isScreenshotLike(row: MediaImageRow): Boolean {
        val pathText = listOfNotNull(row.relativePath, row.bucketName)
            .joinToString("/")
            .lowercase()
            .replace('\\', '/')
        val nameText = row.displayName.orEmpty().lowercase()
        return folderSignals.any { pathText.contains(it) } ||
            nameSignals.any { nameText.contains(it) || pathText.contains(it) }
    }

    fun toDraft(row: MediaImageRow): ScreenshotCandidateDraft {
        return ScreenshotCandidateDraft(
            mediaStoreId = row.mediaStoreId,
            uri = row.uri,
            displayName = row.displayName,
            relativePath = row.relativePath,
            bucketName = row.bucketName,
            dateTakenMillis = normalizedTakenMillis(row),
            sizeBytes = row.sizeBytes,
            mimeType = row.mimeType,
        )
    }
}

object ScreenshotScanner {
    fun filterRows(
        rows: List<MediaImageRow>,
        dateRange: DateRange,
        requireScreenshotSignal: Boolean = true,
    ): List<ScreenshotCandidateDraft> {
        return rows.asSequence()
            .filter { row ->
                val takenAt = ScreenshotDetector.normalizedTakenMillis(row)
                takenAt != null &&
                    takenAt >= dateRange.startMillis &&
                    takenAt < dateRange.endMillisExclusive
            }
            .filter { row -> !requireScreenshotSignal || ScreenshotDetector.isScreenshotLike(row) }
            .map(ScreenshotDetector::toDraft)
            .toList()
    }
}

