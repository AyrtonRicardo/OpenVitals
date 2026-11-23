package tech.mmarca.openvitals.data.repository.contract

import kotlinx.coroutines.flow.StateFlow

/**
 * The folder the user picked for body measurements: the full progress photos and the exported
 * measurements go there, so another device on the home network can read them. It is a Storage
 * Access Framework tree, so it may be a network share from a document provider app, and a write
 * can fail while that share is offline.
 */
interface BodyFolderRepository {
    /** The picked folder's tree URI, or null before one is picked. */
    val folderUri: StateFlow<String?>

    /** Keeps [treeUri] with read and write access, replacing and releasing an earlier folder. */
    suspend fun choose(treeUri: String)

    /** Releases the folder. Its files stay where they are. */
    suspend fun forget()

    /** The folder's name as its provider shows it, or null when the provider cannot answer. */
    suspend fun folderName(): String?

    /**
     * Writes [bytes] as [name] in [directory] under the folder, or in the folder itself when
     * [directory] is null, replacing a file of that name. Throws [BodyFolderMissingException]
     * before a folder is picked, and an I/O error when the provider refuses.
     */
    suspend fun write(directory: String?, name: String, mimeType: String, bytes: ByteArray)

    /** Removes [name] from [directory] (the folder itself when null); a missing file is not an error. */
    suspend fun delete(directory: String?, name: String)
}

/** Raised by a write before the user has picked a folder. */
class BodyFolderMissingException : IllegalStateException("No body measurements folder is picked")
