package com.mark.timerboard

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

@Entity(tableName = "timer_presets")
data class TimerPresetEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val durationMillis: Long,
    val color: Long,
    val alarmId: String,
    val alarmUri: String?,
    val sortOrder: Int,
    val mode: String,
    val warmupMillis: Long,
    val workMillis: Long,
    val restMillis: Long,
    val cooldownMillis: Long,
    val rounds: Int
)

@Entity(tableName = "timer_history")
data class TimerHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val presetId: Long,
    val name: String,
    val completedAtMillis: Long,
    val durationMillis: Long,
    val mode: String
)

data class CompletionSummary(
    val count: Int = 0,
    val totalMillis: Long = 0L
)

@Dao
interface TimerPresetDao {
    @Query("SELECT * FROM timer_presets ORDER BY sortOrder ASC")
    suspend fun getAll(): List<TimerPresetEntity>

    @Query("DELETE FROM timer_presets")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(presets: List<TimerPresetEntity>)
}

@Dao
interface TimerHistoryDao {
    @Insert
    suspend fun insert(history: TimerHistoryEntity)

    @Query("SELECT COUNT(*) FROM timer_history WHERE completedAtMillis >= :startMillis")
    suspend fun countSince(startMillis: Long): Int

    @Query("SELECT COALESCE(SUM(durationMillis), 0) FROM timer_history WHERE completedAtMillis >= :startMillis")
    suspend fun totalDurationSince(startMillis: Long): Long
}

@Database(
    entities = [TimerPresetEntity::class, TimerHistoryEntity::class],
    version = 3,
    exportSchema = false
)
abstract class TimerBoardDatabase : RoomDatabase() {
    abstract fun timerPresetDao(): TimerPresetDao
    abstract fun timerHistoryDao(): TimerHistoryDao

    companion object {
        @Volatile
        private var instance: TimerBoardDatabase? = null

        fun get(context: Context): TimerBoardDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TimerBoardDatabase::class.java,
                    "timer_board.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE timer_presets ADD COLUMN mode TEXT NOT NULL DEFAULT 'countdown'")
                db.execSQL("ALTER TABLE timer_presets ADD COLUMN warmupMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE timer_presets ADD COLUMN workMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE timer_presets ADD COLUMN restMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE timer_presets ADD COLUMN cooldownMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE timer_presets ADD COLUMN rounds INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS timer_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        presetId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        completedAtMillis INTEGER NOT NULL,
                        durationMillis INTEGER NOT NULL,
                        mode TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}

class TimerRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = TimerBoardDatabase.get(appContext)
    private val dao = database.timerPresetDao()
    private val historyDao = database.timerHistoryDao()
    private val prefs = appContext.getSharedPreferences("timer_board", Context.MODE_PRIVATE)

    suspend fun loadPresets(): List<TimerPreset> {
        val savedPresets = dao.getAll().map { it.toPreset() }
        if (savedPresets.isNotEmpty() || prefs.getBoolean(KEY_ROOM_INITIALIZED, false)) {
            return savedPresets
        }

        val migratedPresets = loadLegacyPresets()
        savePresets(migratedPresets)
        prefs.edit().putBoolean(KEY_ROOM_INITIALIZED, true).apply()
        return migratedPresets
    }

    suspend fun savePresets(presets: List<TimerPreset>) {
        database.withTransaction {
            dao.deleteAll()
            dao.insertAll(presets.mapIndexed { index, preset -> preset.toEntity(index) })
        }
        prefs.edit().putBoolean(KEY_ROOM_INITIALIZED, true).apply()
    }

    suspend fun recordCompletion(preset: TimerPreset) {
        historyDao.insert(
            TimerHistoryEntity(
                presetId = preset.id,
                name = preset.name,
                completedAtMillis = System.currentTimeMillis(),
                durationMillis = preset.totalDurationMillis(),
                mode = preset.mode
            )
        )
    }

    suspend fun todaySummary(): CompletionSummary {
        val startOfDayMillis = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return CompletionSummary(
            count = historyDao.countSince(startOfDayMillis),
            totalMillis = historyDao.totalDurationSince(startOfDayMillis)
        )
    }

