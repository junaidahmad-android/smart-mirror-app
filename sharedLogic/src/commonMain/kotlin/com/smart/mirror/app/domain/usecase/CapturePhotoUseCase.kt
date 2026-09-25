package com.smart.mirror.app.domain.usecase

import com.smart.mirror.app.domain.model.CameraCaptureOutcome
import com.smart.mirror.app.domain.model.CaptureFailure
import com.smart.mirror.app.domain.model.CaptureRequest
import com.smart.mirror.app.domain.model.CaptureResult
import com.smart.mirror.app.domain.model.CapturedPhoto
import com.smart.mirror.app.domain.model.SaveFailure
import com.smart.mirror.app.domain.model.SaveOutcome
import com.smart.mirror.app.domain.port.CameraController
import com.smart.mirror.app.domain.port.PhotoSaver
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Capture a photo with the platform camera and save it to the gallery. */
internal class CapturePhotoUseCase(
    private val camera: CameraController,
    private val photoSaver: PhotoSaver,
) {
    suspend operator fun invoke(request: CaptureRequest, onSaving: () -> Unit = {}): CaptureResult =
        when (val outcome = capture(request)) {
            is CameraCaptureOutcome.Failure -> CaptureResult.Failed(CaptureFailure.CAMERA_ERROR)
            is CameraCaptureOutcome.Success -> {
                onSaving()
                when (val saved = save(outcome.photo)) {
                    is SaveOutcome.Saved -> CaptureResult.Saved(saved.uri)
                    is SaveOutcome.Failed -> CaptureResult.Failed(
                        when (saved.reason) {
                            SaveFailure.PERMISSION_DENIED -> CaptureFailure.SAVE_PERMISSION_DENIED
                            SaveFailure.STORAGE_FULL -> CaptureFailure.STORAGE_FULL
                            SaveFailure.UNKNOWN -> CaptureFailure.UNKNOWN
                        },
                    )
                }
            }
        }

    private suspend fun capture(request: CaptureRequest): CameraCaptureOutcome =
        suspendCancellableCoroutine { cont ->
            camera.capturePhoto(request) { if (cont.isActive) cont.resume(it) }
        }

    private suspend fun save(photo: CapturedPhoto): SaveOutcome =
        suspendCancellableCoroutine { cont ->
            photoSaver.save(photo) { if (cont.isActive) cont.resume(it) }
        }
}
