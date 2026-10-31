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

/**
 * Read-only pool shot overlay. No accessibility gestures, automatic shooting,
 * target taps, or pocket selection. Live prediction depends on cue-stick detection.
 */
class LiveService: Service() {
    private val main=Handler(Looper.getMainLooper())
    private val worker=HandlerThread("pool-vision-worker")
    private var projection:MediaProjection?=null
    private var reader:ImageReader?=null
    private var virtualDisplay:VirtualDisplay?=null
    private lateinit var windows:WindowManager
    private lateinit var drawing:Guide
    private lateinit var status:TextView
    private var menu:LinearLayout?=null
    private var screenW=0
    private var screenH=0
    private var cloth=RectF()
    private var allBalls=listOf<Ball>()
    private var cue:Ball?=null
    private var direction:V2?=null
    private var live:LiveTrajectory?=null
    private var thickness=8f
    private var showDetection=false
    private var autoLive=true
    private var lastFrame=0L
    private var missed=0
    private var directionMissed=0
    private var framesProcessed=0L
    private var lastProcessingMs=0L
    @Volatile private var stopped=false
    @Volatile private var pending=false
    @Volatile private var processing=false
    @Volatile private var requestedAt=0L
    @Volatile private var captureAfterNs=Long.MAX_VALUE
    @Volatile private var menuBounds=RectF()

