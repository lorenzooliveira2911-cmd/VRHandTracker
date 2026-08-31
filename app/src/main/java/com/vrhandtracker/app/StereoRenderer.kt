package com.vrhandtracker.app

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class StereoRenderer : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "StereoRenderer"
        private const val JOINT_RADIUS = 0.008f
        private const val BONE_WIDTH = 0.006f
        private const val HAND_DEPTH = 0.5f
    }

    // OpenGL resources
    private var jointProgram = 0
    private var boneProgram = 0
    private var jointVBO = 0
    private var boneVBO = 0
    private var sphereVertices: FloatBuffer? = null
    private var sphereIndices: java.nio.ShortBuffer? = null
    private var indexCount = 0

    // Tracking data
    private var leftHandPositions = Array(21) { FloatArray(3) }
    private var rightHandPositions = Array(21) { FloatArray(3) }
    private var leftHandVisible = false
    private var rightHandVisible = false

    // Matrices
    private val modelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Uniform locations
    private var jointMvpLoc = 0
    private var jointColorLoc = 0
    private var boneMvpLoc = 0
    private var boneColorLoc = 0

    // Colors
    private val leftHandColor = floatArrayOf(0f, 1f, 1f, 1f)
    private val rightHandColor = floatArrayOf(1f, 0f, 1f, 1f)
    private val jointColor = floatArrayOf(1f, 1f, 0f, 1f)
    private val boneColor = floatArrayOf(1f, 1f, 1f, 1f)

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        createShaders()
        createSphereGeometry()
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        // Set up projection for stereo (side-by-side)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.frustumM(projectionMatrix, 0, -aspect, aspect, -1f, 1f, 1f, 100f)
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 3f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onDrawFrame(gl: GL10) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        if (leftHandVisible) {
            renderHand(leftHandPositions, leftHandColor)
        }
        if (rightHandVisible) {
            renderHand(rightHandPositions, rightHandColor)
        }
    }

    private fun createShaders() {
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
        val stacks = 12
        val slices = 12
        val vertices = mutableListOf<Float>()
        val indices = mutableListOf<Short>()

        for (i in 0..stacks) {
            val lat = Math.PI * i / stacks
            val sinLat = Math.sin(lat).toFloat()
            val cosLat = Math.cos(lat).toFloat()

            for (j in 0..slices) {
                val lon = 2 * Math.PI * j / slices
                val sinLon = Math.sin(lon).toFloat()
                val cosLon = Math.cos(lon).toFloat()

                val x = sinLat * cosLon
                val y = cosLat
                val z = sinLat * sinLon

                vertices.add(x)
                vertices.add(y)
                vertices.add(z)
                vertices.add(x)
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

        sphereVertices = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertices.toFloatArray()); position(0) }

        sphereIndices = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(indices.toShortArray()); position(0) }

        val buffers = IntArray(2)
        GLES30.glGenBuffers(2, buffers, 0)
        jointVBO = buffers[0]
        boneVBO = buffers[1]

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, jointVBO)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, sphereVertices!!.capacity() * 4, sphereVertices, GLES30.GL_STATIC_DRAW)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, jointVBO)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, sphereIndices!!.capacity() * 2, sphereIndices, GLES30.GL_STATIC_DRAW)
    }

    private fun renderHand(positions: Array<FloatArray>, handColor: FloatArray) {
        // Render joints
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
            Matrix.scaleM(modelMatrix, 0, 0.008f, 0.008f, 0.008f)

            Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0)

            GLES30.glUniformMatrix4fv(jointMvpLoc, 1, false, mvpMatrix, 0)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        }

        // Render bones
        GLES30.glUseProgram(boneProgram)
        GLES30.glUniform4fv(boneColorLoc, 1, floatArrayOf(1f, 1f, 1f, 1f), 0)
        GLES30.glLineWidth(3f)

        for ((parent, child) in HandLandmark.BONE_CONNECTIONS) {
            val p1 = positions[parent]
            val p2 = positions[child]

            val lineVertices = floatArrayOf(p1[0], p1[1], p1[2], p2[0], p2[1], p2[2])
            val lineBuffer = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder()).asFloatBuffer()
            lineBuffer.put(lineVertices).position(0)

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, boneVBO)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 24, lineBuffer, GLES30.GL_STREAM_DRAW)

            val posLoc2 = GLES30.glGetAttribLocation(boneProgram, "a_Position")
            GLES30.glEnableVertexAttribArray(posLoc2)
            GLES30.glVertexAttribPointer(posLoc2, 3, GLES30.GL_FLOAT, false, 12, 0)

            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0)

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

            for (i in 0..20) {
                val lm = landmarks[i]
                positions[i][0] = (1.0f - lm.x - 0.5f) * 0.8f
                positions[i][1] = (lm.y - 0.5f) * 0.8f
                positions[i][2] = -0.5f - lm.z * 0.3f
            }

            if (hand.isLeftHand) leftHandVisible = true else rightHandVisible = true
        }
    }
}