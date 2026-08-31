package com.vrhandtracker.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.util.Log
import android.util.Size
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerOptions
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

class MediaPipeHandTracker(private val context: Context) {
    private var handLandmarker: HandLandmarker? = null
    private var isInitialized = false
    private var listener: ((List<HandData>) -> Unit)? = null

    companion object {
        private const val TAG = "MediaPipeHandTracker"
        const val MODEL_FILENAME = "hand_landmarker.task"
    }

    interface OnHandsDetectedListener {
        fun onHandsDetected(hands: List<HandData>)
    }

    suspend fun initialize(listener: OnHandsDetectedListener): Boolean {
        this.listener = listener
        return withContext(Dispatchers.IO) {
            try {
                // Copy model from assets to internal storage
                val modelPath = copyModelFromAssets()
                if (modelPath == null) {
                    Log.e(TAG, "Failed to copy model from assets")
                    return@withContext false
                }

                // Build HandLandmarker options
                val options = HandLandmarkerOptions.builder()
                    .setBaseOptions(
                        com.google.mediapipe.tasks.core.BaseOptions.builder()
                            .setModelAssetPath(modelPath)
                            .build()
                    )
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setResultListener { result, image, timestampMs ->
                        handleResult(result, timestampMs)
                    }
                    .build()

                handLandmarker = HandLandmarker.createFromOptions(context, options)
                isInitialized = true
                Log.d(TAG, "MediaPipe Hand Landmarker initialized")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize MediaPipe", e)
                false
            }
        }
    }

    private fun copyModelFromAssets(): String? {
        val destFile = File(context.filesDir, MODEL_FILENAME)
        if (destFile.exists()) {
            return destFile.absolutePath
        }

        try {
            context.assets.open(MODEL_FILENAME).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            return destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error copying model", e)
            return null
        }
    }

    fun processImage(image: Image, rotationDegrees: Int, timestampMs: Long) {
        if (!isInitialized || handLandmarker == null) return

        try {
            // Convert Image to MediaPipe Image
            val mpImage = createMPImage(image, rotationDegrees)
            handLandmarker?.detectAsync(mpImage, timestampMs)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
        }
    }

    private fun createMPImage(image: Image, rotationDegrees: Int): com.google.mediapipe.tasks.vision.core.MPImage {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val yRowStride = planes[0].rowStride
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride

        // Create Bitmap from YUV420
        val width = image.width
        val height = image.height

        // Use MediaPipe's built-in YUV conversion
        return com.google.mediapipe.tasks.vision.core.MPImageBuilder()
            .setFormat(com.google.mediapipe.tasks.vision.core.ImageFormat.YUV420P)
            .setWidth(width)
            .setHeight(height)
            .setData(yBuffer, uBuffer, vBuffer)
            .build()
    }

    private fun handleResult(result: HandLandmarkerResult?, timestampMs: Long) {
        if (result == null) return

        val hands = mutableListOf<HandData>()

        for (i in result.handLandmarks.indices) {
            val landmarks = result.handLandmarks[i].map { lm ->
                HandLandmark(lm.x, lm.y, lm.z, 1.0f, 1.0f)
            }
            val handedness = result.handedness[i].firstOrNull()?.categoryName ?: "Right"
            val confidence = result.handedness[i].firstOrNull()?.score ?: 1.0f

            hands.add(HandData(landmarks, handedness, confidence, timestampMs))
        }

        listener?.onHandsDetected(hands)
    }

    fun close() {
        handLandmarker?.close()
        handLandmarker = null
        isInitialized = false
    }
}