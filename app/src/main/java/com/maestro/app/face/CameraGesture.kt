package com.maestro.app.face

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

private const val TAG = "Maestro.Camera"

/**
 * Binds the front camera's [ImageAnalysis] use case to the current lifecycle while
 * [active] is true, streaming frames into [analyzer]. No preview surface is used —
 * the camera runs purely for gesture detection. Rebinds whenever [active] flips.
 */
@Composable
fun CameraGestureEffect(active: Boolean, analyzer: FaceAnalyzer) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(active, lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        var provider: ProcessCameraProvider? = null

        if (active) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(executor, analyzer) }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        analysis
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to bind camera for gesture detection", e)
                }
            }, ContextCompat.getMainExecutor(context))
        }

        onDispose {
            try {
                provider?.unbindAll()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unbind camera", e)
            }
            executor.shutdown()
        }
    }
}
