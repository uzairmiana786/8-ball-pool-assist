package com.example.poolvisionpro

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.*

data class Ball(val x:Float,val y:Float,val r:Float)
data class Plan(val cue:Ball,val target:Ball,val ghost:PointF,val pocket:PointF)

/**
 * Experimental visual ball tracking and geometric shot guidance.
 * Does not know the game's spin, collision engine, or power.
 */
object Vision {
    private fun colorDistance(a:Int,b:Int):Float {
        val dr=Color.red(a)-Color.red(b)
        val dg=Color.green(a)-Color.green(b)
        val db=Color.blue(a)-Color.blue(b)
        return sqrt((dr*dr+dg*dg+db*db).toFloat())
    }

    // Filter circles that contain cloth instead of a shaded ball.
    private fun looksLikeBall(bitmap:Bitmap, x:Float, y:Float, r:Float):Boolean {
        val samples= listOf(
            0f to 0f, -.35f to 0f, .35f to 0f, 0f to -.35f, 0f to .35f,
            -.35f to -.35f, .35f to .35f, -.35f to .35f, .35f to -.35f
        )
        val rings=listOf(-1.65f to 0f, 1.65f to 0f, 0f to -1.65f, 0f to 1.65f)
        fun pixel(dx:Float,dy:Float):Int? {
            val xx=(x+r*dx).toInt()
            val yy=(y+r*dy).toInt()
            if(xx<0 || yy<0 || xx>=bitmap.width || yy>=bitmap.height) return null
            return bitmap.getPixel(xx,yy)
        }
        val background=rings.mapNotNull{ pixel(it.first,it.second) }
        if(background.size<3) return false
        val cloth=Color.rgb(
            background.map{Color.red(it)}.average().toInt(),
            background.map{Color.green(it)}.average().toInt(),
            background.map{Color.blue(it)}.average().toInt()
        )
        val colors=samples.mapNotNull{ pixel(it.first,it.second) }
        if(colors.size<7) return false
        return colors.count{ colorDistance(it,cloth)>32f } >= 4
    }

    fun detect(bitmap:Bitmap, table:RectF):List<Ball> {
        val src=Mat()
        val gray=Mat()
        val circles=Mat()
        try {
            Utils.bitmapToMat(bitmap,src)
            Imgproc.cvtColor(src,gray,Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray,gray,Size(5.0,5.0),1.2)
            val expectedRadius=table.width()/67f
            Imgproc.HoughCircles(
                gray,circles,Imgproc.HOUGH_GRADIENT,
                1.0,max(7.0,(expectedRadius*1.7).toDouble()),
                105.0,20.0,
                max(4,(expectedRadius*.72).toInt()),
                max(5,(expectedRadius*1.35).toInt())
            )
            val found=mutableListOf<Ball>()
            for(i in 0 until circles.cols()) {
                val v=circles.get(0,i) ?: continue
                val x=v[0].toFloat()
                val y=v[1].toFloat()
                val r=v[2].toFloat()
                if(!table.contains(x,y)) continue
                if(x-r<table.left || x+r>table.right || y-r<table.top || y+r>table.bottom) continue
                if(!looksLikeBall(bitmap,x,y,r)) continue
                if(found.any{distance(it,Ball(x,y,r)) < max(it.r,r)*1.1f})continue
                found.add(Ball(x,y,r))
                if(found.size>=20)break
            }
            return found
        } finally {
            src.release();gray.release();circles.release()
        }
    }

    fun distance(a:Ball,b:Ball)=hypot(a.x-b.x,a.y-b.y)

