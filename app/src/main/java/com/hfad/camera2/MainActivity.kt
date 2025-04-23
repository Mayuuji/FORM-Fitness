package com.hfad.camera2

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.DisplayMetrics
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    companion object {
        private const val CAMERA_REQUEST_CODE = 100
    }

    private lateinit var videoView: VideoView
    private var mediaPlayer: MediaPlayer? = null
    private var pendingIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check session first
        val session = UserSessionManager(this)
        if (!session.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // Transparent system bars
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_launch)

        findViewById<MaterialButton>(R.id.btnStats).setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnPushups).setOnClickListener {
            pendingIntent = Intent(this, PushupSetupActivity::class.java)
            checkCameraPermissionThenLaunch()
        }

        findViewById<MaterialButton>(R.id.btnCurls).setOnClickListener {
            pendingIntent = Intent(this, CurlsSetupActivity::class.java)
            checkCameraPermissionThenLaunch()
        }

        findViewById<MaterialButton>(R.id.btnSquats).setOnClickListener {
            pendingIntent = Intent(this, SquatSetupActivity::class.java)
            checkCameraPermissionThenLaunch()
        }

        findViewById<MaterialButton>(R.id.btnLogout).setOnClickListener {
            session.clearSession()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        setupVideo()
    }

    private fun checkCameraPermissionThenLaunch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_REQUEST_CODE
            )
        } else {
            pendingIntent?.let { startActivity(it) }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            pendingIntent?.let { startActivity(it) }
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupVideo() {
        videoView = findViewById(R.id.videoView)
        val uri = Uri.parse("android.resource://$packageName/${R.raw.form_backing_video}")
        videoView.setVideoURI(uri)
        videoView.requestFocus()
        videoView.setOnPreparedListener { mp ->
            mediaPlayer = mp
            mp.isLooping = true
            applyCenterCrop(mp)
            mp.start()
        }
        videoView.setOnErrorListener { _, _, _ ->
            videoView.visibility = VideoView.GONE
            false
        }
    }

    override fun onResume() {
        super.onResume()
        mediaPlayer?.let { applyCenterCrop(it) }
        if (::videoView.isInitialized && !videoView.isPlaying) {
            videoView.start()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::videoView.isInitialized && videoView.isPlaying) {
            videoView.pause()
        }
    }

    private fun applyCenterCrop(mp: MediaPlayer) {
        val videoWidth  = mp.videoWidth.toFloat()
        val videoHeight = mp.videoHeight.toFloat()
        if (videoWidth == 0f || videoHeight == 0f) return

        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()

        val videoRatio = videoWidth / videoHeight
        val screenRatio = screenWidth / screenHeight
        val baseScale = if (videoRatio > screenRatio) {
            videoRatio / screenRatio
        } else {
            screenRatio / videoRatio
        }
        val scale = baseScale * 1.05f

        videoView.scaleX = scale
        videoView.scaleY = scale
    }
}
