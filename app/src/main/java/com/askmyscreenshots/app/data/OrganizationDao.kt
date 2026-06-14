package com.askmyscreenshots.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OrganizationDao {
    @Query(
        """
        SELECT * FROM organization_runs
        ORDER BY startedAtMillis DESC, id DESC
        LIMIT 1
        """,
    )
    fun observeLatestRun(): Flow<OrganizationRunEntity?>

    @Insert
    suspend fun insertRun(run: OrganizationRunEntity): Long

    @Query(
        """
        UPDATE organization_runs
        SET workId = :workId,
            workName = :workName,
            lastProgressAtMillis = :updatedAtMillis
        WHERE id = :runId
        """,
    )
    suspend fun updateRunWork(
        runId: Long,
        workId: String,
        workName: String,
        updatedAtMillis: Long,
    )

    @Query(
        """
        UPDATE organization_runs
        SET status = :status,
            candidateCount = :candidateCount,
            processedCount = :processedCount,
            indexedCount = :indexedCount,
            newlyIndexedCount = :newlyIndexedCount,
            skippedCount = :skippedCount,
            failedCount = :failedCount,
            lastProgressAtMillis = :updatedAtMillis,
            errorCode = NULL
        WHERE id = :runId
        """,
    )
    suspend fun updateRunProgress(
        runId: Long,
        status: String,
        candidateCount: Int,
        processedCount: Int,
        indexedCount: Int,
        newlyIndexedCount: Int,
        skippedCount: Int,
        failedCount: Int,
        updatedAtMillis: Long,
    )

    @Query(
        """
        UPDATE organization_runs
        SET status = :status,
            candidateCount = :candidateCount,
            processedCount = :processedCount,
            indexedCount = :indexedCount,
            newlyIndexedCount = :newlyIndexedCount,
            skippedCount = :skippedCount,
            failedCount = :failedCount,
            completedAtMillis = :completedAtMillis,
            lastProgressAtMillis = :completedAtMillis,
            errorCode = :errorCode
        WHERE id = :runId
        """,
    )
    suspend fun updateRunCompletion(
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
    )

    @Insert
    suspend fun insertCandidates(candidates: List<ScreenshotCandidateEntity>): List<Long>

    @Query("DELETE FROM organization_runs")
    suspend fun deleteRuns()

    @Query("DELETE FROM screenshot_candidates")
    suspend fun deleteCandidates()
}
