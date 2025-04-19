package com.hfad.camera2

import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.DisplayMetrics
import android.widget.Button
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat  // ← for setDecorFitsSystemWindows
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ← use WindowCompat for backwards compatibility
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor    = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_launch)

        findViewById<MaterialButton>(R.id.btnPushups).setOnClickListener {
            startActivity(Intent(this, PushupActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnCurls).setOnClickListener {
            startActivity(Intent(this, CurlsSetupActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnSquats).setOnClickListener {
            startActivity(Intent(this, SquatActivity::class.java))
        }

        setupVideo()  // ← now resolves
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

        val metrics      : DisplayMetrics = resources.displayMetrics
        val screenWidth  = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()

        val videoRatio  = videoWidth / videoHeight
        val screenRatio = screenWidth / screenHeight
        val baseScale   = if (videoRatio > screenRatio) {
            videoRatio / screenRatio
        } else {
            screenRatio / videoRatio
        }
        val scale = baseScale * 1.05f

        videoView.scaleX = scale
        videoView.scaleY = scale
    }
}
