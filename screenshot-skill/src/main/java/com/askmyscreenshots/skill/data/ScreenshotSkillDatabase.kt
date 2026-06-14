package com.askmyscreenshots.skill.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        IndexRunEntity::class,
        ScreenshotEntity::class,
        ScreenshotFtsEntity::class,
        OcrBlockEntity::class,
        OcrLineEntity::class,
        OcrTokenEntity::class,
        VisualLabelEntity::class,
        DetectedObjectEntity::class,
        DetectedObjectLabelEntity::class,
        BarcodeEntity::class,
        FaceEntity::class,
        DetectedEntityEntity::class,
        CategoryAssignmentEntity::class,
        IndexFailureEntity::class,
        SearchHistoryEntity::class,
        MindMapCacheEntity::class,
        ScreenshotEmbeddingEntity::class,
        VisualDescriptionEntity::class,
        EntityLinkEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class ScreenshotSkillDatabase : RoomDatabase() {
    abstract fun screenshotDao(): ScreenshotSkillDao

    companion object {
        @Volatile
        private var instance: ScreenshotSkillDatabase? = null

        fun get(context: Context): ScreenshotSkillDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ScreenshotSkillDatabase::class.java,
                    "ask-my-screenshots-local-index.db",
                )
                    .openHelperFactory(EncryptedDatabaseFactory.create(context.applicationContext))
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS screenshot_embeddings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        screenshotId INTEGER NOT NULL,
                        modelName TEXT NOT NULL,
                        modelVersion TEXT NOT NULL,
                        inputHash TEXT NOT NULL,
                        dimension INTEGER NOT NULL,
                        vectorBlob BLOB NOT NULL,
                        createdAtMillis INTEGER NOT NULL,
                        FOREIGN KEY(screenshotId) REFERENCES screenshots(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_screenshot_embeddings_screenshotId ON screenshot_embeddings(screenshotId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_screenshot_embeddings_modelName ON screenshot_embeddings(modelName)")
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_screenshot_embeddings_screenshotId_modelName_modelVersion
                    ON screenshot_embeddings(screenshotId, modelName, modelVersion)
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS visual_descriptions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        screenshotId INTEGER NOT NULL,
                        modelName TEXT NOT NULL,
                        modelVersion TEXT NOT NULL,
                        description TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        status TEXT NOT NULL,
                        createdAtMillis INTEGER NOT NULL,
                        FOREIGN KEY(screenshotId) REFERENCES screenshots(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_visual_descriptions_screenshotId ON visual_descriptions(screenshotId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_visual_descriptions_modelName ON visual_descriptions(modelName)")
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_visual_descriptions_screenshotId_modelName_modelVersion
                    ON visual_descriptions(screenshotId, modelName, modelVersion)
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS entity_links (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        leftType TEXT NOT NULL,
                        leftValue TEXT NOT NULL,
                        leftNormalizedValue TEXT NOT NULL,
                        rightType TEXT NOT NULL,
                        rightValue TEXT NOT NULL,
                        rightNormalizedValue TEXT NOT NULL,
                        coOccurrenceCount INTEGER NOT NULL,
                        confidence REAL NOT NULL,
                        firstSeenAtMillis INTEGER NOT NULL,
                        lastSeenAtMillis INTEGER NOT NULL,
                        source TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entity_links_leftType ON entity_links(leftType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entity_links_leftNormalizedValue ON entity_links(leftNormalizedValue)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entity_links_rightType ON entity_links(rightType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entity_links_rightNormalizedValue ON entity_links(rightNormalizedValue)")
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_entity_links_leftType_leftNormalizedValue_rightType_rightNormalizedValue
                    ON entity_links(leftType, leftNormalizedValue, rightType, rightNormalizedValue)
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE visual_labels ADD COLUMN labelIndex INTEGER")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS detected_objects (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        screenshotId INTEGER NOT NULL,
                        objectIndex INTEGER NOT NULL,
                        trackingId INTEGER,
                        left INTEGER NOT NULL,
                        top INTEGER NOT NULL,
                        right INTEGER NOT NULL,
                        bottom INTEGER NOT NULL,
                        areaRatio REAL NOT NULL,
                        FOREIGN KEY(screenshotId) REFERENCES screenshots(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_detected_objects_screenshotId ON detected_objects(screenshotId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_detected_objects_areaRatio ON detected_objects(areaRatio)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS detected_object_labels (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        objectId INTEGER NOT NULL,
                        screenshotId INTEGER NOT NULL,
                        label TEXT NOT NULL,
                        labelIndex INTEGER,
                        confidence REAL NOT NULL,
                        FOREIGN KEY(objectId) REFERENCES detected_objects(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(screenshotId) REFERENCES screenshots(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_detected_object_labels_objectId ON detected_object_labels(objectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_detected_object_labels_screenshotId ON detected_object_labels(screenshotId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_detected_object_labels_label ON detected_object_labels(label)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_detected_object_labels_labelIndex ON detected_object_labels(labelIndex)")
            }
        }
    }
}
