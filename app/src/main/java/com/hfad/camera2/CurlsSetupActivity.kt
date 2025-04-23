package com.hfad.camera2

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class CurlsSetupActivity : AppCompatActivity() {

    companion object {
        // must be non-nullable
        const val EXTRA_GOAL: String = "com.hfad.camera2.EXTRA_GOAL"
    }
    private var goal = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_curls_setup)

        val repCountTV = findViewById<TextView>(R.id.rep_count)
        val btnDec     = findViewById<Button>(R.id.btnDecrease)
        val btnInc     = findViewById<Button>(R.id.btnIncrease)
        val btnStart   = findViewById<Button>(R.id.btnStart)

        repCountTV.text = goal.toString()

        btnDec.setOnClickListener {
            if (goal > 1) {
                goal--
                repCountTV.text = goal.toString()
            }
        }
        btnInc.setOnClickListener {
            goal++
            repCountTV.text = goal.toString()
        }
        btnStart.setOnClickListener {
            Intent(this, CurlsActivity::class.java).also {
                // EXTRA_GOAL is a non-nullable String
                it.putExtra(EXTRA_GOAL, goal)
                startActivity(it)
            }
            finish()
        }
    }
}
