package com.vrunity.vrapk

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

// Screen mode, used on devices that have no VR runtime: the same game, drawn once
// for each eye with the phone's own motion sensors steering the view. When the
// device does have a VR runtime, the headset session is opened first and this is
// never used.
class VrRenderer(private val context: Context) : GLSurfaceView.Renderer {
    private val game = Game(context)

    // Head tracking. The sensor reports the device's orientation; how the screen's
    // upright sits inside the headset is worked out from the sensors themselves
    // instead of assumed, so the horizon comes out level on any holder.
    @Volatile private var sample: FloatArray? = null
    @Volatile private var askRecenter = false
    @Volatile private var walk = 0f
    @Volatile private var dragYaw = 0f
    @Volatile private var dragPitch = 0f
    private val rots = floatArrayOf(0f, 90f, 180f, 270f)
    private val scores = FloatArray(4)
    private var roll = 0f
    private var worn = false
    private var yawRef = 0f
    private var width = 1
    private var height = 1
    private val player = floatArrayOf(0f, 1.6f, 0f)
    private var rigYaw = 0f

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val scratch = FloatArray(16)
    private val deviceM = FloatArray(16)
    private val rollM = FloatArray(16)
    private val effM = FloatArray(16)
    private val sceneM = FloatArray(16)
    private val sceneT = FloatArray(16)
    private val convT = FloatArray(16)
    private val yawM = FloatArray(16)
    private val pitchM = FloatArray(16)
    private val camM = FloatArray(16)
    private val moveM = FloatArray(16)

    // Android's world axes (X east, Y north, Z up) turned into the scene's (Y up).
    private val BASIS = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 0f, -1f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f)

    fun setHead(m: FloatArray) { sample = m }
    fun walkBy(dir: Float) { walk += dir }
    fun recenter() { askRecenter = true }
    fun drag(dx: Float, dy: Float) { dragYaw -= dx; dragPitch -= dy }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        game.setup()
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        player[0] = game.startX
        player[1] = game.eyeHeight
        player[2] = game.startZ
        rigYaw = game.startYaw
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = if (w < 1) 1 else w
        height = if (h < 1) 1 else h
    }

    // The sensor's rotation matrix, laid into a 4x4, plus the roll that makes the
    // screen's upright the headset's upright.
    private fun headMatrix(m: FloatArray) {
        deviceM[0] = m[0]; deviceM[1] = m[3]; deviceM[2] = m[6]; deviceM[3] = 0f
        deviceM[4] = m[1]; deviceM[5] = m[4]; deviceM[6] = m[7]; deviceM[7] = 0f
        deviceM[8] = m[2]; deviceM[9] = m[5]; deviceM[10] = m[8]; deviceM[11] = 0f
        deviceM[12] = 0f; deviceM[13] = 0f; deviceM[14] = 0f; deviceM[15] = 1f
    }

    private fun updateTracking(m: FloatArray) {
        headMatrix(m)
        if (!worn) {
            for (i in 0 until 4) {
                val b = Math.toRadians(rots[i].toDouble())
                val x = (-Math.sin(b)).toFloat()
                val y = Math.cos(b).toFloat()
                val upWorld = m[6] * x + m[7] * y
                scores[i] = scores[i] * 0.9f + upWorld
            }
            var best = 0
            for (i in 1 until 4) if (scores[i] > scores[best]) best = i
            roll = rots[best]
        }
        Matrix.setRotateM(rollM, 0, roll, 0f, 0f, 1f)
        Matrix.multiplyMM(effM, 0, deviceM, 0, rollM, 0)
        Matrix.multiplyMM(scratch, 0, BASIS, 0, effM, 0)
        Matrix.transposeM(convT, 0, BASIS, 0)
        Matrix.multiplyMM(sceneM, 0, scratch, 0, convT, 0)
        val fx = -sceneM[8]
        val fy = -sceneM[9]
        val fz = -sceneM[10]
        if (!worn && Math.abs(fy) < 0.40f && Math.abs(fx) + Math.abs(fz) > 0.1f) {
            worn = true
            yawRef = Math.atan2(fx.toDouble(), fz.toDouble()).toFloat()
        }
        if (askRecenter) {
            askRecenter = false
            worn = true
            yawRef = Math.atan2(fx.toDouble(), fz.toDouble()).toFloat()
        }
    }

    private fun buildView(eyeOffset: Float) {
        val s = sample
        Matrix.setIdentityM(sceneT, 0)
        var yawNow = 0f
        if (s != null) {
            updateTracking(s)
            Matrix.transposeM(sceneT, 0, sceneM, 0)
            val fx = -sceneM[8]
            val fz = -sceneM[10]
            yawNow = Math.atan2(fx.toDouble(), fz.toDouble()).toFloat()
        }
        val extraYaw = rigYaw + (yawNow - yawRef) + dragYaw
        Matrix.setRotateM(yawM, 0, -extraYaw * 57.29578f, 0f, 1f, 0f)
        Matrix.multiplyMM(camM, 0, yawM, 0, sceneT, 0)
        if (dragPitch != 0f) {
            Matrix.setRotateM(pitchM, 0, -dragPitch * 57.29578f, 1f, 0f, 0f)
            Matrix.multiplyMM(scratch, 0, camM, 0, pitchM, 0)
            System.arraycopy(scratch, 0, camM, 0, 16)
        }
        // Standing in the scene wherever the player has walked to.
        val move = walk
        if (move != 0f) {
            walk = 0f
            val fx = -camM[2]
            val fz = -camM[10]
            val len = Math.hypot(fx.toDouble(), fz.toDouble()).toFloat()
            if (len > 0.001f) {
                player[0] += (fx / len) * 0.7f * move
                player[2] += (fz / len) * 0.7f * move
            }
        }
        val rx = camM[0]
        val ry = camM[4]
        val rz = camM[8]
        val ex = player[0] + rx * eyeOffset
        val ey = player[1] + ry * eyeOffset
        val ez = player[2] + rz * eyeOffset
        Matrix.setIdentityM(moveM, 0)
        Matrix.translateM(moveM, 0, -ex, -ey, -ez)
        Matrix.multiplyMM(view, 0, camM, 0, moveM, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        val half = width / 2
        game.clear()
        Matrix.perspectiveM(proj, 0, 72f, half.toFloat() / height.toFloat(), 0.05f, 600f)
        for (eye in 0 until 2) {
            val offset = if (eye == 0) -0.032f else 0.032f
            buildView(offset)
            GLES20.glViewport(eye * half, 0, half, height)
            game.draw(view, proj)
        }
    }
}
