package tech.mmarca.openvitals.data.local.circumference

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BodyCircumferenceDao {
    /** One site's measurements from [startMillis] inclusive to [endMillis] exclusive, oldest first. */
    @Query(
        "SELECT * FROM body_circumferences WHERE site = :site " +
            "AND measured_at_millis >= :startMillis AND measured_at_millis < :endMillis " +
            "ORDER BY measured_at_millis ASC"
    )
    suspend fun between(site: String, startMillis: Long, endMillis: Long): List<BodyCircumferenceEntity>

    /** The newest measurement of one site, re-emitted whenever the table changes. */
    @Query("SELECT * FROM body_circumferences WHERE site = :site ORDER BY measured_at_millis DESC LIMIT 1")
    fun latest(site: String): Flow<BodyCircumferenceEntity?>

    /** Every measurement, oldest first, re-emitted whenever the table changes. */
    @Query("SELECT * FROM body_circumferences ORDER BY measured_at_millis ASC")
    fun all(): Flow<List<BodyCircumferenceEntity>>

    /** Every site's measurements from [startMillis] inclusive to [endMillis] exclusive, oldest first. */
    @Query(
        "SELECT * FROM body_circumferences " +
            "WHERE measured_at_millis >= :startMillis AND measured_at_millis < :endMillis " +
            "ORDER BY measured_at_millis ASC"
    )
    suspend fun allBetween(startMillis: Long, endMillis: Long): List<BodyCircumferenceEntity>

    @Query(
        "DELETE FROM body_circumferences " +
            "WHERE site = :site AND measured_at_millis >= :startMillis AND measured_at_millis < :endMillis"
    )
    suspend fun deleteBetween(site: String, startMillis: Long, endMillis: Long)

    /** Each entry replaces whatever its site holds from [startMillis] to [endMillis], all or none. */
    @Transaction
    suspend fun replaceDay(startMillis: Long, endMillis: Long, entries: List<BodyCircumferenceEntity>) {
        entries.forEach { entry ->
            deleteBetween(entry.site, startMillis, endMillis)
            upsert(entry)
        }
    }

    @Upsert
    suspend fun upsert(entry: BodyCircumferenceEntity)

    @Query("DELETE FROM body_circumferences WHERE site = :site AND measured_at_millis = :measuredAtMillis")
    suspend fun delete(site: String, measuredAtMillis: Long)
}
