package tech.mmarca.openvitals.data.local.photos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressPhotoDao {
    /** Photos from [startMillis] inclusive to [endMillis] exclusive, newest first. */
    @Query(
        "SELECT * FROM progress_photos WHERE taken_at_millis >= :startMillis AND taken_at_millis < :endMillis " +
            "ORDER BY taken_at_millis DESC"
    )
    suspend fun between(startMillis: Long, endMillis: Long): List<ProgressPhotoEntity>

    @Query("SELECT * FROM progress_photos ORDER BY taken_at_millis ASC")
    suspend fun all(): List<ProgressPhotoEntity>

    /** The photos whose full image is still only on the phone, oldest first. */
    @Query("SELECT * FROM progress_photos WHERE saved = 0 ORDER BY taken_at_millis ASC")
    suspend fun pending(): List<ProgressPhotoEntity>

    @Query("SELECT COUNT(*) FROM progress_photos WHERE saved = 0")
    fun pendingCount(): Flow<Int>

    @Insert
    suspend fun insert(photo: ProgressPhotoEntity)

    @Query("UPDATE progress_photos SET saved = 1 WHERE id = :id")
    suspend fun markSaved(id: String)

    @Query("DELETE FROM progress_photos WHERE id = :id")
    suspend fun delete(id: String)
}
