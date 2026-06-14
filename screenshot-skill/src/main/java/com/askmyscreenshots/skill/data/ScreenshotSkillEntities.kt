package com.askmyscreenshots.skill.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

enum class IndexRunStatus(val value: String) {
    STARTED("STARTED"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED"),
}

@Entity(tableName = "index_runs")
data class IndexRunEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val startMillis: Long,
    val endMillisExclusive: Long,
    val source: String,
    val reindexPolicy: String,
    val status: String,
    val candidateCount: Int,
    val indexedCount: Int,
    val failedCount: Int,
    val startedAtMillis: Long,
    val completedAtMillis: Long? = null,
    val errorMessage: String? = null,
)

@Entity(
    tableName = "screenshots",
    indices = [
        Index(value = ["uri"], unique = true),
        Index("mediaStoreId"),
        Index("dateTakenMillis"),
        Index("category"),
        Index("appHint"),
    ],
)
data class ScreenshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val mediaStoreId: Long?,
    val uri: String,
    val displayName: String?,
    val relativePath: String?,
    val bucketName: String?,
    val dateTakenMillis: Long?,
    val sizeBytes: Long?,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val indexedAtMillis: Long,
    val indexStatus: String,
    val languageTag: String?,
    val category: String,
    val appHint: String?,
    val ocrText: String,
    val errorMessage: String? = null,
)

@Fts4
@Entity(tableName = "screenshot_fts")
data class ScreenshotFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    val title: String,
    val body: String,
    val entities: String,
    val categories: String,
)

@Entity(
    tableName = "ocr_blocks",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("screenshotId")],
)
data class OcrBlockEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val screenshotId: Long,
    val blockIndex: Int,
    val text: String,
    val left: Int?,
    val top: Int?,
    val right: Int?,
    val bottom: Int?,
)

@Entity(
    tableName = "ocr_lines",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("screenshotId"), Index("text")],
)
data class OcrLineEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val screenshotId: Long,
    val blockIndex: Int,
    val lineIndex: Int,
    val text: String,
    val left: Int?,
    val top: Int?,
    val right: Int?,
    val bottom: Int?,
)

@Entity(
    tableName = "ocr_tokens",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("screenshotId"), Index("text")],
)
data class OcrTokenEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val screenshotId: Long,
    val lineIndex: Int,
    val tokenIndex: Int,
    val text: String,
    val left: Int?,
    val top: Int?,
    val right: Int?,
    val bottom: Int?,
)

@Entity(
    tableName = "visual_labels",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("screenshotId"), Index("label")],
)
data class VisualLabelEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val screenshotId: Long,
    val label: String,
    val labelIndex: Int?,
    val confidence: Float,
)

@Entity(
    tableName = "detected_objects",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("screenshotId"), Index("areaRatio")],
)
data class DetectedObjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val screenshotId: Long,
    val objectIndex: Int,
    val trackingId: Int?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val areaRatio: Float,
)

@Entity(
    tableName = "detected_object_labels",
    foreignKeys = [
        ForeignKey(
            entity = DetectedObjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["objectId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("objectId"),
        Index("screenshotId"),
        Index("label"),
        Index("labelIndex"),
    ],
)
data class DetectedObjectLabelEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val objectId: Long,
    val screenshotId: Long,
    val label: String,
    val labelIndex: Int?,
    val confidence: Float,
)

@Entity(
    tableName = "barcodes",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("screenshotId"), Index("valueType")],
)
data class BarcodeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val screenshotId: Long,
    val rawValue: String?,
    val displayValue: String?,
    val format: Int,
    val valueType: Int,
    val left: Int?,
    val top: Int?,
    val right: Int?,
    val bottom: Int?,
)

@Entity(
    tableName = "faces",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("screenshotId")],
)
data class FaceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val screenshotId: Long,
    val faceIndex: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val smilingProbability: Float?,
    val leftEyeOpenProbability: Float?,
    val rightEyeOpenProbability: Float?,
    val headEulerAngleX: Float,
    val headEulerAngleY: Float,
    val headEulerAngleZ: Float,
    val landmarksJson: String,
)

@Entity(
    tableName = "detected_entities",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("screenshotId"),
        Index("type"),
        Index("normalizedValue"),
        Index(value = ["screenshotId", "type", "normalizedValue"], unique = true),
    ],
)
data class DetectedEntityEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val screenshotId: Long,
    val type: String,
    val value: String,
    val normalizedValue: String,
    val source: String,
    val confidence: Float,
    val isSensitive: Boolean,
    val evidence: String,
)

@Entity(
    tableName = "category_assignments",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("screenshotId"),
        Index("category"),
        Index(value = ["screenshotId", "category"], unique = true),
    ],
)
data class CategoryAssignmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val screenshotId: Long,
    val category: String,
    val confidence: Float,
    val reason: String,
)

@Entity(
    tableName = "index_failures",
    indices = [Index("runId"), Index("uri")],
)
data class IndexFailureEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val runId: Long,
    val uri: String,
    val displayName: String?,
    val errorMessage: String,
    val failedAtMillis: Long,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val query: String,
    val normalizedQuery: String,
    val resultCount: Int,
    val remoteRewriteUsed: Boolean,
    val searchedAtMillis: Long,
)

@Entity(tableName = "mind_map_cache")
data class MindMapCacheEntity(
    @PrimaryKey
    val cacheKey: String,
    val graphJson: String,
    val generatedAtMillis: Long,
)

@Entity(
    tableName = "screenshot_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("screenshotId"),
        Index("modelName"),
        Index(value = ["screenshotId", "modelName", "modelVersion"], unique = true),
    ],
)
data class ScreenshotEmbeddingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val screenshotId: Long,
    val modelName: String,
    val modelVersion: String,
    val inputHash: String,
    val dimension: Int,
    val vectorBlob: ByteArray,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "visual_descriptions",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("screenshotId"),
        Index("modelName"),
        Index(value = ["screenshotId", "modelName", "modelVersion"], unique = true),
    ],
)
data class VisualDescriptionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val screenshotId: Long,
    val modelName: String,
    val modelVersion: String,
    val description: String,
    val confidence: Float,
    val status: String,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "entity_links",
    indices = [
        Index("leftType"),
        Index("leftNormalizedValue"),
        Index("rightType"),
        Index("rightNormalizedValue"),
        Index(value = ["leftType", "leftNormalizedValue", "rightType", "rightNormalizedValue"], unique = true),
    ],
)
data class EntityLinkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val leftType: String,
    val leftValue: String,
    val leftNormalizedValue: String,
    val rightType: String,
    val rightValue: String,
    val rightNormalizedValue: String,
    val coOccurrenceCount: Int,
    val confidence: Float,
    val firstSeenAtMillis: Long,
    val lastSeenAtMillis: Long,
    val source: String,
)
