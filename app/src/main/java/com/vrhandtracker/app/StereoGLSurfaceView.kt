package com.vrhandtracker.app

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet

class StereoGLSurfaceView(context: Context, attrs: AttributeSet) : GLSurfaceView(context, attrs) {
    init {
        setEGLContextClientVersion(3)
        setPreserveEGLContextOnPause(true)
        setRenderer(StereoRenderer())
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}