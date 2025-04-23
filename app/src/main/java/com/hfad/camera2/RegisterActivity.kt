package com.hfad.camera2

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.DisplayMetrics
import android.widget.*
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import android.media.MediaPlayer


class RegisterActivity : AppCompatActivity() {

    private lateinit var db: UserDB
    private lateinit var session: UserSessionManager
    private lateinit var videoView: VideoView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        db = UserDB(this)
        session = UserSessionManager(this)

        videoView = findViewById(R.id.register_video)
        val uri = Uri.parse("android.resource://${packageName}/${R.raw.form_backing_video}")
        videoView.setVideoURI(uri)
        videoView.setOnPreparedListener { mp ->
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            applyCenterCrop(mp, videoView)
        }
        videoView.start()

        val createUserInput = findViewById<EditText>(R.id.createuser_btn)
        val createPasswordInput = findViewById<EditText>(R.id.createpassword_btn)
        val registerButton = findViewById<Button>(R.id.register_button)
        val backButton = findViewById<Button>(R.id.back_button)

        registerButton.setOnClickListener {
            val username = createUserInput.text.toString().trim()
            val password = createPasswordInput.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            } else if (db.userExists(username)) {
                Toast.makeText(this, "Username already exists", Toast.LENGTH_SHORT).show()
            } else {
                val newUser = User(username = username, password = password)
                db.insertUser(newUser)
                session.createSession(username)
                Toast.makeText(this, "Registered successfully!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }

        backButton.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
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
