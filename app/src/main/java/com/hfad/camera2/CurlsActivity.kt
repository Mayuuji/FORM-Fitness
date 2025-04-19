package com.hfad.camera2

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

class CurlsActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    /* ---------- COCO keypoint indices ---------- */
    private val WR = 10    // wrist
    private val ER = 8     // elbow
    private val SR = 6     // shoulder

    /* ---------- rep–count thresholds ---------- */
    private val MIN_UP_DIST       = 0.10f
    private val ELBOW_EPS         = 0.01f
    private val SHOULDER_ALLOW    = 0.05f
    private val ELBOW_MOVE_THR    = 0.02f

    /* ---------- throttle to ~10 FPS ---------- */
    private val MIN_INFERENCE_INTERVAL_MS = 100L
    private var lastInferenceTime = 0L

    /* ---------- UI ---------- */
    private lateinit var preview : TextureView
    private lateinit var overlay : ImageView
    private lateinit var repText : TextView
    private lateinit var ring    : CircularProgressIndicator
    private lateinit var advice  : TextView

    /* reusable bitmap for camera frames */
    private lateinit var cameraBitmap: Bitmap

    /* ---------- Camera2 ---------- */
    private lateinit var camMan : CameraManager
    private var camDevice      : CameraDevice? = null
    private var idFront        : String?       = null
    private var idBack         : String?       = null
    private var useFront       = false
    private var previewSize    = Size(0, 0)

    /* ---------- Pose helper ---------- */
    private lateinit var pose : PoseHelper
    private val skeleton = PoseSkeleton.lines

    private var reps   = 0
    private var curlUp = false

    /* dynamic goal, provided by setup screen */
    private var GOAL = 30
    private val busy  = AtomicBoolean(false)
    private var lastElbowY: Float? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_curls)

        // read the user’s chosen rep goal (default 30)
        GOAL = intent.getIntExtra(CurlsSetupActivity.EXTRA_GOAL, GOAL)

        // bind views
        preview = findViewById(R.id.textureView)
        overlay = findViewById(R.id.imageView)
        repText = findViewById(R.id.repNumber)
        ring    = findViewById(R.id.progressRing)
        advice  = findViewById(R.id.adviceText)

        // exit button
        findViewById<Button>(R.id.btnExit).setOnClickListener { finish() }
        // switch‐camera button
        findViewById<Button>(R.id.btnSwitchCamera).setOnClickListener {
            useFront = !useFront
            camDevice?.close()
            configureCamera()
            openCamera()
        }

        // camera IDs
        camMan = getSystemService(CAMERA_SERVICE) as CameraManager
        camMan.cameraIdList.forEach { id ->
            when (camMan.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> idFront = id
                CameraCharacteristics.LENS_FACING_BACK  -> idBack  = id
            }
        }

        // load TFLite model
        val modelBuf = assets.openFd("hrnet_pose.tflite").use { afd ->
            FileInputStream(afd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
        }
        pose = PoseHelper(modelBuf)

        // request camera permission if needed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), 101
            )
        }

        // TextureView callbacks
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

    /** Choose best preview size & letterbox */
    private fun configureCamera() {
        val st = preview.surfaceTexture ?: return
        val vw = preview.width
        val vh = preview.height

        val camId = if (useFront) idFront else idBack
        camId ?: return

        val map = camMan.getCameraCharacteristics(camId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return
        val choices = map.getOutputSizes(SurfaceTexture::class.java)

        previewSize = choices
            .filter { abs(it.width.toFloat() / it.height - vw.toFloat() / vh) < 0.01f }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: choices.maxByOrNull { it.width.toLong() * it.height }!!

        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        applyFitCenter(vw, vh)
    }

    /** Open chosen camera */
    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val camId = if (useFront) idFront else idBack
        camId ?: return

        camMan.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camDevice = device
                startPreview()
            }
            override fun onDisconnected(device: CameraDevice) { device.close() }
            override fun onError(device: CameraDevice, error: Int) { device.close() }
        }, null)
    }

    /** Start the preview repeating request */
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

    /** Called on each new frame; throttles and runs pose inference */
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

    /** Update rep count, ring progress, and advice */
    private fun updateUI(kps: List<Keypoint>) {
        val w = kps[WR]
        val e = kps[ER]
        val s = kps[SR]

        if (w.score < PoseHelper.CONF_THRESHOLD
            || e.score < PoseHelper.CONF_THRESHOLD
            || s.score < PoseHelper.CONF_THRESHOLD
        ) {
            advice.text = ""
            return
        }

        val currElbowY = e.y
        val showAdvice = lastElbowY?.let { abs(currElbowY - it) > ELBOW_MOVE_THR } ?: false
        advice.text = if (showAdvice) getString(R.string.avoid_moving_elbows) else ""
        lastElbowY = currElbowY

        val down = w.y > e.y + ELBOW_EPS
        val up   = (e.y - w.y) > MIN_UP_DIST &&
                w.y < e.y - ELBOW_EPS &&
                w.y > s.y - SHOULDER_ALLOW

        if (!curlUp && up) curlUp = true
        if (curlUp && down) {
            curlUp = false
            reps++
            repText.text = reps.toString()
            ring.progress = (reps.coerceAtMost(GOAL) * 100) / GOAL
        }
    }

    /** Draw bones & keypoints */
    private fun drawSkeleton(kps: List<Keypoint>, src: Bitmap) {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)
        val lineP = Paint().apply { color = Color.GREEN; strokeWidth = 4f }
        val dotP  = Paint().apply { color = Color.RED }
        val pts   = kps.map { Pair(it.x * bmp.width, it.y * bmp.height) }

        skeleton.forEach { (a, b) ->
            val ka = kps[a]; val kb = kps[b]
            if (ka.score >= PoseHelper.CONF_THRESHOLD &&
                kb.score >= PoseHelper.CONF_THRESHOLD) {
                canvas.drawLine(pts[a].first, pts[a].second,
                    pts[b].first, pts[b].second, lineP)
            }
        }

        kps.forEachIndexed { i, kp ->
            if (kp.score >= PoseHelper.CONF_THRESHOLD) {
                canvas.drawCircle(pts[i].first, pts[i].second, 6f, dotP)
            }
        }

        overlay.setImageBitmap(bmp)
    }

    /** Letter‑box center crop so preview never stretches */
    private fun applyFitCenter(viewW: Int, viewH: Int) {
        if (previewSize.width == 0) return
        val pr = previewSize.width.toFloat() / previewSize.height
        val vr = viewW.toFloat() / viewH
        val scale = min(pr / vr, vr / pr)
        preview.setTransform(Matrix().apply {
            setScale(scale, scale, viewW / 2f, viewH / 2f)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        camDevice?.close()
        pose.close()
        cancel()  // cancel coroutines
    }
}
