package com.example.poolvisionpro

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Switch

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (panel != null) return START_NOT_STICKY
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 16, 22, 16)
            setBackgroundColor(Color.rgb(25, 32, 46))
        }
        val header = TextView(this).apply {
            text = "🎱 POOL VISION | DEMO ✕"
            textSize = 15f
            setTextColor(Color.CYAN)
            setPadding(0, 0, 0, 8)
        }
        box.addView(header)
        for (name in listOf("Aim Line", "Ghost Ball", "Object Path", "Cushion Reflection")) {
            box.addView(Switch(this).apply {
                text = name
                textSize = 12f
                setTextColor(Color.WHITE)
                isChecked = true
                // Visual demo only; does not alter third-party games.
            })
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 180 }
        var lastX = 0f
        var lastY = 0f
        header.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = event.rawX; lastY = event.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    p.x += (event.rawX - lastX).toInt()
                    p.y += (event.rawY - lastY).toInt()
                    lastX = event.rawX; lastY = event.rawY
                    wm.updateViewLayout(box, p)
                    true
                }
                MotionEvent.ACTION_UP -> { if (event.eventTime - event.downTime < 220) stopSelf(); true }
                else -> true
            }
        }
        wm.addView(box, p)
        panel = box
        params = p
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        panel?.let { wm.removeView(it) }
        panel = null
        super.onDestroy()
    }
}
