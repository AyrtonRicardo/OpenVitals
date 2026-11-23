package tech.mmarca.openvitals.data.repository.contract

import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory stand-in for [BodyFolderRepository]: files are keyed by "directory/name". */
class FakeBodyFolderRepository(
    initialUri: String? = null,
    var name: String? = "Body",
) : BodyFolderRepository {
    private val uri = MutableStateFlow(initialUri)
    override val folderUri: StateFlow<String?> = uri

    val files = linkedMapOf<String, ByteArray>()
    val deleted = mutableListOf<String>()

    /** Set to make every write fail, as an offline share does. */
    var offline: Boolean = false

    /** Names whose write fails even while online. */
    val refusedNames = mutableSetOf<String>()

    var failChoose: Throwable? = null

    fun text(path: String): String? = files[path]?.toString(Charsets.UTF_8)

    override suspend fun choose(treeUri: String) {
        failChoose?.let { throw it }
        uri.value = treeUri
    }

    override suspend fun forget() {
        uri.value = null
    }

    override suspend fun folderName(): String? = name

    override suspend fun write(directory: String?, name: String, mimeType: String, bytes: ByteArray) {
        if (uri.value == null) throw BodyFolderMissingException()
        if (offline || name in refusedNames) throw IOException("The share is offline")
        files[path(directory, name)] = bytes
    }

    override suspend fun delete(directory: String?, name: String) {
        if (offline) throw IOException("The share is offline")
        val path = path(directory, name)
        files.remove(path)
        deleted += path
    }

    private fun path(directory: String?, name: String) = if (directory == null) name else "$directory/$name"
}
