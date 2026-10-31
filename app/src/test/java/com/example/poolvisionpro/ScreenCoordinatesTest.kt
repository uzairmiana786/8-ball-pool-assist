package com.example.poolvisionpro
import org.junit.Assert.assertEquals
import org.junit.Test
class ScreenCoordinatesTest {
 @Test fun landscapeCutoutDoesNotShiftBall(){
  val detected=ScreenCoordinates.screen(896f,1280,1600)
  val local=ScreenCoordinates.local(detected,80)
  assertEquals(1120f,local+80,0.01f)
 }
 @Test fun nonUniformRoundingUsesActualImageDimensions(){
  assertEquals(718f,ScreenCoordinates.screen(574f,574,718),0.01f)
 }
 @Test fun noInsetPreservesScreenCoordinates(){assertEquals(400f,ScreenCoordinates.local(400f,0),0f)}
}
