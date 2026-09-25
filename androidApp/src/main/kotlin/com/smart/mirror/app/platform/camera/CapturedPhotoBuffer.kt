package com.smart.mirror.app.platform.camera

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory hand-off between [CameraXCameraController] (produces the encoded JPEG) and
 * [com.smart.mirror.app.platform.storage.MediaStorePhotoSaver] (writes it to the gallery).
 *
 * `CapturedPhoto.localUri` is an opaque `memory://` key into this buffer, so a captured photo
 * never touches app-private storage (MVP-08.AC7, MVP-13.AC5): the bytes live only in RAM until
 * the saver takes them. Thread-safe.
 */
class CapturedPhotoBuffer {
    private val photos = ConcurrentHashMap<String, ByteArray>()

    fun put(jpeg: ByteArray): String {
        val key = "$SCHEME${UUID.randomUUID()}"
        photos[key] = jpeg
        return key
    }

    /** Returns and removes the photo; null if unknown or already taken. */
    fun take(uri: String): ByteArray? = photos.remove(uri)

    fun discard(uri: String) {
        photos.remove(uri)
    }

    val size: Int get() = photos.size

    fun clear() = photos.clear()

    companion object {
        const val SCHEME = "memory://capture/"
    }
}
