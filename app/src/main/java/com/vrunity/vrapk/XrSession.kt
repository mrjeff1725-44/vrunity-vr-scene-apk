package com.vrunity.vrapk

import android.app.Activity
import android.opengl.GLES20
import android.opengl.Matrix
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

// The game running inside the headset's own VR session: the runtime hands over each
// eye's view and the controllers, and the game draws the scene into both eyes. This
// is what opens when the app starts on a headset.
class XrSession(private val activity: Activity) {
    private val viewData = FloatArray(22)
    private val stick = FloatArray(4)
    private val fbo = IntArray(2)
    private val depth = IntArray(2)
    private var fboW = 0
    private var fboH = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val rotM = FloatArray(16)
    private val eyeM = FloatArray(16)
    private val playerM = FloatArray(16)
    private val world = FloatArray(16)
    private val scratch = FloatArray(16)

    private var playerX = 0f
    private var playerZ = 0f
    private var playerY = 0f
    private var playerYaw = 0f
    private var lastNs = 0L
    private var frameCount = 0

    private val TURN_RATE = 1.7f   // radians per second
    private val WALK_SPEED = 2.6f  // metres per second
    private val NEAR = 0.05f

    // 2 = the headset ran the game, 1 = VR opened but never handed over a frame,
    // 0 = there is no VR runtime here and the screen mode should be used instead.
    fun run(): Int {
        if (!Xr.start(activity)) return 0
        val game = Game(activity)
        game.setup()
        playerX = game.startX
        playerZ = game.startZ
        playerYaw = game.startYaw
        lastNs = System.nanoTime()
        var frames = 0
        try {
            frames = loop(game)
        } finally {
            Xr.stop()
        }
        return if (frames > 0) 2 else 1
    }

    private fun loop(game: Game): Int {
        var frames = 0
        val began = System.nanoTime()
        while (true) {
            val status = Xr.poll(viewData)
            if (status < 0) return frames
            if (status == 0) {
                // Give the runtime a few seconds for the first frame. If it never
                // comes, the screen view is a better answer than a blank headset.
                if (frames == 0 && System.nanoTime() - began > 6000000000L) return 0
                Thread.sleep(6)
                continue
            }
            val now = System.nanoTime()
            var dt = (now - lastNs) / 1000000000f
            lastNs = now
            if (dt > 0.1f) dt = 0.1f
            Xr.input(stick)
            steer(dt)
            drawEyes(game)
            frames++
            if (Xr.endFrame() < 0) return frames
        }
    }

    private fun dead(v: Float): Float = if (Math.abs(v) < 0.15f) 0f else v

    // The controllers walk the player: the left stick moves, the right stick turns.
    // Movement is relative to where the head is looking, so pushing forward always
    // goes the way the player is facing.
    private fun steer(dt: Float) {
        val mx = dead(stick[0])
        val my = dead(stick[1])
        val tx = dead(stick[2])
        if (tx != 0f) playerYaw -= tx * TURN_RATE * dt
        val total = playerYaw + headYaw(0)
        val forward = -my
        val strafe = mx
        if (Math.hypot(forward.toDouble(), strafe.toDouble()) < 0.05) return
        val dx = forward * -sin(total) + strafe * cos(total)
        val dz = forward * -cos(total) + strafe * -sin(total)
        playerX += dx * WALK_SPEED * dt
        playerZ += dz * WALK_SPEED * dt
        // An odd pose from the runtime must never poison the view matrix — one NaN in
        // the player transform makes the whole scene vanish.
        if (playerX.isNaN()) playerX = 0f
        if (playerZ.isNaN()) playerZ = 0f
        if (playerYaw.isNaN()) playerYaw = 0f
    }

    private fun headYaw(eye: Int): Float {
        val o = eye * 11
        val x = viewData[o + 7]
        val y = viewData[o + 8]
        val z = viewData[o + 9]
        val w = viewData[o + 10]
        return atan2(2.0 * (w * y + x * z), 1.0 - 2.0 * (y * y + z * z)).toFloat()
    }

