package com.swan1127.repland

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.swan1127.repland.data.room.ReplandDatabase
import com.swan1127.repland.data.room.RoomTaskRepository
import com.swan1127.repland.domain.model.TaskCategory
import com.swan1127.repland.domain.model.TaskDraft
import com.swan1127.repland.domain.model.TaskOrigin
import com.swan1127.repland.domain.model.TaskPriority
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 24 → 25：只给 tasks 加来源列，存量任务一律 PERSONAL，团队镜像可写 TEAM。 */
class Migration24To25Test {
    @Test fun upgrading_a_24_database_adds_origin_columns_without_losing_tasks() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "legacy24-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, ReplandDatabase::class.java, name).build()
        try {
            RoomTaskRepository(db).save(
                TaskDraft("kept", "旧任务", "", TaskCategory.COURSE, TaskPriority.MEDIUM, 1, 45, null),
            )
            db.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.beginTransaction()
                try {
                    old.execSQL("ALTER TABLE tasks DROP COLUMN origin")
                    old.execSQL("ALTER TABLE tasks DROP COLUMN originRef")
                    old.execSQL("ALTER TABLE tasks DROP COLUMN originLabel")
                    old.version = 24
                    old.setTransactionSuccessful()
                } finally { old.endTransaction() }
            }

            db = Room.databaseBuilder(context, ReplandDatabase::class.java, name)
                .addMigrations(ReplandDatabase.MIGRATION_24_25).build()
            val migrated = RoomTaskRepository(db).observeTasks().first().single()
            assertEquals("旧任务", migrated.displayName)
            assertEquals(TaskOrigin.PERSONAL, migrated.origin)
            assertNull(migrated.originRef)
            assertNull(migrated.originLabel)

            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO tasks (id, description, displayName, category, userPriority, estimatedDays, " +
                    "totalDurationMinutes, dueDateEpochDay, status, completionSummary, actualDurationMinutes, " +
                    "progressPercent, postponeCount, createdAtEpochMillis, updatedAtEpochMillis, completionResult, " +
                    "scheduledForEpochDay, inputSources, origin, originRef, originLabel) VALUES " +
                    "('team:team-1:t1', '', '市场调研', 'EXTRACURRICULAR', 'REQUIRED', NULL, 480, NULL, " +
                    "'NOT_STARTED', NULL, NULL, NULL, 0, 1, 1, NULL, NULL, '', 'TEAM', 'team-1', '星火队')",
            )
            val mirror = RoomTaskRepository(db).observeTasks().first().single { it.id == "team:team-1:t1" }
            assertTrue(mirror.isTeamMirror)
            assertEquals("team-1", mirror.originRef)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
