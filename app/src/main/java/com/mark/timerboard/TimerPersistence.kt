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
import org.json.JSONArray

@Entity(tableName = "timer_presets")
data class TimerPresetEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val durationMillis: Long,
    val color: Long,
    val alarmId: String,
    val alarmUri: String?,
    val sortOrder: Int
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

@Database(
    entities = [TimerPresetEntity::class],
    version = 1,
    exportSchema = false
)
abstract class TimerBoardDatabase : RoomDatabase() {
    abstract fun timerPresetDao(): TimerPresetDao

    companion object {
        @Volatile
        private var instance: TimerBoardDatabase? = null

        fun get(context: Context): TimerBoardDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TimerBoardDatabase::class.java,
                    "timer_board.db"
                ).build().also { instance = it }
            }
        }
    }
}

class TimerRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = TimerBoardDatabase.get(appContext)
    private val dao = database.timerPresetDao()
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
                    alarmUri = item.optString("alarmUri", "").ifBlank { null }
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
            alarmUri = alarmUri
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
            sortOrder = sortOrder
        )
    }

    private companion object {
        const val KEY_ROOM_INITIALIZED = "room_initialized"
    }
}
