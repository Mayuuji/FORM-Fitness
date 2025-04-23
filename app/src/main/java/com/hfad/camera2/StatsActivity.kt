package com.hfad.camera2

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class StatsActivity : AppCompatActivity() {

    private lateinit var statsDB: WorkoutStatsDB
    private lateinit var username: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        statsDB  = WorkoutStatsDB(this)
        username = UserSessionManager(this).getLoggedInUsername() ?: "guest"

        val stats = statsDB.getStatsForUser(username)

        populateModule(findViewById(R.id.pushup_card), stats, "Pushups")
        populateModule(findViewById(R.id.curls_card),  stats, "Curls")
        populateModule(findViewById(R.id.squats_card), stats, "Squats")

        findViewById<MaterialButton>(R.id.btnReturnStats).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
        }
    }

    private fun populateModule(cardView: View, stats: List<WorkoutStat>, type: String) {
        val data = stats.find { it.exerciseName.equals(type, ignoreCase = true) }

        cardView.findViewById<TextView>(R.id.label_title).text = "$type Total"

        val total = data?.totalReps ?: 0
        cardView.findViewById<TextView>(R.id.text_total).text = total.toString()

        val starRow  = cardView.findViewById<LinearLayout>(R.id.starsContainer)
        starRow.removeAllViews()

        val rating   = ((data?.avgStars ?: 0.0) + 0.5).toInt()
        val iconSize = (resources.displayMetrics.density * 24).toInt()

        repeat(5) { i ->
            val iv = ImageView(this).apply {
                setImageResource(
                    if (i < rating) R.drawable.ic_star_filled
                    else            R.drawable.ic_star_outline
                )
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    setMargins(4, 0, 4, 0)
                }
            }
            starRow.addView(iv)
        }
    }
}
