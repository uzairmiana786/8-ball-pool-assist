package com.example.poolvisionpro

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import org.opencv.android.OpenCVLoader

class MainActivity:Activity(){
 override fun onCreate(state:Bundle?){
  super.onCreate(state)
  val layout=LinearLayout(this).apply{orientation=1;setPadding(32,48,32,24)}
  val scroll=ScrollView(this);scroll.addView(layout);setContentView(scroll)
  fun label(s:String){layout.addView(TextView(this).apply{text=s;textSize=18f;setPadding(0,12,0,12)})}
  fun button(s:String,action:()->Unit){layout.addView(Button(this).apply{text=s;setOnClickListener{action()}})}
  label("POOL VISION • Auto Live ESP 0.8")
  label("Experimental 0.8: NO table, cue, target or pocket taps. Rotate your cue stick in the game to see a thick cyan aiming ray, green object-ball trajectory and magenta cushion reflection. Requires visually detected white cue and wooden stick. Spin, force and game physics are not modeled. Start in landscape and share ENTIRE screen.")
  label("Read-only on-screen prediction: this build does NOT aim or shoot for you. Android Accessibility gestures are not needed. Screen frames are processed locally in memory.")
  button("1 • Allow floating controls"){startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))}
  button("2 • Start AUTO LIVE screen capture"){
   if(!Settings.canDrawOverlays(this))Toast.makeText(this,"Allow floating controls first",Toast.LENGTH_LONG).show()
   else if(!OpenCVLoader.initLocal())Toast.makeText(this,"OpenCV could not load",Toast.LENGTH_LONG).show()
   else startActivityForResult((getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent(),40)
  }
  button("Stop capture and controls"){stopService(Intent(this,LiveService::class.java))}
  button("Offline simulator"){startActivity(Intent(this,SimulatorActivity::class.java))}
 }
 @Deprecated("Platform result API")
 override fun onActivityResult(request:Int,result:Int,data:Intent?){
  super.onActivityResult(request,result,data)
  if(request==40 && result==RESULT_OK && data!=null)startForegroundService(Intent(this,LiveService::class.java).putExtra("code",result).putExtra("consent",data))
 }
}
