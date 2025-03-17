package com.hfad.camera2

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var testImageButton: Button
    private lateinit var switchCameraButton: Button
    private lateinit var imageView: ImageView
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var currentCameraId: String? = null
    private lateinit var tflite: Interpreter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        testImageButton = findViewById(R.id.testImageButton)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        imageView = findViewById(R.id.imageView)

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        tflite = Interpreter(loadModelFile())
        Log.d("TFLite", "Model loaded successfully")

        val cameraIdList = cameraManager.cameraIdList
        currentCameraId = cameraIdList.firstOrNull()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        } else {
            setupCamera()
        }

        switchCameraButton.setOnClickListener {
            switchCamera()
        }

        testImageButton.setOnClickListener {
            processTestImage()
        }

        printModelDetails()
    }

    private fun switchCamera() {
        cameraDevice?.close()
        cameraDevice = null

        val cameraIdList = cameraManager.cameraIdList
        if (cameraIdList.size > 1) {
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

    private fun setupCamera() {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                processFrame()
            }
        }
    }

    private fun openCamera() {
        try {
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
            val texture = textureView.surfaceTexture ?: return
            val surface = Surface(texture)

            val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(surface)

            cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    session.setRepeatingRequest(captureRequestBuilder.build(), null, null)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("Camera2", "Camera configuration failed")
                }
            }, null)

        } catch (e: CameraAccessException) {
            Log.e("Camera2", "Start preview error: ${e.message}")
        }
    }

    private fun processFrame() {
        val bitmap = textureView.bitmap ?: return
        val inputBuffer = preprocessBitmap(bitmap)

        val outputArray = Array(1) { Array(64) { Array(48) { FloatArray(17) } } }

        try {
            tflite.run(inputBuffer, outputArray)
            drawKeypointsOnBitmap(bitmap, outputArray)
        } catch (e: Exception) {
            Log.e("TFLite", "Inference failed: ${e.message}")
        }
    }

    private fun loadBitmapFromAssets(fileName: String): Bitmap? {
        return try {
            val assetManager = assets
            val inputStream = assetManager.open(fileName)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    private fun processTestImage() {
        val testBitmap = loadBitmapFromAssets("test_image.png")
        if(testBitmap == null){
            Log.e("TestImage", "Failed to load test_image from assets")
            return
        }
        val inputBuffer = preprocessBitmap(testBitmap)

        val outputArray = Array(1) { Array(64) { Array(48) { FloatArray(17) } } }

        try {
            tflite.run(inputBuffer, outputArray)
            drawKeypointsOnBitmap(testBitmap, outputArray)
        } catch (e: Exception) {
            Log.e("TFLite", "Inference failed: ${e.message}")
        }
    }

    private fun drawKeypointsOnBitmap(bitmap: Bitmap, keypoints: Array<Array<Array<FloatArray>>>) {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        val paint = Paint().apply {
            color = Color.RED
            strokeWidth = 5f
        }

        val bitmapWidth = mutableBitmap.width.toFloat()
        val bitmapHeight = mutableBitmap.height.toFloat()

        for (i in keypoints[0].indices) {
            for (j in keypoints[0][i].indices) {
                val x = keypoints[0][i][j][0] * bitmapWidth
                val y = keypoints[0][i][j][1] * bitmapHeight

                if (x.toDouble() in 0.0..bitmapWidth.toDouble() &&
                    y.toDouble() in 0.0..bitmapHeight.toDouble()) {
                    canvas.drawCircle(x, y, 8f, paint)
                }
            }
        }

        runOnUiThread {
            imageView.setImageBitmap(mutableBitmap)
        }
    }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val inputWidth = 192
        val inputHeight = 256

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputWidth * inputHeight * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputWidth * inputHeight)
        resizedBitmap.getPixels(intValues, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        for (pixelValue in intValues) {
            byteBuffer.putFloat(((pixelValue shr 16) and 0xFF) / 255.0f)
            byteBuffer.putFloat(((pixelValue shr 8) and 0xFF) / 255.0f)
            byteBuffer.putFloat((pixelValue and 0xFF) / 255.0f)
        }

        return byteBuffer
    }

    private fun loadModelFile(): MappedByteBuffer {
        val assetFileDescriptor = assets.openFd("hrnet_pose.tflite")
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, assetFileDescriptor.startOffset, assetFileDescriptor.declaredLength)
    }

    private fun printModelDetails() {
        Log.d("ModelInfo", "Input shape: ${tflite.getInputTensor(0).shape().contentToString()}")
        Log.d("ModelInfo", "Output shape: ${tflite.getOutputTensor(0).shape().contentToString()}")
    }
}




