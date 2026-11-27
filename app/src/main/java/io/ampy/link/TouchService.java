package io.ampy.link;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.graphics.*;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;

public class TouchService extends AccessibilityService {
    static volatile TouchService instance;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private long last;
    @Override protected void onServiceConnected(){instance=this;}
    @Override public void onAccessibilityEvent(AccessibilityEvent e){}
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){instance=null;super.onDestroy();}
    void gesture(float x,float y,float a,float b,int duration){
        ui.post(()->{
            if(!HostService.controlAllowed || !HostService.authenticated || getSystemService(KeyguardManager.class).isKeyguardLocked())return;
            if(SystemClock.elapsedRealtime()-last<100)return;last=SystemClock.elapsedRealtime();
            if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(a)||!Float.isFinite(b)||x<0||x>1||y<0||y>1||a<0||a>1||b<0||b>1)return;
            DisplayMetrics m=new DisplayMetrics();WindowManager wm=getSystemService(WindowManager.class);wm.getDefaultDisplay().getRealMetrics(m);
            float sx=x*(m.widthPixels-1),sy=y*(m.heightPixels-1),ex=a*(m.widthPixels-1),ey=b*(m.heightPixels-1);
            Path p=new Path();p.moveTo(sx,sy);p.lineTo(ex,ey);
            boolean accepted=dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,Math.max(55,Math.min(700,duration)))).build(),null,null);
            if(accepted)marker(wm,(int)ex,(int)ey);
        });
    }
    private void marker(WindowManager wm,int x,int y){
        int size=(int)(64*getResources().getDisplayMetrics().density);View v=new View(this){final Paint p=new Paint(3);@Override protected void onDraw(Canvas c){p.setColor(0xAA80FFAB);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);c.drawCircle(getWidth()/2f,getHeight()/2f,getWidth()/3f,p);}};
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(size,size,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);lp.gravity=Gravity.TOP|Gravity.LEFT;lp.x=x-size/2;lp.y=y-size/2;
        try{wm.addView(v,lp);ui.postDelayed(()->{try{wm.removeView(v);}catch(Exception ignored){}},350);}catch(Exception ignored){}
    }
}
