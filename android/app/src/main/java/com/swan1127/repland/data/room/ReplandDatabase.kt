package com.swan1127.repland.data.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TaskEntity::class,
        WeeklyTimeBlockEntity::class,
        DateOverrideEntity::class,
        SemesterSettingsEntity::class,
        ClassPeriodSettingsEntity::class,
        PlanEntity::class,
        PlanSegmentEntity::class,
        PlanTaskOrderEntity::class,
        CategoryPreferenceEntity::class,
        ExecutionLogEntity::class,
        ReminderSettingsEntity::class,
        ProfileEvidenceEntity::class,
        AiSettingsEntity::class,
        EngagementSettingsEntity::class,
        UsageEventEntity::class,
        PlanningWorkspaceEntity::class,
        TeamSessionEntity::class,
        TeamSettingsEntity::class,
        CompetitionTrackingEntity::class,
        CompetitionAwardEntity::class,
    ],
    version = 30,
    exportSchema = false,
)
abstract class ReplandDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    abstract fun timeDao(): TimeDao

    abstract fun planDao(): PlanDao

    abstract fun categoryPreferenceDao(): CategoryPreferenceDao

    abstract fun executionLogDao(): ExecutionLogDao

    abstract fun reminderSettingsDao(): ReminderSettingsDao

    abstract fun profileEvidenceDao(): ProfileEvidenceDao

    abstract fun aiSettingsDao(): AiSettingsDao
    abstract fun engagementDao(): EngagementDao
    abstract fun planningWorkspaceDao(): PlanningWorkspaceDao
    abstract fun teamSessionDao(): TeamSessionDao
    abstract fun competitionTrackingDao(): CompetitionTrackingDao
    abstract fun competitionAwardDao(): CompetitionAwardDao

    companion object {
        /**
         * 段来源与稳定身份。存量段一律标 USER_PINNED：升级那一下不能让引擎把用户
         * 既有计划当作无主猜测重排；需要重排时由用户主动清空或移出。
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE plan_segments ADD COLUMN authority TEXT NOT NULL DEFAULT 'USER_PINNED'")
                db.execSQL("ALTER TABLE plan_segments ADD COLUMN stableKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE plan_segments SET stableKey = id WHERE stableKey = ''")
            }
        }

        /**
         * team 域首表（一队一行整份快照 + 激活指针）。
         * 1.0.0 的 23 版库里可能已经有这两张表，所以 IF NOT EXISTS：从任一 23 版升上来都能落到同一结构。
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS team_sessions (
                        teamId TEXT NOT NULL,
                        teamName TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(teamId)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS team_settings (
                        id TEXT NOT NULL,
                        activeTeamId TEXT NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
            }
        }

        /** 个人任务库接纳团队镜像：来源列只加不删，存量任务一律 PERSONAL。 */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN origin TEXT NOT NULL DEFAULT 'PERSONAL'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN originRef TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN originLabel TEXT")
            }
        }

        /** 竞赛追踪首表：截止日期是本地覆盖列，用户改了就不被种子窗口刷新碰。 */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS competition_tracking (
                        instanceKey TEXT NOT NULL,
                        title TEXT NOT NULL,
                        level TEXT NOT NULL,
                        catalogueKey TEXT NOT NULL,
                        status TEXT NOT NULL,
                        deadlineEpochDay INTEGER,
                        remindEnabled INTEGER NOT NULL,
                        note TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(instanceKey)
                    )
                """.trimIndent())
            }
        }

        /** 获奖登记首表：个人履历线，与竞赛卡同步线分开。 */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS competition_awards (
                        awardId TEXT NOT NULL,
                        instanceKey TEXT NOT NULL,
                        title TEXT NOT NULL,
                        catalogueKey TEXT NOT NULL,
                        level TEXT NOT NULL,
                        grade TEXT NOT NULL,
                        awardYear INTEGER NOT NULL,
                        isTeam INTEGER NOT NULL,
                        teamRank INTEGER NOT NULL,
                        teammates TEXT NOT NULL,
                        note TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(awardId)
                    )
                """.trimIndent())
            }
        }

        /** 每事件可调提前量；存量行留空 = 继续用全局默认 10 分钟。 */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE plan_segments ADD COLUMN leadMinutes INTEGER")
                db.execSQL("ALTER TABLE weekly_time_blocks ADD COLUMN leadMinutes INTEGER")
                db.execSQL("ALTER TABLE date_overrides ADD COLUMN leadMinutes INTEGER")
            }
        }

        /**
         * 三类事件形态 + 重要度点尺 + 标签。
         * 存量行 horizon 留 UNSPECIFIED（按一天内对待）、标签留空（收纳里归"未标签"），
         * 不替用户猜他以前记的事属于哪一类。
         */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN horizon TEXT NOT NULL DEFAULT 'UNSPECIFIED'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN atMinute INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN dailyInvestMinutes INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN milestones TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tasks ADD COLUMN importance INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 节次时间表：空的表表示沿用默认八节作息，首次保存后写入用户自己的钟点。 */
        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS class_period_settings (
                        id TEXT NOT NULL,
                        periods TEXT NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
            }
        }

        /** Per-event alarm switches; existing rows keep reminders off. */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE plan_segments ADD COLUMN reminderEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE weekly_time_blocks ADD COLUMN reminderEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE date_overrides ADD COLUMN reminderEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Preserve legacy values and history, while separating them from confirmed input. */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE tasks_v21 (
                        id TEXT NOT NULL PRIMARY KEY, description TEXT NOT NULL, displayName TEXT NOT NULL,
                        category TEXT NOT NULL, userPriority TEXT NOT NULL, estimatedDays INTEGER,
                        totalDurationMinutes INTEGER, dueDateEpochDay INTEGER, status TEXT NOT NULL,
                        completionSummary TEXT, actualDurationMinutes INTEGER, progressPercent INTEGER,
                        postponeCount INTEGER NOT NULL, createdAtEpochMillis INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL, completionResult TEXT, scheduledForEpochDay INTEGER,
                        inputSources TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO tasks_v21 (id, description, displayName, category, userPriority, estimatedDays,
                        totalDurationMinutes, dueDateEpochDay, status, completionSummary, actualDurationMinutes,
                        progressPercent, postponeCount, createdAtEpochMillis, updatedAtEpochMillis,
                        completionResult, scheduledForEpochDay)
                    SELECT id, description, displayName, category, userPriority, estimatedDays,
                        totalDurationMinutes, dueDateEpochDay, status, completionSummary, actualDurationMinutes,
                        progressPercent, postponeCount, createdAtEpochMillis, updatedAtEpochMillis,
                        completionResult, scheduledForEpochDay FROM tasks
                """.trimIndent())
                db.execSQL("DROP TABLE tasks")
                db.execSQL("ALTER TABLE tasks_v21 RENAME TO tasks")
            }
        }
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS planning_workspace (`key` TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(`key`))")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS weekly_time_blocks (
                        id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        dayOfWeek INTEGER NOT NULL,
                        startMinute INTEGER NOT NULL,
                        endMinute INTEGER NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS date_overrides (
                        id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        type TEXT NOT NULL,
                        dateEpochDay INTEGER NOT NULL,
                        startMinute INTEGER NOT NULL,
                        endMinute INTEGER NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE weekly_time_blocks ADD COLUMN weekPattern TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS plans (
                        id TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        isCurrent INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS plan_segments (
                        id TEXT NOT NULL,
                        planId TEXT NOT NULL,
                        taskId TEXT NOT NULL,
                        dateEpochDay INTEGER NOT NULL,
                        startMinute INTEGER NOT NULL,
                        endMinute INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_plan_segments_planId ON plan_segments(planId)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS semester_settings (
                        id TEXT NOT NULL,
                        firstWeekMondayEpochDay INTEGER,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Adds immutable execution evidence without rewriting tasks, plans, or time
         * constraints already stored on-device. Existing task feedback stays intact
         * in the tasks table and new feedback is appended to execution_logs.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN completionResult TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS execution_logs (
                        id TEXT NOT NULL,
                        taskId TEXT NOT NULL,
                        eventType TEXT NOT NULL,
                        confirmedStatus TEXT NOT NULL,
                        actualDurationMinutes INTEGER,
                        progressPercent INTEGER,
                        completedContent TEXT,
                        completionResult TEXT,
                        postponeReason TEXT,
                        correctedLogId TEXT,
                        replacementTaskId TEXT,
                        createdAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_execution_logs_taskId_createdAtEpochMillis
                    ON execution_logs(taskId, createdAtEpochMillis)
                    """.trimIndent(),
                )
            }
        }

        /** Persists user-owned locks on confirmed plan segments without rewriting plans. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE plan_segments ADD COLUMN isLocked INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /** Stores the user's reviewed task order with each plan version. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS plan_task_order (
                        planId TEXT NOT NULL,
                        taskId TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        PRIMARY KEY(planId, taskId)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_plan_task_order_planId ON plan_task_order(planId)",
                )
            }
        }

        /** Marks whether a persisted task order was explicitly arranged by the user. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE plan_task_order ADD COLUMN isManual INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /** Persists local category preferences used by the deterministic fallback ranker. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS category_preferences (
                        category TEXT NOT NULL,
                        weight INTEGER NOT NULL,
                        PRIMARY KEY(category)
                    )
                    """.trimIndent(),
                )
            }
        }

        /** Adds a durable revision used for all user-confirmed time-constraint changes. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE semester_settings ADD COLUMN updatedAtEpochMillis INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /** Adds an opt-in local reminder setting; existing users remain opted out. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reminder_settings (
                        id TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
            }
        }

        /** Stores inspectable local profile evidence without touching existing user data. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS profile_evidence (
                        id TEXT NOT NULL,
                        scope TEXT NOT NULL,
                        conclusion TEXT NOT NULL,
                        sourceLogIds TEXT NOT NULL,
                        sourceFingerprint TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        isUserEdited INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_profile_evidence_sourceFingerprint
                    ON profile_evidence(sourceFingerprint)
                    """.trimIndent(),
                )
            }
        }

        /** Adds opt-in consent state only; existing users remain offline and AI-disabled. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ai_settings (
                        id TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL DEFAULT 0,
                        consentedAtEpochMillis INTEGER,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS engagement_settings (id TEXT NOT NULL, mode TEXT NOT NULL, updatedAtEpochMillis INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS usage_events (id TEXT NOT NULL, type TEXT NOT NULL, occurredAtEpochMillis INTEGER NOT NULL, mode TEXT NOT NULL, subjectId TEXT, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_usage_events_occurredAtEpochMillis ON usage_events(occurredAtEpochMillis)")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE plan_segments ADD COLUMN trackId TEXT NOT NULL DEFAULT 'focus'")
            }
        }

        /** Keeps recurring courses on their chosen visual lane across app restarts. */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE weekly_time_blocks ADD COLUMN trackId TEXT NOT NULL DEFAULT 'course'")
            }
        }

        /** Adds optional user-owned context without changing the scheduling semantics. */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE weekly_time_blocks ADD COLUMN note TEXT")
                db.execSQL("ALTER TABLE date_overrides ADD COLUMN note TEXT")
            }
        }

        /** Separates a user's planned workday from a true last-completion deadline. */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN scheduledForEpochDay INTEGER")
            }
        }
    }
}
