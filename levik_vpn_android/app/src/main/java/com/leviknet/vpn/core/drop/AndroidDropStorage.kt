package com.leviknet.vpn.core.drop

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import com.leviknet.vpn.core.security.SecureFileStore
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** LevikDrop's identity key in the Android Keystore: it signs handshakes and never leaves the phone. */
class KeystoreDropIdentity private constructor(private val privateKey: PrivateKey, override val spki: ByteArray) : DropIdentity {
    override fun sign(payload: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey)
        update(payload)
        sign()
    }

    companion object {
        private const val ALIAS = "levik_drop_identity_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        fun load(): KeystoreDropIdentity {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
            if (existing != null) return KeystoreDropIdentity(existing.privateKey, existing.certificate.publicKey.encoded)
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            val pair = generator.generateKeyPair()
            return KeystoreDropIdentity(pair.private, pair.public.encoded)
        }
    }
}

class SecureDropSecrets(private val store: SecureFileStore) : DropSecrets {
    override fun get(name: String): ByteArray? = store.get(name)

    override fun put(name: String, value: ByteArray) = store.put(name, value)
}

/**
 * Files the user picked or shared: content:// only. Another app could share a
 * file:// path or one of Levik's own files; those are refused.
 */
class ContentDropSources(private val context: Context) : DropSources {
    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun describe(refs: List<String>): List<SourceFile> = withContext(Dispatchers.IO) {
        refs.map { ref ->
            val uri = Uri.parse(ref)
            if (!acceptable(uri)) throw DropUserError(DropUserError.Kind.UNREADABLE, null)
            val name = query(uri, OpenableColumns.DISPLAY_NAME)?.takeIf(String::isNotBlank) ?: uri.lastPathSegment ?: "file"
            if (isDirectory(uri)) throw DropUserError(DropUserError.Kind.NOT_A_FILE, name)
            val now = stat(uri) ?: throw DropUserError(DropUserError.Kind.UNREADABLE, name)
            SourceFile(ref, name, now.size, now.modifiedMs)
        }
    }

    override fun stat(file: SourceFile): SourceStat? = stat(Uri.parse(file.ref))

    override fun open(file: SourceFile): InputStream {
        val uri = Uri.parse(file.ref)
        check(acceptable(uri)) { "Not a shareable file" }
        return resolver.openInputStream(uri) ?: throw FileNotFoundException("No stream")
    }

    private fun acceptable(uri: Uri): Boolean =
        uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority?.startsWith(context.packageName) == false

    private fun stat(uri: Uri): SourceStat? = runCatching {
        val size = query(uri, OpenableColumns.SIZE)?.toLongOrNull()
            ?: resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { length -> length >= 0 } }
            ?: return@runCatching null
        val modified = query(uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.toLongOrNull() ?: 0L
        SourceStat(size, modified)
    }.getOrNull()

    private fun isDirectory(uri: Uri): Boolean =
        query(uri, DocumentsContract.Document.COLUMN_MIME_TYPE) == DocumentsContract.Document.MIME_TYPE_DIR

    private fun query(uri: Uri, column: String): String? = runCatching {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(column)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getString(index) else null
        }
    }.getOrNull()
}

/** Writes into a descriptor opened "rw": seekable, cut to where the transfer goes on. */
private class DescriptorWriter(private val descriptor: ParcelFileDescriptor, offset: Long) : TargetWriter {
    private val stream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
    private val channel: FileChannel = stream.channel

    init {
        try {
            if (channel.size() != offset) channel.truncate(offset)
            channel.position(offset)
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        val buffer = ByteBuffer.wrap(bytes, offset, length)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    override fun sync() {
        channel.force(true)
    }

    override fun close() {
        runCatching { stream.close() }
    }
}

private fun mimeFor(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
}

private fun ContentResolver.length(uri: Uri): Long? = try {
    openFileDescriptor(uri, "r")?.use { it.statSize.takeIf { size -> size >= 0 } }
} catch (_: FileNotFoundException) {
    null
} catch (_: SecurityException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private fun ContentResolver.writer(uri: Uri, offset: Long): TargetWriter {
    val descriptor = openFileDescriptor(uri, "rw") ?: throw FileNotFoundException("No descriptor")
    return DescriptorWriter(descriptor, offset)
}

/**
 * Downloads/Levik through MediaStore (Android 10 and newer): no storage
 * permission. An arriving file is a pending entry no other app sees; when it
 * is published the system picks a free name.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreTargets(private val resolver: ContentResolver) : DropTargets {
    override fun freeBytes(): Long? = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrNull()

    override fun createPartial(name: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeFor(name))
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw FileNotFoundException("MediaStore refused the file")
        return uri.toString()
    }

    override fun length(partial: String): Long? = resolver.length(Uri.parse(partial))

    override fun openWrite(partial: String, offset: Long): TargetWriter = resolver.writer(Uri.parse(partial), offset)

    override fun openRead(partial: String): InputStream =
        resolver.openInputStream(Uri.parse(partial)) ?: throw FileNotFoundException("No stream")

    override fun place(partial: String, name: String): String {
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        if (resolver.update(Uri.parse(partial), values, null, null) != 1) throw FileNotFoundException("The file is gone")
        return partial
    }

    override fun delete(partial: String) {
        resolver.delete(Uri.parse(partial), null, null)
    }

    companion object {
        const val SUBFOLDER = "Levik"
    }
}

/** A folder the user picked with the system picker (any Android version), without storage access. */
class TreeTargets(private val resolver: ContentResolver, tree: Uri) : DropTargets {
    private val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    override fun freeBytes(): Long? = null

    override fun createPartial(name: String): String =
        DocumentsContract.createDocument(resolver, folder, "application/octet-stream", DropNames.partialName(name))?.toString()
            ?: throw FileNotFoundException("The folder refused the file")

    override fun length(partial: String): Long? = resolver.length(Uri.parse(partial))

    override fun openWrite(partial: String, offset: Long): TargetWriter = resolver.writer(Uri.parse(partial), offset)

    override fun openRead(partial: String): InputStream =
        resolver.openInputStream(Uri.parse(partial)) ?: throw FileNotFoundException("No stream")

    /** Providers keep names unique themselves; a refusal tries the next number. */
    override fun place(partial: String, name: String): String {
        var last: Exception = FileNotFoundException("The folder refused the name")
        for (attempt in 0 until PLACE_ATTEMPTS) {
            try {
                val renamed = DocumentsContract.renameDocument(resolver, Uri.parse(partial), DropNames.numberedName(name, attempt))
                return (renamed ?: Uri.parse(partial)).toString()
            } catch (error: FileNotFoundException) {
                throw error
            } catch (error: Exception) {
                last = error
            }
        }
        throw last
    }

    override fun delete(partial: String) {
        DocumentsContract.deleteDocument(resolver, Uri.parse(partial))
    }

    private companion object {
        const val PLACE_ATTEMPTS = 20
    }
}

/** "downloads" (MediaStore) or a picked folder's tree URI. */
class AndroidDropFolders(private val context: Context) : DropFolders {
    override val default: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DOWNLOADS else null

    override fun open(folder: String): DropTargets {
        if (folder == DOWNLOADS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return MediaStoreTargets(context.contentResolver)
        val tree = Uri.parse(folder)
        require(tree.scheme == ContentResolver.SCHEME_CONTENT) { "Not a folder" }
        return TreeTargets(context.contentResolver, tree)
    }

    companion object {
        const val DOWNLOADS = "downloads"
    }
}
