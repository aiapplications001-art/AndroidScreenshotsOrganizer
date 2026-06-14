package com.askmyscreenshots.skill.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.askmyscreenshots.skill.api.OrganizeRequest
import com.askmyscreenshots.skill.api.ScreenshotSource
import com.askmyscreenshots.skill.debug.SkillDebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ScreenshotCandidate(
    val mediaStoreId: Long?,
    val uri: String,
    val displayName: String?,
    val relativePath: String?,
    val bucketName: String?,
    val dateTakenMillis: Long?,
    val dateAddedSeconds: Long?,
    val sizeBytes: Long?,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
)

class ScreenshotMediaScanner(
    private val context: Context,
) {
    suspend fun scan(request: OrganizeRequest): List<ScreenshotCandidate> {
        SkillDebugLog.i(
            event = "scanner_start",
            message = "source=${request.source} start=${request.startMillis} " +
                "end=${request.endMillisExclusive} pickedCount=${request.pickedImageUris.size}",
        )
        return when (request.source) {
            ScreenshotSource.MEDIA_STORE -> scanMediaStore(request)
            ScreenshotSource.PICKED_IMAGES -> loadPickedImages(request)
        }.also { candidates ->
            SkillDebugLog.i(
                event = "scanner_done",
                message = "source=${request.source} candidates=${candidates.size}",
            )
        }
    }

    private suspend fun scanMediaStore(request: OrganizeRequest): List<ScreenshotCandidate> {
        return withContext(Dispatchers.IO) {
            val rows = mutableListOf<ScreenshotCandidate>()
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection().toTypedArray(),
                dateSelection(),
                arrayOf(
                    request.startMillis.toString(),
                    request.endMillisExclusive.toString(),
                    (request.startMillis / 1_000L).toString(),
                    (request.endMillisExclusive / 1_000L).toString(),
                ),
                "${MediaStore.Images.Media.DATE_TAKEN} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val row = cursor.toCandidate(context.contentResolver)
                    if (row.isScreenshotLike() && row.normalizedTakenMillis() in request) {
                        rows += row.copy(dateTakenMillis = row.normalizedTakenMillis())
                    }
                }
            }
            rows
        }
    }

    private suspend fun loadPickedImages(request: OrganizeRequest): List<ScreenshotCandidate> {
        return withContext(Dispatchers.IO) {
            request.pickedImageUris.mapNotNull { uriString ->
                val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@mapNotNull null
                context.contentResolver.query(uri, projection().toTypedArray(), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val row = cursor.toCandidate(context.contentResolver, fallbackUri = uri)
                            if (row.normalizedTakenMillis() in request) {
                                row.copy(dateTakenMillis = row.normalizedTakenMillis())
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    } ?: ScreenshotCandidate(
                    mediaStoreId = null,
                    uri = uri.toString(),
                    displayName = uri.lastPathSegment,
                    relativePath = null,
                    bucketName = null,
                    dateTakenMillis = request.startMillis,
                    dateAddedSeconds = null,
                    sizeBytes = null,
                    mimeType = null,
                    width = null,
                    height = null,
                )
            }
        }
    }

    private fun projection(): List<String> {
        return buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.SIZE)
            add(MediaStore.Images.Media.MIME_TYPE)
            add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            add(MediaStore.Images.Media.WIDTH)
            add(MediaStore.Images.Media.HEIGHT)
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

    private fun Cursor.toCandidate(
        resolver: ContentResolver,
        fallbackUri: Uri? = null,
    ): ScreenshotCandidate {
        val id = getLongOrNull(MediaStore.Images.Media._ID)
        val uri = fallbackUri ?: if (id != null) {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        } else {
            Uri.EMPTY
        }
        return ScreenshotCandidate(
            mediaStoreId = id,
            uri = uri.toString(),
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
            width = getIntOrNull(MediaStore.Images.Media.WIDTH),
            height = getIntOrNull(MediaStore.Images.Media.HEIGHT),
        ).also {
            resolver.takePersistableReadPermissionIfPossible(uri)
        }
    }
}

private operator fun OrganizeRequest.contains(value: Long?): Boolean {
    return value != null && value >= startMillis && value < endMillisExclusive
}

private fun ScreenshotCandidate.normalizedTakenMillis(): Long? {
    dateTakenMillis?.takeIf { it > 0L }?.let { return it }
    dateAddedSeconds?.takeIf { it > 0L }?.let { return it * 1_000L }
    return null
}

private fun ScreenshotCandidate.isScreenshotLike(): Boolean {
    val pathText = listOfNotNull(relativePath, bucketName)
        .joinToString("/")
        .lowercase()
        .replace('\\', '/')
    val nameText = displayName.orEmpty().lowercase()
    val folderSignals = listOf(
        "pictures/screenshots",
        "dcim/screenshots",
        "/screenshots/",
        "screenshot/",
        "/screen shots/",
        "screen shots/",
    )
    val nameSignals = listOf(
        "screenshot",
        "screen_shot",
        "screen-shot",
        "screen shot",
    )
    return folderSignals.any { pathText.contains(it) } ||
        nameSignals.any { nameText.contains(it) || pathText.contains(it) }
}

private fun ContentResolver.takePersistableReadPermissionIfPossible(uri: Uri) {
    runCatching {
        takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun Cursor.getStringOrNull(columnName: String): String? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

private fun Cursor.getLongOrNull(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
}

private fun Cursor.getIntOrNull(columnName: String): Int? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && !isNull(index)) getInt(index) else null
}
