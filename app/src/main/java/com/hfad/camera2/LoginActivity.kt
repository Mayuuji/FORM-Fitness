package com.hfad.camera2

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.DisplayMetrics
import android.widget.*
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import android.media.MediaPlayer


class LoginActivity : AppCompatActivity() {

    private lateinit var db: UserDB
    private lateinit var session: UserSessionManager
    private lateinit var videoView: VideoView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        db = UserDB(this)
        session = UserSessionManager(this)

        // Auto-login check
        if (session.isLoggedIn()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        videoView = findViewById(R.id.background_video)
        val uri = Uri.parse("android.resource://${packageName}/${R.raw.form_backing_video}")
        videoView.setVideoURI(uri)
        videoView.setOnPreparedListener { mp ->
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            applyCenterCrop(mp, videoView)
        }
        videoView.start()

        val usernameInput = findViewById<EditText>(R.id.username_input)
        val passwordInput = findViewById<EditText>(R.id.password_input)
        val loginBtn = findViewById<Button>(R.id.login_btn)
        val registerBtn = findViewById<Button>(R.id.register_btn)

        loginBtn.setOnClickListener {
            val username = usernameInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please enter both username and password.", Toast.LENGTH_SHORT).show()
            } else if (db.validateUser(username, password)) {
                session.createSession(username)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                Toast.makeText(this, "Invalid credentials.", Toast.LENGTH_SHORT).show()
            }
        }

        registerBtn.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun applyCenterCrop(mp: MediaPlayer, videoView: VideoView) {
        val videoWidth = mp.videoWidth.toFloat()
        val videoHeight = mp.videoHeight.toFloat()
        if (videoWidth == 0f || videoHeight == 0f) return

        val metrics: DisplayMetrics = resources.displayMetrics
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

    override fun onResume() {
        super.onResume()
        if (::videoView.isInitialized) {
            videoView.start()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::videoView.isInitialized && videoView.isPlaying) {
            videoView.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::videoView.isInitialized) {
            videoView.stopPlayback()
        }
    }
}
