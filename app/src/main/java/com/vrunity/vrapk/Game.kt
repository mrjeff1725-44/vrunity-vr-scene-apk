package com.vrunity.vrapk

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix

// The game on the GPU: the scene's geometry plus the shader that draws it. Both
// the headset's VR session and the phone's own screen mode draw through this, so
// the two always show the same game.
class Game(context: Context) {
    private val scene = Scene.load(context)
    private val shapes = HashMap<String, Mesh>()
    private var program = 0
    private var aPos = 0
    private var aNormal = 0
    private var uMvp = 0
    private var uColor = 0
    private var uLight = 0
    private var uAmbient = 0
    private val mvp = FloatArray(16)
    private val scratch = FloatArray(16)

    val startX = scene.startX
    val startZ = scene.startZ
    val startYaw = scene.startYaw
    val eyeHeight = scene.eyeHeight

    // Safe to call again after the GPU context is recreated.
    fun setup() {
        if (program != 0) return
        shapes["cube"] = Mesh(Mesh.box())
        shapes["sphere"] = Mesh(Mesh.sphere())
        shapes["cylinder"] = Mesh(Mesh.cylinder())
        shapes["cone"] = Mesh(Mesh.cone())
        shapes["plane"] = Mesh(Mesh.plane())
        buildProgram()
    }

    private fun shader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(id)
            GLES20.glDeleteShader(id)
            throw RuntimeException("Shader failed: " + log)
        }
        return id
    }

    private fun buildProgram() {
        val vertex = "uniform mat4 uMvp;attribute vec3 aPos;attribute vec3 aNormal;varying vec3 vN;" +
            "void main(){vN=aNormal;gl_Position=uMvp*vec4(aPos,1.0);}"
        val fragment = "precision mediump float;uniform vec3 uColor;uniform vec3 uLight;uniform float uAmbient;" +
            "varying vec3 vN;void main(){float d=max(dot(normalize(vN),normalize(uLight)),0.0);" +
            "vec3 c=uColor*(uAmbient+(1.0-uAmbient)*d);gl_FragColor=vec4(pow(c,vec3(0.4545)),1.0);}"
        val vs = shader(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Program failed: " + GLES20.glGetProgramInfoLog(program))
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uLight = GLES20.glGetUniformLocation(program, "uLight")
        uAmbient = GLES20.glGetUniformLocation(program, "uAmbient")
    }

    fun clear() {
        GLES20.glClearColor(scene.bg[0], scene.bg[1], scene.bg[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
    }

    // A flat colour for the eye images — used by the startup check that the picture
    // really is reaching the lenses.
    fun clearTo(r: Float, g: Float, b: Float) {
        GLES20.glClearColor(r, g, b, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
    }

    // Draws the whole scene for one eye, given that eye's view and projection.
    fun draw(view: FloatArray, proj: FloatArray) {
        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glUniform1f(uAmbient, 0.32f)
        for (i in scene.items.indices) {
            val item = scene.items[i]
            Matrix.multiplyMM(scratch, 0, view, 0, item.model, 0)
            Matrix.multiplyMM(mvp, 0, proj, 0, scratch, 0)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniform3f(uColor, item.color[0], item.color[1], item.color[2])
            GLES20.glUniform3f(uLight, item.light[0], item.light[1], item.light[2])
            val mesh = shapes[item.shape]
            if (mesh != null) mesh.draw(aPos, aNormal)
        }
    }
}
