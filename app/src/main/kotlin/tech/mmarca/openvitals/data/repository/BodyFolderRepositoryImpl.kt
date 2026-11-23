package tech.mmarca.openvitals.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import tech.mmarca.openvitals.core.performance.DefaultDispatcherProvider
import tech.mmarca.openvitals.core.performance.DispatcherProvider
import tech.mmarca.openvitals.data.repository.contract.BodyFolderMissingException
import tech.mmarca.openvitals.data.repository.contract.BodyFolderRepository

/**
 * The picked folder lives outside Health Connect: it holds files, which Health Connect has no type
 * for. Its tree URI is kept in a preference file of its own, with a persisted read and write grant,
 * as the offline map import keeps its file's. Every call goes through [DocumentsContract], so a
 * network share from a document provider app works the same as a local folder.
 */
@Singleton
class BodyFolderRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : BodyFolderRepository {
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val resolver: ContentResolver get() = context.contentResolver

    private val _folderUri = MutableStateFlow(storedUriWithGrant())
    override val folderUri: StateFlow<String?> = _folderUri.asStateFlow()

    override suspend fun choose(treeUri: String) = withContext(dispatchers.io) {
        val previous = _folderUri.value
        resolver.takePersistableUriPermission(Uri.parse(treeUri), ReadWrite)
        if (previous != null && previous != treeUri) release(previous)
        prefs.edit { putString(KEY_TREE_URI, treeUri) }
        _folderUri.value = treeUri
    }

    override suspend fun forget() = withContext(dispatchers.io) {
        _folderUri.value?.let(::release)
        prefs.edit { remove(KEY_TREE_URI) }
        _folderUri.value = null
    }

    override suspend fun folderName(): String? = withContext(dispatchers.io) {
        val tree = _folderUri.value?.let(Uri::parse) ?: return@withContext null
        runCatching {
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.query(root, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    override suspend fun write(directory: String?, name: String, mimeType: String, bytes: ByteArray) =
        withContext(dispatchers.io) {
            val tree = _folderUri.value?.let(Uri::parse) ?: throw BodyFolderMissingException()
            val parentId = parentDocumentId(tree, directory, create = true)
                ?: throw IOException("The folder refused a subfolder")
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            val existing = childDocumentId(tree, parentId, name)?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
            if (existing != null && writeTruncating(existing, bytes)) return@withContext
            // A provider without "wt" cannot shorten a file in place, so the file is made again.
            existing?.let { DocumentsContract.deleteDocument(resolver, it) }
            val created = DocumentsContract.createDocument(resolver, parent, mimeType, name)
                ?: throw IOException("The folder refused a new file")
            resolver.openOutputStream(created, "w")?.use { it.write(bytes) }
                ?: throw IOException("The folder refused to open a file")
        }

    override suspend fun delete(directory: String?, name: String) = withContext(dispatchers.io) {
        val tree = _folderUri.value?.let(Uri::parse) ?: return@withContext
        val parentId = parentDocumentId(tree, directory, create = false) ?: return@withContext
        val id = childDocumentId(tree, parentId, name) ?: return@withContext
        DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id))
        Unit
    }

    /** Overwrites in place; false when the provider has no truncating mode. */
    private fun writeTruncating(document: Uri, bytes: ByteArray): Boolean =
        try {
            resolver.openOutputStream(document, "wt")?.use { it.write(bytes) } != null
        } catch (_: FileNotFoundException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }

    private fun parentDocumentId(tree: Uri, directory: String?, create: Boolean): String? {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        if (directory == null) return rootId
        childDocumentId(tree, rootId, directory)?.let { return it }
        if (!create) return null
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val created = DocumentsContract.createDocument(resolver, root, Document.MIME_TYPE_DIR, directory) ?: return null
        return DocumentsContract.getDocumentId(created)
    }

    private fun childDocumentId(tree: Uri, parentId: String, name: String): String? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME)
        return resolver.query(children, projection, null, null, null)?.use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor else null }
                .firstOrNull { it.getString(1) == name }
                ?.getString(0)
        }
    }

    /** The stored folder, unless its grant is gone: the user can revoke it in the system settings. */
    private fun storedUriWithGrant(): String? {
        val stored = prefs.getString(KEY_TREE_URI, null) ?: return null
        val granted = resolver.persistedUriPermissions.any { it.uri.toString() == stored && it.isWritePermission }
        return stored.takeIf { granted }
    }

    private fun release(treeUri: String) {
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(treeUri), ReadWrite) }
    }

    private companion object {
        const val PREFS_FILE = "openvitals_body_folder_preferences"
        const val KEY_TREE_URI = "tree_uri"
        const val ReadWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
