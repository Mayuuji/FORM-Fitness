package com.hfad.camera2

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class CurlsSetupActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_GOAL = "rep_goal"
        private const val MIN_REPS = 1
        private const val MAX_REPS = 100
    }

    private var goal = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_curls_setup)

        val tvCount   = findViewById<TextView>(R.id.rep_count)
        val btnMinus  = findViewById<Button>(R.id.btnDecrease)
        val btnPlus   = findViewById<Button>(R.id.btnIncrease)
        val btnStart  = findViewById<Button>(R.id.btnStartCurls)

        // initialize display
        tvCount.text = goal.toString()

        btnMinus.setOnClickListener {
            if (goal > MIN_REPS) {
                goal--
                tvCount.text = goal.toString()
            }
        }

        btnPlus.setOnClickListener {
            if (goal < MAX_REPS) {
                goal++
                tvCount.text = goal.toString()
            }
        }

        btnStart.setOnClickListener {
            // launch the curls activity with the chosen goal
            val intent = Intent(this, CurlsActivity::class.java)
            intent.putExtra(EXTRA_GOAL, goal)
            startActivity(intent)
            finish()
        }
    }
}
