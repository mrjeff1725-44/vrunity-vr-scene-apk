package com.vrunity.vrapk

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

// The game's geometry, built on the device at start-up: positions and normals in
// one buffer, six floats per vertex.
class Mesh(vertices: FloatArray) {
    private val buffer: FloatBuffer
    private val count: Int

    init {
        buffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(vertices)
        buffer.position(0)
        count = vertices.size / 6
    }

    fun draw(posHandle: Int, normHandle: Int) {
        buffer.position(0)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 24, buffer)
        GLES20.glEnableVertexAttribArray(posHandle)
        buffer.position(3)
        GLES20.glVertexAttribPointer(normHandle, 3, GLES20.GL_FLOAT, false, 24, buffer)
        GLES20.glEnableVertexAttribArray(normHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
    }

    companion object {
        private fun push(v: ArrayList<Float>, p: FloatArray, n: FloatArray) {
            v.add(p[0]); v.add(p[1]); v.add(p[2])
            v.add(n[0]); v.add(n[1]); v.add(n[2])
        }

        private fun unit(p: FloatArray): FloatArray {
            val m = Math.hypot(Math.hypot(p[0].toDouble(), p[1].toDouble()), p[2].toDouble()).toFloat()
            if (m < 0.00001f) return floatArrayOf(0f, 1f, 0f)
            return floatArrayOf(p[0] / m, p[1] / m, p[2] / m)
        }

        private fun tri(v: ArrayList<Float>, a: FloatArray, b: FloatArray, c: FloatArray, na: FloatArray, nb: FloatArray, nc: FloatArray) {
            push(v, a, na); push(v, b, nb); push(v, c, nc)
        }

        private fun at(c: FloatArray, a: FloatArray, sa: Float, b: FloatArray, sb: Float): FloatArray {
            return floatArrayOf(c[0] + a[0] * sa + b[0] * sb, c[1] + a[1] * sa + b[1] * sb, c[2] + a[2] * sa + b[2] * sb)
        }

        fun box(): FloatArray {
            val v = ArrayList<Float>()
            val faces = arrayOf(
                floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f),
                floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f),
                floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f))
            for (n in faces) {
                val u = if (Math.abs(n[0]) > 0.5f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(1f, 0f, 0f)
                val w = floatArrayOf(n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0])
                val c = floatArrayOf(n[0] * 0.5f, n[1] * 0.5f, n[2] * 0.5f)
                val p0 = at(c, u, 0.5f, w, 0.5f)
                val p1 = at(c, u, -0.5f, w, 0.5f)
                val p2 = at(c, u, -0.5f, w, -0.5f)
                val p3 = at(c, u, 0.5f, w, -0.5f)
                tri(v, p0, p1, p2, n, n, n)
                tri(v, p0, p2, p3, n, n, n)
            }
            return v.toFloatArray()
        }

        private fun spherePoint(ring: Double, sector: Double): FloatArray {
            return floatArrayOf(
                (0.5 * Math.sin(ring) * Math.cos(sector)).toFloat(),
                (0.5 * Math.cos(ring)).toFloat(),
                (0.5 * Math.sin(ring) * Math.sin(sector)).toFloat())
        }

        private fun outward(p: FloatArray): FloatArray {
            return floatArrayOf(p[0] * 2f, p[1] * 2f, p[2] * 2f)
        }

        fun sphere(): FloatArray {
            val v = ArrayList<Float>()
            val rings = 18
            val sectors = 26
            for (r in 0 until rings) {
                val r0 = Math.PI * r / rings
                val r1 = Math.PI * (r + 1) / rings
                for (s in 0 until sectors) {
                    val s0 = 2.0 * Math.PI * s / sectors
                    val s1 = 2.0 * Math.PI * (s + 1) / sectors
                    val a = spherePoint(r0, s0)
                    val b = spherePoint(r0, s1)
                    val c = spherePoint(r1, s1)
                    val d = spherePoint(r1, s0)
                    tri(v, a, c, b, outward(a), outward(c), outward(b))
                    tri(v, a, d, c, outward(a), outward(d), outward(c))
                }
            }
            return v.toFloatArray()
        }

        fun cylinder(): FloatArray {
            val v = ArrayList<Float>()
            val sectors = 30
            for (s in 0 until sectors) {
                val t0 = 2.0 * Math.PI * s / sectors
                val t1 = 2.0 * Math.PI * (s + 1) / sectors
                val x0 = (0.5 * Math.cos(t0)).toFloat()
                val z0 = (0.5 * Math.sin(t0)).toFloat()
                val x1 = (0.5 * Math.cos(t1)).toFloat()
                val z1 = (0.5 * Math.sin(t1)).toFloat()
                val n0 = floatArrayOf(x0 * 2f, 0f, z0 * 2f)
                val n1 = floatArrayOf(x1 * 2f, 0f, z1 * 2f)
                val a = floatArrayOf(x0, -0.5f, z0)
                val b = floatArrayOf(x1, -0.5f, z1)
                val c = floatArrayOf(x1, 0.5f, z1)
                val d = floatArrayOf(x0, 0.5f, z0)
                tri(v, a, b, c, n0, n1, n1)
                tri(v, a, c, d, n0, n1, n0)
                val top = floatArrayOf(0f, 1f, 0f)
                val bottom = floatArrayOf(0f, -1f, 0f)
                tri(v, floatArrayOf(0f, 0.5f, 0f), d, c, top, top, top)
                tri(v, floatArrayOf(0f, -0.5f, 0f), b, a, bottom, bottom, bottom)
            }
            return v.toFloatArray()
        }

        fun cone(): FloatArray {
            val v = ArrayList<Float>()
            val sectors = 30
            val apex = floatArrayOf(0f, 0.5f, 0f)
            for (s in 0 until sectors) {
                val t0 = 2.0 * Math.PI * s / sectors
                val t1 = 2.0 * Math.PI * (s + 1) / sectors
                val a = floatArrayOf((0.5 * Math.cos(t0)).toFloat(), -0.5f, (0.5 * Math.sin(t0)).toFloat())
                val b = floatArrayOf((0.5 * Math.cos(t1)).toFloat(), -0.5f, (0.5 * Math.sin(t1)).toFloat())
                val side = unit(floatArrayOf((a[0] + b[0]) * 1.4f, 0.45f, (a[2] + b[2]) * 1.4f))
                tri(v, a, b, apex, side, side, side)
                val bottom = floatArrayOf(0f, -1f, 0f)
                tri(v, floatArrayOf(0f, -0.5f, 0f), b, a, bottom, bottom, bottom)
            }
            return v.toFloatArray()
        }

        fun plane(): FloatArray {
            val v = ArrayList<Float>()
            val n = floatArrayOf(0f, 0f, 1f)
            val p0 = floatArrayOf(-0.5f, -0.5f, 0f)
            val p1 = floatArrayOf(0.5f, -0.5f, 0f)
            val p2 = floatArrayOf(0.5f, 0.5f, 0f)
            val p3 = floatArrayOf(-0.5f, 0.5f, 0f)
            tri(v, p0, p1, p2, n, n, n)
            tri(v, p0, p2, p3, n, n, n)
            return v.toFloatArray()
        }
    }
}
