package com.askmyscreenshots.app.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.askmyscreenshots.app.domain.DateRange
import com.askmyscreenshots.app.domain.MediaImageRow
import com.askmyscreenshots.app.domain.ScreenshotCandidateDraft
import com.askmyscreenshots.app.domain.ScreenshotScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ScreenshotImageSource {
    suspend fun scan(dateRange: DateRange): List<ScreenshotCandidateDraft>
}

interface PickedImageSource {
    suspend fun load(
        uris: List<Uri>,
        dateRange: DateRange,
    ): List<ScreenshotCandidateDraft>
}

class MediaStoreScreenshotDataSource(
    private val context: Context,
) : ScreenshotImageSource {
    override suspend fun scan(dateRange: DateRange): List<ScreenshotCandidateDraft> {
        return withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val projection = buildProjection()
            val rows = mutableListOf<MediaImageRow>()
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                dateSelection(),
                dateSelectionArgs(dateRange),
                "${MediaStore.Images.Media.DATE_TAKEN} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    rows += cursor.toMediaImageRow()
                }
            }
            ScreenshotScanner.filterRows(rows, dateRange)
        }
    }

    private fun buildProjection(): List<String> {
        return buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.SIZE)
            add(MediaStore.Images.Media.MIME_TYPE)
            add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
            }
        }
    }

    private fun dateSelection(): String {
        val dateTaken = MediaStore.Images.Media.DATE_TAKEN
        val dateAdded = MediaStore.Images.Media.DATE_ADDED
        return """
            (($dateTaken >= ? AND $dateTaken < ?)
            OR (($dateTaken IS NULL OR $dateTaken = 0) AND $dateAdded >= ? AND $dateAdded < ?))
        """.trimIndent()
    }

    private fun dateSelectionArgs(dateRange: DateRange): Array<String> {
        return arrayOf(
            dateRange.startMillis.toString(),
            dateRange.endMillisExclusive.toString(),
            (dateRange.startMillis / 1_000L).toString(),
            (dateRange.endMillisExclusive / 1_000L).toString(),
        )
    }

    private fun Cursor.toMediaImageRow(): MediaImageRow {
        val id = getLongOrNull(MediaStore.Images.Media._ID)
        val uri = if (id != null) {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString()
        } else {
            Uri.EMPTY.toString()
        }
        return MediaImageRow(
            mediaStoreId = id,
            uri = uri,
            displayName = getStringOrNull(MediaStore.Images.Media.DISPLAY_NAME),
            relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                getStringOrNull(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                null
            },
            bucketName = getStringOrNull(MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            dateTakenMillis = getLongOrNull(MediaStore.Images.Media.DATE_TAKEN),
            dateAddedSeconds = getLongOrNull(MediaStore.Images.Media.DATE_ADDED),
            sizeBytes = getLongOrNull(MediaStore.Images.Media.SIZE),
            mimeType = getStringOrNull(MediaStore.Images.Media.MIME_TYPE),
        )
    }
}

class PickedImageDataSource(
    private val context: Context,
) : PickedImageSource {
    override suspend fun load(
        uris: List<Uri>,
        dateRange: DateRange,
    ): List<ScreenshotCandidateDraft> {
        return withContext(Dispatchers.IO) {
            val rows = uris.map { uri -> context.contentResolver.toPickedRow(uri, dateRange) }
            ScreenshotScanner.filterRows(
                rows = rows,
                dateRange = dateRange,
                requireScreenshotSignal = false,
            )
        }
    }
}

private fun ContentResolver.toPickedRow(
    uri: Uri,
    dateRange: DateRange,
): MediaImageRow {
    val projection = buildList {
        add(MediaStore.Images.Media._ID)
        add(MediaStore.Images.Media.DISPLAY_NAME)
        add(MediaStore.Images.Media.DATE_TAKEN)
        add(MediaStore.Images.Media.DATE_ADDED)
        add(MediaStore.Images.Media.SIZE)
        add(MediaStore.Images.Media.MIME_TYPE)
        add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(MediaStore.Images.Media.RELATIVE_PATH)
        }
    }.toTypedArray()

    query(uri, projection, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            return MediaImageRow(
                mediaStoreId = cursor.getLongOrNull(MediaStore.Images.Media._ID),
                uri = uri.toString(),
                displayName = cursor.getStringOrNull(MediaStore.Images.Media.DISPLAY_NAME),
                relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getStringOrNull(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    null
                },
                bucketName = cursor.getStringOrNull(MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
                dateTakenMillis = cursor.getLongOrNull(MediaStore.Images.Media.DATE_TAKEN)
                    ?: dateRange.startMillis,
                dateAddedSeconds = cursor.getLongOrNull(MediaStore.Images.Media.DATE_ADDED),
                sizeBytes = cursor.getLongOrNull(MediaStore.Images.Media.SIZE),
                mimeType = cursor.getStringOrNull(MediaStore.Images.Media.MIME_TYPE),
            )
        }
    }

    return MediaImageRow(
        mediaStoreId = null,
        uri = uri.toString(),
        displayName = uri.lastPathSegment,
        relativePath = null,
        bucketName = null,
        dateTakenMillis = dateRange.startMillis,
        dateAddedSeconds = null,
        sizeBytes = null,
        mimeType = null,
    )
}

private fun Cursor.getStringOrNull(columnName: String): String? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

private fun Cursor.getLongOrNull(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
}