/*package com.hfad.camera2

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.hardware.camera2.*
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var switchCameraButton: Button
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var currentCameraId: String? = null
    private lateinit var tflite: Interpreter

    private fun switchCamera() {
        cameraDevice?.close()
        cameraDevice = null

        val cameraIdList = cameraManager.cameraIdList
        if (cameraIdList.size > 1) {
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


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        // Load TensorFlow Lite model
        tflite = Interpreter(loadModelFile())
        Log.d("TFLite", "Model loaded successfully")

        val cameraIdList = cameraManager.cameraIdList
        currentCameraId = cameraIdList.firstOrNull()

        // Request Camera Permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        } else {
            setupCamera()
        }

        switchCameraButton.setOnClickListener {
            switchCamera()
        }

        printModelDetails()
    }

    private fun setupCamera() {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                processFrame()
            }
        }
    }

    private fun openCamera() {
        try {
            val cameraIdList = cameraManager.cameraIdList
            if (cameraIdList.isEmpty()) {
                Log.e("Camera2", "No available cameras")
                return
            }

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
            val texture = textureView.surfaceTexture ?: return
            val surface = Surface(texture)

            val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(surface)

            cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    session.setRepeatingRequest(captureRequestBuilder.build(), null, null)
                    Log.d("Camera2", "Camera preview started")
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("Camera2", "Camera configuration failed")
                }
            }, null)

        } catch (e: CameraAccessException) {
            Log.e("Camera2", "Start preview error: ${e.message}")
        }
    }

    private fun processFrame() {
        val bitmap = textureView.bitmap ?: return
        val inputBuffer = preprocessBitmap(bitmap)

        val outputArray = Array(1) { Array(64) { Array(48) { FloatArray(17) } } }

        try {
            tflite.run(inputBuffer, outputArray)
            Log.d("PoseEstimation", "Detected Keypoints: ${outputArray.contentDeepToString()}")
            drawKeypointsOnBitmap(bitmap, outputArray)
        } catch (e: Exception) {
            Log.e("TFLite", "Inference failed: ${e.message}")
        }
    }

    private fun drawKeypointsOnBitmap(bitmap: Bitmap, keypoints: Array<Array<Array<FloatArray>>>) {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true) // Make the bitmap mutable
        val canvas = Canvas(mutableBitmap)
        val paint = Paint().apply {
            color = Color.RED
            strokeWidth = 5f
        }

        for (i in keypoints[0].indices) {
            for (j in keypoints[0][i].indices) {
                val x = keypoints[0][i][j][0] * mutableBitmap.width
                val y = keypoints[0][i][j][1] * mutableBitmap.height
                canvas.drawCircle(x, y, 8f, paint)
            }
        }

        runOnUiThread {
            textureView.invalidate() // Force the UI to update
        }
    }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val inputWidth = 192
        val inputHeight = 256

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputWidth * inputHeight * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputWidth * inputHeight)
        resizedBitmap.getPixels(intValues, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        for (pixelValue in intValues) {
            byteBuffer.putFloat(((pixelValue shr 16) and 0xFF) / 255.0f)
            byteBuffer.putFloat(((pixelValue shr 8) and 0xFF) / 255.0f)
            byteBuffer.putFloat((pixelValue and 0xFF) / 255.0f)
        }

        return byteBuffer
    }

    private fun loadModelFile(): MappedByteBuffer {
        val assetFileDescriptor = assets.openFd("hrnet_pose.tflite")
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, assetFileDescriptor.startOffset, assetFileDescriptor.declaredLength)
    }

    private fun printModelDetails() {
        val inputTensorShape = tflite.getInputTensor(0).shape()
        val outputTensorShape = tflite.getOutputTensor(0).shape()

        Log.d("ModelInfo", "Input shape: ${inputTensorShape.contentToString()}")
        Log.d("ModelInfo", "Output shape: ${outputTensorShape.contentToString()}")
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraDevice?.close()
        tflite.close()
    }
}*/



