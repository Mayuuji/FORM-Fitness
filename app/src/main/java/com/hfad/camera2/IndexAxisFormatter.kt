package com.hfad.camera2

import com.github.mikephil.charting.formatter.IndexAxisValueFormatter

class IndexAxisFormatter(private val labels: List<String>) : IndexAxisValueFormatter() {
    override fun getFormattedValue(value: Float): String {
        val index = value.toInt()
        return if (index in labels.indices) labels[index] else ""
    }
}