    override fun onBind(intent:Intent?):IBinder?=null

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="STOP"){stopSelf();return START_NOT_STICKY}
        if(projection!=null)return START_NOT_STICKY
        val manager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            "pool-vision","Pool Vision capture",NotificationManager.IMPORTANCE_LOW))
        val stopIntent=PendingIntent.getService(
            this,1,Intent(this,LiveService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE)
        val note=Notification.Builder(this,"pool-vision")
            .setContentTitle("Pool Vision • Auto Live")
            .setContentText("Read-only shot path; tap Stop to end capture")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(android.R.drawable.ic_delete,"Stop",stopIntent).build()
        if(Build.VERSION.SDK_INT>=29)
            startForeground(10,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(10,note)

        try {
            if(!OpenCVLoader.initLocal())error("OpenCV failed to initialize")
            windows=getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics=android.util.DisplayMetrics()
            @Suppress("DEPRECATION") windows.defaultDisplay.getRealMetrics(metrics)
            screenW=metrics.widthPixels
            screenH=metrics.heightPixels
            if(screenW<=screenH)error("Start in landscape after opening the game")
            // Relative cloth frame matching the landscape game screenshot;
            // scales with screen resolution (no per-shot table selection).
            cloth=RectF(screenW*.195f,screenH*.24f,screenW*.805f,screenH*.9f)
            buildOverlay()
            @Suppress("DEPRECATION")
            val consent=intent?.getParcelableExtra<Intent>("consent")
                ?: error("Screen capture consent missing")
            val projectionManager=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection=projectionManager.getMediaProjection(intent.getIntExtra("code",0),consent)
            worker.start()
            projection!!.registerCallback(object:MediaProjection.Callback(){
                override fun onStop(){stopSelf()}
                override fun onCapturedContentResize(w:Int,h:Int) {
                    if(w!=screenW || h!=screenH){
                        Toast.makeText(this@LiveService,"Screen size changed; restart capture in landscape",Toast.LENGTH_LONG).show()
                        stopSelf()
                    }
                }
            },main)
            reader=ImageReader.newInstance(screenW,screenH,PixelFormat.RGBA_8888,2)
            virtualDisplay=projection!!.createVirtualDisplay(
                "PoolVisionAuto",screenW,screenH,metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface,null,null)
            reader!!.setOnImageAvailableListener({ imageReader->
                val image=runCatching{imageReader.acquireLatestImage()}.getOrNull()
                    ?: return@setOnImageAvailableListener
                try {
                    if(stopped || !pending || !autoLive)
                        return@setOnImageAvailableListener
                    pending=false
                    processing=true
                    val frameTime=SystemClock.elapsedRealtime()
                    val startNs=SystemClock.elapsedRealtimeNanos()
                    val plane=image.planes[0]
                    val buffer=plane.buffer
                    // Bulk native pixel copy (usually a few milliseconds).
                    // The previous Java per-channel loop performed over four
                    // million ByteBuffer reads per captured screen.
                    val raw:Bitmap
                    if(plane.pixelStride==4 && plane.rowStride%4==0) {
                        val paddedWidth=plane.rowStride/4
                        val padded=Bitmap.createBitmap(paddedWidth,image.height,Bitmap.Config.ARGB_8888)
                        buffer.rewind()
                        val required=plane.rowStride.toLong()*image.height
                        if(buffer.remaining().toLong()>=required) {
                            padded.copyPixelsFromBuffer(buffer)
                        }else{
                            // Last-row padding is not guaranteed on every Android driver.
                            val bytes=ByteArray(required.toInt())
                            buffer.get(bytes,0,min(bytes.size,buffer.remaining()))
                            padded.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
                        }
                        if(paddedWidth==image.width) raw=padded
                        else {
                            raw=Bitmap.createBitmap(padded,0,0,image.width,image.height)
                            padded.recycle()
                        }
                    }else{
                        val pixels=IntArray(image.width*image.height)
                        for(y in 0 until image.height)for(x in 0 until image.width){
                            val index=y*plane.rowStride+x*plane.pixelStride
                            if(index+3>=buffer.limit())continue
                            pixels[y*image.width+x]=Color.argb(
                                buffer.get(index+3).toInt() and 255,
                                buffer.get(index).toInt() and 255,
                                buffer.get(index+1).toInt() and 255,
                                buffer.get(index+2).toInt() and 255)
                        }
                        raw=Bitmap.createBitmap(pixels,image.width,image.height,Bitmap.Config.ARGB_8888)
                    }
                    val scale=min(1f,1024f/image.width)
                    val scaled=Bitmap.createScaledBitmap(raw,
                        max(1,(image.width*scale).toInt()),
                        max(1,(image.height*scale).toInt()),true)
                    val results=try {
                        val bounds=RectF(
                            cloth.left*scaled.width/screenW,
                            cloth.top*scaled.height/screenH,
                            cloth.right*scaled.width/screenW,
                            cloth.bottom*scaled.height/screenH)
                        val obscured=menuBounds
                        val candidates=Vision.detect(scaled,bounds).filterNot{ b ->
                            obscured.contains(
                                b.x*screenW/scaled.width,b.y*screenH/scaled.height)
                        }
                        val detection=Vision.findCueBallAndDirection(scaled,candidates,bounds)
                        val balls=candidates.map { b ->
                            Ball(b.x*screenW/scaled.width,b.y*screenH/scaled.height,
                                b.r*screenW/scaled.width)
                        }
                        val white=detection.first?.let{ b ->
                            Ball(b.x*screenW/scaled.width,b.y*screenH/scaled.height,
                                b.r*screenW/scaled.width)
                        }
                        Triple(balls,white,detection.second)
                    } finally {
                        if(scaled!==raw)scaled.recycle()
                        raw.recycle()
                    }
                    val workMs=(SystemClock.elapsedRealtimeNanos()-startNs)/1_000_000L
                    main.post {
                        try {
                            if(!stopped) {
                                lastProcessingMs=workMs
                                framesProcessed++
                                lastFrame=frameTime
                                update(results.first,results.second,results.third)
                                drawing.visibility=View.VISIBLE
                            }
                        } finally {processing=false}
                    }
                }catch(e:Exception){
                    main.post {
                        if(!stopped){
                            pending=false
                            live=null
                            status.text="VISION ERROR: ${e.javaClass.simpleName} • restart screen capture"
                            drawing.visibility=View.VISIBLE
                            drawing.invalidate()
                        }
                        processing=false
                    }
                }finally{image.close()}
            },Handler(worker.looper))
            main.post(sampleLoop)
        }catch(e:Exception){
            Toast.makeText(this,"Pool Vision: ${e.message}",Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private val sampleLoop=object:Runnable {
        override fun run(){
            if(stopped)return
            if(!autoLive){
                live=null
                drawing.visibility=View.GONE
            } else if(!processing && !pending){
                // Hide only the trajectory layer while sampling so it never
                // feeds into the next detection frame.
                pending=true
                requestedAt=SystemClock.elapsedRealtime()
                drawing.visibility=View.INVISIBLE
                captureAfterNs=0L
            } else if(pending && SystemClock.elapsedRealtime()-requestedAt>4500L) {
                pending=false
                live=null
                status.text="NO NEW SCREEN FRAME (4.5s) • restart with Entire screen"
                drawing.visibility=View.VISIBLE
            }
            if(SystemClock.elapsedRealtime()-lastFrame>5000L && lastFrame>0L){
                live=null
                if(!pending)drawing.invalidate()
            }
            if(processing && SystemClock.elapsedRealtime()-lastFrame>6000L && lastFrame>0L){
                status.text="Vision is processing slowly • ${lastProcessingMs}ms/frame"
            }
            main.postDelayed(this,220L)
        }
    }

    private fun update(detected:List<Ball>,white:Ball?,visual:PointF?) {
        allBalls=detected
        if(white!=null){
            cue=white
            missed=0
        }else{
            missed++
            // Keep only short-lived cue positioning, never freeze the aim line.
            cue=if(missed<=1)cue?.let{old->
                detected.minByOrNull{Vision.distance(it,old)}
                    ?.takeIf{Vision.distance(it,old)<max(it.r,old.r)*1.4f}
            } else null
        }
        val previous=direction
        direction=if(visual==null) {
            directionMissed++
            if(directionMissed<=1 && cue!=null)previous else null
        } else {
            directionMissed=0
            val raw=V2(visual.x,visual.y).unit()
            if(previous!=null && previous.dot(raw)>.95f)
                (previous*.2f+raw*.8f).unit()
            else raw
        }
        val c=cue
        val d=direction
        live=if(autoLive && c!=null && d!=null) {
            val r=c.r.coerceIn(6f,cloth.width()/45f)
            val others=detected.filter{Vision.distance(it,c)>max(it.r,c.r)*.85f}
                .map{Circle2(V2(it.x,it.y),it.r.coerceIn(5f,r*1.4f))}
            ShotGeometry.trace(
                Circle2(V2(c.x,c.y),r),
                d,others,
                Bounds2(cloth.left,cloth.top,cloth.right,cloth.bottom))
        } else null
        status.text=when {
            !autoLive -> "Auto Live OFF"
            c==null -> "SCAN ${detected.size} balls • searching white cue and wooden stick • frame ${framesProcessed}"
            d==null -> "Cue found • wooden stick angle not reliable • ${lastProcessingMs}ms"
            live==null -> "Aim direction unavailable"
            directionMissed>0 -> "LIVE (angle reacquiring) • ${lastProcessingMs}ms/frame"
            live!!.hitBall!=null -> "LIVE • ball collision path • ${lastProcessingMs}ms/frame"
            else -> "LIVE • cue ray + cushion • ${lastProcessingMs}ms/frame"
        }
        drawing.invalidate()
    }

    private fun params(touch:Boolean):WindowManager.LayoutParams =
        WindowManager.LayoutParams(-1,-1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if(touch)0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.START
            if(Build.VERSION.SDK_INT>=28)
                layoutInDisplayCutoutMode=
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if(Build.VERSION.SDK_INT>=30)setFitInsetsTypes(0)
        }

    private fun buildOverlay(){
        drawing=Guide()
        windows.addView(drawing,params(false))
        fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
        val body=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(8),dp(6),dp(8),dp(6))
            setBackgroundColor(Color.rgb(13,22,37))
        }
        val title=TextView(this).apply {
            text="POOL VISION 0.8 • drag"
            setTextColor(Color.CYAN)
            textSize=13f
            setPadding(0,0,0,dp(4))
        }
        body.addView(title)
        status=TextView(this).apply {
            text="AUTO LIVE • searching for cue stick"
            textSize=10f
            setTextColor(Color.WHITE)
            setPadding(0,0,0,dp(5))
        }
        body.addView(status)
        body.addView(Switch(this).apply {
            text="AUTO LIVE ESP"
            textSize=12f
            setTextColor(Color.WHITE)
            isChecked=true
            setOnCheckedChangeListener { _,value ->
                autoLive=value
                if(!value){pending=false;live=null;drawing.invalidate()}
                status.text=if(value)"AUTO LIVE ON • no manual target selection" else "AUTO LIVE OFF"
            }
        })
        val controls=LinearLayout(this)
        fun addButton(text:String,action:()->Unit){
            controls.addView(Button(this).apply{
                this.text=text
                textSize=10f
                minimumWidth=0;minWidth=0
                setPadding(0,0,0,0)
                setOnClickListener{action()}
            },LinearLayout.LayoutParams(0,dp(38),1f))
        }
        addButton("LINE -"){
            thickness=(thickness-2f).coerceAtLeast(4f)
            drawing.invalidate()
        }
        addButton("LINE +"){
            thickness=(thickness+2f).coerceAtMost(16f)
            drawing.invalidate()
        }
        body.addView(controls)
        body.addView(Switch(this).apply{
            text="Show ball detector circles (debug)"
            textSize=10f
            setTextColor(Color.WHITE)
            isChecked=false
            setOnCheckedChangeListener{_,value->
                showDetection=value
                drawing.invalidate()
            }
        })
        val footer=LinearLayout(this)
        body.addView(footer)
        var collapsed=false
        lateinit var hideButton:Button
        hideButton=Button(this).apply{
            text="HIDE"
            textSize=10f
            setOnClickListener{
                collapsed=!collapsed
                for(index in 1 until body.childCount-1)
                    body.getChildAt(index).visibility=
                        if(collapsed)View.GONE else View.VISIBLE
                text=if(collapsed)"SHOW" else "HIDE"
            }
        }
        footer.addView(hideButton,LinearLayout.LayoutParams(0,dp(38),1f))
        footer.addView(Button(this).apply{
            text="STOP"
            textSize=10f
            setOnClickListener{stopSelf()}
        },LinearLayout.LayoutParams(0,dp(38),1f))
        menu=body
        val layout=WindowManager.LayoutParams(
            min(dp(275),screenW/2),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply{
                gravity=Gravity.TOP or Gravity.START
                x=dp(6);y=dp(6)
                if(Build.VERSION.SDK_INT>=28)
                    layoutInDisplayCutoutMode=
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                if(Build.VERSION.SDK_INT>=30)setFitInsetsTypes(0)
            }
        var startX=0f;var startY=0f;var startLeft=0;var startTop=0
        fun updateMenuBounds() {
            if(body.width==0 || body.height==0)return
            val position=IntArray(2)
            body.getLocationOnScreen(position)
            menuBounds=RectF(position[0].toFloat(),position[1].toFloat(),
                (position[0]+body.width).toFloat(),
                (position[1]+body.height).toFloat())
        }
        body.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->updateMenuBounds()}
        title.setOnTouchListener{_,event->
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->{
                    startX=event.rawX;startY=event.rawY
                    startLeft=layout.x;startTop=layout.y
                }
                MotionEvent.ACTION_MOVE->{
                    layout.x=(startLeft+(event.rawX-startX).toInt())
                        .coerceIn(0,max(0,screenW-body.width))
                    layout.y=(startTop+(event.rawY-startY).toInt())
                        .coerceIn(0,max(0,screenH-body.height))
                    windows.updateViewLayout(body,layout)
                    body.post{updateMenuBounds()}
                }
            }
            true
        }
        windows.addView(body,layout)
    }

    inner class Guide:View(this) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private fun stroke(canvas:Canvas,start:V2,end:V2,color:Int,
                           width:Float,dashed:Boolean=false) {
            paint.style=Paint.Style.STROKE
            paint.strokeCap=Paint.Cap.ROUND
            paint.strokeJoin=Paint.Join.ROUND
            paint.pathEffect=if(dashed)
                DashPathEffect(floatArrayOf(17f,12f),0f) else null
            paint.strokeWidth=width+6f
            paint.color=Color.argb(200,0,0,0)
            canvas.drawLine(start.x,start.y,end.x,end.y,paint)
            paint.strokeWidth=width
            paint.color=color
            canvas.drawLine(start.x,start.y,end.x,end.y,paint)
            paint.pathEffect=null
        }
        override fun onDraw(canvas:Canvas) {
            super.onDraw(canvas)
            if(!autoLive)return
            val position=IntArray(2)
            getLocationOnScreen(position)
            canvas.save()
            canvas.translate(-position[0].toFloat(),-position[1].toFloat())
            val trace=live
            if(showDetection) {
                paint.style=Paint.Style.STROKE
                paint.strokeWidth=2f
                paint.color=Color.CYAN
                canvas.drawRect(cloth,paint)
                for(b in allBalls) {
                    paint.color=Color.argb(210,0,255,255)
                    canvas.drawCircle(b.x,b.y,b.r+4f,paint)
                }
                cue?.let{
                    paint.strokeWidth=4f;paint.color=Color.WHITE
                    canvas.drawCircle(it.x,it.y,it.r+5f,paint)
                }
            }
            if(trace!=null && SystemClock.elapsedRealtime()-lastFrame<5000L) {
                val width=thickness
                // Thick cyan: direction of the currently rotated cue stick.
                stroke(canvas,trace.cueStart,trace.cueEnd,Color.CYAN,width)
                val hit=trace.hitBall
                if(hit!=null) {
                    paint.style=Paint.Style.STROKE
                    paint.strokeWidth=4f
                    paint.color=Color.WHITE
                    canvas.drawCircle(trace.cueEnd.x,trace.cueEnd.y,
                        max(8f,cue?.r ?: 10f),paint)
                    val objEnd=trace.objectEnd
                    if(objEnd!=null)
                        stroke(canvas,hit.center,objEnd,Color.GREEN,width)
                    trace.objectCushionEnd?.let {bounceEnd->
                        if(objEnd!=null)
                            stroke(canvas,objEnd,bounceEnd,Color.MAGENTA,width*.8f,true)
                    }
                    trace.cueAfterEnd?.let{after->
                        stroke(canvas,trace.cueEnd,after,
                            Color.rgb(80,170,255),width*.65f,true)
                    }
                }else {
                    trace.cueCushionEnd?.let {
                        stroke(canvas,trace.cueEnd,it,Color.MAGENTA,width*.8f,true)
                    }
                }
                paint.style=Paint.Style.FILL
                paint.color=Color.WHITE
                paint.textSize=16f
                canvas.drawText(
                    if(hit!=null)"Cue → first ball → predicted object path"
                    else "Cue ray → cushion reflection",
                    cloth.left+10f,cloth.bottom-14f,paint)
            }
            canvas.restore()
        }
    }

    override fun onDestroy() {
        stopped=true
        main.removeCallbacksAndMessages(null)
        runCatching {reader?.setOnImageAvailableListener(null,null)}
        runCatching {virtualDisplay?.release()}
        runCatching {reader?.close()}
        runCatching {projection?.stop()}
        if(worker.isAlive)worker.quitSafely()
        if(::windows.isInitialized){
            if(::drawing.isInitialized)runCatching{windows.removeView(drawing)}
            menu?.let{runCatching{windows.removeView(it)}}
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
