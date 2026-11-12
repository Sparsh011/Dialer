package com.sparsh.dialer.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One row per call. `id` mirrors the system call-log id so syncs are idempotent. */
@Immutable
@Entity(tableName = "calls", indices = [Index("numKey"), Index("date")])
data class CallEntity(
    @PrimaryKey val id: Long,
    val number: String,
    /** Normalised matching key (last 10 digits) so +91 / 0-prefixed variants collapse together. */
    val numKey: String,
    val cachedName: String?,
    val type: Int,
    val date: Long,
    val duration: Long,
    val accountId: String?,
    val features: Int,
)

@Immutable
@Entity(tableName = "recordings", indices = [Index("numKey"), Index("startedAt")])
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val numKey: String,
    val name: String?,
    val startedAt: Long,
    val durationMs: Long,
    val path: String,
)

@Dao
interface CallDao {
    @Query("SELECT * FROM calls ORDER BY date DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CallEntity>>

    @Query("SELECT * FROM calls WHERE numKey IN (:keys) ORDER BY date DESC")
    fun observeFor(keys: List<String>): Flow<List<CallEntity>>

    @Query("SELECT * FROM calls WHERE numKey = :key ORDER BY date DESC LIMIT 1")
    suspend fun last(key: String): CallEntity?

    @Query("SELECT accountId FROM calls WHERE numKey = :key AND accountId IS NOT NULL AND accountId != '' ORDER BY date DESC, id DESC LIMIT 1")
    suspend fun lastAccountFor(key: String): String?

    @Query("SELECT COUNT(*) FROM calls WHERE numKey = :key")
    suspend fun countFor(key: String): Int

    @Query("SELECT * FROM calls WHERE type = 2 ORDER BY date DESC LIMIT 1")
    suspend fun lastOutgoing(): CallEntity?

    @Query("SELECT id FROM calls WHERE date >= :since")
    suspend fun idsSince(since: Long): List<Long>

    @Upsert
    suspend fun upsert(list: List<CallEntity>)

    @Query("DELETE FROM calls WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT * FROM recordings ORDER BY startedAt DESC")
    fun observeRecordings(): Flow<List<RecordingEntity>>

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteRecording(id: Long)
}

@Database(entities = [CallEntity::class, RecordingEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun calls(): CallDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "dialer.db")
                .fallbackToDestructiveMigration(true)
                .build().also { instance = it }
        }
    }
}
