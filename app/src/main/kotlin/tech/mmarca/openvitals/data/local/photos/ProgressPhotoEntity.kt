package tech.mmarca.openvitals.data.local.photos

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One progress photo. The thumbnail is a file under `files/progress_photos`, named by [id]; the full
 * image is there too until [saved], and then only in the folder the user picked, as [fileName].
 */
@Entity(tableName = "progress_photos")
data class ProgressPhotoEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "taken_at_millis") val takenAtMillis: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    val saved: Boolean,
)
