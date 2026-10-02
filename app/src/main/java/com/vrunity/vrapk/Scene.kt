package com.vrunity.vrapk

import android.content.Context
import android.opengl.Matrix
import org.json.JSONArray
import org.json.JSONObject

// The scene as the game draws it: every shape with its world transform, its colour
// already in the space the shader lights in, and where the player starts.
class Scene private constructor() {
    val bg = floatArrayOf(0.06f, 0.08f, 0.14f)
    val items = ArrayList<Item>()
    var startX = 0f
    var startZ = 0f
    var startYaw = 0f
    var eyeHeight = 1.6f

    class Item(val shape: String, val model: FloatArray, val color: FloatArray, val light: FloatArray)

    companion object {
        // The scene's own light, in world space.
        private val lightWorld = floatArrayOf(0.40f, 0.78f, 0.48f)

        fun load(context: Context): Scene {
            val scene = Scene()
            val text = context.assets.open("scene.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val bgArr = vec(root.optJSONArray("bg"), scene.bg)
            scene.bg[0] = bgArr[0]; scene.bg[1] = bgArr[1]; scene.bg[2] = bgArr[2]
            val start = root.optJSONObject("start")
            if (start != null) {
                val p = vec(start.optJSONArray("pos"), floatArrayOf(0f, 0f, 0f))
                scene.startX = p[0]
                scene.startZ = p[2]
                scene.startYaw = start.optDouble("yaw", 0.0).toFloat()
            }
            scene.eyeHeight = root.optDouble("eyeHeight", 1.6).toFloat()
            val list = root.optJSONArray("objects") ?: return scene
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val shape = o.optString("shape", "")
                if (shape.isEmpty()) continue
                val p = vec(o.optJSONArray("pos"), floatArrayOf(0f, 0f, 0f))
                val rot = vec(o.optJSONArray("rot"), floatArrayOf(0f, 0f, 0f))
                val scl = vec(o.optJSONArray("scale"), floatArrayOf(1f, 1f, 1f))
                val col = vec(o.optJSONArray("color"), floatArrayOf(0.5f, 0.5f, 0.5f))
                val rotation = FloatArray(16)
                Matrix.setIdentityM(rotation, 0)
                Matrix.rotateM(rotation, 0, rot[0] * 57.29578f, 1f, 0f, 0f)
                Matrix.rotateM(rotation, 0, rot[1] * 57.29578f, 0f, 1f, 0f)
                Matrix.rotateM(rotation, 0, rot[2] * 57.29578f, 0f, 0f, 1f)
                val placed = FloatArray(16)
                Matrix.setIdentityM(placed, 0)
                Matrix.translateM(placed, 0, p[0], p[1], p[2])
                val model = FloatArray(16)
                Matrix.multiplyMM(model, 0, placed, 0, rotation, 0)
                Matrix.scaleM(model, 0, scl[0], scl[1], scl[2])
                // The light is turned into the object's own frame once, so the
                // shader needs no normal matrix and stays tiny.
                val lx = lightWorld[0]; val ly = lightWorld[1]; val lz = lightWorld[2]
                val light = floatArrayOf(
                    rotation[0] * lx + rotation[4] * ly + rotation[8] * lz,
                    rotation[1] * lx + rotation[5] * ly + rotation[9] * lz,
                    rotation[2] * lx + rotation[6] * ly + rotation[10] * lz)
                scene.items.add(Item(shape, model, col, light))
            }
            return scene
        }

        private fun vec(a: JSONArray?, fallback: FloatArray): FloatArray {
            if (a == null || a.length() < 3) return floatArrayOf(fallback[0], fallback[1], fallback[2])
            return floatArrayOf(a.optDouble(0, 0.0).toFloat(), a.optDouble(1, 0.0).toFloat(), a.optDouble(2, 0.0).toFloat())
        }
    }
}
