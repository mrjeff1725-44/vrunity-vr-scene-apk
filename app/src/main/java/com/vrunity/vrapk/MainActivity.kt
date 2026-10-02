package com.vrunity.vrapk

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager

// A native Android VR game. On a headset the app opens straight into the headset's
// own VR session; on a device without one it falls back to screen mode.
class MainActivity : Activity() {
    private var surface: VrSurfaceView? = null
    private var screenMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(View(this))
        // The headset's VR runtime is tried first, off the main thread — it does not
        // answer instantly.
        val thread = Thread {
            // 2 = the headset ran the game, anything else falls back to the screen.
            val result = XrSession(this).run()
            runOnUiThread {
                if (result == 2) finish() else startScreenMode()
            }
        }
        thread.start()
    }

    private fun startScreenMode() {
        if (screenMode) return
        screenMode = true
        val s = VrSurfaceView(this)
        surface = s
        setContentView(s)
        s.onResume()
        s.startSensors()
        fullscreen()
    }

    override fun onResume() {
        super.onResume()
        surface?.onResume()
        surface?.startSensors()
        fullscreen()
    }

    override fun onPause() {
        surface?.stopSensors()
        surface?.onPause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) fullscreen()
    }

    // Screen mode only: volume up walks forward, volume down walks back, reachable
    // by touch while the device sits in a phone holder.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = surface
        if (s != null) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                s.walk(1f)
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                s.walk(-1f)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun fullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }
}
