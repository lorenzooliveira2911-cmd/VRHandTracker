package com.vrhandtracker.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.google.vr.sdk.base.GvrView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VRHandTracker"
        private const val REQUEST_CAMERA_PERMISSION = 1001
    }

    // UI
    private lateinit var gvrView: GvrView
    private lateinit var statusText: TextView
    private lateinit var btnRecenter: Button
    private lateinit var btnToggleVR: Button

    // Camera
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Hand tracking
    private lateinit var handTracker: MediaPipeHandTracker
    private lateinit var vrRenderer: VRHandRenderer
    private var isVRMode = true
    private var isTrackingInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initGVR()
        initHandTracker()
        requestCameraPermission()
    }

    private fun initViews() {
        gvrView = findViewById(R.id.gvrView)
        statusText = findViewById(R.id.statusText)
        btnRecenter = findViewById(R.id.btnRecenter)
        btnToggleVR = findViewById(R.id.btnToggleVR)

        btnRecenter.setOnClickListener { recenter() }
        btnToggleVR.setOnClickListener { toggleVR() }
    }

    private fun initGVR() {
        vrRenderer = VRHandRenderer(this, handTracker)
        gvrView.setRenderer(vrRenderer)
        gvrView.setTransitionViewEnabled(false)
    }

    private fun initHandTracker() {
        handTracker = MediaPipeHandTracker(this)
        CoroutineScope(Dispatchers.IO).launch {
            val success = handTracker.initialize { hands ->
                runOnUiThread {
                    updateStatus(hands)
                    vrRenderer.updateHandData(hands)
                }
            }
            runOnUiThread {
                isTrackingInitialized = success
                if (success) {
                    statusText.text = getString(R.string.tracking_status_ready)
                } else {
                    statusText.text = "Failed to initialize hand tracking"
                }
            }
        }
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
        } else {
            startCamera()
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
                startCamera()
            } else {
                statusText.text = getString(R.string.tracking_status_permission_denied)
            }
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
                runOnUiThread { statusText.text = getString(R.string.tracking_status_no_camera) }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        // Preview (for GVR view - but we'll use CameraX analysis for hand tracking)
        val preview = Preview.Builder().build()

        // Image analysis for hand tracking
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
            cameraProvider.bindToLifecycle(this as LifecycleOwner, cameraSelector, preview, imageAnalysis)
            runOnUiThread { statusText.text = getString(R.string.tracking_status_ready) }
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
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
        status.append("Left: ").append(if (left != null) "✓ ${left.confidence:.2f}" else "✗").append("\n")
        status.append("Right: ").append(if (right != null) "✓ ${right.confidence:.2f}" else "✗")

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

    private fun recenter() {
        gvrView.recenter()
    }

    private fun toggleVR() {
        isVRMode = !isVRMode
        if (isVRMode) {
            gvrView.setRenderer(vrRenderer)
            btnToggleVR.text = getString(R.string.btn_toggle_vr)
        } else {
            // Could switch to mono view here
            btnToggleVR.text = "VR Mode"
        }
    }

    override fun onResume() {
        super.onResume()
        gvrView.onResume()
    }

    override fun onPause() {
        gvrView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handTracker.close()
        cameraExecutor.shutdown()
        backgroundThread?.quitSafely()
        super.onDestroy()
    }
}