package com.vrhandtracker.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.vrhandtracker.app.MediaPipeHandTracker.OnHandsDetectedListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VRHandTracker"
        private const val REQUEST_CAMERA_PERMISSION = 1001
    }

    // UI
    private lateinit var glSurfaceView: StereoGLSurfaceView
    private lateinit var statusText: TextView
    private lateinit var btnRecenter: Button
    private lateinit var btnToggleVR: Button

    // Camera
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null

    // Hand tracking
    private lateinit var handTracker: MediaPipeHandTracker
    private lateinit var stereoRenderer: StereoRenderer
    private var isTrackingInitialized = false
    private var initAttempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        setContentView(R.layout.activity_main)

        initViews()
        initRenderer()
        statusText.text = "Checking permissions..."
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
        
        // Initialize on resume (safer than onCreate)
        if (!initAttempted) {
            initAttempted = true
            requestCameraPermission()
        }
    }

    override fun onPause() {
        glSurfaceView.onPause()
        super.onPause()
    }

    private fun initViews() {
        glSurfaceView = findViewById(R.id.glSurfaceView)
        statusText = findViewById(R.id.statusText)
        btnRecenter = findViewById(R.id.btnRecenter)
        btnToggleVR = findViewById(R.id.btnToggleVR)

        btnRecenter.setOnClickListener { }
        btnToggleVR.setOnClickListener { }
    }

    private fun initRenderer() {
        stereoRenderer = StereoRenderer()
        glSurfaceView.setRenderer(stereoRenderer)
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "Requesting camera permission..."
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
        } else {
            initAll()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initAll()
            } else {
                statusText.text = "Camera permission denied. Enable in Settings."
            }
        }
    }

    private fun initAll() {
        statusText.text = "Initializing..."
        initHandTracker()
        startCamera()
    }

    private fun initHandTracker() {
        try {
            handTracker = MediaPipeHandTracker(this)
            CoroutineScope(Dispatchers.IO).launch {
                val success = handTracker.initialize(OnHandsDetectedListener { hands ->
                    runOnUiThread {
                        updateStatus(hands)
                        stereoRenderer.updateHandData(hands)
                    }
                })
                runOnUiThread {
                    isTrackingInitialized = success
                    if (!success) {
                        statusText.text = "Hand tracker init failed"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hand tracker init failed", e)
            runOnUiThread { statusText.text = "Hand tracker error: ${e.message}" }
        }
    }

    private fun startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture?.addListener({
            try {
                val cameraProvider = cameraProviderFuture?.get()
                bindCameraUseCases(cameraProvider!!)
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
                runOnUiThread { statusText.text = "Camera error: ${e.message}" }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            analyzeImage(imageProxy)
            imageProxy.close()
        }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this as LifecycleOwner, cameraSelector, imageAnalysis)
            runOnUiThread { statusText.text = getString(R.string.tracking_status_ready) }
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
            runOnUiThread { statusText.text = "Camera bind error: ${e.message}" }
        }
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        if (!isTrackingInitialized) return

        val mediaImage = imageProxy.image ?: return
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val timestampMs = System.currentTimeMillis()

        handTracker.processImage(mediaImage, rotationDegrees, timestampMs)
    }

    private fun updateStatus(hands: List<HandData>) {
        val left = hands.firstOrNull { it.isLeftHand }
        val right = hands.firstOrNull { !it.isLeftHand }

        val status = StringBuilder()
        status.append("FPS: ").append(calculateFPS()).append("\n")
        
        val leftStr = if (left != null) "✓ ${"%.2f".format(left.confidence)}" else "✗"
        val rightStr = if (right != null) "✓ ${"%.2f".format(right.confidence)}" else "✗"
        
        status.append("Left: ").append(leftStr).append("\n")
        status.append("Right: ").append(rightStr)

        statusText.text = status.toString()
    }

    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var currentFps = 0

    private fun calculateFPS(): Int {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            currentFps = frameCount
            frameCount = 0
            lastFpsTime = now
        }
        return currentFps
    }

    override fun onDestroy() {
        handTracker.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}