    /**
     * Among circular candidates, prefer a bright, low-saturation mostly-white ball.
     * Can confuse striped balls, so auto shoot must remain guarded.
     */
    fun findCueBall(bitmap:Bitmap, found:List<Ball>):Ball? {
        if(found.isEmpty())return null

        // A plain white ball cannot reliably be differentiated from striped balls
        // using brightness alone. Prefer the endpoint of the long game cue stick.
        val src=Mat()
        val gray=Mat()
        val edges=Mat()
        val segments=Mat()
        val longLines=mutableListOf<FloatArray>()
        try {
            Utils.bitmapToMat(bitmap,src)
            Imgproc.cvtColor(src,gray,Imgproc.COLOR_RGBA2GRAY)
            Imgproc.Canny(gray,edges,65.0,145.0)
            Imgproc.HoughLinesP(edges,segments,1.0,Math.PI/180.0,55,75.0,12.0)
            for(i in 0 until segments.rows()){
                val points=segments.get(i,0) ?: continue
                if(points.size>=4)longLines.add(floatArrayOf(points[0].toFloat(),points[1].toFloat(),points[2].toFloat(),points[3].toFloat()))
            }
        } finally {
            src.release();gray.release();edges.release();segments.release()
        }
        fun nearStickTip(ball:Ball):Boolean {
            for(line in longLines){
                val x1=line[0];val y1=line[1];val x2=line[2];val y2=line[3]
                val vx=x2-x1;val vy=y2-y1
                val len=hypot(vx,vy)
                if(len<ball.r*7f)continue
                val d1=hypot(ball.x-x1,ball.y-y1)
                val d2=hypot(ball.x-x2,ball.y-y2)
                val endX=if(d1<d2)x1 else x2
                val endY=if(d1<d2)y1 else y2
                val dirX=if(d1<d2)vx/len else -vx/len
                val dirY=if(d1<d2)vy/len else -vy/len
                val dx=ball.x-endX;val dy=ball.y-endY
                val along=dx*dirX+dy*dirY
                val across=abs(dx*dirY-dy*dirX)
                if(min(d1,d2)<ball.r*2.6f && across<ball.r && along<ball.r*.3f)
                    return true
            }
            return false
        }
        val offsets=listOf(
            0f to 0f, -.25f to 0f, .25f to 0f, 0f to -.25f, 0f to .25f,
            -.45f to 0f, .45f to 0f, 0f to -.45f, 0f to .45f,
            -.3f to -.3f, -.3f to .3f, .3f to -.3f, .3f to .3f
        )
        var best:Ball?=null
        var bestScore=.95f
        for(ball in found){
            var white=0;var count=0;var colored=0
            for((dx,dy) in offsets){
                val x=(ball.x+dx*ball.r).toInt()
                val y=(ball.y+dy*ball.r).toInt()
                if(x<0 || y<0 || x>=bitmap.width || y>=bitmap.height)continue
                val pixel=bitmap.getPixel(x,y)
                val r=Color.red(pixel);val g=Color.green(pixel);val b=Color.blue(pixel)
                val spread=max(r,max(g,b))-min(r,min(g,b))
                val brightness=(r+g+b)/3
                if(brightness>165 && spread<53)white++
                if(spread>70 && brightness>85)colored++
                count++
            }
            if(count==0)continue
            val whiteScore=(white-colored*.6f)/count
            val hasStick=nearStickTip(ball)
            val score=whiteScore+(if(hasStick)1.1f else 0f)
            if(score>bestScore && (hasStick || whiteScore>.95f)){
                bestScore=score
                best=ball
            }
        }
        return best
    }

    private fun segmentDistance(b:Ball,x:Float,y:Float,xx:Float,yy:Float):Float {
        val dx=xx-x
        val dy=yy-y
        val den=dx*dx+dy*dy
        if(den<1f)return hypot(b.x-x,b.y-y)
        val u=(((b.x-x)*dx+(b.y-y)*dy)/den).coerceIn(0f,1f)
        return hypot(b.x-x-u*dx,b.y-y-u*dy)
    }

    fun plan(c:Ball,t:Ball,p:PointF,all:List<Ball>,table:RectF):Plan? {
        val d=hypot(t.x-p.x,t.y-p.y)
        if(d<1f || distance(c,t)<c.r+t.r-1f)return null
        val g=PointF(t.x+(t.x-p.x)/d*(c.r+t.r),t.y+(t.y-p.y)/d*(c.r+t.r))
        if(!table.contains(g.x,g.y))return null
        if((g.x-c.x)*(p.x-t.x)+(g.y-c.y)*(p.y-t.y)<=0)return null
        // Exclude balls by POSITION, not Kotlin object identity: manual taps create new Ball instances.
        if(all.any {
            val isCue=distance(it,c)<max(c.r,it.r)*.9f
            val isTarget=distance(it,t)<max(t.r,it.r)*.9f
            !isCue && !isTarget &&
              (segmentDistance(it,c.x,c.y,g.x,g.y)<it.r+c.r*.92f ||
               segmentDistance(it,t.x,t.y,p.x,p.y)<it.r+t.r*.92f)
        })return null
        return Plan(c,t,g,p)
    }

    fun bestPocketPlan(c:Ball,t:Ball,all:List<Ball>,table:RectF):Plan? {
        val midX=(table.left+table.right)*.5f
        val pockets=listOf(
            PointF(table.left,table.top),
            PointF(midX,table.top),
            PointF(table.right,table.top),
            PointF(table.left,table.bottom),
            PointF(midX,table.bottom),
            PointF(table.right,table.bottom)
        )
        return pockets.mapNotNull{p->
            val candidate=plan(c,t,p,all,table) ?: return@mapNotNull null
            val cueDistance=hypot(candidate.ghost.x-c.x,candidate.ghost.y-c.y)
            val pocketDistance=hypot(p.x-t.x,p.y-t.y)
            val ux=(candidate.ghost.x-c.x)/max(cueDistance,1f)
            val uy=(candidate.ghost.y-c.y)/max(cueDistance,1f)
            val vx=(p.x-t.x)/max(pocketDistance,1f)
            val vy=(p.y-t.y)/max(pocketDistance,1f)
            val cutPenalty=(1f-(ux*vx+uy*vy).coerceIn(-1f,1f))*600f
            Pair(candidate, cueDistance*.35f + pocketDistance + cutPenalty)
        }.minByOrNull{it.second}?.first
    }
}
