package com.example.poolvisionpro

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.*
import android.os.*
import android.view.*
import android.widget.*
import org.opencv.android.OpenCVLoader
import kotlin.math.*

class LiveService:Service(){
 private val main=Handler(Looper.getMainLooper())
 private val worker=HandlerThread("vision")
 private var projection:MediaProjection?=null
 private var reader:ImageReader?=null
 private var display:VirtualDisplay?=null
 private lateinit var wm:WindowManager
 private lateinit var drawing:Guide
 private var panel:LinearLayout?=null
 private lateinit var status:TextView
 private var screenW=0; private var screenH=0
 private var balls=listOf<Ball>();@Volatile private var table=RectF()
 private var cue:Ball?=null;private var target:Ball?=null;private var pocket:PointF?=null
 private var powerStart:PointF?=null;private var powerEnd:PointF?=null
 private var mode="";private var first:PointF?=null
 private var busy=false;@Volatile private var lastFrame=0L;private var stable=0;@Volatile private var frozen=false
 @Volatile private var sampleAfter=Long.MAX_VALUE
 private var panelParams:WindowManager.LayoutParams?=null
 @Volatile private var panelRect=RectF()
 private var statusHoldUntil=0L
 private var manualCue=false;private var manualTarget=false
 // Auto mode only acts on the currently selected target, once per selection.
 private var autoAimEnabled=false
 private var autoShootEnabled=false
 private var autoAttempted=false
 private var cueMissCount=0
 private val sampleLoop=object:Runnable {
  override fun run(){
   if(cancelled)return
   if(!busy && mode.isEmpty()){
    drawing.visibility=View.INVISIBLE
    sampleAfter=SystemClock.elapsedRealtimeNanos()+100_000_000L
    main.postDelayed({if(!cancelled && !busy)drawing.visibility=View.VISIBLE},250)
   }
   main.postDelayed(this,550)
  }
 }
 private var enabled=false; private var cancelled=false
 private var plan:Plan?=null
 override fun onBind(i:Intent?)=null
 private fun say(s:String,hold:Boolean=false){
  status.text=s
  if(hold)statusHoldUntil=SystemClock.elapsedRealtime()+3500L
 }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  if(intent?.action=="STOP"){stopSelf();return START_NOT_STICKY}
  if(projection!=null)return START_NOT_STICKY
  val nm=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
  nm.createNotificationChannel(NotificationChannel("capture","Screen capture",NotificationManager.IMPORTANCE_LOW))
  val stop=PendingIntent.getService(this,1,Intent(this,LiveService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
  val n=Notification.Builder(this,"capture").setContentTitle("Pool Vision: screen capture active").setContentText("Tap Stop to end capture and gestures").setSmallIcon(android.R.drawable.ic_menu_view).addAction(android.R.drawable.ic_delete,"Stop",stop).build()
  if(Build.VERSION.SDK_INT>=29)startForeground(10,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(10,n)
  try{
   if(!OpenCVLoader.initLocal())error("OpenCV load failed")
   wm=getSystemService(WINDOW_SERVICE) as WindowManager
   val metrics=android.util.DisplayMetrics();@Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
   screenW=metrics.widthPixels;screenH=metrics.heightPixels
   if(screenW<screenH)error("Rotate to landscape before starting capture")
   table=RectF(screenW*.195f,screenH*.24f,screenW*.805f,screenH*.90f)
   buildOverlay()
   @Suppress("DEPRECATION") val consent=intent?.getParcelableExtra<Intent>("consent") ?: error("Missing capture permission")
   projection=(getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(intent.getIntExtra("code",0),consent)
   worker.start()
   projection!!.registerCallback(object:MediaProjection.Callback(){
    override fun onStop(){stopSelf()}
    override fun onCapturedContentResize(w:Int,h:Int){if(w!=screenW || h!=screenH){Toast.makeText(this@LiveService,"Capture size changed. Restart using entire-screen capture in landscape.",Toast.LENGTH_LONG).show();stopSelf()}}
   },main)
   // Full-size capture; detection runs on a 1280px-wide image.
   reader=ImageReader.newInstance(screenW,screenH,PixelFormat.RGBA_8888,2)
   display=projection!!.createVirtualDisplay("PoolVision",screenW,screenH,metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,null)
   reader!!.setOnImageAvailableListener({r->
    val img=runCatching{r.acquireLatestImage()}.getOrNull() ?: return@setOnImageAvailableListener
    try{
     val now=SystemClock.elapsedRealtime()
     if(frozen || img.timestamp<sampleAfter)return@setOnImageAvailableListener
     sampleAfter=Long.MAX_VALUE
     val plane=img.planes[0]
     // Read rows independently: some devices omit padding after the final row.
     val pixels=IntArray(img.width*img.height);val buffer=plane.buffer
     for(y in 0 until img.height)for(x in 0 until img.width){
      val off=y*plane.rowStride+x*plane.pixelStride
      pixels[y*img.width+x]=Color.argb(buffer.get(off+3).toInt() and 255,buffer.get(off).toInt() and 255,buffer.get(off+1).toInt() and 255,buffer.get(off+2).toInt() and 255)
     }
     val raw=Bitmap.createBitmap(pixels,img.width,img.height,Bitmap.Config.ARGB_8888)
     val scale=min(1f,1280f/img.width)
     val bitmap=Bitmap.createScaledBitmap(raw,(img.width*scale).toInt(),(img.height*scale).toInt(),true)
     val sx=bitmap.width.toFloat()/screenW;val sy=bitmap.height.toFloat()/screenH
     val bounds=table
     val area=RectF(bounds.left*sx,bounds.top*sy,bounds.right*sx,bounds.bottom*sy)
     val result=try{
      // Exclude the floating menu: white buttons looked like balls in captured frames.
      val excluded=panelRect
      val detected=Vision.detect(bitmap,area).filterNot { ball ->
       excluded.contains(
        ScreenCoordinates.screen(ball.x,bitmap.width,screenW),
        ScreenCoordinates.screen(ball.y,bitmap.height,screenH)
       )
      }
      val white=Vision.findCueBall(bitmap,detected)
      val mapped=detected.map{Ball(ScreenCoordinates.screen(it.x,bitmap.width,screenW),ScreenCoordinates.screen(it.y,bitmap.height,screenH),it.r/sx)}
      val whiteMapped=white?.let{Ball(ScreenCoordinates.screen(it.x,bitmap.width,screenW), ScreenCoordinates.screen(it.y,bitmap.height,screenH),it.r/sx)}
      Pair(mapped,whiteMapped)
     }finally{if(bitmap!==raw)bitmap.recycle();raw.recycle()}
     main.post{if(!cancelled && !busy && mode.isEmpty()){lastFrame=now;update(result.first,result.second);drawing.visibility=View.VISIBLE}}
    }catch(e:Exception){main.post{if(!cancelled)say("Detection error: ${e.javaClass.simpleName}")}}finally{img.close()}
   },Handler(worker.looper))
   main.post(sampleLoop)
  }catch(e:Exception){Toast.makeText(this,"Capture failed: ${e.message}",Toast.LENGTH_LONG).show();stopSelf()}
  return START_NOT_STICKY
 }
 private fun update(found:List<Ball>, whiteCue:Ball?){
  fun track(old:Ball?):Ball? {
   if(old==null)return null
   return found.minByOrNull{Vision.distance(it,old)}
    ?.takeIf{Vision.distance(it,old)<max(old.r,it.r)*2.1f}
  }
  val quiet=balls.size==found.size && found.isNotEmpty() &&
   found.all{b->balls.any{Vision.distance(it,b)<max(b.r,it.r)*.34f}}
  stable=if(quiet)stable+1 else 0
  if(!manualCue) {
   if(whiteCue!=null){cue=whiteCue;cueMissCount=0}
   else {
    cueMissCount++
    cue=if(cueMissCount<=2)track(cue) else null
   }
  }
  val trackedTarget=track(target)
  if(trackedTarget!=null){target=trackedTarget;manualTarget=false}
  else if(!manualTarget)target=null
  balls=found
  val selectedCue=cue
  val selectedTarget=target
  plan=if(selectedCue!=null && selectedTarget!=null)
   Vision.bestPocketPlan(selectedCue,selectedTarget,balls,table) else null
  if(mode.isEmpty() && !busy && SystemClock.elapsedRealtime()>=statusHoldUntil) {
   say(when {
    selectedTarget==null -> "${balls.size} balls • Tap Target once"
    selectedCue==null -> "White cue undetected • tap Cue once as fallback"
    plan==null -> "No clear DIRECT pocket path for selected ball (amber preview only)"
    autoAimEnabled && GestureService.instance==null -> "Enable Pool Vision Gestures in Accessibility settings"
    autoAimEnabled && !GestureService.inGame -> "Game foreground not confirmed by Accessibility service"
    autoAimEnabled && stable<2 -> "Shot path ready • checking frame stability ${stable}/2"
    else -> "AUTO POCKET READY • ${balls.size} detected balls"
   })
  }
  drawing.invalidate()
  // One automatic aim attempt per target. No continuous gestures or repeated shots.
  if(autoAimEnabled && !autoAttempted && !busy && mode.isEmpty() &&
     plan!=null && stable>=2 &&
     GestureService.inGame && SystemClock.elapsedRealtime()-lastFrame<1500L){
   autoAttempted=true
   main.postDelayed({
    if(!cancelled && autoAimEnabled && !busy && mode.isEmpty())execute(autoShootEnabled)
   },120)
  }
 }
 private fun fullParams(touch:Boolean)=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
  WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or (if(touch)0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;alpha=.65f
   if(Build.VERSION.SDK_INT>=28)layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
   if(Build.VERSION.SDK_INT>=30)setFitInsetsTypes(0)
  }
 private fun pick(s:String){
  if(busy)return
  mode=s
  first=null
  if(s=="target" || s=="cue" || s=="table") autoAttempted=false
  sampleAfter=Long.MAX_VALUE
  drawing.visibility=View.VISIBLE
  wm.updateViewLayout(drawing,fullParams(true))
  say("Tap $s${if(s=="table" || s=="power")": first point, then second point" else ""}")
 }
 private fun finishPick(){mode="";wm.updateViewLayout(drawing,fullParams(false));stable=0;plan=null;drawing.invalidate()}
 private fun buildOverlay(){
  drawing=Guide();wm.addView(drawing,fullParams(false))
  fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
  val box=LinearLayout(this).apply{orientation=1;setPadding(dp(4),dp(2),dp(4),dp(2));setBackgroundColor(Color.rgb(12,22,38))}
  val header=TextView(this).apply{text="POOL VISION 0.6 • drag here";textSize=12f;setTextColor(Color.CYAN);setPadding(8,8,8,8)}
  box.addView(header)
  val body=LinearLayout(this).apply{orientation=1};box.addView(body)
  status=TextView(this).apply{setTextColor(Color.WHITE);textSize=10f;text="Table → Cue → Target → Pocket"};body.addView(status)
  fun row():LinearLayout {val r=LinearLayout(this);body.addView(r);return r}
  fun button(r:LinearLayout,t:String,fn:()->Unit){r.addView(Button(this).apply{text=t;textSize=10f;minWidth=0;minimumWidth=0;setPadding(0,0,0,0);setOnClickListener{fn()}},LinearLayout.LayoutParams(0,dp(36),1f))}
  val r=row();button(r,"Table"){pick("table")};button(r,"Cue"){pick("cue")};button(r,"Target"){pick("target")}
  val r2=row();button(r2,"Pocket"){pick("pocket")};button(r2,"Power"){pick("power")};button(r2,"Cancel"){finishPick();say("Selection cancelled")}
  body.addView(Switch(this).apply{
   text="AUTO AIM • selected target"
   textSize=11f;setTextColor(Color.WHITE)
   setOnCheckedChangeListener{_,v->
    enabled=v;autoAimEnabled=v;autoAttempted=false
    say(if(v)"AUTO AIM ON • tap Target once, pocket is automatic" else "Auto Aim OFF")
   }
  })
  body.addView(Switch(this).apply{
   text="AUTO SHOT • optional (Power calibration required)"
   textSize=10f;setTextColor(Color.WHITE)
   setOnCheckedChangeListener{_,v->
    autoShootEnabled=v
    if(v && (powerStart==null || powerEnd==null))
     say("Auto Shot armed but Power is not calibrated. Use Power once.")
   }
  })
  val r3=row()
  button(r3,"Aim Now"){execute(false)}
  button(r3,"Aim + Shot"){execute(true)}
  val footer=LinearLayout(this);box.addView(footer)
  button(footer,"Hide / Show"){body.visibility=if(body.visibility==View.VISIBLE)View.GONE else View.VISIBLE}
  button(footer,"STOP"){cancelled=true;stopSelf()}
  panel=box
  val params=WindowManager.LayoutParams(min(dp(285),screenW/2),-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;x=0;y=0}
  panelParams=params
  fun updatePanelRect(){
   if(box.width<=0 || box.height<=0)return
   val location=IntArray(2)
   box.getLocationOnScreen(location)
   panelRect=RectF(
    location[0].toFloat(), location[1].toFloat(),
    (location[0]+box.width).toFloat(),(location[1]+box.height).toFloat()
   )
  }
  box.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->updatePanelRect()}
  var startX=0f;var startY=0f;var px=0;var py=0
  header.setOnTouchListener{_,e->when(e.actionMasked){
   MotionEvent.ACTION_DOWN->{startX=e.rawX;startY=e.rawY;px=params.x;py=params.y}
   MotionEvent.ACTION_MOVE->{params.x=(px+e.rawX-startX).toInt().coerceIn(0,max(0,screenW-box.width));params.y=(py+e.rawY-startY).toInt().coerceIn(0,max(0,screenH-box.height));wm.updateViewLayout(box,params);box.post{updatePanelRect()}}
  };true}
  wm.addView(box,params)
 }
 private fun execute(shoot:Boolean){
  val p=plan;val svc=GestureService.instance;val a=powerStart;val b=powerEnd
  if(busy){say("Aim currently busy",true);return}
  val reason=when {
   !enabled -> "AUTO AIM switch is OFF"
   mode.isNotEmpty() -> "Finish current selection first"
   target==null -> "Target not selected • tap Target"
   cue==null -> "White cue ball missing • tap Cue once"
   p==null -> "No clear direct pocket for this target • try another ball"
   svc==null -> "Enable Pool Vision Gestures in Accessibility"
   !GestureService.inGame -> "Accessibility cannot confirm the 8 Ball Pool foreground"
   SystemClock.elapsedRealtime()-lastFrame>2500 -> "Screen capture frames stale • restart Capture"
   shoot && (stable<2 || manualCue || manualTarget) -> "Auto Shot requires stable automatically detected balls"
   shoot && (a==null || b==null) -> "Auto Shot needs one-time Power calibration"
   else -> null
  }
  if(reason!=null){say(reason,true);autoAttempted=false;return}
  val shotPlan=p ?: return
  val gestureSvc=svc ?: return
  val panelLocation=IntArray(2);panel?.getLocationOnScreen(panelLocation)
  val covered=panel?.let{RectF(panelLocation[0].toFloat(),panelLocation[1].toFloat(),(panelLocation[0]+it.width).toFloat(),(panelLocation[1]+it.height).toFloat())}
  if(covered!=null && (covered.contains(shotPlan.ghost.x,shotPlan.ghost.y) || (shoot && a!=null && b!=null && RectF.intersects(covered,RectF(min(a.x,b.x)-2,min(a.y,b.y)-2,max(a.x,b.x)+2,max(a.y,b.y)+2))))){say("Move panel away from aim / power slider",true);autoAttempted=false;return}
  busy=true;frozen=true;say("Aiming — STOP cancels pending shot")
  // Hide drawings so they cannot obstruct the injected tap or contaminate screenshots.
  drawing.visibility=View.GONE
  // The game responds to an on-table tap along cue -> ghost direction, not overlay coordinates.
  val dx=shotPlan.ghost.x-shotPlan.cue.x
  val dy=shotPlan.ghost.y-shotPlan.cue.y
  val length=hypot(dx,dy).coerceAtLeast(1f)
  val ux=dx/length
  val uy=dy/length
  var aimDist=max(length*1.4f,table.width()*.3f)
  if(ux>0.001f)aimDist=min(aimDist,(table.right-shotPlan.cue.r*2-shotPlan.cue.x)/ux)
  if(ux<-.001f)aimDist=min(aimDist,(table.left+shotPlan.cue.r*2-shotPlan.cue.x)/ux)
  if(uy>0.001f)aimDist=min(aimDist,(table.bottom-shotPlan.cue.r*2-shotPlan.cue.y)/uy)
  if(uy<-.001f)aimDist=min(aimDist,(table.top+shotPlan.cue.r*2-shotPlan.cue.y)/uy)
  val aim=Path().apply{moveTo(shotPlan.cue.x+ux*max(0f,aimDist),shotPlan.cue.y+uy*max(0f,aimDist))}
  gestureSvc.gesture(aim,100){ok->
   if(!ok){reset("Android aim gesture failed");return@gesture}
   if(!shoot){reset("Auto aim tap sent; verify cue direction before manual shot",false);return@gesture}
   main.postDelayed({
    if(cancelled || !GestureService.inGame || !enabled){reset("Shot cancelled",false);return@postDelayed}
    val path=Path().apply{moveTo(a!!.x,a.y);lineTo(b!!.x,b.y)}
    gestureSvc.gesture(path,650){shot->reset(if(shot)"Shot gesture sent; check game result" else "Shot gesture failed",shot)}
   },900)
  }
 }
 private fun reset(message:String,clearSelection:Boolean=false){
  if(cancelled)return
  busy=false;frozen=false;stable=0
  if(clearSelection){cue=null;target=null;plan=null;autoAttempted=false;manualCue=false;manualTarget=false}
  drawing.visibility=View.VISIBLE
  say(message,true)
 }
 inner class Guide:View(this){
  private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
  override fun onDraw(c:Canvas){
   val origin=IntArray(2);getLocationOnScreen(origin)
   c.save();c.translate(ScreenCoordinates.local(0f,origin[0]),ScreenCoordinates.local(0f,origin[1]))
   paint.style=Paint.Style.STROKE;paint.strokeWidth=2f;paint.color=Color.CYAN
   c.drawRect(table,paint)
   // Outside edges reduce interference with the next detection frame.
   balls.forEach{paint.color=if(it===cue)Color.WHITE else if(it===target)Color.YELLOW else Color.CYAN;c.drawCircle(it.x,it.y,it.r+5,paint)}
   // Diagnostic manual guide remains visible without a detected ball or selected pocket.
   val manualStart=cue;val manualEnd=target
   if(manualStart!=null && manualEnd!=null && plan==null){
    // This amber dashed line is NOT a valid pot trajectory.
    paint.strokeWidth=3f;paint.color=Color.rgb(255,165,0)
    paint.pathEffect=DashPathEffect(floatArrayOf(13f,9f),0f)
    c.drawLine(manualStart.x,manualStart.y,manualEnd.x,manualEnd.y,paint)
    paint.pathEffect=null
    c.drawCircle(manualStart.x,manualStart.y,manualStart.r+3f,paint)
    paint.color=Color.rgb(255,165,0)
    c.drawCircle(manualEnd.x,manualEnd.y,manualEnd.r+3f,paint)
    paint.strokeWidth=2f
   }
   plan?.let{p->
    paint.strokeWidth=4f
    paint.color=Color.YELLOW
    c.drawLine(p.cue.x,p.cue.y,p.ghost.x,p.ghost.y,paint)
    c.drawCircle(p.ghost.x,p.ghost.y,p.cue.r,paint)
    paint.color=Color.GREEN
    c.drawLine(p.target.x,p.target.y,p.pocket.x,p.pocket.y,paint)
    paint.strokeWidth=2f
   }
   c.restore()
  }
  override fun onTouchEvent(e:MotionEvent):Boolean{
   if(e.action!=MotionEvent.ACTION_UP)return true
   val pt=PointF(e.rawX,e.rawY)
   when(mode){
    "table"->{
      if(first==null){first=pt;return true}
      val f=first!!
      if(abs(pt.x-f.x)<screenW*.15 || abs(pt.y-f.y)<screenH*.15){
       say("Table selection too small; retry");first=null;return true
      }
      table=RectF(min(f.x,pt.x),min(f.y,pt.y),max(f.x,pt.x),max(f.y,pt.y))
      cue=null;target=null;manualCue=false;manualTarget=false;autoAttempted=false
    }
    "power"->{if(first==null){first=pt;return true};powerStart=first;powerEnd=pt}
    "cue","target"->{val selected=balls.minByOrNull{hypot(it.x-pt.x,it.y-pt.y)}?.takeIf{hypot(it.x-pt.x,it.y-pt.y)<it.r*3};if(!table.contains(pt.x,pt.y)){say("Tap inside calibrated table");return true}
     val detected=balls.minByOrNull{hypot(it.x-pt.x,it.y-pt.y)}
      ?.takeIf{hypot(it.x-pt.x,it.y-pt.y)<max(it.r,table.width()/67f)*2.2f}
     val chosen=detected ?: Ball(pt.x,pt.y,(table.width()/67f).coerceIn(7f,24f))
     if(mode=="cue"){cue=chosen;manualCue=detected==null}
     else{target=chosen;manualTarget=detected==null}
     if(cue===target)target=null}
    "pocket"->pocket=pt
   }
   finishPick();return true
  }
 }
 override fun onDestroy(){
  cancelled=true;main.removeCallbacksAndMessages(null)
  runCatching{display?.release()};reader?.setOnImageAvailableListener(null,null)
  if(worker.isAlive)Handler(worker.looper).post{runCatching{reader?.close()}} else runCatching{reader?.close()}
  runCatching{projection?.stop()};projection=null
  worker.quitSafely()
  if(::drawing.isInitialized)runCatching{wm.removeView(drawing)}
  panel?.let{runCatching{wm.removeView(it)}}
  stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
 }
}
