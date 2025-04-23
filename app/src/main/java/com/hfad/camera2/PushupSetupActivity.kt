package com.hfad.camera2

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class PushupSetupActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GOAL = "com.hfad.camera2.EXTRA_GOAL"
    }

    private var goal = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pushup_setup)

        val repCountTv = findViewById<TextView>(R.id.rep_count)
        val btnDec     = findViewById<Button>(R.id.btnDecrease)
        val btnInc     = findViewById<Button>(R.id.btnIncrease)
        val btnStart   = findViewById<Button>(R.id.btnStart)

        // initialize
        repCountTv.text = goal.toString()

        btnDec.setOnClickListener {
            if (goal > 1) {
                goal--
                repCountTv.text = goal.toString()
            }
        }

        btnInc.setOnClickListener {
            goal++
            repCountTv.text = goal.toString()
        }

        btnStart.setOnClickListener {
            Intent(this, PushupActivity::class.java).also {
                it.putExtra(EXTRA_GOAL, goal)
                startActivity(it)
            }
            finish()
        }
    }
}
