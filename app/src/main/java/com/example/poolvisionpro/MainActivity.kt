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
  label("POOL VISION • Auto Aim 0.6")
  label("Auto cue tracking + tap Target once + AUTO AIM switch. App chooses a clear pocket and shows shot guides. AUTO SHOT is separately armed and requires one-time Power calibration. Experimental accuracy: no guaranteed pots. Start in landscape and share the ENTIRE screen.")
  label("Accessibility sends only the aim tap and calibrated power swipe when you press Aim + Shoot. Screen frames stay in memory; no uploads or recordings. Turn this service off in Android settings whenever you wish.")
  button("1 • Allow floating controls"){startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))}
  button("2 • Enable Pool Vision gestures"){startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}
  button("3 • Start screen capture"){
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
