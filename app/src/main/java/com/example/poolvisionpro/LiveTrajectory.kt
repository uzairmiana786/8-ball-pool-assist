package com.example.poolvisionpro

import kotlin.math.*

/** Pure geometry for a single anticipated 2-D shot, independent of Android APIs. */
data class V2(val x:Float,val y:Float) {
    operator fun plus(other:V2)=V2(x+other.x,y+other.y)
    operator fun minus(other:V2)=V2(x-other.x,y-other.y)
    operator fun times(amount:Float)=V2(x*amount,y*amount)
    fun dot(other:V2)=x*other.x+y*other.y
    fun norm():Float=hypot(x,y)
    fun unit():V2 { val n=norm();return if(n<.00001f) V2(0f,0f) else V2(x/n,y/n) }
}
data class Circle2(val center:V2,val radius:Float)
data class Bounds2(val left:Float,val top:Float,val right:Float,val bottom:Float) {
    fun valid()=right>left+20f && bottom>top+20f
}
data class LiveTrajectory(
    val cueStart:V2,
    val cueEnd:V2,
    val cueCushionEnd:V2?,
    val hitBall:Circle2?,
    val objectEnd:V2?,
    val objectCushionEnd:V2?,
    val cueAfterEnd:V2?
)
object ShotGeometry {
    private data class WallHit(val t:Float,val normal:V2)
    private fun wall(start:V2,dir:V2,box:Bounds2,r:Float):WallHit? {
        var best=Float.POSITIVE_INFINITY
        var normal=V2(0f,0f)
        fun pick(t:Float,n:V2) {
            if(t>.0001f && t<best){best=t;normal=n}
        }
        if(dir.x>.00001f)pick((box.right-r-start.x)/dir.x,V2(-1f,0f))
        if(dir.x<-.00001f)pick((box.left+r-start.x)/dir.x,V2(1f,0f))
        if(dir.y>.00001f)pick((box.bottom-r-start.y)/dir.y,V2(0f,-1f))
        if(dir.y<-.00001f)pick((box.top+r-start.y)/dir.y,V2(0f,1f))
        return if(best.isFinite() && best>0f)WallHit(best,normal) else null
    }
    /** Returns the first contact distance of a moving ball's CENTER along a ray. */
    private fun collision(start:V2,dir:V2,movingRadius:Float,ball:Circle2):Float? {
        val delta=ball.center-start
        val projected=delta.dot(dir)
        if(projected<=0f)return null
        val r=movingRadius+ball.radius
        val distanceSquared=delta.dot(delta)-projected*projected
        val squared=r*r-distanceSquared
        if(squared<0f)return null
        val first=projected-sqrt(max(0f,squared))
        return if(first>=.0001f) first else null
    }
    private fun reflected(dir:V2,normal:V2):V2 =
        (dir-normal*(2f*dir.dot(normal))).unit()
    private fun bounce(at:V2,dir:V2,normal:V2,box:Bounds2,r:Float):V2? {
        val newDir=reflected(dir,normal)
        val nudge=at+newDir*.1f
        val next=wall(nudge,newDir,box,r) ?: return null
        return nudge+newDir*next.t
    }
    /**
     * Geometric, no spin/friction/power. The first cue/object collision and
     * outgoing object-ball tangent are computed from circle contact.
     */
    fun trace(cue:Circle2,aim:V2,others:List<Circle2>,box:Bounds2):LiveTrajectory? {
        if(!box.valid() || cue.radius<=0f)return null
        val dir=aim.unit()
        if(dir.norm()<.5f)return null
        val wallHit=wall(cue.center,dir,box,cue.radius) ?: return null
        var target:Circle2?=null
        var nearest=wallHit.t
        for(ball in others) {
            if(ball.radius<=0f || (ball.center-cue.center).norm()<cue.radius*.8f)continue
            val t=collision(cue.center,dir,cue.radius,ball) ?: continue
            if(t<nearest){nearest=t;target=ball}
        }
        val hitAt=cue.center+dir*nearest
        val actual=target
        if(actual==null) {
            return LiveTrajectory(cue.center,hitAt,
                bounce(hitAt,dir,wallHit.normal,box,cue.radius),
                null,null,null,null)
        }
        // Normal from impact cue center to object ball center.
        val normal=(actual.center-hitAt).unit()
        val objectWall=wall(actual.center,normal,box,actual.radius)
        var objectDist=objectWall?.t ?: 0f
        var hitSecond=false
        for(other in others) {
            if((other.center-actual.center).norm() < actual.radius*.6f)continue
            val t=collision(actual.center,normal,actual.radius,other) ?: continue
            if(t<objectDist){objectDist=t;hitSecond=true}
        }
        val objectEnd=actual.center+normal*objectDist
        val objectBounce=if(!hitSecond && objectWall!=null)
            bounce(objectEnd,normal,objectWall.normal,box,actual.radius) else null
        // Equal-mass no-spin approximation; display as a separate cue branch.
        val tangent=dir-normal*dir.dot(normal)
        val cueFollow=if(tangent.norm()>.13f) {
            val d=tangent.unit()
            val wallAfter=wall(hitAt+d*.1f,d,box,cue.radius)
            if(wallAfter!=null) hitAt+d*min(wallAfter.t,(box.right-box.left)*.55f)
            else null
        } else null
        return LiveTrajectory(cue.center,hitAt,null,actual,objectEnd,objectBounce,cueFollow)
    }
}
