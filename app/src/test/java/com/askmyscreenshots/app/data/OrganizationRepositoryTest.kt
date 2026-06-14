package com.askmyscreenshots.app.data

import android.net.Uri
import com.askmyscreenshots.app.domain.DateRange
import com.askmyscreenshots.app.domain.DateRangeCalculator
import com.askmyscreenshots.app.domain.MediaImageRow
import com.askmyscreenshots.app.domain.ScreenshotCandidateDraft
import com.askmyscreenshots.app.domain.ScreenshotScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class OrganizationRepositoryTest {
    private val range = DateRangeCalculator.custom(
        start = LocalDate.parse("2026-06-01"),
        endInclusive = LocalDate.parse("2026-06-08"),
        zone = ZoneId.of("UTC"),
    )

    @Test
    fun mediaStoreRowsStartRunWhenScreenshotsAreFound() = runTest {
        val dao = FakeOrganizationDao()
        val repository = repository(
            dao = dao,
            rows = listOf(
                row(1, "Screenshot_20260608.png", "Pictures/Screenshots/", "Screenshots"),
                row(2, "IMG_20260608.jpg", "DCIM/Camera/", "Camera"),
            ),
        )

        val result = repository.organizeFromMediaStore(range)

        assertTrue(result is OrganizationResult.Started)
        assertEquals(RunStatus.IN_PROGRESS.value, dao.runs.single().status)
        assertEquals(1, dao.runs.single().candidateCount)
        assertEquals("Screenshot_20260608.png", dao.candidates.single().displayName)
    }

    @Test
    fun emptyMediaStoreRowsRecordFailedRunAndKeepSetupEligible() = runTest {
        val dao = FakeOrganizationDao()
        val repository = repository(dao = dao, rows = emptyList())

        val result = repository.organizeFromMediaStore(range)

        assertEquals(OrganizationResult.NoScreenshotsFound, result)
        assertEquals(RunStatus.FAILED.value, dao.runs.single().status)
        assertEquals(OrganizationError.NO_SCREENSHOTS_FOUND.value, dao.runs.single().errorCode)
        assertEquals(0, dao.candidates.size)
    }

    @Test
    fun manualPickerRowsCanStartRunWithoutScreenshotHeuristic() = runTest {
        val dao = FakeOrganizationDao()
        val repository = repository(
            dao = dao,
            pickedCandidates = listOf(
                ScreenshotCandidateDraft(
                    mediaStoreId = 7,
                    uri = "content://picked/7",
                    displayName = "manual_pick.jpg",
                    relativePath = null,
                    bucketName = null,
                    dateTakenMillis = range.startMillis,
                    sizeBytes = 20L,
                    mimeType = "image/jpeg",
                ),
            ),
        )

        val result = repository.organizeFromPickedImages(range, emptyList())

        assertTrue(result is OrganizationResult.Started)
        assertEquals(AccessMode.PHOTO_PICKER.value, dao.runs.single().accessMode)
        assertEquals("manual_pick.jpg", dao.candidates.single().displayName)
    }

    private fun repository(
        dao: FakeOrganizationDao,
        rows: List<MediaImageRow> = emptyList(),
        pickedCandidates: List<ScreenshotCandidateDraft> = emptyList(),
    ): OrganizationRepository {
        return OrganizationRepository(
            dao = dao,
            mediaStoreDataSource = FakeMediaStoreSource(rows),
            pickedImageDataSource = FakePickedImageSource(pickedCandidates),
        )
    }

    private fun row(
        id: Long,
        displayName: String,
        relativePath: String,
        bucketName: String,
    ): MediaImageRow {
        return MediaImageRow(
            mediaStoreId = id,
            uri = "content://media/$id",
            displayName = displayName,
            relativePath = relativePath,
            bucketName = bucketName,
            dateTakenMillis = range.startMillis,
            dateAddedSeconds = null,
            sizeBytes = 10L,
            mimeType = "image/png",
        )
    }
}

private class FakeMediaStoreSource(
    private val rows: List<MediaImageRow>,
) : ScreenshotImageSource {
    override suspend fun scan(dateRange: DateRange): List<ScreenshotCandidateDraft> {
        return ScreenshotScanner.filterRows(rows, dateRange)
    }
}

private class FakePickedImageSource(
    private val candidates: List<ScreenshotCandidateDraft>,
) : PickedImageSource {
    override suspend fun load(
        uris: List<Uri>,
        dateRange: DateRange,
    ): List<ScreenshotCandidateDraft> {
        return candidates
    }
}

private class FakeOrganizationDao : OrganizationDao {
    val runs = mutableListOf<OrganizationRunEntity>()
    val candidates = mutableListOf<ScreenshotCandidateEntity>()
    private val latestRun = MutableStateFlow<OrganizationRunEntity?>(null)

    override fun observeLatestRun(): Flow<OrganizationRunEntity?> {
        return latestRun
    }

    override suspend fun insertRun(run: OrganizationRunEntity): Long {
        val id = runs.size + 1L
        val storedRun = run.copy(id = id)
        runs += storedRun
        latestRun.value = storedRun
        return id
    }

    override suspend fun updateRunWork(
        runId: Long,
        workId: String,
        workName: String,
        updatedAtMillis: Long,
    ) {
        val index = runs.indexOfFirst { it.id == runId }
        if (index == -1) return
        val updated = runs[index].copy(
            workId = workId,
            workName = workName,
            lastProgressAtMillis = updatedAtMillis,
        )
        runs[index] = updated
        latestRun.value = updated
    }

    override suspend fun updateRunProgress(
        runId: Long,
        status: String,
        candidateCount: Int,
        processedCount: Int,
        indexedCount: Int,
        newlyIndexedCount: Int,
        skippedCount: Int,
        failedCount: Int,
        updatedAtMillis: Long,
    ) {
        val index = runs.indexOfFirst { it.id == runId }
        if (index == -1) return
        val updated = runs[index].copy(
            status = status,
            candidateCount = candidateCount,
            processedCount = processedCount,
            indexedCount = indexedCount,
            newlyIndexedCount = newlyIndexedCount,
            skippedCount = skippedCount,
            failedCount = failedCount,
            lastProgressAtMillis = updatedAtMillis,
            errorCode = null,
        )
        runs[index] = updated
        latestRun.value = updated
    }

    override suspend fun updateRunCompletion(
        runId: Long,
        status: String,
        candidateCount: Int,
        processedCount: Int,
        indexedCount: Int,
        newlyIndexedCount: Int,
        skippedCount: Int,
        failedCount: Int,
        completedAtMillis: Long,
        errorCode: String?,
    ) {
        val index = runs.indexOfFirst { it.id == runId }
        if (index == -1) return
        val updated = runs[index].copy(
            status = status,
            candidateCount = candidateCount,
            processedCount = processedCount,
            indexedCount = indexedCount,
            newlyIndexedCount = newlyIndexedCount,
            skippedCount = skippedCount,
            failedCount = failedCount,
            completedAtMillis = completedAtMillis,
            lastProgressAtMillis = completedAtMillis,
            errorCode = errorCode,
        )
        runs[index] = updated
        latestRun.value = updated
    }

    override suspend fun insertCandidates(candidates: List<ScreenshotCandidateEntity>): List<Long> {
        this.candidates += candidates
        return candidates.indices.map { index -> this.candidates.size - candidates.size + index + 1L }
    }

    override suspend fun deleteRuns() {
        runs.clear()
        latestRun.value = null
    }

    override suspend fun deleteCandidates() {
        candidates.clear()
    }
}
