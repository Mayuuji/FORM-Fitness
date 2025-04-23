package com.hfad.camera2

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class CompletedWorkoutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_completed_workout)

        /* ─── Workout data from the previous Activity ─── */
        val reps      = intent.getIntExtra("EXTRA_REPS", 0)
        val errors    = intent.getIntExtra("EXTRA_ERRORS", 0)
        val exercise  = intent.getStringExtra("EXTRA_EXERCISE") ?: "Unknown"
        val errorType = intent.getStringExtra("EXTRA_COMMON_ERROR") ?: "None"

        /* ─── Convert accuracy to star rating ─── */
        val stars = calculateStars(reps, errors)

        /* ─── Save workout to DB for the Stats screen ─── */
        val username = UserSessionManager(this).getLoggedInUsername() ?: "guest"
        WorkoutStatsDB(this).insertWorkout(
            username = username,
            exercise = exercise,
            reps     = reps,
            stars    = stars,
            error    = errorType
        )

        /* ─── Choose a friendly message ─── */
        val message = when (stars) {
            5    -> "Awesome! Perfect Score!"
            4    -> "Nice! Well Done!"
            3    -> "Great! Almost There"
            2    -> "Keep At It!"
            1    -> "Keep Trying!"
            else -> "Don’t Give Up!"
        }

        /* ─── Bind UI elements ─── */
        findViewById<TextView>(R.id.messageText).text = message
        findViewById<TextView>(R.id.scoreText).text   = "$stars/5"

        // Add star icons programmatically
        val iconSize = (resources.displayMetrics.density * 40).toInt()   // 40 dp
        val starsRow = findViewById<LinearLayout>(R.id.starsContainer)
        repeat(5) { i ->
            val iv = ImageView(this).apply {
                setImageResource(
                    if (i < stars) R.drawable.ic_star_filled
                    else            R.drawable.ic_star_outline
                )
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    setMargins(12, 0, 12, 0)
                }
            }
            starsRow.addView(iv)
        }

        /* ─── Return to MainActivity ─── */
        findViewById<MaterialButton>(R.id.btnReturn).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
        }
    }

    /** Accuracy‑based star algorithm */
    private fun calculateStars(reps: Int, errors: Int): Int {
        if (reps <= 0) return 0
        val accuracy = (reps - errors).toFloat() / reps
        return when {
            accuracy >= 0.95f -> 5
            accuracy >= 0.80f -> 4
            accuracy >= 0.60f -> 3
            accuracy >= 0.40f -> 2
            accuracy >= 0.20f -> 1
            else              -> 0
        }
    }
}
