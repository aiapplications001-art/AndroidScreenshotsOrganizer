package com.askmyscreenshots.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ScreenshotDetectorTest {
    private val zone = ZoneId.of("UTC")
    private val range = DateRangeCalculator.custom(
        start = LocalDate.parse("2026-06-01"),
        endInclusive = LocalDate.parse("2026-06-08"),
        zone = zone,
    )

    @Test
    fun detectsCommonScreenshotFoldersAndNames() {
        assertTrue(
            ScreenshotDetector.isScreenshotLike(
                row(
                    displayName = "IMG_0001.png",
                    relativePath = "Pictures/Screenshots/",
                    bucketName = "Screenshots",
                ),
            ),
        )
        assertTrue(
            ScreenshotDetector.isScreenshotLike(
                row(displayName = "Screen shot 2026-06-08.png"),
            ),
        )
    }

    @Test
    fun ignoresNormalCameraImages() {
        assertFalse(
            ScreenshotDetector.isScreenshotLike(
                row(
                    displayName = "IMG_20260608_120000.jpg",
                    relativePath = "DCIM/Camera/",
                    bucketName = "Camera",
                ),
            ),
        )
    }

    @Test
    fun scannerFiltersByDateAndScreenshotSignal() {
        val rows = listOf(
            row(
                mediaStoreId = 1,
                displayName = "Screenshot_20260608.png",
                relativePath = "DCIM/Screenshots/",
                dateTakenMillis = millis("2026-06-08"),
            ),
            row(
                mediaStoreId = 2,
                displayName = "Screenshot_20260501.png",
                relativePath = "DCIM/Screenshots/",
                dateTakenMillis = millis("2026-05-01"),
            ),
            row(
                mediaStoreId = 3,
                displayName = "IMG_20260608.jpg",
                relativePath = "DCIM/Camera/",
                dateTakenMillis = millis("2026-06-08"),
            ),
        )

        val candidates = ScreenshotScanner.filterRows(rows, range)

        assertEquals(listOf(1L), candidates.map { it.mediaStoreId })
    }

    @Test
    fun manualPickerModeCanAcceptUserSelectedImagesWithoutScreenshotName() {
        val rows = listOf(
            row(
                mediaStoreId = 9,
                displayName = "IMG_from_picker.jpg",
                relativePath = null,
                dateTakenMillis = millis("2026-06-04"),
            ),
        )

        val candidates = ScreenshotScanner.filterRows(
            rows = rows,
            dateRange = range,
            requireScreenshotSignal = false,
        )

        assertEquals(listOf(9L), candidates.map { it.mediaStoreId })
    }

    private fun row(
        mediaStoreId: Long? = null,
        displayName: String? = null,
        relativePath: String? = null,
        bucketName: String? = null,
        dateTakenMillis: Long? = millis("2026-06-08"),
    ): MediaImageRow {
        return MediaImageRow(
            mediaStoreId = mediaStoreId,
            uri = "content://media/$mediaStoreId",
            displayName = displayName,
            relativePath = relativePath,
            bucketName = bucketName,
            dateTakenMillis = dateTakenMillis,
            dateAddedSeconds = null,
            sizeBytes = 123L,
            mimeType = "image/png",
        )
    }

    private fun millis(date: String): Long {
        return LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

