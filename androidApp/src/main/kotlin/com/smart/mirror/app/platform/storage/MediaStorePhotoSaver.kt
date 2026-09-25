package com.smart.mirror.app.platform.storage

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.smart.mirror.app.domain.model.CapturedPhoto
import com.smart.mirror.app.domain.model.SaveFailure
import com.smart.mirror.app.domain.model.SaveOutcome
import com.smart.mirror.app.domain.port.PhotoSaver
import com.smart.mirror.app.platform.camera.CapturedPhotoBuffer
import com.smart.mirror.app.platform.permission.ActivityPermissionRequester
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [PhotoSaver] writing JPEGs to the shared gallery via MediaStore.
 *
 *  - API 29+: scoped storage, `Pictures/Smart Mirror`, no permission needed.
 *  - API 26-28: needs `WRITE_EXTERNAL_STORAGE` (declared with maxSdkVersion=28). Requested on the
 *    first save only; if denied, later saves in the same session fail fast without re-prompting
 *    (MVP-08.AC5).
 *
 * The photo bytes come from [CapturedPhotoBuffer] (RAM only) and are dropped after the save,
 * successful or not, so nothing stays in app storage (MVP-08.AC7).
 */
class MediaStorePhotoSaver(
    context: Context,
    private val requester: ActivityPermissionRequester,
    private val buffer: CapturedPhotoBuffer,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : PhotoSaver {

    private val appContext = context.applicationContext
    private val io: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "photo-save") }

    @Volatile
    private var legacyPermissionDeniedThisSession = false

    override fun save(photo: CapturedPhoto, onResult: (SaveOutcome) -> Unit) {
        val done = AtomicBoolean(false)
        val finish: (SaveOutcome) -> Unit = { if (done.compareAndSet(false, true)) onResult(it) }

        if (sdkInt < Build.VERSION_CODES.Q && !hasLegacyWritePermission()) {
            if (legacyPermissionDeniedThisSession) {
                buffer.discard(photo.localUri)
                finish(SaveOutcome.Failed(SaveFailure.PERMISSION_DENIED))
                return
            }
            requester.request(Manifest.permission.WRITE_EXTERNAL_STORAGE) { granted ->
                if (granted) {
                    write(photo, finish)
                } else {
                    legacyPermissionDeniedThisSession = true
                    buffer.discard(photo.localUri)
                    finish(SaveOutcome.Failed(SaveFailure.PERMISSION_DENIED))
                }
            }
            return
        }
        write(photo, finish)
    }

    fun release() {
        io.shutdown()
    }

    private fun write(photo: CapturedPhoto, finish: (SaveOutcome) -> Unit) {
        try {
            io.execute {
                val bytes = buffer.take(photo.localUri)
                if (bytes == null) {
                    finish(SaveOutcome.Failed(SaveFailure.UNKNOWN))
                    return@execute
                }
                val outcome = try {
                    val uri = if (sdkInt >= Build.VERSION_CODES.Q) insertScoped(bytes) else insertLegacy(bytes)
                    SaveOutcome.Saved(uri.toString())
                } catch (e: IOException) {
                    Log.w(TAG, "Saving photo failed", e)
                    SaveOutcome.Failed(if (isStorageFull(e)) SaveFailure.STORAGE_FULL else SaveFailure.UNKNOWN)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Saving photo not permitted", e)
                    SaveOutcome.Failed(SaveFailure.PERMISSION_DENIED)
                } catch (t: Throwable) {
                    Log.w(TAG, "Saving photo failed", t)
                    SaveOutcome.Failed(SaveFailure.UNKNOWN)
                }
                finish(outcome)
            }
        } catch (t: Throwable) { // executor shut down
            buffer.discard(photo.localUri)
            finish(SaveOutcome.Failed(SaveFailure.UNKNOWN))
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertScoped(bytes: ByteArray): Uri {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = baseValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert returned null")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("No output stream for $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    @Suppress("DEPRECATION") // DATA / public directory are the correct APIs below API 29.
    private fun insertLegacy(bytes: ByteArray): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val file = File(dir, fileName())
        try {
            file.outputStream().use { it.write(bytes) }
            val values = baseValues().apply { put(MediaStore.MediaColumns.DATA, file.absolutePath) }
            return appContext.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert returned null")
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    private fun baseValues() = ContentValues().apply {
        val now = System.currentTimeMillis()
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(now))
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.DATE_TAKEN, now)
    }

    private fun fileName(now: Long = System.currentTimeMillis()): String =
        "SmartMirror_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now))}.jpg"

    private fun hasLegacyWritePermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "MediaStorePhotoSaver"
        const val ALBUM = "Smart Mirror"

        internal fun isStorageFull(e: IOException): Boolean {
            val msg = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
            return "ENOSPC" in msg || "No space left" in msg
        }
    }
}