    fun restoreRuntimeState(presets: List<TimerPreset>): List<TimerItem> {
        val snapshots = loadRuntimeSnapshots()
        val nowWallTime = System.currentTimeMillis()
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        return presets.map { preset ->
            val snapshot = snapshots[preset.id] ?: return@map TimerItem(preset)
            val total = preset.totalDurationMillis()
            val remaining = when {
                snapshot.isRunning && snapshot.endWallTimeMillis > nowWallTime ->
                    snapshot.endWallTimeMillis - nowWallTime
                snapshot.isRunning -> 0L
                else -> snapshot.remainingMillis
            }.coerceIn(0L, total)
            TimerItem(
                preset = preset,
                remainingMillis = remaining,
                isRunning = snapshot.isRunning && remaining > 0L,
                endElapsedRealtime = if (snapshot.isRunning && remaining > 0L) {
                    nowElapsed + remaining
                } else {
                    0L
                }
            )
        }
    }

    fun saveRuntimeState(timers: List<TimerItem>) {
        val nowWallTime = System.currentTimeMillis()
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val array = JSONArray()
        timers.filter { timer ->
            timer.isRunning || timer.remainingMillis != timer.preset.totalDurationMillis()
        }.forEach { timer ->
            val remaining = if (timer.isRunning) {
                (timer.endElapsedRealtime - nowElapsed).coerceAtLeast(0L)
            } else {
                timer.remainingMillis
            }
            array.put(
                JSONObject()
                    .put("id", timer.preset.id)
                    .put("remainingMillis", remaining)
                    .put("isRunning", timer.isRunning)
                    .put("endWallTimeMillis", if (timer.isRunning) nowWallTime + remaining else 0L)
            )
        }
        prefs.edit().putString(KEY_RUNTIME_STATE, array.toString()).apply()
    }

    private fun loadRuntimeSnapshots(): Map<Long, RuntimeSnapshot> {
        val raw = prefs.getString(KEY_RUNTIME_STATE, null) ?: return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            buildMap {
                repeat(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    put(
                        item.getLong("id"),
                        RuntimeSnapshot(
                            remainingMillis = item.getLong("remainingMillis"),
                            isRunning = item.getBoolean("isRunning"),
                            endWallTimeMillis = item.optLong("endWallTimeMillis", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun loadLegacyPresets(): List<TimerPreset> {
        val raw = prefs.getString("presets", null) ?: return defaultPresets()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                TimerPreset(
                    id = item.getLong("id"),
                    name = item.getString("name"),
                    durationMillis = item.getLong("durationMillis"),
                    color = item.getLong("color"),
                    alarmId = alarmById(item.optString("alarmId", DEFAULT_ALARM_ID)).id,
                    alarmUri = item.optString("alarmUri", "").ifBlank { null },
                    mode = TIMER_MODE_COUNTDOWN
                )
            }
        }.getOrDefault(defaultPresets())
    }

    private fun defaultPresets(): List<TimerPreset> = listOf(
        TimerPreset(1L, "Coffee", 4.minutes, 0xFF8B5E3C, "chime"),
        TimerPreset(2L, "Stretch", 10.minutes, 0xFF2F7D69, "soft"),
        TimerPreset(3L, "Focus", 25.minutes, 0xFF365D8C, "chime")
    )

    private fun TimerPresetEntity.toPreset(): TimerPreset {
        return TimerPreset(
            id = id,
            name = name,
            durationMillis = durationMillis,
            color = color,
            alarmId = alarmById(alarmId).id,
            alarmUri = alarmUri,
            mode = mode,
            warmupMillis = warmupMillis,
            workMillis = workMillis,
            restMillis = restMillis,
            cooldownMillis = cooldownMillis,
            rounds = rounds
        )
    }

    private fun TimerPreset.toEntity(sortOrder: Int): TimerPresetEntity {
        return TimerPresetEntity(
            id = id,
            name = name,
            durationMillis = durationMillis,
            color = color,
            alarmId = alarmById(alarmId).id,
            alarmUri = alarmUri,
            sortOrder = sortOrder,
            mode = mode,
            warmupMillis = warmupMillis,
            workMillis = workMillis,
            restMillis = restMillis,
            cooldownMillis = cooldownMillis,
            rounds = rounds
        )
    }

    private companion object {
        const val KEY_ROOM_INITIALIZED = "room_initialized"
        const val KEY_RUNTIME_STATE = "runtime_state"
    }
}

private data class RuntimeSnapshot(
    val remainingMillis: Long,
    val isRunning: Boolean,
    val endWallTimeMillis: Long
)
