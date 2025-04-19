package com.hfad.camera2

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

@SuppressLint("SetTextI18n", "MissingPermission")
class SquatActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    private val LS = 5
    private val RS = 6
    private val LW = 9
    private val RW = 10

    private val DOWN_THR = 0.10f
    private val UP_THR   = 0.20f
    private val Z_THR    = 0.02f

    private val MIN_INFERENCE_INTERVAL_MS = 100L
    private var lastInferenceTime = 0L

    private lateinit var preview   : TextureView
    private lateinit var overlay   : ImageView
    private lateinit var repView   : TextView
    private lateinit var handPos   : TextView
    private lateinit var depthWarn : TextView
    private lateinit var zWarn     : TextView

    private lateinit var cameraBitmap: Bitmap

    private lateinit var camMan     : CameraManager
    private var camDevice           : CameraDevice? = null
    private var idFront             : String?       = null
    private var idBack              : String?       = null
    private var useFront            = true
    private var previewSize         = Size(0, 0)

    private lateinit var pose       : PoseHelper
    private val skeleton            = PoseSkeleton.lines
    private var reps                = 0
    private var goingDown           = false
    private val busy                = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_squat)

        preview   = findViewById(R.id.textureView)
        overlay   = findViewById(R.id.imageView)
        repView   = findViewById(R.id.textViewRepCount)
        handPos   = findViewById(R.id.textViewHandPosition)
        depthWarn = findViewById(R.id.textViewDepthWarning)
        zWarn     = findViewById(R.id.textViewHandZPosition)

        findViewById<Button>(R.id.switchCameraButton).setOnClickListener {
            useFront = !useFront
            camDevice?.close()
            configureCamera()
            openCamera()
        }
        findViewById<Button>(R.id.testImageButton).visibility = View.GONE

        camMan = getSystemService(CAMERA_SERVICE) as CameraManager
        camMan.cameraIdList.forEach { id ->
            val facing = camMan.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                idFront = id
            } else if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                idBack = id
            }
        }

        val modelBuf = assets.openFd("hrnet_pose.tflite").use { afd ->
            FileInputStream(afd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
        }
        pose = PoseHelper(modelBuf)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                st: SurfaceTexture, width: Int, height: Int
            ) {
                configureCamera()
                openCamera()
            }
            override fun onSurfaceTextureSizeChanged(
                st: SurfaceTexture, width: Int, height: Int
            ) = applyFitCenter(width, height)
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = runPose()
        }
    }

    private fun configureCamera() {
        val st = preview.surfaceTexture ?: return
        val vw = preview.width
        val vh = preview.height

        val cameraId = if (useFront) idFront else idBack
        cameraId ?: return

        val map = camMan.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return
        val choices = map.getOutputSizes(SurfaceTexture::class.java)

        previewSize = choices
            .filter { abs(it.width.toFloat()/it.height - vw.toFloat()/vh) < 0.01f }
            .maxByOrNull { it.width.toLong()*it.height }
            ?: choices.maxByOrNull { it.width.toLong()*it.height }!!

        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        applyFitCenter(vw, vh)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cameraId = if (useFront) idFront else idBack
        cameraId ?: return

        camMan.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camDevice = device
                startPreview()
            }
            override fun onDisconnected(device: CameraDevice) { device.close() }
            override fun onError(device: CameraDevice, error: Int) { device.close() }
        }, null)
    }

    private fun startPreview() {
        val st = preview.surfaceTexture ?: return
        val surf = Surface(st)
        camDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
            addTarget(surf)
            camDevice?.createCaptureSession(
                listOf(surf),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        session.setRepeatingRequest(build(), null, null)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                },
                null
            )
        }
    }

    private fun runPose() {
        if (busy.getAndSet(true)) return
        val now = SystemClock.uptimeMillis()
        if (now - lastInferenceTime < MIN_INFERENCE_INTERVAL_MS) {
            busy.set(false)
            return
        }
        lastInferenceTime = now

        if (!::cameraBitmap.isInitialized) {
            cameraBitmap = Bitmap.createBitmap(
                preview.width, preview.height, Bitmap.Config.ARGB_8888
            )
        }
        preview.getBitmap(cameraBitmap)

        launch {
            val kps = pose.runPose(cameraBitmap)
            withContext(Dispatchers.Main) {
                updateUI(kps)
                drawSkeleton(kps, cameraBitmap)
                busy.set(false)
            }
        }
    }

    private fun updateUI(kps: List<Keypoint>) {
        val lsh = kps[LS]; val rsh = kps[RS]
        val lwr = kps[LW]; val rwr = kps[RW]

        if (lsh.score < PoseHelper.CONF_THRESHOLD ||
            rsh.score < PoseHelper.CONF_THRESHOLD ||
            lwr.score < PoseHelper.CONF_THRESHOLD ||
            rwr.score < PoseHelper.CONF_THRESHOLD
        ) {
            handPos.visibility = View.GONE
            depthWarn.visibility = View.GONE
            zWarn.visibility = View.GONE
            return
        }

        val shoulderSpan = abs(lsh.x - rsh.x)
        val wristSpan    = abs(lwr.x - rwr.x)
        handPos.visibility =
            if (wristSpan < shoulderSpan * 1.1f) View.VISIBLE else View.GONE

        val avgShY = (lsh.y + rsh.y)/2f
        val avgWrY = (lwr.y + rwr.y)/2f
        val diff   = abs(avgWrY - avgShY)
        depthWarn.visibility = if (diff > DOWN_THR) View.VISIBLE else View.GONE

        when {
            avgWrY < avgShY - Z_THR -> {
                zWarn.text = "Hands too high"
                zWarn.visibility = View.VISIBLE
            }
            avgWrY > avgShY + Z_THR -> {
                zWarn.text = "Hands too low"
                zWarn.visibility = View.VISIBLE
            }
            else -> zWarn.visibility = View.GONE
        }

        if (avgWrY < avgShY) return
        if (!goingDown && diff < DOWN_THR) goingDown = true
        if (goingDown && diff > UP_THR) {
            goingDown = false
            reps++
            repView.text = "Reps: $reps"
        }
    }

    private fun drawSkeleton(kps: List<Keypoint>, src: Bitmap) {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)
        val lineP = Paint().apply { color = Color.GREEN; strokeWidth = 5f }
        val dotP  = Paint().apply { color = Color.RED }
        val pts   = kps.map { Pair(it.x * bmp.width, it.y * bmp.height) }

        skeleton.forEach { (i, j) ->
            val a = kps[i]; val b = kps[j]
            if (a.score >= PoseHelper.CONF_THRESHOLD && b.score >= PoseHelper.CONF_THRESHOLD) {
                canvas.drawLine(pts[i].first, pts[i].second,
                    pts[j].first, pts[j].second, lineP)
            }
        }
        kps.forEachIndexed { idx, p ->
            if (p.score >= PoseHelper.CONF_THRESHOLD) {
                canvas.drawCircle(pts[idx].first, pts[idx].second, 8f, dotP)
            }
        }
        overlay.setImageBitmap(bmp)
    }

    private fun applyFitCenter(viewW: Int, viewH: Int) {
        if (previewSize.width == 0) return
        val pr = previewSize.width.toFloat() / previewSize.height
        val vr = viewW.toFloat() / viewH
        val scale = min(pr/vr, vr/pr)
        preview.setTransform(Matrix().apply {
            setScale(scale, scale, viewW/2f, viewH/2f)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        camDevice?.close()
        pose.close()
        cancel()
    }
}
