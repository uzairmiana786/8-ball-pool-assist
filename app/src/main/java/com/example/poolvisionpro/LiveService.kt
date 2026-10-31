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
 private var manualCue=false;private var manualTarget=false
 private val sampleLoop=object:Runnable {
  override fun run(){
   if(cancelled)return
   if(!busy && mode.isEmpty()){
    drawing.visibility=View.INVISIBLE
    sampleAfter=SystemClock.elapsedRealtimeNanos()+180_000_000L
    main.postDelayed({if(!cancelled && !busy)drawing.visibility=View.VISIBLE},450)
   }
   main.postDelayed(this,900)
  }
 }
 private var enabled=false; private var cancelled=false
 private var plan:Plan?=null
 override fun onBind(i:Intent?)=null
 private fun say(s:String){status.text=s}
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
     val found=try{Vision.detect(bitmap,area).map{Ball(ScreenCoordinates.screen(it.x,bitmap.width,screenW),ScreenCoordinates.screen(it.y,bitmap.height,screenH),it.r/sx)}}finally{if(bitmap!==raw)bitmap.recycle();raw.recycle()}
     main.post{if(!cancelled && !busy && mode.isEmpty()){lastFrame=now;update(found);drawing.visibility=View.VISIBLE}}
    }catch(e:Exception){main.post{if(!cancelled)say("Detection error: ${e.javaClass.simpleName}")}}finally{img.close()}
   },Handler(worker.looper))
   main.post(sampleLoop)
  }catch(e:Exception){Toast.makeText(this,"Capture failed: ${e.message}",Toast.LENGTH_LONG).show();stopSelf()}
  return START_NOT_STICKY
 }
 private fun update(found:List<Ball>){
  fun track(old:Ball?):Ball? {if(old==null)return null;return found.minByOrNull{Vision.distance(it,old)}?.takeIf{Vision.distance(it,old)<old.r*1.5f}}
  val quiet=balls.size==found.size && found.isNotEmpty() && found.all{b->balls.any{Vision.distance(it,b)<b.r*.25f}}
  stable=if(quiet)stable+1 else 0
  val newCue=track(cue);val newTarget=track(target)
  if(newCue!=null){cue=newCue;manualCue=false}else if(!manualCue)cue=null
  if(newTarget!=null){target=newTarget;manualTarget=false}else if(!manualTarget)target=null
  balls=found
  plan=if(cue!=null && target!=null && pocket!=null)Vision.plan(cue!!,target!!,pocket!!,balls,table) else null
  if(mode.isEmpty() && !busy)say("${balls.size} circles • ${if(stable>=3)"stable" else "moving"} • ${if(plan!=null)"path ready" else "select cue, target, pocket / path blocked"}")
  drawing.invalidate()
 }
 private fun fullParams(touch:Boolean)=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
  WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or (if(touch)0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;alpha=.65f
   if(Build.VERSION.SDK_INT>=28)layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
   if(Build.VERSION.SDK_INT>=30)setFitInsetsTypes(0)
  }
 private fun pick(s:String){if(busy)return;mode=s;first=null;sampleAfter=Long.MAX_VALUE;drawing.visibility=View.VISIBLE;wm.updateViewLayout(drawing,fullParams(true));say("Tap $s${if(s=="table" || s=="power")": first point, then second point" else ""}")}
 private fun finishPick(){mode="";wm.updateViewLayout(drawing,fullParams(false));stable=0;plan=null;drawing.invalidate()}
 private fun buildOverlay(){
  drawing=Guide();wm.addView(drawing,fullParams(false))
  fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
  val box=LinearLayout(this).apply{orientation=1;setPadding(dp(4),dp(2),dp(4),dp(2));setBackgroundColor(Color.rgb(12,22,38))}
  val header=TextView(this).apply{text="POOL VISION 0.5 • drag here";textSize=12f;setTextColor(Color.CYAN);setPadding(8,8,8,8)}
  box.addView(header)
  val body=LinearLayout(this).apply{orientation=1};box.addView(body)
  status=TextView(this).apply{setTextColor(Color.WHITE);textSize=10f;text="Table → Cue → Target → Pocket"};body.addView(status)
  fun row():LinearLayout {val r=LinearLayout(this);body.addView(r);return r}
  fun button(r:LinearLayout,t:String,fn:()->Unit){r.addView(Button(this).apply{text=t;textSize=10f;minWidth=0;minimumWidth=0;setPadding(0,0,0,0);setOnClickListener{fn()}},LinearLayout.LayoutParams(0,dp(36),1f))}
  val r=row();button(r,"Table"){pick("table")};button(r,"Cue"){pick("cue")};button(r,"Target"){pick("target")}
  val r2=row();button(r2,"Pocket"){pick("pocket")};button(r2,"Power"){pick("power")};button(r2,"Cancel"){finishPick();say("Selection cancelled")}
  body.addView(Switch(this).apply{text="Game Tap-to-Aim enabled";textSize=10f;setTextColor(Color.WHITE);setOnCheckedChangeListener{_,v->enabled=v}})
  val r3=row();button(r3,"Aim only"){execute(false)};button(r3,"Aim + Shot"){execute(true)}
  val footer=LinearLayout(this);box.addView(footer)
  button(footer,"Hide / Show"){body.visibility=if(body.visibility==View.VISIBLE)View.GONE else View.VISIBLE}
  button(footer,"STOP"){cancelled=true;stopSelf()}
  panel=box
  val params=WindowManager.LayoutParams(min(dp(285),screenW/2),-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;x=0;y=0}
  panelParams=params
  var startX=0f;var startY=0f;var px=0;var py=0
  header.setOnTouchListener{_,e->when(e.actionMasked){
   MotionEvent.ACTION_DOWN->{startX=e.rawX;startY=e.rawY;px=params.x;py=params.y}
   MotionEvent.ACTION_MOVE->{params.x=(px+e.rawX-startX).toInt().coerceIn(0,max(0,screenW-box.width));params.y=(py+e.rawY-startY).toInt().coerceIn(0,max(0,screenH-box.height));wm.updateViewLayout(box,params)}
  };true}
  wm.addView(box,params)
 }
 private fun execute(shoot:Boolean){
  val p=plan;val svc=GestureService.instance;val a=powerStart;val b=powerEnd
  if(busy)return
  if(!enabled || p==null || svc==null || !GestureService.inGame || mode.isNotEmpty() || (shoot && (stable<3 || manualCue || manualTarget || a==null || b==null)) || SystemClock.elapsedRealtime()-lastFrame>2500){say("Need game foreground, gestures, stable clear path, Tap-to-Aim and power calibration");return}
  val panelLocation=IntArray(2);panel?.getLocationOnScreen(panelLocation)
  val covered=panel?.let{RectF(panelLocation[0].toFloat(),panelLocation[1].toFloat(),(panelLocation[0]+it.width).toFloat(),(panelLocation[1]+it.height).toFloat())}
  if(covered!=null && (covered.contains(p.ghost.x,p.ghost.y) || (shoot && a!=null && b!=null && RectF.intersects(covered,RectF(min(a.x,b.x)-2,min(a.y,b.y)-2,max(a.x,b.x)+2,max(a.y,b.y)+2))))){say("Move panel away from aim / power slider");return}
  busy=true;frozen=true;say("Aiming — STOP cancels pending shot")
  // Hide drawings so they cannot obstruct the injected tap or contaminate screenshots.
  drawing.visibility=View.GONE
  val aim=Path().apply{moveTo(p.ghost.x,p.ghost.y)}
  svc.gesture(aim,100){ok->
   if(!ok){reset("Aim gesture failed");return@gesture}
   if(!shoot){reset("Aim tap sent — check game line before shooting");return@gesture}
   main.postDelayed({
    if(cancelled || !GestureService.inGame || !enabled){reset("Shot cancelled");return@postDelayed}
    val path=Path().apply{moveTo(a!!.x,a.y);lineTo(b!!.x,b.y)}
    svc.gesture(path,650){shot->reset(if(shot)"Gesture sent; verify result in game" else "Shot gesture failed")}
   },900)
  }
 }
 private fun reset(message:String){if(cancelled)return;busy=false;frozen=false;stable=0;cue=null;target=null;plan=null;drawing.visibility=View.VISIBLE;say(message)}
 inner class Guide:View(this){
  private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
  override fun onDraw(c:Canvas){
   val origin=IntArray(2);getLocationOnScreen(origin)
   c.save();c.translate(ScreenCoordinates.local(0f,origin[0]),ScreenCoordinates.local(0f,origin[1]))
   paint.style=Paint.Style.STROKE;paint.strokeWidth=2f;paint.color=Color.CYAN
   c.drawRect(table,paint)
   // Outside edges reduce interference with the next detection frame.
   balls.forEach{paint.color=if(it===cue)Color.WHITE else if(it===target)Color.YELLOW else Color.CYAN;c.drawCircle(it.x,it.y,it.r+5,paint)}
   plan?.let{p->paint.color=Color.YELLOW;c.drawLine(p.cue.x,p.cue.y,p.ghost.x,p.ghost.y,paint);c.drawCircle(p.ghost.x,p.ghost.y,p.cue.r,paint);paint.color=Color.GREEN;c.drawLine(p.target.x,p.target.y,p.pocket.x,p.pocket.y,paint)}
   c.restore()
  }
  override fun onTouchEvent(e:MotionEvent):Boolean{
   if(e.action!=MotionEvent.ACTION_UP)return true
   val pt=PointF(e.rawX,e.rawY)
   when(mode){
    "table"->{if(first==null){first=pt;return true};val f=first!!;if(abs(pt.x-f.x)<screenW*.3 || abs(pt.y-f.y)<screenH*.3){say("Table selection too small; retry");first=null;return true};table=RectF(min(f.x,pt.x),min(f.y,pt.y),max(f.x,pt.x),max(f.y,pt.y));cue=null;target=null}
    "power"->{if(first==null){first=pt;return true};powerStart=first;powerEnd=pt}
    "cue","target"->{val selected=balls.minByOrNull{hypot(it.x-pt.x,it.y-pt.y)}?.takeIf{hypot(it.x-pt.x,it.y-pt.y)<it.r*3};if(!table.contains(pt.x,pt.y)){say("Tap inside calibrated table");return true}
     val chosen=selected ?: Ball(pt.x,pt.y,table.width()/67f)
     if(mode=="cue"){cue=chosen;manualCue=selected==null}else{target=chosen;manualTarget=selected==null};if(cue===target)target=null}
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
