package io.ampy.link;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.io.*;
import java.net.*;
import java.nio.*;

public class HostService extends Service {
    static volatile boolean controlAllowed,authenticated,active;
    static volatile String status="IDLE";
    private volatile Link link;private volatile ServerSocket listener;private volatile Socket pending;
    private MediaProjection projection;private VirtualDisplay display;private ImageReader reader;private HandlerThread capture;private Handler handler;
    private OrbLan advertisement;
    private long last;private int width,height,physicalW,physicalH;
    @Override public IBinder onBind(Intent i){return null;}
    @Override public int onStartCommand(Intent i,int flags,int id){
        if(i==null||"STOP".equals(i.getAction())){stopSelf();return START_NOT_STICKY;}
        if(active)return START_NOT_STICKY;
        if(i.getParcelableExtra("data")==null){stopSelf();return START_NOT_STICKY;}
        NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("host","AmpyLink active session",NotificationManager.IMPORTANCE_HIGH));
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,HostService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open=PendingIntent.getActivity(this,2,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,"host").setSmallIcon(android.R.drawable.ic_menu_share).setContentTitle("AmpyLink: screen sharing enabled").setContentText("Authorized local session · stop at any time").setOngoing(true).setContentIntent(open).addAction(new Notification.Action.Builder(null,"STOP SESSION",stop).build()).build();
        startForeground(23,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        active=true;authenticated=false;controlAllowed=i.getBooleanExtra("control",false);status="STARTING";
        try{
            MediaProjectionManager pm=getSystemService(MediaProjectionManager.class);projection=pm.getMediaProjection(i.getIntExtra("result",Activity.RESULT_CANCELED),(Intent)i.getParcelableExtra("data"));
            if(projection==null)throw new IOException("Projection denied");
            projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){stopSelf();}@Override public void onCapturedContentResize(int w,int h){if(physicalW>0 && ((w>h)!=(physicalW>physicalH))){status="ROTATED — restart sharing";stopSelf();}}},new Handler(Looper.getMainLooper()));
            DisplayMetrics m=new DisplayMetrics();getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(m);physicalW=m.widthPixels;physicalH=m.heightPixels;
            double scale=Math.min(1.0,1280.0/Math.max(physicalW,physicalH));width=Math.max(2,(int)(physicalW*scale));height=Math.max(2,(int)(physicalH*scale));
            capture=new HandlerThread("ampylink-capture");capture.start();handler=new Handler(capture.getLooper());reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);reader.setOnImageAvailableListener(this::frame,handler);
            display=projection.createVirtualDisplay("AmpyLink",width,height,m.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,handler);
            advertisement=new OrbLan(this);advertisement.advertise();
            String code=i.getStringExtra("code");new Thread(()->serve(code),"ampylink-host").start();
        }catch(Exception e){status="ERROR: "+e.getClass().getSimpleName();stopSelf();}
        return START_NOT_STICKY;
    }
    private void serve(String code){
        try(ServerSocket s=new ServerSocket()){listener=s;s.setReuseAddress(true);s.bind(new InetSocketAddress(Link.PORT));s.setSoTimeout(120000);status="WAITING FOR PAIRING (2 min)";
            try(Socket peer=s.accept()){pending=peer;if(!peer.getInetAddress().isSiteLocalAddress())throw new IOException("Private LAN peer required");Link l=Link.open(peer,code,true);link=l;authenticated=true;status="CONNECTED · ENCRYPTED";
                while(active){byte[] b=l.read();if(b[0]==Link.PING&&b.length==9)l.send(Link.PONG,java.util.Arrays.copyOfRange(b,1,9));else if(b[0]==Link.END)break;else if(b[0]==Link.TOUCH&&b.length==21&&controlAllowed&&TouchService.instance!=null){ByteBuffer p=ByteBuffer.wrap(b,1,20);TouchService.instance.gesture(p.getFloat(),p.getFloat(),p.getFloat(),p.getFloat(),p.getInt());}}
            }
        }catch(Exception e){if(active)status="LINK CLOSED: "+e.getClass().getSimpleName();}finally{authenticated=false;controlAllowed=false;stopSelf();}
    }
    private void frame(ImageReader r){
        try(Image im=r.acquireLatestImage()){
            Link l=link;if(im==null||l==null||!authenticated||!active||SystemClock.elapsedRealtime()-last<150)return;last=SystemClock.elapsedRealtime();
            Image.Plane p=im.getPlanes()[0];int stride=p.getRowStride(),pixel=p.getPixelStride();if(pixel!=4)return;ByteBuffer buf=p.getBuffer();
            // Copy only valid rows; the final row may not include stride padding.
            ByteBuffer packed=ByteBuffer.allocateDirect(width*height*4);byte[] row=new byte[width*4];for(int y=0;y<height;y++){buf.position(y*stride);buf.get(row);packed.put(row);}packed.rewind();
            Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);try{bitmap.copyPixelsFromBuffer(packed);ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,65,out);l.send(Link.FRAME,out.toByteArray());}finally{bitmap.recycle();}
        }catch(Exception e){if(active&&authenticated){status="STREAM ERROR";stopSelf();}}
    }
    @Override public void onDestroy(){if(advertisement!=null)advertisement.close();active=false;authenticated=false;controlAllowed=false;try{if(listener!=null)listener.close();}catch(Exception ignored){}try{if(pending!=null)pending.close();}catch(Exception ignored){}try{if(link!=null)link.close();}catch(Exception ignored){}if(reader!=null){reader.setOnImageAvailableListener(null,null);}if(display!=null)display.release();if(reader!=null)reader.close();if(projection!=null)projection.stop();if(capture!=null)capture.quitSafely();stopForeground(STOP_FOREGROUND_REMOVE);if(!status.startsWith("LINK CLOSED")&&!status.startsWith("ERROR")&&!status.contains("ERROR")&&!status.startsWith("ROTATED"))status="IDLE";super.onDestroy();}
}
