package com.fpsgame.arena

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*
import kotlin.random.Random

class GameRenderer(private val g: Game) : GLSurfaceView.Renderer {
    private var prog = 0
    private var aPos = 0; private var aNor = 0
    private var uMvp = 0; private var uModel = 0; private var uColor = 0
    private var uCam = 0; private var uFog = 0; private var uGrid = 0
    private lateinit var vb: FloatBuffer
    private val proj = FloatArray(16); private val view = FloatArray(16)
    private val vp = FloatArray(16); private val base = FloatArray(16)
    private val model = FloatArray(16); private val mvp = FloatArray(16)
    private var curVp = vp
    private var aspect = 1f
    private var last = 0L
    private val sky = floatArrayOf(0.42f, 0.52f, 0.68f)

    private val vsSrc = """
        uniform mat4 uMvp;
        uniform mat4 uModel;
        attribute vec3 aPos;
        attribute vec3 aNor;
        varying vec3 vN;
        varying vec3 vW;
        void main() {
            vN = (uModel * vec4(aNor, 0.0)).xyz;
            vW = (uModel * vec4(aPos, 1.0)).xyz;
            gl_Position = uMvp * vec4(aPos, 1.0);
        }
    """.trimIndent()

    private val fsSrc = """
        precision mediump float;
        uniform vec4 uColor;
        uniform vec3 uCam;
        uniform vec3 uFog;
        uniform float uGrid;
        varying vec3 vN;
        varying vec3 vW;
        void main() {
            vec3 L = normalize(vec3(0.4, 1.0, 0.3));
            float d = max(dot(normalize(vN), L), 0.0);
            float l = 0.45 + 0.55 * d;
            if (uGrid > 1.5) l = 1.0;
            vec3 c = uColor.rgb * l;
            if (uGrid > 0.5 && uGrid < 1.5) {
                if (mod(floor(vW.x) + floor(vW.z), 2.0) < 0.5) c *= 0.82;
            }
            float f = clamp(length(vW - uCam) / 40.0, 0.0, 1.0);
            c = mix(c, uFog, f * f);
            gl_FragColor = vec4(c, 1.0);
        }
    """.trimIndent()

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException(GLES20.glGetShaderInfoLog(s))
        return s
    }

    private fun buildCube(): FloatBuffer {
        val faces = arrayOf(
            floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f),
            floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f)
        )
        val out = ArrayList<Float>()
        for (n in faces) {
            val u = if (abs(n[1]) > 0.5f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
            val v = floatArrayOf(
                n[1] * u[2] - n[2] * u[1],
                n[2] * u[0] - n[0] * u[2],
                n[0] * u[1] - n[1] * u[0]
            )
            val order = arrayOf(
                intArrayOf(-1, -1), intArrayOf(1, -1), intArrayOf(1, 1),
                intArrayOf(-1, -1), intArrayOf(1, 1), intArrayOf(-1, 1)
            )
            for (c in order) {
                for (i in 0..2) out.add(n[i] * 0.5f + u[i] * c[0] * 0.5f + v[i] * c[1] * 0.5f)
                for (i in 0..2) out.add(n[i])
            }
        }
        val arr = out.toFloatArray()
        val fb = ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(arr).position(0)
        return fb
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, vsSrc))
        GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, fsSrc))
        GLES20.glLinkProgram(prog)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aNor = GLES20.glGetAttribLocation(prog, "aNor")
        uMvp = GLES20.glGetUniformLocation(prog, "uMvp")
        uModel = GLES20.glGetUniformLocation(prog, "uModel")
        uColor = GLES20.glGetUniformLocation(prog, "uColor")
        uCam = GLES20.glGetUniformLocation(prog, "uCam")
        uFog = GLES20.glGetUniformLocation(prog, "uFog")
        uGrid = GLES20.glGetUniformLocation(prog, "uGrid")
        vb = buildCube()
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        last = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        aspect = w.toFloat() / max(h, 1)
    }

    private fun setBase(x: Float, y: Float, z: Float, yawDeg: Float) {
        Matrix.setIdentityM(base, 0)
        Matrix.translateM(base, 0, x, y, z)
        if (yawDeg != 0f) Matrix.rotateM(base, 0, yawDeg, 0f, 1f, 0f)
    }

    /** grid: 0 normal, 1 lantai kotak-kotak, 2 menyala (emissive) */
    private fun part(
        lx: Float, ly: Float, lz: Float, sx: Float, sy: Float, sz: Float,
        cr: Float, cg: Float, cb: Float, grid: Float = 0f
    ) {
        System.arraycopy(base, 0, model, 0, 16)
        Matrix.translateM(model, 0, lx, ly, lz)
        Matrix.scaleM(model, 0, sx, sy, sz)
        Matrix.multiplyMM(mvp, 0, curVp, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform4f(uColor, cr, cg, cb, 1f)
        GLES20.glUniform1f(uGrid, grid)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
    }

    private fun mix(c: Float, f: Float) = c + (1f - c) * f

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else min((now - last) / 1e9f, 0.05f)
        last = now
        g.update(dt)

        GLES20.glClearColor(sky[0], sky[1], sky[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(prog)
        vb.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, vb)
        GLES20.glEnableVertexAttribArray(aPos)
        vb.position(3)
        GLES20.glVertexAttribPointer(aNor, 3, GLES20.GL_FLOAT, false, 24, vb)
        GLES20.glEnableVertexAttribArray(aNor)
        GLES20.glUniform3f(uFog, sky[0], sky[1], sky[2])

        // ---- kamera ----
        val spd = min(1f, g.speedNow / 6f)
        val ex = g.px
        val ey = if (g.dead) 0.5f else 1.6f + sin(g.bob * 2f) * 0.045f * spd
        val ez = g.pz
        val cp = cos(g.pitch)
        val sh = g.shake
        val fx = sin(g.yaw) * cp + (Random.nextFloat() - .5f) * 0.12f * sh
        val fy = sin(g.pitch) + (Random.nextFloat() - .5f) * 0.12f * sh
        val fz = -cos(g.yaw) * cp
        Matrix.setLookAtM(view, 0, ex, ey, ez, ex + fx, ey + fy, ez + fz, 0f, 1f, 0f)
        Matrix.perspectiveM(proj, 0, 70f + g.kick * 1.5f, aspect, 0.05f, 80f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        curVp = vp
        GLES20.glUniform3f(uCam, ex, ey, ez)

        // ---- dunia ----
        val H = Game.HALF
        setBase(0f, 0f, 0f, 0f)
        part(0f, -0.5f, 0f, 2 * H + 2f, 1f, 2 * H + 2f, 0.38f, 0.42f, 0.36f, 1f)
        part(0f, 2f, -H - 0.5f, 2 * H + 2f, 4f, 1f, 0.35f, 0.37f, 0.44f)
        part(0f, 2f, H + 0.5f, 2 * H + 2f, 4f, 1f, 0.35f, 0.37f, 0.44f)
        part(-H - 0.5f, 2f, 0f, 1f, 4f, 2 * H + 2f, 0.32f, 0.34f, 0.40f)
        part(H + 0.5f, 2f, 0f, 1f, 4f, 2 * H + 2f, 0.32f, 0.34f, 0.40f)
        for (b in g.pillars) part(b.cx, b.h / 2f, b.cz, b.hx * 2f, b.h, b.hz * 2f, 0.55f, 0.45f, 0.36f)

        // ---- musuh ----
        for (e in g.enemies) {
            setBase(e.x, 0f, e.z, 0f)
            part(0f, 0.015f, 0f, 1.0f, 0.02f, 1.0f, 0.12f, 0.12f, 0.14f)
            setBase(e.x, 0f, e.z, Math.toDegrees(e.yaw.toDouble()).toFloat())
            val f = min(1f, e.flash / 0.12f) * 0.7f
            val sw = sin(e.phase) * 0.28f
            part(-0.18f, 0.4f, sw, 0.25f, 0.8f, 0.25f, mix(0.2f, f), mix(0.2f, f), mix(0.3f, f))
            part(0.18f, 0.4f, -sw, 0.25f, 0.8f, 0.25f, mix(0.2f, f), mix(0.2f, f), mix(0.3f, f))
            part(0f, 1.15f, 0f, 0.7f, 0.7f, 0.4f, mix(0.6f, f), mix(0.15f, f), mix(0.15f, f))
            part(-0.45f, 1.2f, 0.2f, 0.2f, 0.2f, 0.6f, mix(0.45f, f), mix(0.62f, f), mix(0.4f, f))
            part(0.45f, 1.2f, 0.2f, 0.2f, 0.2f, 0.6f, mix(0.45f, f), mix(0.62f, f), mix(0.4f, f))
            part(0f, 1.68f, 0f, 0.4f, 0.4f, 0.4f, mix(0.45f, f), mix(0.62f, f), mix(0.4f, f))
            part(-0.1f, 1.72f, 0.21f, 0.08f, 0.08f, 0.04f, 1f, 0.1f, 0.1f, 2f)
            part(0.1f, 1.72f, 0.21f, 0.08f, 0.08f, 0.04f, 1f, 0.1f, 0.1f, 2f)
        }

        // ---- partikel ----
        for (p in g.parts) {
            setBase(p.x, p.y, p.z, 0f)
            part(0f, 0f, 0f, 0.07f, 0.07f, 0.07f, p.r, p.g, p.b, 2f)
        }

        // ---- senjata (viewmodel) ----
        if (!g.dead) {
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
            curVp = proj
            GLES20.glUniform3f(uCam, 0f, 0f, 0f)
            val gx = 0.22f + sin(g.bob) * 0.012f * spd
            val gy = -0.24f + sin(g.bob * 2f) * 0.01f * spd + (if (g.reloading) -0.18f else 0f)
            val gz = -0.5f + g.kick * 0.06f
            Matrix.setIdentityM(base, 0)
            Matrix.translateM(base, 0, gx, gy, gz)
            Matrix.rotateM(base, 0, g.kick * 5f, 1f, 0f, 0f)
            part(0f, 0f, 0f, 0.09f, 0.12f, 0.45f, 0.18f, 0.18f, 0.2f)
            part(0f, 0.02f, -0.38f, 0.05f, 0.05f, 0.35f, 0.1f, 0.1f, 0.1f)
            part(0f, -0.1f, 0.1f, 0.07f, 0.16f, 0.1f, 0.25f, 0.2f, 0.15f)
            if (g.flash > 0f) part(0f, 0.02f, -0.62f, 0.12f, 0.12f, 0.1f, 1f, 0.9f, 0.4f, 2f)
        }
    }
}
