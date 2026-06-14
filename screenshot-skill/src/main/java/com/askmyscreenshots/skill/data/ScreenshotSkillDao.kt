package com.askmyscreenshots.skill.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

data class CategoryCount(
    val category: String,
    val count: Int,
)

data class AppSourceCount(
    val source: String,
    val count: Int,
)

data class VisualBucketCount(
    val label: String,
    val count: Int,
)

data class EntityTypeBucketCount(
    val type: String,
    val count: Int,
    val sensitiveCount: Int,
)

data class EntityCount(
    val type: String,
    val value: String,
    val normalizedValue: String,
    val count: Int,
    val isSensitive: Boolean,
)

data class EntityForScreenshot(
    val screenshotId: Long,
    val type: String,
    val value: String,
    val isSensitive: Boolean,
)

data class CategoryForScreenshot(
    val screenshotId: Long,
    val category: String,
    val confidence: Float,
    val reason: String,
)

data class VisualLabelForScreenshot(
    val screenshotId: Long,
    val label: String,
    val labelIndex: Int?,
    val confidence: Float,
)

data class ObjectLabelForScreenshot(
    val screenshotId: Long,
    val objectId: Long,
    val objectIndex: Int,
    val trackingId: Int?,
    val objectLeft: Int,
    val objectTop: Int,
    val objectRight: Int,
    val objectBottom: Int,
    val areaRatio: Float,
    val label: String,
    val labelIndex: Int?,
    val confidence: Float,
)

data class DetectedObjectWithLabels(
    val objectRow: DetectedObjectEntity,
    val labels: List<DetectedObjectLabelEntity>,
)

data class VisualDescriptionForScreenshot(
    val screenshotId: Long,
    val description: String,
    val confidence: Float,
    val status: String,
)

data class EmbeddingForSearch(
    val screenshotId: Long,
    val vectorBlob: ByteArray,
)

data class VisualCandidateRow(
    val screenshotId: Long,
    val confidence: Float,
    val matchCount: Int,
)

data class EntityLinkTarget(
    val type: String,
    val value: String,
    val normalizedValue: String,
    val confidence: Float,
    val coOccurrenceCount: Int,
)

data class ScreenshotWithRank(
    val id: Long,
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
    val errorMessage: String?,
)

data class IndexedScreenshotSnapshot(
    val uri: String,
    val mediaStoreId: Long?,
    val dateTakenMillis: Long?,
    val sizeBytes: Long?,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
)

data class MindMapEntityFeature(
    val screenshotId: Long,
    val type: String,
    val value: String,
    val normalizedValue: String,
    val confidence: Float,
    val isSensitive: Boolean,
)

data class MindMapVisualFeature(
    val screenshotId: Long,
    val label: String,
    val confidence: Float,
)

data class MindMapLineFeature(
    val screenshotId: Long,
    val text: String,
    val lineIndex: Int,
)

@Dao
interface ScreenshotSkillDao {
    @Insert
    suspend fun insertRun(run: IndexRunEntity): Long

