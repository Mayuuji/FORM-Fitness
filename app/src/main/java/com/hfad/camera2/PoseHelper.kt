package com.hfad.camera2

import android.graphics.Bitmap
import android.graphics.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer

/** A single detected keypoint (relative coords + confidence) */
data class Keypoint(val x: Float, val y: Float, val score: Float)

class PoseHelper(model: MappedByteBuffer) {
    companion object {
        /** Filter out any keypoints below this confidence */
        const val CONF_THRESHOLD = 0.2f
    }

    // HRNet input dims (portrait)
    private val IW = 192
    private val IH = 256

    // reusable buffers
    private val buf = ByteBuffer.allocateDirect(4 * IW * IH * 3)
        .order(ByteOrder.nativeOrder())
    private val pixels = IntArray(IW * IH)
    private val heat = Array(1) { Array(64) { Array(48) { FloatArray(17) } } }

    private val tflite: Interpreter
    private val delegate: GpuDelegate?

    init {
        // Try GPU first
        var gpu: GpuDelegate? = null
        var itf: Interpreter
        try {
            gpu = GpuDelegate()
            itf = Interpreter(model, Interpreter.Options().addDelegate(gpu).setNumThreads(2))
        } catch (_: Throwable) {
            gpu?.close()
            itf = Interpreter(model, Interpreter.Options().setNumThreads(4))
        }
        tflite = itf
        delegate = gpu
    }

    /** Runs HRNet on the given bitmap and returns 17 keypoints with confidence. */
    suspend fun runPose(src0: Bitmap): List<Keypoint> = withContext(Dispatchers.Default) {
        // rotate if landscape
        val src = if (src0.width > src0.height) {
            Bitmap.createBitmap(
                src0, 0, 0, src0.width, src0.height,
                Matrix().apply { postRotate(90f) }, true
            )
        } else src0

        // resize + normalize
        val scaled = Bitmap.createScaledBitmap(src, IW, IH, true)
        scaled.getPixels(pixels, 0, IW, 0, 0, IW, IH)
        buf.rewind()
        for (p in pixels) {
            buf.putFloat(((p ushr 16) and 0xFF) / 255f)
            buf.putFloat(((p ushr 8)  and 0xFF) / 255f)
            buf.putFloat((p           and 0xFF) / 255f)
        }

        // run
        tflite.run(buf, heat)

        // extract maxima
        val H = heat[0].size
        val W = heat[0][0].size
        val N = heat[0][0][0].size
        List(N) { k ->
            var best = Float.MIN_VALUE
            var br = 0; var bc = 0
            for (r in 0 until H) for (c in 0 until W) {
                val v = heat[0][r][c][k]
                if (v > best) { best = v; br = r; bc = c }
            }
            Keypoint(
                x = (bc + 0.5f) / W,
                y = (br + 0.5f) / H,
                score = best
            )
        }
    }

    /** Simple helper for drift advice */
    fun elbowDrift(elbow: Keypoint, shoulder: Keypoint): Float =
        kotlin.math.abs(elbow.y - shoulder.y)

    fun close() {
        tflite.close()
        delegate?.close()
    }
}
