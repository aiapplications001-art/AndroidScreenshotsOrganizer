package com.askmyscreenshots.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RunStatus(val value: String) {
    IN_PROGRESS("IN_PROGRESS"),
    DONE("DONE"),
    UP_TO_DATE("UP_TO_DATE"),
    STARTED("STARTED"),
    FAILED("FAILED"),
}

enum class AccessMode(val value: String) {
    MEDIA_STORE("MEDIA_STORE"),
    PHOTO_PICKER("PHOTO_PICKER"),
}

enum class OrganizationError(val value: String) {
    NO_SCREENSHOTS_FOUND("NO_SCREENSHOTS_FOUND"),
}

@Entity(tableName = "organization_runs")
data class OrganizationRunEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val dateRangePreset: String,
    val startMillis: Long,
    val endMillis: Long,
    val status: String,
    val candidateCount: Int,
    val processedCount: Int = 0,
    val indexedCount: Int = 0,
    val newlyIndexedCount: Int = 0,
    val skippedCount: Int = 0,
    val failedCount: Int = 0,
    val accessMode: String,
    val startedAtMillis: Long,
    val completedAtMillis: Long? = null,
    val lastProgressAtMillis: Long? = null,
    val workId: String? = null,
    val workName: String? = null,
    val errorCode: String? = null,
)

@Entity(
    tableName = "screenshot_candidates",
    indices = [
        Index("runId"),
        Index("mediaStoreId"),
    ],
)
data class ScreenshotCandidateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val runId: Long,
    val mediaStoreId: Long?,
    val uri: String,
    val displayName: String?,
    val relativePath: String?,
    val bucketName: String?,
    val dateTakenMillis: Long?,
    val sizeBytes: Long?,
    val mimeType: String?,
)