    @Query(
        """
        UPDATE index_runs
        SET status = :status,
            candidateCount = :candidateCount,
            indexedCount = :indexedCount,
            failedCount = :failedCount,
            completedAtMillis = :completedAtMillis,
            errorMessage = :errorMessage
        WHERE id = :runId
        """,
    )
    suspend fun finishRun(
        runId: Long,
        status: String,
        candidateCount: Int,
        indexedCount: Int,
        failedCount: Int,
        completedAtMillis: Long,
        errorMessage: String?,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceScreenshot(screenshot: ScreenshotEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceFts(fts: ScreenshotFtsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlocks(blocks: List<OcrBlockEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLines(lines: List<OcrLineEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTokens(tokens: List<OcrTokenEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLabels(labels: List<VisualLabelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDetectedObject(objectRow: DetectedObjectEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDetectedObjectLabels(labels: List<DetectedObjectLabelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBarcodes(barcodes: List<BarcodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFaces(faces: List<FaceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntities(entities: List<DetectedEntityEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryAssignmentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbedding(embedding: ScreenshotEmbeddingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVisualDescription(description: VisualDescriptionEntity)

    @Insert
    suspend fun insertFailure(failure: IndexFailureEntity)

    @Insert
    suspend fun insertSearchHistory(history: SearchHistoryEntity)

    @Query(
        """
        SELECT uri, mediaStoreId, dateTakenMillis, sizeBytes, mimeType, width, height
        FROM screenshots
        WHERE uri IN (:uris)
        """,
    )
    suspend fun indexedSnapshotsForUris(uris: List<String>): List<IndexedScreenshotSnapshot>

    @Query(
        """
        SELECT *
        FROM screenshots
        WHERE uri IN (:uris)
        """,
    )
    suspend fun screenshotsForUris(uris: List<String>): List<ScreenshotEntity>

    @Query(
        """
        SELECT screenshots.*
        FROM screenshots
        LEFT JOIN screenshot_embeddings AS text_embeddings
          ON text_embeddings.screenshotId = screenshots.id
         AND text_embeddings.modelName = :textModelName
         AND text_embeddings.modelVersion = :textModelVersion
        LEFT JOIN screenshot_embeddings AS visual_embeddings
          ON visual_embeddings.screenshotId = screenshots.id
         AND visual_embeddings.modelName = :visualModelName
         AND visual_embeddings.modelVersion = :visualModelVersion
        WHERE screenshots.uri IN (:uris)
          AND (
            text_embeddings.id IS NULL
            OR visual_embeddings.id IS NULL
          )
        ORDER BY screenshots.dateTakenMillis DESC
        """,
    )
    suspend fun screenshotsMissingSearchSignalEmbeddingsForUris(
        uris: List<String>,
        textModelName: String,
        textModelVersion: String,
        visualModelName: String,
        visualModelVersion: String,
    ): List<ScreenshotEntity>

    @Query(
        """
        SELECT *
        FROM detected_entities
        WHERE screenshotId IN (:screenshotIds)
        """,
    )
    suspend fun detectedEntityRowsForScreenshots(screenshotIds: List<Long>): List<DetectedEntityEntity>

    @Query(
        """
        SELECT *
        FROM visual_labels
        WHERE screenshotId IN (:screenshotIds)
        """,
    )
    suspend fun visualLabelRowsForScreenshots(screenshotIds: List<Long>): List<VisualLabelEntity>

    @Query(
        """
        SELECT *
        FROM barcodes
        WHERE screenshotId IN (:screenshotIds)
        """,
    )
    suspend fun barcodeRowsForScreenshots(screenshotIds: List<Long>): List<BarcodeEntity>

    @Query(
        """
        SELECT *
        FROM faces
        WHERE screenshotId IN (:screenshotIds)
        """,
    )
    suspend fun faceRowsForScreenshots(screenshotIds: List<Long>): List<FaceEntity>

    @Query(
        """
        SELECT detected_object_labels.screenshotId AS screenshotId,
               detected_objects.id AS objectId,
               detected_objects.objectIndex AS objectIndex,
               detected_objects.trackingId AS trackingId,
               detected_objects.left AS objectLeft,
               detected_objects.top AS objectTop,
               detected_objects.right AS objectRight,
               detected_objects.bottom AS objectBottom,
               detected_objects.areaRatio AS areaRatio,
               detected_object_labels.label AS label,
               detected_object_labels.labelIndex AS labelIndex,
               detected_object_labels.confidence AS confidence
        FROM detected_object_labels
        JOIN detected_objects ON detected_objects.id = detected_object_labels.objectId
        WHERE detected_object_labels.screenshotId IN (:screenshotIds)
        ORDER BY detected_object_labels.confidence DESC
        """,
    )
    suspend fun objectLabelRowsForScreenshots(screenshotIds: List<Long>): List<ObjectLabelForScreenshot>

    @Query("DELETE FROM detected_entities WHERE screenshotId = :screenshotId")
    suspend fun deleteEntitiesForScreenshot(screenshotId: Long)

    @Query("DELETE FROM category_assignments WHERE screenshotId = :screenshotId")
    suspend fun deleteCategoriesForScreenshot(screenshotId: Long)

    @Query("UPDATE screenshots SET category = :category WHERE id = :screenshotId")
    suspend fun updateScreenshotCategory(screenshotId: Long, category: String)

    @Query("UPDATE screenshots SET appHint = :appHint WHERE id = :screenshotId")
    suspend fun updateScreenshotAppHint(screenshotId: Long, appHint: String?)

    @Query("UPDATE screenshot_fts SET categories = :categories WHERE rowid = :screenshotId")
    suspend fun updateScreenshotFtsCategories(screenshotId: Long, categories: String)

    @Query("UPDATE screenshot_fts SET entities = :entities WHERE rowid = :screenshotId")
    suspend fun updateScreenshotFtsEntities(screenshotId: Long, entities: String)

    @Query("UPDATE screenshot_fts SET body = :body WHERE rowid = :screenshotId")
    suspend fun updateScreenshotFtsBody(screenshotId: Long, body: String)

    @Query(
        """
        INSERT INTO entity_links(
            leftType,
            leftValue,
            leftNormalizedValue,
            rightType,
            rightValue,
            rightNormalizedValue,
            coOccurrenceCount,
            confidence,
            firstSeenAtMillis,
            lastSeenAtMillis,
            source
        )
        VALUES(
            :leftType,
            :leftValue,
            :leftNormalizedValue,
            :rightType,
            :rightValue,
            :rightNormalizedValue,
            1,
            :confidence,
            :seenAtMillis,
            :seenAtMillis,
            :source
        )
        ON CONFLICT(leftType, leftNormalizedValue, rightType, rightNormalizedValue)
        DO UPDATE SET
            coOccurrenceCount = coOccurrenceCount + 1,
            confidence = max(confidence, excluded.confidence),
            lastSeenAtMillis = excluded.lastSeenAtMillis
        """,
    )
    suspend fun upsertEntityLink(
        leftType: String,
        leftValue: String,
        leftNormalizedValue: String,
        rightType: String,
        rightValue: String,
        rightNormalizedValue: String,
        confidence: Float,
        seenAtMillis: Long,
        source: String,
    )

    @Transaction
    suspend fun replaceScreenshotDerivedSignals(
        screenshotId: Long,
        appHint: String?,
        primaryCategory: String,
        entities: List<DetectedEntityEntity>,
        categories: List<CategoryAssignmentEntity>,
    ) {
        deleteEntitiesForScreenshot(screenshotId)
        if (entities.isNotEmpty()) insertEntities(entities.map { it.copy(screenshotId = screenshotId) })
        updateScreenshotAppHint(screenshotId, appHint)
        updateScreenshotCategory(screenshotId, primaryCategory)
        updateScreenshotFtsEntities(
            screenshotId = screenshotId,
            entities = entities.joinToString(" ") { "${it.type} ${it.value} ${it.normalizedValue}" },
        )
        updateScreenshotFtsCategories(
            screenshotId = screenshotId,
            categories = categories.joinToString(" ") { it.category },
        )
        deleteCategoriesForScreenshot(screenshotId)
        if (categories.isNotEmpty()) {
            insertCategories(categories.map { it.copy(screenshotId = screenshotId) })
        }
    }

    @Transaction
    suspend fun replaceScreenshotIndex(
        screenshot: ScreenshotEntity,
        ftsBody: ScreenshotFtsEntity,
        blocks: List<OcrBlockEntity>,
        lines: List<OcrLineEntity>,
        tokens: List<OcrTokenEntity>,
        labels: List<VisualLabelEntity>,
        detectedObjects: List<DetectedObjectWithLabels>,
        barcodes: List<BarcodeEntity>,
        faces: List<FaceEntity>,
        entities: List<DetectedEntityEntity>,
        categories: List<CategoryAssignmentEntity>,
        embeddings: List<ScreenshotEmbeddingEntity>,
        visualDescription: VisualDescriptionEntity?,
        entityLinks: List<EntityLinkEntity>,
    ): Long {
        val screenshotId = replaceScreenshot(screenshot)
        val withId = ftsBody.copy(rowId = screenshotId)
        replaceFts(withId)
        if (blocks.isNotEmpty()) insertBlocks(blocks.map { it.copy(screenshotId = screenshotId) })
        if (lines.isNotEmpty()) insertLines(lines.map { it.copy(screenshotId = screenshotId) })
        if (tokens.isNotEmpty()) insertTokens(tokens.map { it.copy(screenshotId = screenshotId) })
        if (labels.isNotEmpty()) insertLabels(labels.map { it.copy(screenshotId = screenshotId) })
        detectedObjects.forEach { detectedObject ->
            val objectId = insertDetectedObject(detectedObject.objectRow.copy(screenshotId = screenshotId))
            if (detectedObject.labels.isNotEmpty()) {
                insertDetectedObjectLabels(
                    detectedObject.labels.map { label ->
                        label.copy(objectId = objectId, screenshotId = screenshotId)
                    },
                )
            }
        }
        if (barcodes.isNotEmpty()) insertBarcodes(barcodes.map { it.copy(screenshotId = screenshotId) })
        if (faces.isNotEmpty()) insertFaces(faces.map { it.copy(screenshotId = screenshotId) })
        if (entities.isNotEmpty()) insertEntities(entities.map { it.copy(screenshotId = screenshotId) })
        if (categories.isNotEmpty()) insertCategories(categories.map { it.copy(screenshotId = screenshotId) })
        embeddings.forEach { insertEmbedding(it.copy(screenshotId = screenshotId)) }
        visualDescription?.let { insertVisualDescription(it.copy(screenshotId = screenshotId)) }
        entityLinks.forEach { link ->
            upsertEntityLink(
                leftType = link.leftType,
                leftValue = link.leftValue,
                leftNormalizedValue = link.leftNormalizedValue,
                rightType = link.rightType,
                rightValue = link.rightValue,
                rightNormalizedValue = link.rightNormalizedValue,
                confidence = link.confidence,
                seenAtMillis = link.lastSeenAtMillis,
                source = link.source,
            )
        }
        return screenshotId
    }

    @Transaction
    suspend fun replaceScreenshotCategories(
        screenshotId: Long,
        primaryCategory: String,
        categories: List<CategoryAssignmentEntity>,
    ) {
        deleteCategoriesForScreenshot(screenshotId)
        updateScreenshotCategory(screenshotId, primaryCategory)
        updateScreenshotFtsCategories(
            screenshotId = screenshotId,
            categories = categories.joinToString(" ") { it.category },
        )
        if (categories.isNotEmpty()) {
            insertCategories(categories.map { it.copy(screenshotId = screenshotId) })
        }
    }

    @Transaction
    suspend fun insertObjectDetectionsForScreenshot(
        screenshotId: Long,
        detectedObjects: List<DetectedObjectWithLabels>,
    ) {
        detectedObjects.forEach { detectedObject ->
            val objectId = insertDetectedObject(detectedObject.objectRow.copy(screenshotId = screenshotId))
            if (detectedObject.labels.isNotEmpty()) {
                insertDetectedObjectLabels(
                    detectedObject.labels.map { label ->
                        label.copy(objectId = objectId, screenshotId = screenshotId)
                    },
                )
            }
        }
    }

    @Query(
        """
        SELECT screenshots.*
        FROM screenshots
        LEFT JOIN screenshot_embeddings
          ON screenshot_embeddings.screenshotId = screenshots.id
         AND screenshot_embeddings.modelName = :modelName
         AND screenshot_embeddings.modelVersion = :modelVersion
        WHERE screenshot_embeddings.id IS NULL
          AND (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
        ORDER BY screenshots.dateTakenMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun screenshotsMissingEmbeddings(
        modelName: String,
        modelVersion: String,
        startMillis: Long?,
        endMillisExclusive: Long?,
        limit: Int,
    ): List<ScreenshotEntity>

    @Query(
        """
        SELECT screenshot_embeddings.screenshotId AS screenshotId,
               screenshot_embeddings.vectorBlob AS vectorBlob
        FROM screenshot_embeddings
        JOIN screenshots ON screenshots.id = screenshot_embeddings.screenshotId
        WHERE screenshot_embeddings.modelName = :modelName
          AND screenshot_embeddings.modelVersion = :modelVersion
          AND (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
        ORDER BY screenshots.dateTakenMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun embeddingsForSearch(
        modelName: String,
        modelVersion: String,
        startMillis: Long?,
        endMillisExclusive: Long?,
        limit: Int,
    ): List<EmbeddingForSearch>

    @Query(
        """
        SELECT *
        FROM screenshots
        WHERE id IN (:screenshotIds)
        """,
    )
    suspend fun screenshotsByIds(screenshotIds: List<Long>): List<ScreenshotEntity>

    @Query(
        """
        SELECT screenshots.*
        FROM screenshots
        LEFT JOIN visual_descriptions
          ON visual_descriptions.screenshotId = screenshots.id
         AND visual_descriptions.modelName = :modelName
         AND visual_descriptions.modelVersion = :modelVersion
        WHERE visual_descriptions.id IS NULL
          AND (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
        ORDER BY screenshots.dateTakenMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun screenshotsMissingVisualDescriptions(
        modelName: String,
        modelVersion: String,
        startMillis: Long?,
        endMillisExclusive: Long?,
        limit: Int,
    ): List<ScreenshotEntity>

    @Query(
        """
        SELECT screenshotId, description, confidence, status
        FROM visual_descriptions
        WHERE screenshotId IN (:screenshotIds)
        ORDER BY confidence DESC
        """,
    )
    suspend fun visualDescriptionsForScreenshots(screenshotIds: List<Long>): List<VisualDescriptionForScreenshot>

    @Query(
        """
        SELECT screenshots.*
        FROM screenshots
        LEFT JOIN detected_objects ON detected_objects.screenshotId = screenshots.id
        WHERE detected_objects.id IS NULL
          AND (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
        ORDER BY screenshots.dateTakenMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun screenshotsMissingObjectDetections(
        startMillis: Long?,
        endMillisExclusive: Long?,
        limit: Int,
    ): List<ScreenshotEntity>

    @Query(
        """
        SELECT screenshots.*
        FROM screenshots
        LEFT JOIN detected_objects ON detected_objects.screenshotId = screenshots.id
        WHERE screenshots.id IN (:screenshotIds)
          AND detected_objects.id IS NULL
        ORDER BY screenshots.dateTakenMillis DESC
        """,
    )
    suspend fun screenshotsMissingObjectDetections(screenshotIds: List<Long>): List<ScreenshotEntity>

    @Query(
        """
        SELECT screenshotId, label, labelIndex, confidence
        FROM detected_object_labels
        WHERE screenshotId IN (:screenshotIds)
          AND confidence >= :minConfidence
        ORDER BY confidence DESC, label ASC
        """,
    )
    suspend fun objectLabelsForScreenshots(
        screenshotIds: List<Long>,
        minConfidence: Float,
    ): List<VisualLabelForScreenshot>

    @Query(
        """
        SELECT rightType AS type,
               rightValue AS value,
               rightNormalizedValue AS normalizedValue,
               confidence AS confidence,
               coOccurrenceCount AS coOccurrenceCount
        FROM entity_links
        WHERE leftType IN (:types)
          AND leftNormalizedValue IN (:normalizedValues)
        UNION
        SELECT leftType AS type,
               leftValue AS value,
               leftNormalizedValue AS normalizedValue,
               confidence AS confidence,
               coOccurrenceCount AS coOccurrenceCount
        FROM entity_links
        WHERE rightType IN (:types)
          AND rightNormalizedValue IN (:normalizedValues)
        ORDER BY coOccurrenceCount DESC, confidence DESC
        LIMIT :limit
        """,
    )
    suspend fun linkedEntityTargets(
        types: List<String>,
        normalizedValues: List<String>,
        limit: Int,
    ): List<EntityLinkTarget>

    @Query(
        """
        SELECT screenshots.*
        FROM screenshot_fts
        JOIN screenshots ON screenshot_fts.rowid = screenshots.id
        WHERE screenshot_fts MATCH :matchQuery
          AND (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
        ORDER BY screenshots.dateTakenMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun searchFts(
        matchQuery: String,
        startMillis: Long?,
        endMillisExclusive: Long?,
        limit: Int,
    ): List<ScreenshotEntity>

    @Query(
        """
        SELECT DISTINCT screenshots.*
        FROM screenshots
        LEFT JOIN category_assignments ON category_assignments.screenshotId = screenshots.id
        WHERE category_assignments.category IN (:categories)
          AND (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
        ORDER BY screenshots.dateTakenMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun searchCategories(
        categories: List<String>,
        startMillis: Long?,
        endMillisExclusive: Long?,
        limit: Int,
    ): List<ScreenshotEntity>

    @Query(
        """
        SELECT DISTINCT screenshots.*
        FROM screenshots
        JOIN detected_entities ON detected_entities.screenshotId = screenshots.id
        WHERE detected_entities.type IN (:entityTypes)
          AND (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
        ORDER BY screenshots.dateTakenMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun searchEntityTypes(
        entityTypes: List<String>,
        startMillis: Long?,
        endMillisExclusive: Long?,
        limit: Int,
    ): List<ScreenshotEntity>

    @Query(
        """
        SELECT *
        FROM screenshots
        WHERE LOWER(appHint) IN (:appHints)
          AND (:startMillis IS NULL OR dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR dateTakenMillis < :endMillisExclusive)
        ORDER BY dateTakenMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun searchAppHints(
        appHints: List<String>,
        startMillis: Long?,
        endMillisExclusive: Long?,
        limit: Int,
    ): List<ScreenshotEntity>

    @Query(
        """
        SELECT screenshots.*
        FROM screenshots
        JOIN detected_entities ON detected_entities.screenshotId = screenshots.id
        WHERE detected_entities.type IN (:entityTypes)
          AND detected_entities.normalizedValue IN (:normalizedValues)
          AND (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
        GROUP BY screenshots.id
        ORDER BY MAX(detected_entities.confidence) DESC, screenshots.dateTakenMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun searchExactEntityValues(
        entityTypes: List<String>,
        normalizedValues: List<String>,
        startMillis: Long?,
        endMillisExclusive: Long?,
        limit: Int,
    ): List<ScreenshotEntity>

    @Query(
        """
        SELECT screenshots.*
        FROM screenshots
        JOIN detected_entities ON detected_entities.screenshotId = screenshots.id
        WHERE detected_entities.type IN (:entityTypes)
          AND (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
        GROUP BY screenshots.id
        ORDER BY MAX(detected_entities.confidence) DESC, screenshots.dateTakenMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun searchExactEntityTypes(
        entityTypes: List<String>,
        startMillis: Long?,
        endMillisExclusive: Long?,
        limit: Int,
    ): List<ScreenshotEntity>

    @Query(
        """
        SELECT *
        FROM screenshots
        WHERE (:startMillis IS NULL OR dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR dateTakenMillis < :endMillisExclusive)
        ORDER BY dateTakenMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun recentScreenshots(
        startMillis: Long?,
        endMillisExclusive: Long?,
        limit: Int,
    ): List<ScreenshotEntity>

    @Query(
        """
        SELECT *
        FROM screenshots
        WHERE (:startMillis IS NULL OR dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR dateTakenMillis < :endMillisExclusive)
        ORDER BY dateTakenMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun mindMapScreenshots(
        startMillis: Long?,
        endMillisExclusive: Long?,
        limit: Int,
    ): List<ScreenshotEntity>

    @Query(
        """
        SELECT screenshotId, type, value, normalizedValue, confidence, isSensitive
        FROM detected_entities
        WHERE screenshotId IN (:screenshotIds)
        ORDER BY confidence DESC, type ASC
        """,
    )
    suspend fun mindMapEntities(screenshotIds: List<Long>): List<MindMapEntityFeature>

    @Query(
        """
        SELECT screenshotId, label, confidence
        FROM visual_labels
        WHERE screenshotId IN (:screenshotIds)
          AND confidence >= :minConfidence
        ORDER BY confidence DESC
        """,
    )
    suspend fun mindMapVisualLabels(
        screenshotIds: List<Long>,
        minConfidence: Float,
    ): List<MindMapVisualFeature>

    @Query(
        """
        SELECT screenshotId, text, lineIndex
        FROM ocr_lines
        WHERE screenshotId IN (:screenshotIds)
        ORDER BY screenshotId ASC, lineIndex ASC
        LIMIT :limit
        """,
    )
    suspend fun mindMapLines(
        screenshotIds: List<Long>,
        limit: Int,
    ): List<MindMapLineFeature>

    @Query(
        """
        SELECT category, COUNT(*) AS count
        FROM category_assignments
        GROUP BY category
        ORDER BY count DESC
        """,
    )
    suspend fun categoryCounts(): List<CategoryCount>

    @Query(
        """
        SELECT category_assignments.category AS category, COUNT(*) AS count
        FROM category_assignments
        JOIN screenshots ON screenshots.id = category_assignments.screenshotId
        WHERE (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
        GROUP BY category_assignments.category
        ORDER BY count DESC
        """,
    )
    suspend fun categoryCounts(
        startMillis: Long?,
        endMillisExclusive: Long?,
    ): List<CategoryCount>

    @Query(
        """
        SELECT COUNT(*) FROM screenshots
        WHERE (:startMillis IS NULL OR dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR dateTakenMillis < :endMillisExclusive)
        """,
    )
    suspend fun indexedScreenshotCount(
        startMillis: Long?,
        endMillisExclusive: Long?,
    ): Int

    @Query(
        """
        SELECT source, COUNT(DISTINCT screenshotId) AS count
        FROM (
            SELECT LOWER(appHint) AS source, id AS screenshotId
            FROM screenshots
            WHERE appHint IS NOT NULL
              AND TRIM(appHint) != ''
              AND (:startMillis IS NULL OR dateTakenMillis >= :startMillis)
              AND (:endMillisExclusive IS NULL OR dateTakenMillis < :endMillisExclusive)
            UNION
            SELECT LOWER(detected_entities.normalizedValue) AS source,
                   detected_entities.screenshotId AS screenshotId
            FROM detected_entities
            JOIN screenshots ON screenshots.id = detected_entities.screenshotId
            WHERE detected_entities.type = 'app'
              AND TRIM(detected_entities.normalizedValue) != ''
              AND (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
              AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
        )
        WHERE source IS NOT NULL AND TRIM(source) != ''
        GROUP BY source
        ORDER BY count DESC, source ASC
        LIMIT :limit
        """,
    )
    suspend fun appSourceCounts(
        startMillis: Long?,
        endMillisExclusive: Long?,
        limit: Int,
    ): List<AppSourceCount>

    @Query(
        """
        SELECT label, COUNT(DISTINCT screenshotId) AS count
        FROM (
            SELECT LOWER(label) AS label, screenshotId AS screenshotId
            FROM visual_labels
            JOIN screenshots ON screenshots.id = visual_labels.screenshotId
            WHERE visual_labels.confidence >= :minLabelConfidence
              AND (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
              AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
            UNION
            SELECT LOWER(detected_object_labels.label) AS label,
                   detected_object_labels.screenshotId AS screenshotId
            FROM detected_object_labels
            JOIN detected_objects ON detected_objects.id = detected_object_labels.objectId
            JOIN screenshots ON screenshots.id = detected_object_labels.screenshotId
            WHERE detected_object_labels.confidence >= :minObjectConfidence
              AND detected_objects.areaRatio >= :minObjectAreaRatio
              AND (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
              AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
        )
        WHERE label IS NOT NULL AND TRIM(label) != ''
        GROUP BY label
        ORDER BY count DESC, label ASC
        LIMIT :limit
        """,
    )
    suspend fun visualBucketCounts(
        startMillis: Long?,
        endMillisExclusive: Long?,
        minLabelConfidence: Float,
        minObjectConfidence: Float,
        minObjectAreaRatio: Float,
        limit: Int,
    ): List<VisualBucketCount>

    @Query(
        """
        SELECT detected_entities.type AS type,
               COUNT(DISTINCT detected_entities.screenshotId) AS count,
               COUNT(CASE WHEN detected_entities.isSensitive THEN 1 END) AS sensitiveCount
        FROM detected_entities
        JOIN screenshots ON screenshots.id = detected_entities.screenshotId
        WHERE (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
        GROUP BY detected_entities.type
        ORDER BY count DESC, type ASC
        LIMIT :limit
        """,
    )
    suspend fun entityTypeBucketCounts(
        startMillis: Long?,
        endMillisExclusive: Long?,
        limit: Int,
    ): List<EntityTypeBucketCount>

    @Query(
        """
        SELECT type, value, normalizedValue, COUNT(DISTINCT screenshotId) AS count, isSensitive
        FROM detected_entities
        GROUP BY type, normalizedValue
        ORDER BY count DESC
        LIMIT :limit
        """,
    )
    suspend fun topEntities(limit: Int): List<EntityCount>

    @Query(
        """
        SELECT detected_entities.type AS type,
               detected_entities.value AS value,
               detected_entities.normalizedValue AS normalizedValue,
               COUNT(DISTINCT detected_entities.screenshotId) AS count,
               detected_entities.isSensitive AS isSensitive
        FROM detected_entities
        JOIN screenshots ON screenshots.id = detected_entities.screenshotId
        WHERE (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
        GROUP BY detected_entities.type, detected_entities.normalizedValue
        ORDER BY count DESC
        LIMIT :limit
        """,
    )
    suspend fun topEntities(
        startMillis: Long?,
        endMillisExclusive: Long?,
        limit: Int,
    ): List<EntityCount>

    @Query(
        """
        SELECT screenshotId, type, value, isSensitive
        FROM detected_entities
        WHERE screenshotId IN (:screenshotIds)
        ORDER BY isSensitive DESC, type ASC
        """,
    )
    suspend fun entitiesForScreenshots(screenshotIds: List<Long>): List<EntityForScreenshot>

    @Query(
        """
        SELECT screenshotId, category, confidence, reason
        FROM category_assignments
        WHERE screenshotId IN (:screenshotIds)
        ORDER BY confidence DESC, category ASC
        """,
    )
    suspend fun categoriesForScreenshots(screenshotIds: List<Long>): List<CategoryForScreenshot>

    @Query(
        """
        SELECT screenshotId, label, labelIndex, confidence
        FROM visual_labels
        WHERE screenshotId IN (:screenshotIds)
          AND confidence >= :minConfidence
        ORDER BY confidence DESC, label ASC
        """,
    )
    suspend fun labelsForScreenshots(
        screenshotIds: List<Long>,
        minConfidence: Float,
    ): List<VisualLabelForScreenshot>

    @Query(
        """
        SELECT visual_labels.screenshotId AS screenshotId,
               MAX(visual_labels.confidence) AS confidence,
               COUNT(DISTINCT LOWER(visual_labels.label)) AS matchCount
        FROM visual_labels
        JOIN screenshots ON screenshots.id = visual_labels.screenshotId
        WHERE LOWER(visual_labels.label) IN (:labels)
          AND visual_labels.confidence >= :minConfidence
          AND (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
        GROUP BY visual_labels.screenshotId
        ORDER BY confidence DESC, matchCount DESC
        LIMIT :limit
        """,
    )
    suspend fun searchVisualLabelCandidates(
        labels: List<String>,
        minConfidence: Float,
        startMillis: Long?,
        endMillisExclusive: Long?,
        limit: Int,
    ): List<VisualCandidateRow>

    @Query(
        """
        SELECT detected_object_labels.screenshotId AS screenshotId,
               MAX(detected_object_labels.confidence) AS confidence,
               COUNT(DISTINCT LOWER(detected_object_labels.label)) AS matchCount
        FROM detected_object_labels
        JOIN screenshots ON screenshots.id = detected_object_labels.screenshotId
        JOIN detected_objects ON detected_objects.id = detected_object_labels.objectId
        WHERE LOWER(detected_object_labels.label) IN (:labels)
          AND detected_object_labels.confidence >= :minConfidence
          AND detected_objects.areaRatio >= :minAreaRatio
          AND (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
        GROUP BY detected_object_labels.screenshotId
        ORDER BY confidence DESC, matchCount DESC, MAX(detected_objects.areaRatio) DESC
        LIMIT :limit
        """,
    )
    suspend fun searchVisualObjectCandidates(
        labels: List<String>,
        minConfidence: Float,
        minAreaRatio: Float,
        startMillis: Long?,
        endMillisExclusive: Long?,
        limit: Int,
    ): List<VisualCandidateRow>

    @Query(
        """
        SELECT *
        FROM ocr_lines
        WHERE screenshotId = :screenshotId
        ORDER BY lineIndex ASC
        LIMIT :limit
        """,
    )
    suspend fun linesForScreenshot(screenshotId: Long, limit: Int): List<OcrLineEntity>

    @Query(
        """
        SELECT screenshots.*
        FROM screenshots
        JOIN category_assignments ON category_assignments.screenshotId = screenshots.id
        WHERE category_assignments.category = :category
        ORDER BY screenshots.dateTakenMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun screenshotsForCategory(category: String, limit: Int): List<ScreenshotEntity>

    @Query(
        """
        SELECT screenshots.*
        FROM screenshots
        JOIN category_assignments ON category_assignments.screenshotId = screenshots.id
        WHERE category_assignments.category = :category
          AND (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
        ORDER BY screenshots.dateTakenMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun screenshotsForCategory(
        category: String,
        startMillis: Long?,
        endMillisExclusive: Long?,
        limit: Int,
    ): List<ScreenshotEntity>

    @Query(
        """
        SELECT DISTINCT screenshots.*
        FROM screenshots
        LEFT JOIN detected_entities
          ON detected_entities.screenshotId = screenshots.id
         AND detected_entities.type = 'app'
        WHERE (
            LOWER(screenshots.appHint) = :appSource
            OR LOWER(detected_entities.normalizedValue) = :appSource
        )
          AND (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
        ORDER BY screenshots.dateTakenMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun screenshotsForAppSource(
        appSource: String,
        startMillis: Long?,
        endMillisExclusive: Long?,
        limit: Int,
    ): List<ScreenshotEntity>

    @Query(
        """
        SELECT DISTINCT screenshots.*
        FROM screenshots
        WHERE (
            EXISTS (
                SELECT 1
                FROM visual_labels
                WHERE visual_labels.screenshotId = screenshots.id
                  AND LOWER(visual_labels.label) = :label
                  AND visual_labels.confidence >= :minLabelConfidence
            )
            OR EXISTS (
                SELECT 1
                FROM detected_object_labels
                JOIN detected_objects ON detected_objects.id = detected_object_labels.objectId
                WHERE detected_object_labels.screenshotId = screenshots.id
                  AND LOWER(detected_object_labels.label) = :label
                  AND detected_object_labels.confidence >= :minObjectConfidence
                  AND detected_objects.areaRatio >= :minObjectAreaRatio
            )
        )
          AND (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
        ORDER BY screenshots.dateTakenMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun screenshotsForVisualBucket(
        label: String,
        startMillis: Long?,
        endMillisExclusive: Long?,
        minLabelConfidence: Float,
        minObjectConfidence: Float,
        minObjectAreaRatio: Float,
        limit: Int,
    ): List<ScreenshotEntity>

    @Query(
        """
        SELECT DISTINCT screenshots.*
        FROM screenshots
        JOIN detected_entities ON detected_entities.screenshotId = screenshots.id
        WHERE detected_entities.type = :entityType
          AND (:startMillis IS NULL OR screenshots.dateTakenMillis >= :startMillis)
          AND (:endMillisExclusive IS NULL OR screenshots.dateTakenMillis < :endMillisExclusive)
        ORDER BY screenshots.dateTakenMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun screenshotsForEntityTypeBucket(
        entityType: String,
        startMillis: Long?,
        endMillisExclusive: Long?,
        limit: Int,
    ): List<ScreenshotEntity>

    @Query("DELETE FROM screenshots")
    suspend fun deleteAllScreenshots()

    @Query("DELETE FROM index_runs")
    suspend fun deleteRuns()

    @Query("DELETE FROM index_failures")
    suspend fun deleteFailures()

    @Query("DELETE FROM search_history")
    suspend fun deleteSearchHistory()

    @Query("DELETE FROM mind_map_cache")
    suspend fun deleteMindMapCache()

    @Query("DELETE FROM entity_links")
    suspend fun deleteEntityLinks()

    @Query(
        """
        DELETE FROM screenshots
        WHERE dateTakenMillis >= :startMillis AND dateTakenMillis < :endMillisExclusive
        """,
    )
    suspend fun deleteScreenshotsInRange(startMillis: Long, endMillisExclusive: Long)

    @Transaction
    suspend fun deleteAllIndexData() {
        deleteAllScreenshots()
        deleteRuns()
        deleteFailures()
        deleteSearchHistory()
        deleteMindMapCache()
        deleteEntityLinks()
    }
}
