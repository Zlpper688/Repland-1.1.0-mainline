package com.swan1127.repland

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.swan1127.repland.data.room.*
import com.swan1127.repland.domain.model.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 1.0.0 的 23 版库有团队表、0.1.35 的 23 版库没有，两条升级路都得落到同一结构且旧数据不丢。 */
class Migration23To24Test {
    @Test fun upgrading_a_23_database_without_team_tables_keeps_existing_data() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "legacy23-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, ReplandDatabase::class.java, name).build()
        try {
            RoomTaskRepository(db).save(TaskDraft("kept", "旧任务", "", TaskCategory.COURSE,
                TaskPriority.MEDIUM, 1, 45, null))
            db.close()
            // 当前 Room 建的是 24 版完整结构；抹掉团队表并退回 23，模拟 0.1.35 那份库。
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.beginTransaction()
                try {
                    old.execSQL("DROP TABLE IF EXISTS team_sessions")
                    old.execSQL("DROP TABLE IF EXISTS team_settings")
                    // v25 才有的镜像来源列也剔掉，否则重开时 24→25 会撞上已有的列。
                    old.execSQL("ALTER TABLE tasks DROP COLUMN origin")
                    old.execSQL("ALTER TABLE tasks DROP COLUMN originRef")
                    old.execSQL("ALTER TABLE tasks DROP COLUMN originLabel")
                    old.version = 23
                    old.setTransactionSuccessful()
                } finally { old.endTransaction() }
            }
            db = Room.databaseBuilder(context, ReplandDatabase::class.java, name)
                .addMigrations(ReplandDatabase.MIGRATION_23_24, ReplandDatabase.MIGRATION_24_25).build()
            assertEquals("旧任务", RoomTaskRepository(db).observeTasks().first().single().displayName)
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO team_settings (id, activeTeamId, updatedAtEpochMillis) VALUES ('default', 't1', 1)")
            assertEquals("t1", db.teamSessionDao().getSetting("default")?.activeTeamId)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun upgrading_a_23_database_that_already_has_team_tables_keeps_its_rows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "legacy23team-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, ReplandDatabase::class.java, name).build()
        try {
            val entity = TeamSessionEntity("t1", "旧队", "{\"version\":1}", 1, 2)
            db.teamSessionDao().upsert(entity)
            db.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.beginTransaction()
                try {
                    old.execSQL("ALTER TABLE tasks DROP COLUMN origin")
                    old.execSQL("ALTER TABLE tasks DROP COLUMN originRef")
                    old.execSQL("ALTER TABLE tasks DROP COLUMN originLabel")
                    old.version = 23
                    old.setTransactionSuccessful()
                } finally { old.endTransaction() }
            }
            db = Room.databaseBuilder(context, ReplandDatabase::class.java, name)
                .addMigrations(ReplandDatabase.MIGRATION_23_24, ReplandDatabase.MIGRATION_24_25).build()
            assertEquals(entity, db.teamSessionDao().get("t1"))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
