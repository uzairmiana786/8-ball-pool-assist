package com.example.poolvisionpro

import android.app.Activity
import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class SimulatorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TableView())
    }
    inner class TableView : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var angle = -0.5f
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                angle = atan2(event.y - height * 0.65f, event.x - width * 0.32f)
                invalidate()
            }
            return true
        }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawColor(Color.rgb(9, 25, 33))
            val l = w * 0.06f; val r = w * 0.94f; val t = h * 0.15f; val b = h * 0.85f
            paint.color = Color.rgb(18, 105, 70)
            canvas.drawRoundRect(l, t, r, b, 16f, 16f, paint)
            paint.color = Color.BLACK
            for (x in listOf(l,r)) for (y in listOf(t,b)) canvas.drawCircle(x,y,14f,paint)
            val cx=w*0.32f; val cy=h*0.65f; val ox=w*0.67f; val oy=h*0.40f; val radius=14f
            paint.color=Color.WHITE; canvas.drawCircle(cx,cy,radius,paint)
            paint.color=Color.YELLOW; canvas.drawCircle(ox,oy,radius,paint)
            val dx=cos(angle); val dy=sin(angle)
            val vx=ox-cx; val vy=oy-cy
            val proj=vx*dx+vy*dy
            val disc=4f*radius*radius-(vx*vx+vy*vy-proj*proj)
            val hit=if(disc>=0f && proj>0f) (proj-sqrt(disc)).takeIf { it>=0f } else null
            var wall=Float.POSITIVE_INFINITY; var nx=0f; var ny=0f
            fun consider(v:Float,x:Float,y:Float) { if(v>0f && v<wall) {wall=v;nx=x;ny=y} }
            if(dx>0) consider((r-radius-cx)/dx,-1f,0f)
            if(dx<0) consider((l+radius-cx)/dx,1f,0f)
            if(dy>0) consider((b-radius-cy)/dy,0f,-1f)
            if(dy<0) consider((t+radius-cy)/dy,0f,1f)
            val collide=hit!=null && hit<wall
            val travel=if(collide) hit!! else wall
            val ex=cx+dx*travel; val ey=cy+dy*travel
            paint.strokeWidth=3f;paint.color=Color.CYAN
            canvas.drawLine(cx,cy,ex,ey,paint)
            if(collide) {
                paint.style=Paint.Style.STROKE;paint.color=Color.WHITE
                canvas.drawCircle(ex,ey,radius,paint)
                paint.style=Paint.Style.FILL
                paint.color=Color.GREEN
                val normalX=(ox-ex)/(2*radius);val normalY=(oy-ey)/(2*radius)
                canvas.drawLine(ox,oy,ox+normalX*145,oy+normalY*145,paint)
            } else if(wall.isFinite()) {
                val dot=dx*nx+dy*ny
                paint.color=Color.MAGENTA
                canvas.drawLine(ex,ey,ex+(dx-2*dot*nx)*125,ey+(dy-2*dot*ny)*125,paint)
            }
            paint.color=Color.WHITE;paint.textSize=26f
            canvas.drawText("Touch to aim • idealized physics",l,b+45f,paint)
        }
    }
}
