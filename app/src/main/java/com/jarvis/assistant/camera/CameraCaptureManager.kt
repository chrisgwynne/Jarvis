package com.jarvis.assistant.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.camera.lifecycle.awaitInstance
import java.io.File
import kotlin.coroutines.resume

/** Result of a single capture attempt. */
sealed class CaptureResult {
    /** Capture succeeded; [file] is the saved JPEG in app-private storage. */
    data class Success(val file: File) : CaptureResult()
    /** Capture failed; [reason] is safe to surface to the user. */
    data class Failure(val reason: String) : CaptureResult()
}

/**
 * CameraCaptureManager — headless CameraX still-image capture from a foreground service.
 *
 * HOW IT WORKS (no Activity, no preview surface required):
 *   1. Obtain the singleton ProcessCameraProvider on the main thread.
 *   2. Create a ServiceLifecycleOwner and walk it to RESUMED.
 *   3. Bind an ImageCapture use case (no Preview needed — fully supported by CameraX).
 *   4. Fire takePicture() into an app-private file.
 *   5. On success/error: walk lifecycle to DESTROYED (auto-unbinds use case).
 *   6. On success: copy to MediaStore gallery in parallel on IO dispatcher.
 *
 * LOCKED PHONE:
 *   Works on a locked device provided JarvisService is declared with
 *   foregroundServiceType="microphone|camera" and FOREGROUND_SERVICE_CAMERA
 *   permission is in the manifest — both are set. On some OEM devices with
 *   extra camera security restrictions this may still fail; the failure is
 *   returned as CaptureResult.Failure, never a crash.
 *
 * THREADING:
 *   bindToLifecycle() and CameraX set-up MUST happen on the main thread.
 *   capturePhoto() switches to Dispatchers.Main internally.
 *
 * TIMEOUT:
 *   15 seconds hard limit on the entire capture operation. If CameraX fails
 *   to call back within that window (hardware issue, locked-screen policy,
 *   etc.) a Failure is returned rather than hanging the pipeline.
 */
class CameraCaptureManager(private val context: Context) {

    companion object {
        private const val TAG              = "CameraCaptureManager"
        private const val CAPTURE_TIMEOUT_MS = 15_000L
    }

    // Use applicationContext so we never accidentally retain the Service reference.
    private val appContext: Context = context.applicationContext

    /**
     * Capture a still image with the specified [lensFacing] camera.
     *
     * @param lensFacing [CameraSelector.LENS_FACING_BACK] or [CameraSelector.LENS_FACING_FRONT]
     * @return [CaptureResult.Success] with the saved file, or [CaptureResult.Failure].
     */
    suspend fun capturePhoto(@CameraSelector.LensFacing lensFacing: Int): CaptureResult {

        // All CameraX operations must run on the main thread.
        val result: CaptureResult = withContext(Dispatchers.Main) {

            // Hard timeout — prevents the coroutine hanging forever if the
            // camera hardware or locked-screen policy never responds.
            val opResult = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                val lifecycleOwner = ServiceLifecycleOwner()
                var provider: ProcessCameraProvider? = null
                try {
                    // awaitInstance() is a suspend fun — no ListenableFuture needed.
                    val cameraProvider = ProcessCameraProvider.awaitInstance(appContext)
                    provider = cameraProvider

                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()

                    val selector = CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build()

                    // Walk lifecycle to RESUMED *before* binding.
                    lifecycleOwner.markResumed()

                    // Unbind any lingering use cases from a previous capture.
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, imageCapture)

                    val outputFile    = CameraFileStore.createImageFile(appContext)
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
                    val executor      = ContextCompat.getMainExecutor(appContext)

                    suspendCancellableCoroutine<CaptureResult> { cont ->
                        imageCapture.takePicture(outputOptions, executor,
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    release(lifecycleOwner, provider)
                                    Log.i(TAG, "Photo saved: ${outputFile.name} (${outputFile.length()} bytes)")
                                    if (cont.isActive) cont.resume(CaptureResult.Success(outputFile))
                                }

                                override fun onError(ex: ImageCaptureException) {
                                    release(lifecycleOwner, provider)
                                    val reason = buildErrorMessage(ex)
                                    Log.w(TAG, "Capture error code=${ex.imageCaptureError}: $reason")
                                    if (cont.isActive) cont.resume(CaptureResult.Failure(reason))
                                }
                            }
                        )
                        cont.invokeOnCancellation { release(lifecycleOwner, provider) }
                    }
                } catch (e: Exception) {
                    release(lifecycleOwner, provider)
                    val reason = buildExceptionMessage(e)
                    Log.e(TAG, "Camera setup failed: ${e.message}", e)
                    CaptureResult.Failure(reason)
                }
            }
            opResult ?: CaptureResult.Failure("Camera timed out. The phone may be restricting camera access while locked.")
        }

        // Publish to the user's gallery on IO — non-fatal, never blocks success.
        if (result is CaptureResult.Success) {
            withContext(Dispatchers.IO) {
                publishToGallery(result.file)
            }
        }
        return result
    }

    // ── Gallery publishing ────────────────────────────────────────────────────

    /**
     * Copy the captured file into MediaStore (Pictures/Jarvis) so it appears
     * in the Photos app.  Non-fatal — a failure here does not affect the result.
     */
    private fun publishToGallery(file: File) {
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "publishToGallery: file missing or empty, skipping")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishViaMediaStore(file)
            } else {
                publishLegacy(file)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gallery publish failed (non-fatal): ${e.message}")
        }
    }

    private fun publishViaMediaStore(file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/Jarvis")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: run {
                Log.w(TAG, "MediaStore insert returned null URI")
                return
            }
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Log.d(TAG, "Published to gallery via MediaStore: ${file.name}")
    }

    private fun publishLegacy(file: File) {
        // API 26–28: copy to public Pictures directory then trigger a media scan.
        val destDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Jarvis"
        ).also { it.mkdirs() }
        val dest = File(destDir, file.name)
        file.copyTo(dest, overwrite = true)
        MediaScannerConnection.scanFile(
            appContext,
            arrayOf(dest.absolutePath),
            arrayOf("image/jpeg"),
            null
        )
        Log.d(TAG, "Published to gallery via MediaScanner (legacy): ${file.name}")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun release(owner: ServiceLifecycleOwner, provider: ProcessCameraProvider?) {
        try {
            owner.markDestroyed()
            provider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Camera release error (non-fatal): ${e.message}")
        }
    }

    private fun buildErrorMessage(ex: ImageCaptureException): String = when (ex.imageCaptureError) {
        ImageCapture.ERROR_CAMERA_CLOSED      -> "Camera closed before capture completed."
        ImageCapture.ERROR_CAPTURE_FAILED     -> "Capture failed. Is another app using the camera?"
        ImageCapture.ERROR_FILE_IO            -> "Could not save photo to storage."
        ImageCapture.ERROR_INVALID_CAMERA     -> "Camera not found on this device."
        ImageCapture.ERROR_UNKNOWN            -> "Unknown camera error."
        else                                  -> ex.message ?: "Capture error."
    }

    private fun buildExceptionMessage(e: Exception): String = when {
        e.message?.contains("CAMERA_DISABLED", ignoreCase = true) == true ->
            "Camera is disabled. Check your device's camera security settings."
        e.message?.contains("locked", ignoreCase = true) == true ->
            "Camera may be restricted while the phone is locked."
        e is SecurityException ->
            "Camera permission was denied."
        else ->
            e.message?.take(100) ?: "Camera setup error."
    }
}
