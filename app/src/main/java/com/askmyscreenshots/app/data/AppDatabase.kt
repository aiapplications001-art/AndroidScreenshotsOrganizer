package com.askmyscreenshots.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        OrganizationRunEntity::class,
        ScreenshotCandidateEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun organizationDao(): OrganizationDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ask-my-screenshots.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE organization_runs ADD COLUMN indexedCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE organization_runs ADD COLUMN failedCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE organization_runs ADD COLUMN completedAtMillis INTEGER")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE organization_runs ADD COLUMN newlyIndexedCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE organization_runs ADD COLUMN skippedCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE organization_runs SET status = 'DONE' WHERE status = 'STARTED' AND completedAtMillis IS NOT NULL")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE organization_runs ADD COLUMN processedCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE organization_runs ADD COLUMN lastProgressAtMillis INTEGER")
                db.execSQL("ALTER TABLE organization_runs ADD COLUMN workId TEXT")
                db.execSQL("ALTER TABLE organization_runs ADD COLUMN workName TEXT")
                db.execSQL(
                    """
                    UPDATE organization_runs
                    SET processedCount = CASE
                        WHEN status IN ('DONE', 'UP_TO_DATE') THEN candidateCount
                        ELSE indexedCount
                    END,
                    lastProgressAtMillis = completedAtMillis
                    """.trimIndent(),
                )
            }
        }
    }
}
