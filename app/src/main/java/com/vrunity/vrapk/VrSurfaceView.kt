package com.vrunity.vrapk

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.view.MotionEvent

// The screen the game draws on in screen mode, plus the headset's motion sensors
// feeding it the head's orientation.
class VrSurfaceView(context: Context) : GLSurfaceView(context), SensorEventListener {
    private val renderer = VrRenderer(context)
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotationMatrix = FloatArray(9)
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun startSensors() {
        val sensor = rotationSensor
        if (sensor != null) sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stopSensors() {
        sensors.unregisterListener(this)
    }

    fun walk(dir: Float) { renderer.walkBy(dir) }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        renderer.setHead(rotationMatrix)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    renderer.drag((event.x - lastX) * 0.004f, (event.y - lastY) * 0.004f)
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging && Math.abs(event.x - lastX) < 8f && Math.abs(event.y - lastY) < 8f) renderer.recenter()
                dragging = false
            }
        }
        return true
    }
}
