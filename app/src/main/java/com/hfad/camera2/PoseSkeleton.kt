package com.hfad.camera2

/**
 * COCO‑17 edge list (joint pairs) shared by both workout screens.
 */
object PoseSkeleton {
    val lines: List<Pair<Int, Int>> = listOf(
        // head & neck
        0 to 1, 0 to 2, 1 to 3, 2 to 4,
        // shoulders & arms
        5 to 6, 5 to 7, 7 to 9, 6 to 8, 8 to 10,
        // torso & hips
        5 to 11, 6 to 12, 11 to 12,
        // legs
        11 to 13, 13 to 15, 12 to 14, 14 to 16
    )
}
