package com.example.poolvisionpro

object ScreenCoordinates {
 fun local(screen:Float, origin:Int)=screen-origin
 fun screen(pixel:Float, bitmapSize:Int, screenSize:Int):Float {
  require(bitmapSize>0 && screenSize>0)
  return pixel*screenSize/bitmapSize
 }
}
