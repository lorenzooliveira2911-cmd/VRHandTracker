package com.vrhandtracker.app

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.vr.sdk.base.GvrView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VRHandRenderer(
    private val context: android.content.Context,
    private val handTracker: MediaPipeHandTracker
) : GvrView.StereoRenderer {

    companion object {
        private const val TAG = "VRHandRenderer"
        // Hand rendering
        private const val JOINT_RADIUS = 0.008f
        private const val BONE_WIDTH = 0.006f
        private const val HAND_DEPTH = 0.5f  // Distance from camera in meters
    }

    // OpenGL resources
    private var jointProgram: Int = 0
    private var boneProgram: Int = 0
    private var jointVBO: Int = 0
    private var boneVBO: Int = 0
    private var sphereVertices: FloatBuffer? = null
    private var sphereIndices: java.nio.ShortBuffer? = null
    private var indexCount: Int = 0

    // Tracking data
    private var leftHandPositions = Array(21) { FloatArray(3) }
    private var rightHandPositions = Array(21) { FloatArray(3) }
    private var leftHandVisible = false
    private var rightHandVisible = false
    private var handDataTimestamp = 0L

    // Matrices
    private val modelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Uniform locations
    private var jointMvpLoc: Int = 0
    private var jointColorLoc: Int = 0
    private var boneMvpLoc: Int = 0
    private var boneColorLoc: Int = 0

    // Colors
    private val leftHandColor = floatArrayOf(0f, 1f, 1f, 1f)   // Cyan
    private val rightHandColor = floatArrayOf(1f, 0f, 1f, 1f)  // Magenta
    private val jointColor = floatArrayOf(1f, 1f, 0f, 1f)      // Yellow
    private val boneColor = floatArrayOf(1f, 1f, 1f, 1f)       // White

    override fun onNewFrame(headTransform: FloatArray) {
        // Called once per frame before rendering each eye
        // headTransform is the head view matrix
        System.arraycopy(headTransform, 0, viewMatrix, 0, 16)
    }

    override fun onDrawEye(eye: GvrView.Eye) {
        // Get eye-specific projection matrix
        val eyeProjection = FloatArray(16)
        eye.getProjectionMatrix(0.1f, 100f, eyeProjection, 0)

        // Clear for each eye
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        // Render hands
        if (leftHandVisible) {
            renderHand(leftHandPositions, leftHandColor, eyeProjection)
        }
        if (rightHandVisible) {
            renderHand(rightHandPositions, rightHandColor, eyeProjection)
        }
    }

    override fun onFinishFrame(viewport: android.graphics.Rect) {
        // Called after both eyes rendered
    }

    override fun onSurfaceCreated(eglConfig: EGLConfig) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        createShaders()
        createSphereGeometry()
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onRendererShutdown() {
        cleanup()
    }

    private fun createShaders() {
        // Joint shader (point sprite / sphere)
        val jointVertexShader = """
            uniform mat4 u_MVPMatrix;
            attribute vec4 a_Position;
            attribute vec3 a_Normal;
            varying vec3 v_Normal;
            void main() {
                v_Normal = a_Normal;
                gl_Position = u_MVPMatrix * a_Position;
            }
        """.trimIndent()

        val jointFragmentShader = """
            precision mediump float;
            uniform vec4 u_Color;
            varying vec3 v_Normal;
            void main() {
                vec3 lightDir = normalize(vec3(0.5, 1.0, 0.5));
                float diffuse = max(dot(v_Normal, lightDir), 0.0);
                vec3 color = u_Color.rgb * (0.3 + 0.7 * diffuse);
                gl_FragColor = vec4(color, u_Color.a);
            }
        """.trimIndent()

        jointProgram = createProgram(jointVertexShader, jointFragmentShader)
        jointMvpLoc = GLES30.glGetUniformLocation(jointProgram, "u_MVPMatrix")
        jointColorLoc = GLES30.glGetUniformLocation(jointProgram, "u_Color")

        // Bone shader (lines)
        val boneVertexShader = """
            uniform mat4 u_MVPMatrix;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_MVPMatrix * a_Position;
            }
        """.trimIndent()

        val boneFragmentShader = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """.trimIndent()

        boneProgram = createProgram(boneVertexShader, boneFragmentShader)
        boneMvpLoc = GLES30.glGetUniformLocation(boneProgram, "u_MVPMatrix")
        boneColorLoc = GLES30.glGetUniformLocation(boneProgram, "u_Color")
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program link error: ${GLES30.glGetProgramInfoLog(program)}")
        }
        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            Log.e(TAG, "Shader compile error: ${GLES30.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    private fun createSphereGeometry() {
        // Generate sphere vertices for joints
        val stacks = 12
        val slices = 12
        val vertices = mutableListOf<Float>()
        val indices = mutableListOf<Short>()

        for (i in 0..stacks) {
            val lat = kotlin.math.PI * i / stacks
            val sinLat = kotlin.math.sin(lat).toFloat()
            val cosLat = kotlin.math.cos(lat).toFloat()

            for (j in 0..slices) {
                val lon = 2 * kotlin.math.PI * j / slices
                val sinLon = kotlin.math.sin(lon).toFloat()
                val cosLon = kotlin.math.cos(lon).toFloat()

                val x = sinLat * cosLon
                val y = cosLat
                val z = sinLat * sinLon

                vertices.add(x)
                vertices.add(y)
                vertices.add(z)
                vertices.add(x)  // normal = position for unit sphere
                vertices.add(y)
                vertices.add(z)
            }
        }

        for (i in 0 until stacks) {
            for (j in 0 until slices) {
                val first = (i * (slices + 1) + j)
                val second = first + slices + 1
                indices.add(first.toShort())
                indices.add(second.toShort())
                indices.add((first + 1).toShort())
                indices.add((second + 1).toShort())
                indices.add(second.toShort())
                indices.add((first + 1).toShort())
            }
        }

        indexCount = indices.size

        // Upload to GPU
        sphereVertices = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertices.toFloatArray()); position(0) }

        sphereIndices = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(indices.toShortArray()); position(0) }

        // VBOs
        val buffers = IntArray(2)
        GLES30.glGenBuffers(2, buffers, 0)
        jointVBO = buffers[0]
        boneVBO = buffers[1]

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, jointVBO)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, sphereVertices!!.capacity() * 4, sphereVertices, GLES30.GL_STATIC_DRAW)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, jointVBO)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, sphereIndices!!.capacity() * 2, sphereIndices, GLES30.GL_STATIC_DRAW)
    }

    private fun renderHand(positions: Array<FloatArray>, handColor: FloatArray, eyeProjection: FloatArray) {
        // Render joints (spheres)
        GLES30.glUseProgram(jointProgram)
        GLES30.glUniform4fv(jointColorLoc, 1, handColor, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, jointVBO)
        val posLoc = GLES30.glGetAttribLocation(jointProgram, "a_Position")
        val normLoc = GLES30.glGetAttribLocation(jointProgram, "a_Normal")
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glEnableVertexAttribArray(normLoc)
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 6 * 4, 0)
        GLES30.glVertexAttribPointer(normLoc, 3, GLES30.GL_FLOAT, false, 6 * 4, 3 * 4)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, jointVBO)

        for (i in 0..20) {
            val pos = positions[i]
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, pos[0], pos[1], pos[2])
            Matrix.scaleM(modelMatrix, 0, JOINT_RADIUS, JOINT_RADIUS, JOINT_RADIUS)

            Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, eyeProjection, 0, mvpMatrix, 0)

            GLES30.glUniformMatrix4fv(jointMvpLoc, 1, false, mvpMatrix, 0)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        }

        // Render bones (lines)
        GLES30.glUseProgram(boneProgram)
        GLES30.glUniform4fv(boneColorLoc, 1, boneColor, 0)
        GLES30.glLineWidth(3f)

        // Simple line rendering - draw line for each bone connection
        for ((parent, child) in HandLandmark.BONE_CONNECTIONS) {
            val p1 = positions[parent]
            val p2 = positions[child]

            // Create line vertices
            val lineVertices = floatArrayOf(
                p1[0], p1[1], p1[2],
                p2[0], p2[1], p2[2]
            )
            val lineBuffer = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder()).asFloatBuffer()
            lineBuffer.put(lineVertices).position(0)

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, boneVBO)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 24, lineBuffer, GLES30.GL_STREAM_DRAW)

            val posLoc2 = GLES30.glGetAttribLocation(boneProgram, "a_Position")
            GLES30.glEnableVertexAttribArray(posLoc2)
            GLES30.glVertexAttribPointer(posLoc2, 3, GLES30.GL_FLOAT, false, 12, 0)

            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, eyeProjection, 0, mvpMatrix, 0)

            GLES30.glUniformMatrix4fv(boneMvpLoc, 1, false, mvpMatrix, 0)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, 2)
        }
    }

    fun updateHandData(hands: List<HandData>) {
        leftHandVisible = false
        rightHandVisible = false

        for (hand in hands) {
            val positions = if (hand.isLeftHand) leftHandPositions else rightHandPositions
            val landmarks = hand.landmarks

            // Convert normalized coordinates to world space
            // MediaPipe: x=left->right, y=top->bottom, z=depth
            // OpenGL: x=left->right, y=bottom->top, z=out of screen
            for (i in 0..20) {
                val lm = landmarks[i]
                // Mirror X for VR view, flip Y, set depth
                positions[i][0] = (1.0f - lm.x - 0.5f) * 0.8f  // Scale and center
                positions[i][1] = (lm.y - 0.5f) * 0.8f
                positions[i][2] = -HAND_DEPTH - lm.z * 0.3f
            }

            if (hand.isLeftHand) leftHandVisible = true else rightHandVisible = true
        }
        handDataTimestamp = System.currentTimeMillis()
    }

    private fun cleanup() {
        if (jointProgram != 0) GLES30.glDeleteProgram(jointProgram)
        if (boneProgram != 0) GLES30.glDeleteProgram(boneProgram)
        if (jointVBO != 0) {
            val buffers = intArrayOf(jointVBO, boneVBO)
            GLES30.glDeleteBuffers(2, buffers, 0)
        }
    }
}