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
import android.util.Size
import android.view.Surface
import android.view.TextureView
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
import kotlin.math.sqrt

@SuppressLint("MissingPermission")
class PushupActivity : AppCompatActivity(), CoroutineScope {
    companion object {
        private const val CAMERA_REQUEST_CODE = 101
        private const val SCALE_FACTOR       = 1.9f

        // Frame‐sampling + throttle
        private const val FRAME_SAMPLE    = 5
        private const val MIN_INTERVAL_MS = 100L
        private const val ADVICE_MIN_MS   = 5000L
    }

    private lateinit var job: Job
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    // UI
    private lateinit var preview   : TextureView
    private lateinit var repNumber : TextView
    private lateinit var ring      : CircularProgressIndicator
    private lateinit var advice    : TextView
    private lateinit var switchCam : ImageButton
    private lateinit var exitBtn   : ImageButton

    // rep goal
    private var goal = 10

    // Camera2
    private lateinit var camMan     : CameraManager
    private var     camDevice      : CameraDevice? = null
    private var     idFront        : String?       = null
    private var     idBack         : String?       = null
    private var     useFront       = true
    private lateinit var previewSize : Size

    // Pose model
    private lateinit var pose       : PoseHelper

    // keypoint indices
    private val LS = 5; private val RS = 6
    private val LW = 9; private val RW = 10

    // distance‐based rep thresholds
    private val DIST_DOWN_THR = 0.15f   // bottom
    private val DIST_UP_THR   = 0.30f   // top

    // width advice thresholds
    private val NARROW_THR = 0.6f       // < 0.6×shoulderSpan
    private val WIDE_THR   = 1.8f       // > 1.8×shoulderSpan

    // rep counting state
    private var reps       = 0
    private var goingDown  = false

    // error tracking
    private var errorCount  = 0
    private var repHasError = false

    // inference throttle
    private var lastInferenceTime = 0L
    private val busy              = AtomicBoolean(false)
    private var frameCount        = 0

    // advice timing
    private var lastAdviceMsg: String? = null

    // bitmap buffer
    private lateinit var cameraBmp: Bitmap

    private val hideAdvice = Runnable {
        advice.visibility = TextView.GONE
        lastAdviceMsg = null
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        job = SupervisorJob()
        setContentView(R.layout.activity_pushup)

        // bind UI
        preview    = findViewById(R.id.textureView)
        repNumber  = findViewById(R.id.repNumber)
        ring       = findViewById(R.id.progressRing)
        advice     = findViewById(R.id.adviceText)
        switchCam  = findViewById(R.id.btnSwitchCamera)
        exitBtn    = findViewById(R.id.btnExit)

        // read & apply goal
        goal = intent.getIntExtra(PushupSetupActivity.EXTRA_GOAL, goal)
        ring.max = goal
        repNumber.text = "0"
        ring.progress  = 0

        // load pose model
        pose = loadPoseModel()

        // locate cameras
        camMan = getSystemService(CAMERA_SERVICE) as CameraManager
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
        exitBtn.setOnClickListener { finish() }

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(tex: SurfaceTexture, w: Int, h: Int) {
                val camId = (if (useFront) idFront else idBack) ?: return
                val map   = camMan.getCameraCharacteristics(camId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
                val best  = map.getOutputSizes(SurfaceTexture::class.java)
                    .maxByOrNull { it.width.toLong() * it.height }!!
                previewSize = best

                tex.setDefaultBufferSize(best.width, best.height)
                val sx = w.toFloat() / best.width
                val sy = h.toFloat() / best.height
                val s  = max(sx, sy) * SCALE_FACTOR
                preview.setTransform(Matrix().apply { setScale(s, s, w/2f, h/2f) })

                openCamera()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = false
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                runInference()
            }
        }

        // request CAMERA permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_REQUEST_CODE
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val camId = (if (useFront) idFront else idBack) ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return

        camMan.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camDevice = device
                startPreview()
            }
            override fun onDisconnected(device: CameraDevice) = device.close()
            override fun onError(device: CameraDevice, error: Int)   = device.close()
        }, null)
    }

    private fun startPreview() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val surf = Surface(preview.surfaceTexture!!)
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

    private fun runInference() {
        if (++frameCount % FRAME_SAMPLE != 0) return
        if (busy.getAndSet(true))       return
        val now = SystemClock.uptimeMillis()
        if (now - lastInferenceTime < MIN_INTERVAL_MS) {
            busy.set(false)
            return
        }
        lastInferenceTime = now

        if (!::cameraBmp.isInitialized) {
            cameraBmp = Bitmap.createBitmap(
                preview.width, preview.height, Bitmap.Config.ARGB_8888
            )
        }
        preview.getBitmap(cameraBmp)

        launch {
            val kps = withContext(Dispatchers.Default) { pose.runPose(cameraBmp) }
            withContext(Dispatchers.Main) {
                updateUI(kps)
                busy.set(false)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateUI(kps: List<Keypoint>) {
        val lsh = kps[LS]; val rsh = kps[RS]
        val lwr = kps[LW]; val rwr = kps[RW]

        // require confident keypoints
        if (lsh.score < PoseHelper.CONF_THRESHOLD ||
            rsh.score < PoseHelper.CONF_THRESHOLD ||
            lwr.score < PoseHelper.CONF_THRESHOLD ||
            rwr.score < PoseHelper.CONF_THRESHOLD
        ) {
            advice.visibility = TextView.GONE
            lastAdviceMsg = null
            return
        }

        // midpoints
        val shoulderSpan = abs(lsh.x - rsh.x)
        val wristSpan    = abs(lwr.x - rwr.x)

        // only wide/narrow advice
        val candidate = when {
            wristSpan < shoulderSpan * NARROW_THR -> getString(R.string.hands_too_narrow)
            wristSpan > shoulderSpan * WIDE_THR   -> getString(R.string.hands_too_wide)
            else                                  -> null
        }
        candidate?.let {
            if (it != lastAdviceMsg) {
                advice.apply {
                    text = it
                    visibility = TextView.VISIBLE
                    removeCallbacks(hideAdvice)
                    postDelayed(hideAdvice, ADVICE_MIN_MS)
                }
                lastAdviceMsg = it
                repHasError   = true
            }
        }

        // now rep counting by Euclidean distance
        val midShX = (lsh.x + rsh.x) / 2f
        val midShY = (lsh.y + rsh.y) / 2f
        val midWrX = (lwr.x + rwr.x) / 2f
        val midWrY = (lwr.y + rwr.y) / 2f
        val dx = midWrX - midShX
        val dy = midWrY - midShY
        val dist = sqrt(dx*dx + dy*dy)

        if (dist < DIST_DOWN_THR) {
            goingDown = true
        }
        if (goingDown && dist > DIST_UP_THR) {
            goingDown = false
            reps++
            repNumber.text = reps.toString()
            ring.progress  = reps.coerceAtMost(goal)

            if (repHasError) errorCount++
            repHasError = false

            if (reps >= goal) {
                startActivity(
                    Intent(this, CompletedWorkoutActivity::class.java).apply {
                        putExtra("EXTRA_REPS", reps)
                        putExtra("EXTRA_ERRORS", errorCount)
                        /*  ───── FIX ─────  */
                        putExtra("EXTRA_EXERCISE", "Pushups")
                    }
                )
                finish()
            }
        }
    }

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
