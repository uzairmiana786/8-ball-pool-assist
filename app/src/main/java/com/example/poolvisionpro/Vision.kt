package com.example.poolvisionpro

import android.graphics.*
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

data class Ball(val x:Float,val y:Float,val r:Float)
data class Plan(val cue:Ball,val target:Ball,val ghost:PointF,val pocket:PointF)
object Vision {
    fun detect(bitmap:Bitmap, table:RectF):List<Ball> {
        val src=Mat(); val gray=Mat(); val circles=Mat()
        try {
            Utils.bitmapToMat(bitmap,src)
            Imgproc.cvtColor(src,gray,Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray,gray,Size(5.0,5.0),1.1)
            val r=table.width()/67f
            Imgproc.HoughCircles(gray,circles,Imgproc.HOUGH_GRADIENT,1.0,(r*1.7).toDouble(),90.0,18.0,max(3,(r*.72).toInt()),max(4,(r*1.3).toInt()))
            return (0 until circles.cols()).mapNotNull { i ->
                val v=circles.get(0,i) ?: return@mapNotNull null
                if(table.contains(v[0].toFloat(),v[1].toFloat())) Ball(v[0].toFloat(),v[1].toFloat(),v[2].toFloat()) else null
            }.take(24)
        } finally {src.release();gray.release();circles.release()}
    }
    fun distance(a:Ball,b:Ball)=hypot(a.x-b.x,a.y-b.y)
    fun plan(c:Ball,t:Ball,p:PointF,all:List<Ball>,table:RectF):Plan? {
        val d=hypot(t.x-p.x,t.y-p.y);if(d<1)return null
        val g=PointF(t.x+(t.x-p.x)/d*(c.r+t.r),t.y+(t.y-p.y)/d*(c.r+t.r))
        if(!table.contains(g.x,g.y))return null
        // Reject back-facing contact, obstructed cue paths, and obstructed pot paths.
        if((g.x-c.x)*(p.x-t.x)+(g.y-c.y)*(p.y-t.y)<=0)return null
        fun segmentDistance(b:Ball,x:Float,y:Float,xx:Float,yy:Float):Float {
            val dx=xx-x;val dy=yy-y;val den=dx*dx+dy*dy
            if(den<1)return hypot(b.x-x,b.y-y)
            val u=((b.x-x)*dx+(b.y-y)*dy)/den
            return hypot(b.x-x-u.coerceIn(0f,1f)*dx,b.y-y-u.coerceIn(0f,1f)*dy)
        }
        if(all.any { it!==c && it!==t && (segmentDistance(it,c.x,c.y,g.x,g.y)<it.r+c.r || segmentDistance(it,t.x,t.y,p.x,p.y)<it.r+t.r) })return null
        return Plan(c,t,g,p)
    }
}
