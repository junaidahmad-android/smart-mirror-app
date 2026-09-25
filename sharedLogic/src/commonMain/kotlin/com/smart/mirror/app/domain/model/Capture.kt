package com.smart.mirror.app.domain.model

data class CaptureRequest(
    /** Save the photo flipped like the preview (MIRROR) or un-flipped (TRUE_VIEW). */
    val mirrored: Boolean,
    /** When the preview is frozen, save exactly the frozen frame instead of taking a new shot. */
    val fromFrozenFrame: Boolean,
)

/**
 * A photo captured by the platform camera. Pixels stay native: Kotlin only sees an opaque
 * platform-owned reference ([localUri], e.g. a temp file URL) plus metadata.
 */
data class CapturedPhoto(
    val localUri: String,
    val width: Int,
    val height: Int,
    val mirrored: Boolean,
)

/** Result of `CameraController.capturePhoto`. */
sealed interface CameraCaptureOutcome {
    data class Success(val photo: CapturedPhoto) : CameraCaptureOutcome
    data class Failure(val error: CameraError) : CameraCaptureOutcome
}

enum class SaveFailure { PERMISSION_DENIED, STORAGE_FULL, UNKNOWN }

/** Result of `PhotoSaver.save`. */
sealed interface SaveOutcome {
    /** [uri] = the saved asset (MediaStore content URI / PHAsset local identifier). */
    data class Saved(val uri: String) : SaveOutcome
    data class Failed(val reason: SaveFailure) : SaveOutcome
}

enum class CaptureFailure { CAMERA_ERROR, SAVE_PERMISSION_DENIED, STORAGE_FULL, UNKNOWN }

/** End-to-end result of the capture-and-save use case. */
sealed interface CaptureResult {
    data class Saved(val uri: String) : CaptureResult
    data class Failed(val reason: CaptureFailure) : CaptureResult
}

enum class CaptureStatus { IDLE, CAPTURING, SAVING }
