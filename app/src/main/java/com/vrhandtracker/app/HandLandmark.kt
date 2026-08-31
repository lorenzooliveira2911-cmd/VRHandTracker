package com.vrhandtracker.app

import android.opengl.Matrix

/**
 * Hand landmark data structure matching MediaPipe Hand Landmarker output
 * 21 landmarks per hand
 */
data class HandLandmark(
    val x: Float,      // Normalized 0-1
    val y: Float,      // Normalized 0-1
    val z: Float,      // Normalized depth
    val visibility: Float = 1.0f,
    val presence: Float = 1.0f
) {
    companion object {
        // Landmark indices
        const val WRIST = 0
        // Thumb
        const val THUMB_CMC = 1
        const val THUMB_MCP = 2
        const val THUMB_IP = 3
        const val THUMB_TIP = 4
        // Index
        const val INDEX_MCP = 5
        const val INDEX_PIP = 6
        const val INDEX_DIP = 7
        const val INDEX_TIP = 8
        // Middle
        const val MIDDLE_MCP = 9
        const val MIDDLE_PIP = 10
        const val MIDDLE_DIP = 11
        const val MIDDLE_TIP = 12
        // Ring
        const val RING_MCP = 13
        const val RING_PIP = 14
        const val RING_DIP = 15
        const val RING_TIP = 16
        // Pinky
        const val PINKY_MCP = 17
        const val PINKY_PIP = 18
        const val PINKY_DIP = 19
        const val PINKY_TIP = 20

        // Bone connections (parent -> child)
        val BONE_CONNECTIONS = listOf(
            // Thumb
            WRIST to THUMB_CMC, THUMB_CMC to THUMB_MCP, THUMB_MCP to THUMB_IP, THUMB_IP to THUMB_TIP,
            // Index
            WRIST to INDEX_MCP, INDEX_MCP to INDEX_PIP, INDEX_PIP to INDEX_DIP, INDEX_DIP to INDEX_TIP,
            // Middle
            WRIST to MIDDLE_MCP, MIDDLE_MCP to MIDDLE_PIP, MIDDLE_PIP to MIDDLE_DIP, MIDDLE_DIP to MIDDLE_TIP,
            // Ring
            WRIST to RING_MCP, RING_MCP to RING_PIP, RING_PIP to RING_DIP, RING_DIP to RING_TIP,
            // Pinky
            WRIST to PINKY_MCP, PINKY_MCP to PINKY_PIP, PINKY_PIP to PINKY_DIP, PINKY_DIP to PINKY_TIP
        )

        val FINGER_TIPS = intArrayOf(THUMB_TIP, INDEX_TIP, MIDDLE_TIP, RING_TIP, PINKY_TIP)
        val FINGER_INDICES = listOf(
            intArrayOf(THUMB_CMC, THUMB_MCP, THUMB_IP, THUMB_TIP),
            intArrayOf(INDEX_MCP, INDEX_PIP, INDEX_DIP, INDEX_TIP),
            intArrayOf(MIDDLE_MCP, MIDDLE_PIP, MIDDLE_DIP, MIDDLE_TIP),
            intArrayOf(RING_MCP, RING_PIP, RING_DIP, RING_TIP),
            intArrayOf(PINKY_MCP, PINKY_PIP, PINKY_DIP, PINKY_TIP)
        )
    }

    fun toVector3(): FloatArray = floatArrayOf(x, y, z)

    fun distanceTo(other: HandLandmark): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }
}

data class HandData(
    val landmarks: List<HandLandmark>,  // 21 landmarks
    val handedness: String,              // "Left" or "Right"
    val confidence: Float,
    val timestamp: Long
) {
    val isLeftHand: Boolean get() = handedness == "Left"

    fun getFingerTips(): List<HandLandmark> {
        return HandLandmark.FINGER_TIPS.map { landmarks[it] }
    }

    fun isFingerExtended(fingerIndices: IntArray): Boolean {
        if (fingerIndices.size < 4) return false
        val tip = landmarks[fingerIndices[3]]
        val pip = landmarks[fingerIndices[1]]
        val wrist = landmarks[HandLandmark.WRIST]
        val tipDist = tip.distanceTo(wrist)
        val pipDist = pip.distanceTo(wrist)
        return tipDist > pipDist * 1.1f
    }

    fun getPalmCenter(): HandLandmark {
        // Average of MCP joints
        val mcpIndices = intArrayOf(
            HandLandmark.INDEX_MCP,
            HandLandmark.MIDDLE_MCP,
            HandLandmark.RING_MCP,
            HandLandmark.PINKY_MCP
        )
        var sumX = 0f
        var sumY = 0f
        var sumZ = 0f
        for (idx in mcpIndices) {
            val lm = landmarks[idx]
            sumX += lm.x
            sumY += lm.y
            sumZ += lm.z
        }
        return HandLandmark(sumX / 4, sumY / 4, sumZ / 4)
    }
}