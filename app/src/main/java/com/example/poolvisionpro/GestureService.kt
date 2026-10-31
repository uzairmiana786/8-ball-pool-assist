package com.example.poolvisionpro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class GestureService:AccessibilityService() {
    companion object {var instance:GestureService?=null;var inGame=false}
    override fun onServiceConnected(){instance=this}
    override fun onAccessibilityEvent(event:AccessibilityEvent?) {
        if(event?.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){
            val name=event.packageName?.toString() ?: return
            if(name!=packageName) inGame=name=="com.miniclip.eightballpool"
        }
    }
    override fun onInterrupt(){inGame=false}
    override fun onDestroy(){instance=null;inGame=false;super.onDestroy()}
    fun gesture(path:Path,duration:Long,done:(Boolean)->Unit) {
        if(!inGame){done(false);return}
        val accepted=dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,duration)).build(),
            object:GestureResultCallback(){
                override fun onCompleted(d:GestureDescription){done(true)}
                override fun onCancelled(d:GestureDescription){done(false)}
            },null)
        if(!accepted)done(false)
    }
}
