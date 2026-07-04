package com.maestro.app.face

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * CameraX analyzer that runs on-device ML Kit face detection on each frame,
 * distils the result into [FaceSignals], and feeds them to a [GestureEngine].
 *
 * ML Kit's success listeners dispatch on the main thread, so [onResult] is always
 * invoked on the main thread — safe to update UI state / turn pages directly.
 */
class FaceAnalyzer(
    private val configProvider: () -> GestureConfig,
    private val onResult: (status: EngineStatus, fired: PageAction?, signals: FaceSignals) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.2f)
            .build()
    )

    private val engine = GestureEngine()

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener { faces ->
                val signals = if (faces.isEmpty()) {
                    FaceSignals(present = false)
                } else {
                    val f = faces[0]
                    FaceSignals(
                        present = true,
                        yaw = f.headEulerAngleY,
                        pitch = f.headEulerAngleX,
                        leftEyeOpen = f.leftEyeOpenProbability ?: -1f,
                        rightEyeOpen = f.rightEyeOpenProbability ?: -1f,
                        smiling = f.smilingProbability ?: -1f
                    )
                }
                val out = engine.onFrame(signals, configProvider(), System.currentTimeMillis())
                onResult(out.status, out.fired, signals)
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    fun close() {
        detector.close()
    }
}
