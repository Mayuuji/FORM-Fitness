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
class SquatActivity : AppCompatActivity(), CoroutineScope {
    companion object {
        private const val CAMERA_REQUEST_CODE = 103
        private const val FRAME_SAMPLE       = 1
        private const val SCALE_FACTOR       = 1.9f
        private const val MIN_INTERVAL_MS    = 100L

        // rep detection: count when hips and knees are level within this tolerance
        private const val REP_LEVEL_THR      = 0.02f
        // reset rep gating when hips move away by at least this amount
        private const val RESET_Y_THR        = 0.05f

        // error thresholds
        private const val NARROW_THR         = 0.8f
        private const val WIDE_THR           = 1.5f
        private const val LEAN_THR           = 0.10f
    }

    private lateinit var job: Job
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    // UI (reusing activity_curls.xml)
    private lateinit var preview    : TextureView
    private lateinit var repNumber  : TextView
    private lateinit var ring       : CircularProgressIndicator
    private lateinit var advice     : TextView
    private lateinit var switchCam  : ImageButton
    private lateinit var exitBtn    : ImageButton

    // rep goal
    private var goal = 10

    // Camera2
    private lateinit var camMan     : CameraManager
    private var camDevice: CameraDevice? = null
    private var idFront : String?       = null
    private var idBack  : String?       = null
    private var useFront = true

    // Pose model
    private lateinit var pose       : PoseHelper

    // Keypoint indices
    private val LS = 5; private val RS = 6    // shoulders
    private val LH = 11; private val RH = 12  // hips
    private val LK = 13; private val RK = 14  // knees
    private val LA = 15; private val RA = 16  // ankles

    // rep & error state
    private var reps          = 0
    private var canCount      = true
    private var firstRepDone  = false
    private var errorCount    = 0

    // inference throttle
    private var lastInferenceTime = 0L
    private val busy              = AtomicBoolean(false)
    private var frameCount        = 0

    // buffer for camera frames
    private lateinit var cameraBmp: Bitmap

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        job = SupervisorJob()
        setContentView(R.layout.activity_curls)

        // bind UI
        preview    = findViewById(R.id.textureView)
        repNumber  = findViewById(R.id.repNumber)
        ring       = findViewById(R.id.progressRing)
        advice     = findViewById(R.id.adviceText)
        switchCam  = findViewById(R.id.btnSwitchCamera)
        exitBtn    = findViewById(R.id.btnExit)

        // read goal
        goal = intent.getIntExtra(SquatSetupActivity.EXTRA_GOAL, goal)
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
        exitBtn.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
                .apply {
                    // clear anything on top of MainActivity so you return directly to it
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                }
            startActivity(intent)
            // finish this activity so it can’t come back on Back
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
                val s  = max(sx, sy) * SCALE_FACTOR
                preview.setTransform(Matrix().apply { setScale(s, s, w / 2f, h / 2f) })

                openCamera()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = false
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) { runInference() }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA),
                CAMERA_REQUEST_CODE
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val camId = (if (useFront) idFront else idBack) ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) return

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
            != PackageManager.PERMISSION_GRANTED) return
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
                }, null
            )
        }
    }

    private fun runInference() {
        if (++frameCount % FRAME_SAMPLE != 0) return
        if (busy.getAndSet(true)) return
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
                detectSquatRepsAndErrors(kps)
                busy.set(false)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun detectSquatRepsAndErrors(kps: List<Keypoint>) {
        // require confidence
        val lh = kps[LH]; val rh = kps[RH]
        val lk = kps[LK]; val rk = kps[RK]
        val la = kps[LA]; val ra = kps[RA]
        val ls = kps[LS]; val rs = kps[RS]
        if (listOf(lh, rh, lk, rk, la, ra, ls, rs)
                .any { it.score < PoseHelper.CONF_THRESHOLD }) {
            advice.visibility = View.GONE
            return
        }

        // midpoints & spans
        val midHipY    = (lh.y + rh.y) / 2f
        val midKneeY   = (lk.y + rk.y) / 2f
        val midAnkleX  = (la.x + ra.x) / 2f
        val midKneeX   = (lk.x + rk.x) / 2f
        val shoulderSp = abs(ls.x - rs.x)
        val footSp     = abs(la.x - ra.x)
        val diff       = midHipY - midKneeY  // positive = hips below knees

        // count rep when level within tolerance
        if (canCount && abs(diff) < REP_LEVEL_THR) {
            reps++
            canCount = false
            firstRepDone = true

            repNumber.text = reps.toString()
            ring.progress  = reps.coerceAtMost(goal)

            // after first rep, check foot & lean errors
            if (firstRepDone) {
                when {
                    footSp < shoulderSp * NARROW_THR -> {
                        showError(getString(R.string.feet_too_narrow))
                        errorCount++
                    }
                    footSp > shoulderSp * WIDE_THR -> {
                        showError(getString(R.string.feet_too_wide))
                        errorCount++
                    }
                }
                if (midKneeX - midAnkleX > LEAN_THR) {
                    showError(getString(R.string.lean_back))
                    errorCount++
                }
                if ((ls.x + rs.x)/2f - midKneeX > LEAN_THR) {
                    showError(getString(R.string.lean_forward))
                    errorCount++
                }
            }

            // finish
            if (reps >= goal) {
                val intent = Intent(this, CompletedWorkoutActivity::class.java).apply {
                    putExtra("EXTRA_REPS", reps)
                    putExtra("EXTRA_ERRORS", errorCount)
                    putExtra("EXTRA_EXERCISE", "Squats")   // ★ NEW
                }
                startActivity(intent)
                finish()
                return
            }
        }

        // reset gating once hips move away
        if (!canCount && abs(diff) > RESET_Y_THR) {
            canCount = true
        }
    }

    private fun showError(msg: String) {
        advice.text = msg
        advice.visibility = View.VISIBLE
    }

    private fun loadPoseModel(): PoseHelper {
        assets.openFd("hrnet_pose.tflite").use { afd ->
            val buf: MappedByteBuffer =
                FileInputStream(afd.fileDescriptor)
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