/*package com.hfad.camera2

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var switchCameraButton: Button
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var currentCameraId: String? = null
    private lateinit var tflite: Interpreter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        // Load the TensorFlow Lite model
        tflite = Interpreter(loadModelFile())
        Log.d("TFLite", "Model loaded successfully")

        val cameraIdList = cameraManager.cameraIdList
        currentCameraId = cameraIdList.firstOrNull()

        // Request Camera Permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        } else {
            setupCamera()
        }

        switchCameraButton.setOnClickListener {
            switchCamera()
        }

        // Print model input & output details for debugging
        printModelDetails()
    }

    private fun setupCamera() {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                processFrame()
            }
        }
    }

    private fun openCamera() {
        try {
            val cameraIdList = cameraManager.cameraIdList
            if (cameraIdList.isEmpty()) {
                Log.e("Camera2", "No available cameras")
                return
            }

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
                    session.setRepeatingRequest(captureRequestBuilder.build(), null, null)
                    Log.d("Camera2", "Camera preview started")
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("Camera2", "Camera configuration failed")
                }
            }, null)

        } catch (e: CameraAccessException) {
            Log.e("Camera2", "Start preview error: ${e.message}")
        }
    }

    private fun processFrame() {
        val bitmap = textureView.bitmap ?: return

        val inputBuffer = preprocessBitmap(bitmap)

        // HRNetPose output (assuming 17 keypoints, each with (x,y) coords)
        val outputArray = Array(1) { Array(64) { Array(48) { FloatArray(17) } } }

        try {
            tflite.run(inputBuffer, outputArray)
            Log.d("PoseEstimation", "Detected Keypoints: ${outputArray.contentDeepToString()}")
        } catch (e: Exception) {
            Log.e("TFLite", "Inference failed: ${e.message}")
        }
        //tflite.run(inputBuffer, outputArray)

        //Log.d("PoseEstimation", "Detected Keypoints: ${outputArray.contentDeepToString()}")

        //drawKeypointsOnBitmap(bitmap, outputArray)
    }

    private fun drawKeypointsOnBitmap(bitmap: Bitmap, keypoints: Array<Array<FloatArray>>) {
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.RED
            strokeWidth = 5f
        }

        for (point in keypoints[0]) {
            val x = point[0] * bitmap.width  // Scale x to image size
            val y = point[1] * bitmap.height // Scale y to image size
            canvas.drawCircle(x, y, 8f, paint)
        }

        runOnUiThread {
            textureView.invalidate()  // Force UI update
        }
    }


    /*val bitmap = textureView.bitmap ?: return

        // Preprocess the image for HRNet model
        val inputArray = preprocessBitmap(Bitmap.createScaledBitmap(bitmap, 192, 192, true))

        // HRNet Pose Model output (assuming 17 keypoints with (x,y) coordinates)
        val outputArray = Array(1) { Array(17) { FloatArray(2) } }

        // Run inference
        tflite.run(inputArray, outputArray)

        // Log the detected keypoints
        Log.d("PoseEstimation", "Detected Keypoints: ${outputArray.contentDeepToString()}")*/


    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val inputWidth = 192
        val inputHeight = 256  // Adjusted to match the model's expected input shape

        // Resize to match model input dimensions
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)

        val byteBuffer = ByteBuffer.allocateDirect(4 * inputWidth * inputHeight * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputWidth * inputHeight)
        resizedBitmap.getPixels(intValues, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        for (pixelValue in intValues) {
            byteBuffer.putFloat(((pixelValue shr 16) and 0xFF) / 255.0f) // Red
            byteBuffer.putFloat(((pixelValue shr 8) and 0xFF) / 255.0f)  // Green
            byteBuffer.putFloat((pixelValue and 0xFF) / 255.0f)          // Blue
        }

        return byteBuffer
    }
        /*val inputSize = 192  // Adjust to match HRNet input size
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        scaledBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixelValue in intValues) {
            byteBuffer.putFloat(((pixelValue shr 16) and 0xFF) / 255.0f)  // Red
            byteBuffer.putFloat(((pixelValue shr 8) and 0xFF) / 255.0f)   // Green
            byteBuffer.putFloat((pixelValue and 0xFF) / 255.0f)           // Blue
        }

        return byteBuffer
    }*/
        /* Array<Array<Array<FloatArray>>>
        val width = bitmap.width
        val height = bitmap.height
        val floatValues = Array(1) { Array(height) { Array(width) { FloatArray(3) } } }
        val intValues = IntArray(width * height)
        bitmap.getPixels(intValues, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = intValues[y * width + x]
                floatValues[0][y][x][0] = ((pixel shr 16 and 0xFF) / 255.0f)
                floatValues[0][y][x][1] = ((pixel shr 8 and 0xFF) / 255.0f)
                floatValues[0][y][x][2] = ((pixel and 0xFF) / 255.0f)
            }
        }

        return floatValues*/


    private fun switchCamera() {
        cameraDevice?.close()
        cameraDevice = null

        val cameraIdList = cameraManager.cameraIdList
        if (cameraIdList.size > 1) {
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

    private fun loadModelFile(): MappedByteBuffer {
            val assetManager = assets
            val files = assetManager.list("") ?: arrayOf()
            Log.d("Assets", "Files in assets: ${files.joinToString()}")

            val assetFileDescriptor = assets.openFd("hrnet_pose.tflite")
            val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = fileInputStream.channel
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, assetFileDescriptor.startOffset, assetFileDescriptor.declaredLength)
        }

        /*val assetFileDescriptor = assets.openFd("hrnet_pose.tflite")
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, assetFileDescriptor.startOffset, assetFileDescriptor.declaredLength)*/

    private fun printModelDetails() {
        val inputTensorShape = tflite.getInputTensor(0).shape()
        val outputTensorShape = tflite.getOutputTensor(0).shape()

        Log.d("ModelInfo", "Input shape: ${inputTensorShape.contentToString()}")
        Log.d("ModelInfo", "Output shape: ${outputTensorShape.contentToString()}")
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraDevice?.close()
        tflite.close()
    }
}*/

