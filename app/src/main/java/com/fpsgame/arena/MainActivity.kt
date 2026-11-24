package com.fpsgame.arena

import android.app.Activity
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var gl: GLSurfaceView
    private lateinit var sfx: Sfx

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        sfx = Sfx()
        val game = Game(sfx)
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        game.onHurt = { try { vib?.vibrate(35) } catch (_: Exception) {} }

        gl = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(GameRenderer(game))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(FrameLayout(this).apply {
            addView(gl)
            addView(HudView(this@MainActivity, game))
        })
    }

    private fun hideUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULL_SCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onResume() { super.onResume(); gl.onResume(); hideUi() }
    override fun onPause() { super.onPause(); gl.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideUi()
    }
    override fun onDestroy() { super.onDestroy(); sfx.release() }
}
