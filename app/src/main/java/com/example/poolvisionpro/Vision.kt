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
            // Hough detection must operate on the pool cloth, NOT the entire
            // landscape frame (which contains lots of HUD circular icons).
            val left=table.left.toInt().coerceIn(0,gray.cols()-2)
            val top=table.top.toInt().coerceIn(0,gray.rows()-2)
            val right=table.right.toInt().coerceIn(left+2,gray.cols())
            val bottom=table.bottom.toInt().coerceIn(top+2,gray.rows())
            val crop=gray.submat(org.opencv.core.Rect(left,top,right-left,bottom-top))
            try {
                Imgproc.HoughCircles(
                    crop,circles,Imgproc.HOUGH_GRADIENT,
                    1.0,max(7.0,(expectedRadius*1.7).toDouble()),
                    105.0,20.0,
                    max(4,(expectedRadius*.72).toInt()),
                    max(5,(expectedRadius*1.35).toInt())
                )
            } finally { crop.release() }
            val found=mutableListOf<Ball>()
            for(i in 0 until circles.cols()) {
                val v=circles.get(0,i) ?: continue
                val x=v[0].toFloat()+left
                val y=v[1].toFloat()+top
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
    fun findCueBallAndDirection(bitmap:Bitmap, found:List<Ball>, playingSurface:RectF):Pair<Ball?,PointF?> {
        if(found.isEmpty()) return Pair(null,null)

        // Colored in-game aiming lines should not be treated as a physical cue stick.
        // Identify longer brown cue-stick segments, then extrapolate them backward
        // toward the whitish circular candidate nearest the wooden tip.
        val src=Mat()
        val gray=Mat()
        val edges=Mat()
        val segments=Mat()
        val woodenLines=mutableListOf<FloatArray>()
        val guideLines=mutableListOf<FloatArray>()
        try {
            Utils.bitmapToMat(bitmap,src)
            Imgproc.cvtColor(src,gray,Imgproc.COLOR_RGBA2GRAY)
            Imgproc.Canny(gray,edges,65.0,145.0)
            // Keep a margin for wooden cue entering from below/side, while
            // avoiding HUD edges and other expensive full-screen Hough lines.
            val left=(playingSurface.left-20f).toInt().coerceIn(0,edges.cols()-2)
            val top=(playingSurface.top-20f).toInt().coerceIn(0,edges.rows()-2)
            val right=(playingSurface.right+20f).toInt().coerceIn(left+2,edges.cols())
            val bottom=(playingSurface.bottom+20f).toInt().coerceIn(top+2,edges.rows())
            val roi=edges.submat(org.opencv.core.Rect(left,top,right-left,bottom-top))
            try {
                Imgproc.HoughLinesP(roi,segments,1.0,Math.PI/180.0,52,60.0,12.0)
            } finally { roi.release() }
            for(i in 0 until segments.rows()) {
                val points=segments.get(i,0) ?: continue
                if(points.size<4)continue
                val x1=points[0].toFloat()+left;val y1=points[1].toFloat()+top
                val x2=points[2].toFloat()+left;val y2=points[3].toFloat()+top
                val lineLength=hypot(x2-x1,y2-y1)
                if(lineLength<60f)continue
                val mx=((x1+x2)*.5f).toInt()
                val my=((y1+y2)*.5f).toInt()
                if(mx<0 || my<0 || mx>=bitmap.width || my>=bitmap.height)continue
                // HUD/table rails may have long straight edges: use the cloth ROI.
                if(!playingSurface.contains(mx.toFloat(),my.toFloat()))continue
                guideLines.add(floatArrayOf(x1,y1,x2,y2))
                val pixel=bitmap.getPixel(mx,my)
                val red=Color.red(pixel);val green=Color.green(pixel);val blue=Color.blue(pixel)
                if(red>green+18 && green>blue+8 && red>75) {
                    woodenLines.add(floatArrayOf(x1,y1,x2,y2))
                }
            }
        } finally {
            src.release();gray.release();edges.release();segments.release()
        }

        val offsets=listOf(
            0f to 0f, -.25f to 0f, .25f to 0f, 0f to -.25f, 0f to .25f,
            -.45f to 0f, .45f to 0f, 0f to -.45f, 0f to .45f,
            -.3f to -.3f, -.3f to .3f, .3f to -.3f, .3f to .3f
        )
        fun whiteness(ball:Ball):Float {
            var white=0;var colored=0;var count=0
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
            return if(count==0)-1f else (white-colored*.6f)/count
        }

        var selected:Ball?=null
        var direction:PointF?=null
        var bestScore=Float.POSITIVE_INFINITY
        for(ball in found) {
            val whiteScore=whiteness(ball)
            if(whiteScore<.10f)continue
            for(line in woodenLines){
                val x1=line[0];val y1=line[1];val x2=line[2];val y2=line[3]
                val vx=x2-x1;val vy=y2-y1
                val length=hypot(vx,vy)
                if(length<1f)continue
                val ux=vx/length;val uy=vy/length
                val along=(ball.x-x1)*ux+(ball.y-y1)*uy
                val across=abs((ball.x-x1)*uy-(ball.y-y1)*ux)
                val outside=when {
                    along<0f -> -along
                    along>length -> along-length
                    else -> 0f
                }
                // Ball should lie in front of (not on top of) the wooden segment.
                if(outside<ball.r*.5f || outside>max(25f*ball.r,210f))continue
                if(across>ball.r*.95f)continue
                val score=(outside/ball.r)+(across/ball.r)*3f-whiteScore*5f
                if(score<bestScore){
                    val farX=if(along<0f)x2 else x1
                    val farY=if(along<0f)y2 else y1
                    val dx=ball.x-farX;val dy=ball.y-farY
                    val mag=hypot(dx,dy).coerceAtLeast(1f)
                    direction=PointF(dx/mag,dy/mag)
                    selected=ball
                    bestScore=score
                }
            }
        }
        // A wooden cue can merge into the game's bright native aim line.
        // As a fallback, use one long straight edge crossing a white candidate,
        // and locate the brown shaft on precisely ONE side of that candidate.
        // This identifies direction from wood -> white ball -> shot, without
        // manually selecting a cue, target, pocket, or table each turn.
        if(selected==null){
            var bestFallback=Float.NEGATIVE_INFINITY
            for(ball in found){
                val whiteScore=whiteness(ball)
                if(whiteScore<.12f)continue
                for(line in guideLines){
                    val x1=line[0];val y1=line[1];val x2=line[2];val y2=line[3]
                    val vx=x2-x1;val vy=y2-y1
                    val lineLength=hypot(vx,vy)
                    if(lineLength<ball.r*7f)continue
                    val ux=vx/lineLength;val uy=vy/lineLength
                    val along=(ball.x-x1)*ux+(ball.y-y1)*uy
                    val cross=abs((ball.x-x1)*uy-(ball.y-y1)*ux)
                    if(along< -ball.r*3f || along>lineLength+ball.r*3f ||
                       cross>ball.r*1.15f)continue
                    fun woodVotes(sign:Float):Int{
                        var count=0
                        for(m in floatArrayOf(3f,4f,5f,6f,8f,10f,12f)){
                            val px=ball.x+sign*ux*ball.r*m
                            val py=ball.y+sign*uy*ball.r*m
                            if(!playingSurface.contains(px,py))continue
                            val xx=px.toInt();val yy=py.toInt()
                            if(xx<0||yy<0||xx>=bitmap.width||yy>=bitmap.height)continue
                            val sample=bitmap.getPixel(xx,yy)
                            val r=Color.red(sample);val g=Color.green(sample)
                            val b=Color.blue(sample)
                            if(r>g+8 && g>b+6 && r in 66..239)count++
                        }
                        return count
                    }
                    val leftVotes=woodVotes(-1f)
                    val rightVotes=woodVotes(1f)
                    val dominant=max(leftVotes,rightVotes)
                    val separation=abs(leftVotes-rightVotes)
                    if(dominant<3 || separation<3)continue
                    val score=whiteScore*4f+separation*1.4f -
                        cross/ball.r*4f
                    if(score>bestFallback) {
                        bestFallback=score
                        selected=ball
                        val sign=if(rightVotes>leftVotes) -1f else 1f
                        direction=PointF(ux*sign,uy*sign)
                    }
                }
            }
        }
        return Pair(selected,direction)
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

    // Geometry-only preview when all direct routes are blocked by other balls.
    // Caller MUST render this in red rather than as a reliable shot.
    fun blockedRoutePreview(c:Ball,t:Ball,table:RectF):Plan? =
        bestPocketPlan(c,t,emptyList(),table)

    fun bestPocketPlan(c:Ball,t:Ball,all:List<Ball>,table:RectF):Plan? {
        val midX=(table.left+table.right)*.5f
        // Calibration rectangle describes the blue playing cloth, not the
        // centers of the pocket openings. Move targets outward from the rails.
        val radius=table.width()/67f
        val leftPocket=table.left-radius*.8f
        val rightPocket=table.right+radius*.8f
        val topPocket=table.top-radius*1.65f
        val bottomPocket=table.bottom+radius*1.8f
        val pockets=listOf(
            PointF(leftPocket,topPocket),
            PointF(midX,topPocket),
            PointF(rightPocket,topPocket),
            PointF(leftPocket,bottomPocket),
            PointF(midX,bottomPocket),
            PointF(rightPocket,bottomPocket)
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
