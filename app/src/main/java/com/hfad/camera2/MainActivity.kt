package com.hfad.camera2

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var switchCameraButton: Button
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var currentCameraId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        // Log available cameras
        val cameraIdList = cameraManager.cameraIdList
        for (id in cameraIdList) {
            Log.d("Camera2", "Available Camera ID: $id")
        }

        // Pick the first available camera as default
        currentCameraId = cameraIdList.firstOrNull()

        // Check and request camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        } else {
            setupCamera()
        }

        // Switch camera button
        switchCameraButton.setOnClickListener {
            switchCamera()
        }
    }

    private fun setupCamera() {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.d("Camera2", "SurfaceTexture available, opening camera...")
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun openCamera() {
        try {
            val cameraIdList = cameraManager.cameraIdList
            if (cameraIdList.isEmpty()) {
                Log.e("Camera2", "No available cameras")
                return
            }

            // Ensure the selected camera ID is valid
            if (!cameraIdList.contains(currentCameraId)) {
                Log.e("Camera2", "Invalid camera ID: $currentCameraId")
                return
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED) {

                cameraManager.openCamera(currentCameraId!!, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        startPreview()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e("Camera2", "Camera error: $error")
                        camera.close()
                        cameraDevice = null
                    }
                }, null)
            }
        } catch (e: CameraAccessException) {
            Log.e("Camera2", "Camera access exception: ${e.message}")
        }
    }

    private fun startPreview() {
        try {
            val texture = textureView.surfaceTexture
            if (texture == null) {
                Log.e("Camera2", "SurfaceTexture is null, cannot start preview")
                return
            }

            val surface = Surface(texture)

            val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(surface)

            cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    cameraCaptureSession = session
                    try {
                        session.setRepeatingRequest(captureRequestBuilder.build(), null, null)
                        Log.d("Camera2", "Camera preview started")
                    } catch (e: CameraAccessException) {
                        Log.e("Camera2", "Preview request error: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("Camera2", "Camera configuration failed")
                }
            }, null)

        } catch (e: CameraAccessException) {
            Log.e("Camera2", "Start preview error: ${e.message}")
        }
    }

    private fun switchCamera() {
        cameraDevice?.close()
        cameraDevice = null

        val cameraIdList = cameraManager.cameraIdList
        if (cameraIdList.size > 1) {
            // Switch to the next available camera
            val currentIndex = cameraIdList.indexOf(currentCameraId)
            val nextIndex = (currentIndex + 1) % cameraIdList.size
            currentCameraId = cameraIdList[nextIndex]
        } else {
            Log.e("Camera2", "Only one camera available")
            return
        }

        Log.d("Camera2", "Switching to camera ID: $currentCameraId")
        openCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraDevice?.close()
    }
}


