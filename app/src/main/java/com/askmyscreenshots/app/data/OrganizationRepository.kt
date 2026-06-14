package com.askmyscreenshots.app.data

import android.net.Uri
import com.askmyscreenshots.app.domain.DateRange
import com.askmyscreenshots.app.domain.ScreenshotCandidateDraft
import kotlinx.coroutines.flow.Flow

sealed interface OrganizationResult {
    data class Started(val runId: Long, val candidateCount: Int) : OrganizationResult
    data object NoScreenshotsFound : OrganizationResult
}

class OrganizationRepository(
    private val dao: OrganizationDao,
    private val mediaStoreDataSource: ScreenshotImageSource,
    private val pickedImageDataSource: PickedImageSource,
) {
    fun observeLatestRun(): Flow<OrganizationRunEntity?> {
        return dao.observeLatestRun()
    }

    suspend fun organizeFromMediaStore(dateRange: DateRange): OrganizationResult {
        val candidates = mediaStoreDataSource.scan(dateRange)
        return recordScanResult(
            dateRange = dateRange,
            accessMode = AccessMode.MEDIA_STORE,
            candidates = candidates,
        )
    }

    suspend fun organizeFromPickedImages(
        dateRange: DateRange,
        uris: List<Uri>,
    ): OrganizationResult {
        val candidates = pickedImageDataSource.load(uris, dateRange)
        return recordScanResult(
            dateRange = dateRange,
            accessMode = AccessMode.PHOTO_PICKER,
            candidates = candidates,
        )
    }

    private suspend fun recordScanResult(
        dateRange: DateRange,
        accessMode: AccessMode,
        candidates: List<ScreenshotCandidateDraft>,
    ): OrganizationResult {
        val now = System.currentTimeMillis()
        if (candidates.isEmpty()) {
            dao.insertRun(
                OrganizationRunEntity(
                    dateRangePreset = dateRange.preset.name,
                    startMillis = dateRange.startMillis,
                    endMillis = dateRange.endMillisExclusive,
                    status = RunStatus.FAILED.value,
                    candidateCount = 0,
                    accessMode = accessMode.value,
                    startedAtMillis = now,
                    errorCode = OrganizationError.NO_SCREENSHOTS_FOUND.value,
                ),
            )
            return OrganizationResult.NoScreenshotsFound
        }

        val run = OrganizationRunEntity(
            dateRangePreset = dateRange.preset.name,
            startMillis = dateRange.startMillis,
            endMillis = dateRange.endMillisExclusive,
            status = RunStatus.IN_PROGRESS.value,
            candidateCount = candidates.size,
            accessMode = accessMode.value,
            startedAtMillis = now,
            errorCode = null,
        )
        val runId = dao.insertRun(run)
        dao.insertCandidates(
            candidates.map { draft ->
                ScreenshotCandidateEntity(
                    runId = runId,
                    mediaStoreId = draft.mediaStoreId,
                    uri = draft.uri,
                    displayName = draft.displayName,
                    relativePath = draft.relativePath,
                    bucketName = draft.bucketName,
                    dateTakenMillis = draft.dateTakenMillis,
                    sizeBytes = draft.sizeBytes,
                    mimeType = draft.mimeType,
                )
            },
        )
        return OrganizationResult.Started(runId = runId, candidateCount = candidates.size)
    }
}