    private fun quatMatrix(m: FloatArray, x: Float, y: Float, z: Float, w: Float) {
        val n = Math.sqrt((x * x + y * y + z * z + w * w).toDouble()).toFloat()
        val s = if (n > 0.00001f) 2f / (n * n) else 0f
        val xx = x * s * x; val xy = y * s * x; val xz = z * s * x; val xw = w * s * x
        val yy = y * s * y; val yz = z * s * y; val yw = w * s * y
        val zz = z * s * z; val zw = w * s * z
        m[0] = 1f - (yy + zz); m[1] = xy + zw; m[2] = xz - yw; m[3] = 0f
        m[4] = xy - zw; m[5] = 1f - (xx + zz); m[6] = yz + xw; m[7] = 0f
        m[8] = xz + yw; m[9] = yz - xw; m[10] = 1f - (xx + yy); m[11] = 0f
        m[12] = 0f; m[13] = 0f; m[14] = 0f; m[15] = 1f
    }

    private fun targets(w: Int, h: Int) {
        if (fboW > 0) {
            GLES20.glDeleteFramebuffers(2, fbo, 0)
            GLES20.glDeleteRenderbuffers(2, depth, 0)
        }
        fboW = w
        fboH = h
        GLES20.glGenFramebuffers(2, fbo, 0)
        GLES20.glGenRenderbuffers(2, depth, 0)
        for (i in 0 until 2) {
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depth[i])
            GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, w, h)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
            GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, depth[i])
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun drawEyes(game: Game) {
        val w = Xr.eyeWidth()
        val h = Xr.eyeHeight()
        if (w <= 0 || h <= 0) return
        if (fboW != w || fboH != h) targets(w, h)
        frameCount++
        // A floor-relative space already reports the eyes at their real height. One
        // that is not floor-relative starts at the head, so the eyes are lifted to the
        // scene's own height instead of sitting on the ground.
        playerY = if (Xr.floorSpace()) 0f else game.eyeHeight
        // A pose that is not a number would make the frustum degenerate and hide the
        // whole scene, so it is replaced with a sane view instead.
        for (i in 0 until 22) {
            if (viewData[i].isNaN() || viewData[i].isInfinite()) {
                viewData[i] = when (i % 11) {
                    0 -> -0.9f
                    1 -> 0.9f
                    2 -> 0.9f
                    3 -> -0.9f
                    10 -> 1f
                    else -> 0f
                }
            }
        }
        Matrix.setIdentityM(playerM, 0)
        Matrix.translateM(playerM, 0, playerX, playerY, playerZ)
        Matrix.rotateM(playerM, 0, Math.toDegrees(playerYaw.toDouble()).toFloat(), 0f, 1f, 0f)
        for (eye in 0 until 2) {
            val tex = Xr.eyeTexture(eye)
            if (tex == 0) continue
            val o = eye * 11
            // Each eye gets the runtime's own field of view for that lens.
            Matrix.frustumM(proj, 0,
                tan(viewData[o].toDouble()).toFloat() * NEAR,
                tan(viewData[o + 1].toDouble()).toFloat() * NEAR,
                -tan(viewData[o + 3].toDouble()).toFloat() * NEAR,
                tan(viewData[o + 2].toDouble()).toFloat() * NEAR,
                NEAR, 600f)
            quatMatrix(rotM, viewData[o + 7], viewData[o + 8], viewData[o + 9], viewData[o + 10])
            Matrix.setIdentityM(eyeM, 0)
            Matrix.translateM(eyeM, 0, viewData[o + 4], viewData[o + 5], viewData[o + 6])
            Matrix.multiplyMM(world, 0, eyeM, 0, rotM, 0)
            // The eye in the scene, then the scene from that eye.
            Matrix.multiplyMM(scratch, 0, playerM, 0, world, 0)
            Matrix.invertM(view, 0, scratch, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[eye])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
            GLES20.glViewport(0, 0, w, h)
            // The first moments are a flat colour, so a blank headset can be told
            // apart from the scene simply not being drawn.
            if (frameCount <= 40) game.clearTo(1f, 0f, 1f) else game.clear()
            game.draw(view, proj)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
    }
}
