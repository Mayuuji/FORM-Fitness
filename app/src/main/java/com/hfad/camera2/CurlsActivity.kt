package com.hfad.camera2

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.max

@SuppressLint("MissingPermission")
class CurlsActivity : AppCompatActivity(), CoroutineScope {

    companion object {
        private const val CAMERA_REQUEST_CODE = 102
        private const val FRAME_SAMPLE        = 1
        private const val SCALE_FACTOR        = 1.9f
    }

    /** Coroutine setup */
    private lateinit var job: Job
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    /** UI */
    private lateinit var preview   : TextureView
    private lateinit var repText   : TextView
    private lateinit var ring      : CircularProgressIndicator
    private lateinit var advice    : TextView
    private lateinit var switchCam : ImageButton
    private lateinit var exitBtn   : ImageButton

    /** Camera 2 */
    private lateinit var camMan   : CameraManager
    private var camDevice         : CameraDevice? = null
    private var idFront           : String? = null
    private var idBack            : String? = null
    private var useFront          = true

    /** Pose model */
    private lateinit var pose : PoseHelper

    /** Inference control */
    private var frameCount        = 0
    private var lastInferenceTime = 0L
    private val MIN_INTERVAL_MS   = 100L
    private val busy              = AtomicBoolean(false)

    /** Keypoints (COCO indices) */
    private val WR = 10  // right wrist
    private val ER = 8   // right elbow
    private val SR = 6   // right shoulder

    /** Rep‑detection tuning (more sensitive) */
    private val MIN_UP_DIST     = 0.05f
    private val DOWN_RESET_DIST = 0.03f
    private val ELBOW_MOVE_THR  = 0.02f

    /** Workout counters */
    private enum class CurlState { DOWN, UP }
    private var state          = CurlState.DOWN
    private var reps           = 0
    private var goal           = 30
    private var errorCount     = 0
    private var repHasError    = false
    private var lastElbowY     : Float? = null

    /** Runnable to hide advice */
    private val hideAdviceRunnable = Runnable { advice.visibility = View.GONE }

    /* ********************************************************************** */
    /*                              LIFECYCLE                                 */
    /* ********************************************************************** */

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        job = SupervisorJob()
        setContentView(R.layout.activity_curls)

        /* bind views */
        preview   = findViewById(R.id.textureView)
        repText   = findViewById(R.id.repNumber)
        ring      = findViewById(R.id.progressRing)
        advice    = findViewById(R.id.adviceText)
        switchCam = findViewById(R.id.btnSwitchCamera)
        exitBtn   = findViewById(R.id.btnExit)

        /* read goal from setup screen */
        goal = intent.getIntExtra(CurlsSetupActivity.EXTRA_GOAL, goal)

        /* progress ring uses percentage scale (0–100) */
        ring.max = 100
        ring.progress = 0

        /* load pose model */
        pose = loadPoseModel()

        /* enumerate cameras */
        camMan = ContextCompat.getSystemService(this, CameraManager::class.java)!!
        camMan.cameraIdList.forEach { id ->
            when (camMan.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> idFront = id
                CameraCharacteristics.LENS_FACING_BACK  -> idBack  = id
            }
        }

        switchCam.setOnClickListener {
            useFront = !useFront
            camDevice?.close()
            openCamera()
        }
        exitBtn.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                val camId = (if (useFront) idFront else idBack) ?: return
                val map   = camMan.getCameraCharacteristics(camId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
                val best  = map.getOutputSizes(SurfaceTexture::class.java)
                    .maxByOrNull { it.width.toLong() * it.height }!!

                st.setDefaultBufferSize(best.width, best.height)

                val sx = w.toFloat() / best.width
                val sy = h.toFloat() / best.height
                val scale = max(sx, sy) * SCALE_FACTOR
                preview.setTransform(Matrix().apply { setScale(scale, scale, w/2f, h/2f) })

                openCamera()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = false
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) { runInference() }
        }

        /* camera permission */
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_CODE)
        }
    }

    /* ********************************************************************** */
    /*                             CAMERA HELPERS                             */
    /* ********************************************************************** */

    private fun openCamera() {
        val camId = (if (useFront) idFront else idBack) ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) return

        camMan.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) { camDevice = device; startPreview() }
            override fun onDisconnected(device: CameraDevice) { device.close() }
            override fun onError(device: CameraDevice, error: Int) { device.close() }
        }, null)
    }

    private fun startPreview() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) return
        val surf = Surface(preview.surfaceTexture!!)
        camDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
            addTarget(surf)
            camDevice?.createCaptureSession(listOf(surf),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        session.setRepeatingRequest(build(), null, null)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                }, null)
        }
    }

    /* ********************************************************************** */
    /*                         INFERENCE & REP LOGIC                          */
    /* ********************************************************************** */

    private fun runInference() {
        if (++frameCount % FRAME_SAMPLE != 0) return
        if (busy.getAndSet(true))              return

        val now = SystemClock.uptimeMillis()
        if (now - lastInferenceTime < MIN_INTERVAL_MS) {
            busy.set(false); return
        }
        lastInferenceTime = now

        val bmp = Bitmap.createBitmap(preview.width, preview.height, Bitmap.Config.ARGB_8888)
        preview.getBitmap(bmp)

        launch {
            val kps = withContext(Dispatchers.Default) { pose.runPose(bmp) }
            withContext(Dispatchers.Main) { updateUI(kps); busy.set(false) }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateUI(kps: List<Keypoint>) {

        val wrist = kps[WR]
        val elbow = kps[ER]
        val shldr = kps[SR]

        /* confidence gate */
        if (listOf(wrist, elbow, shldr)
                .any { it.score < PoseHelper.CONF_THRESHOLD }) {
            advice.visibility = View.GONE
            return
        }

        /* simple elbow‑drift advice */
        val drift = lastElbowY?.let { abs(elbow.y - it) > ELBOW_MOVE_THR } ?: false
        if (drift) {
            advice.apply {
                text = getString(R.string.avoid_moving_elbows)
                visibility = View.VISIBLE
                removeCallbacks(hideAdviceRunnable)
                postDelayed(hideAdviceRunnable, 5_000L)
            }
            repHasError = true
        } else advice.visibility = View.GONE
        lastElbowY = elbow.y

        /* state‑machine rep counting */
        when (state) {
            CurlState.DOWN -> {
                if (elbow.y - wrist.y > MIN_UP_DIST) {
                    state = CurlState.UP
                }
            }
            CurlState.UP -> {
                if (wrist.y - elbow.y > DOWN_RESET_DIST) {
                    state = CurlState.DOWN
                    reps++

                    repText.text = reps.toString()
                    ring.progress = (reps.coerceAtMost(goal) * 100) / goal

                    if (repHasError) errorCount++
                    repHasError = false

                    if (reps >= goal) {
                        startActivity(Intent(this, CompletedWorkoutActivity::class.java).apply {
                            putExtra("EXTRA_REPS", reps)
                            putExtra("EXTRA_ERRORS", errorCount)
                            putExtra("EXTRA_EXERCISE", "Curls")
                        })
                        finish()
                    }
                }
            }
        }
    }

    /* ********************************************************************** */
    /*                               UTILITIES                                */
    /* ********************************************************************** */

    private fun loadPoseModel(): PoseHelper {
        assets.openFd("hrnet_pose.tflite").use { afd ->
            val buf: MappedByteBuffer = FileInputStream(afd.fileDescriptor)
                .channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            return PoseHelper(buf)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        camDevice?.close()
        pose.close()
    }
}
