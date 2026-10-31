package com.example.poolvisionpro

import org.junit.Assert.*
import org.junit.Test

class ShotGeometryTest {
    private val table=Bounds2(0f,0f,600f,400f)
    private val cue=Circle2(V2(100f,200f),10f)

    @Test fun headOnCollisionSendsObjectForward() {
        val target=Circle2(V2(350f,200f),10f)
        val trace=ShotGeometry.trace(cue,V2(1f,0f),listOf(target),table)!!
        assertEquals(330f,trace.cueEnd.x,.01f)
        assertNotNull(trace.hitBall)
        assertEquals(590f,trace.objectEnd!!.x,.01f)
        assertNull(trace.cueCushionEnd)
    }

    @Test fun missesAllBallsAndReflectsOffRail() {
        val missed=Circle2(V2(350f,280f),10f)
        val trace=ShotGeometry.trace(cue,V2(1f,0f),listOf(missed),table)!!
        assertNull(trace.hitBall)
        assertEquals(590f,trace.cueEnd.x,.01f)
        assertNotNull(trace.cueCushionEnd)
        assertEquals(10f,trace.cueCushionEnd!!.x,.2f)
    }

    @Test fun nearestOfTwoBallsIsChosen() {
        val far=Circle2(V2(350f,200f),10f)
        val near=Circle2(V2(200f,200f),10f)
        val trace=ShotGeometry.trace(cue,V2(1f,0f),listOf(far,near),table)!!
        assertEquals(180f,trace.cueEnd.x,.01f)
        assertEquals(near,trace.hitBall)
    }

    @Test fun glancingHitCreatesDeflectionBranch() {
        val target=Circle2(V2(230f,212f),10f)
        val trace=ShotGeometry.trace(cue,V2(1f,0f),listOf(target),table)!!
        assertNotNull(trace.hitBall)
        assertNotNull(trace.cueAfterEnd)
        assertNotNull(trace.objectEnd)
    }

    @Test fun invalidAimReturnsNull() {
        assertNull(ShotGeometry.trace(cue,V2(0f,0f),emptyList(),table))
    }
}
