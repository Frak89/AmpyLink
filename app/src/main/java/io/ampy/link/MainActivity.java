package io.ampy.link;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.media.projection.*;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    static final int GREEN=0xff54f9cc, DIM=0xff91adb2, BG=0xff07070c;
    final Handler ui=new Handler(Looper.getMainLooper());
    final ExecutorService sender=Executors.newSingleThreadExecutor();
    final AtomicBoolean gesturePending=new AtomicBoolean();
    final Random rng=new Random();
    final String glyphs="01ABCDEF#$%&+-*/<>[]{}ｱｲｳｴｵ";
    LinearLayout root,panel; TextView status,metrics,preview; EditText address,code; CheckBox control,fx; ImageView screen; MatrixView matrix;
    volatile Link link; volatile Socket pending; volatile boolean connecting; volatile int generation; volatile long lastFrame,rxBytes,frameCount; volatile double rtt=-1,jitter; volatile long lastPong; long prevBytes,prevFrames,prevTick; String hostCode; boolean hostControl; float sx,sy; long down;
    final java.util.Map<TextView,Integer> scrambleTokens=new java.util.WeakHashMap<>(); int scrambleGeneration; boolean dead; String lastStatus="";
    int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.MONOSPACE);t.setPadding(dp(6),dp(5),dp(6),dp(5));return t;}
    TextView button(String s,Runnable action){TextView b=com.ampylink.app.Cyber.button(this,s,GREEN);b.setMinHeight(dp(48));b.setText(s);b.setTypeface(Typeface.MONOSPACE);b.setTextColor(GREEN);b.setOnClickListener(v->action.run());return b;}
    EditText field(String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setTypeface(Typeface.MONOSPACE);e.setTextColor(GREEN);e.setHintTextColor(DIM);e.setHint(hint);return e;}
    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);ScrollView scroll=new ScrollView(this);root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(12),dp(8),dp(12),dp(16));root.setBackgroundColor(BG);scroll.addView(root);setContentView(scroll);
        root.addView(text("AMPYLINK / ORB",23,GREEN));root.addView(text("Android · Windows",11,DIM));
        matrix=new MatrixView();root.addView(matrix,new LinearLayout.LayoutParams(-1,dp(75)));
        status=text("[ IDLE ]",14,GREEN);root.addView(status);metrics=text("RTT --   JITTER --\nRX --    FPS --",12,DIM);root.addView(metrics);
        fx=new CheckBox(this);fx.setText("GENIE / MATRIX / GLITCH / SCRAMBLE");fx.setTextColor(GREEN);fx.setChecked(true);fx.setTypeface(Typeface.MONOSPACE);root.addView(fx);fx.setOnCheckedChangeListener((v,on)->{matrix.setVisibility(on?View.VISIBLE:View.GONE);if(on)matrix.invalidate();scramble(preview,"INPUT > "+address.getText());});
        root.addView(button("STOP SESSION",()->{disconnect();stopService(new Intent(this,HostService.class));hostCode=null;scramble(status,"[ STOPPED ]");}));
        root.addView(text("01 // HOST",16,GREEN));control=new CheckBox(this);control.setText("Allow remote taps and swipes");control.setTextColor(GREEN);root.addView(control);
        root.addView(button("ENABLE AUTHORIZED TOUCH SERVICE",()->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        root.addView(button("SHARE THIS SCREEN",this::requestHost));
        root.addView(text("01B // LIVE CONTENT / HOST MIRROR",14,GREEN));
        root.addView(button("SHARE IMAGE",()->chooseContent("image/*")));
        root.addView(button("SHARE VIDEO",()->chooseContent("video/*")));
        root.addView(button("SHARE DOCUMENT",()->chooseContent("application/*")));
        root.addView(text("02 // CONTROLLER",16,GREEN));address=field("Peer LAN IP address");root.addView(address);root.addView(button("Cerca dispositivi Orb",()->OrbLan.pick(this, address)));preview=text("INPUT DECODER //",12,DIM);root.addView(preview);address.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){scramble(preview,"INPUT > "+s);}public void afterTextChanged(Editable e){}});
        code=field("32-character pairing secret");code.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);root.addView(code);
        root.addView(button("CONNECT TO PEER",this::connect));
        root.addView(button("Apri un altro dispositivo", () -> {
            if (link == null) {
                scramble(status, "Collega prima questo dispositivo");
                return;
            }
            startActivity(new Intent(this, MainActivity.class));
        }));
        panel=new LinearLayout(this);panel.setOrientation(1);root.addView(panel);screen=new ImageView(this);screen.setScaleType(ImageView.ScaleType.FIT_CENTER);screen.setBackgroundColor(0xff101c16);panel.addView(screen,new LinearLayout.LayoutParams(-1,dp(460)));screen.setOnTouchListener(this::touch);
        panel.setVisibility(View.GONE);ui.post(tick);
    }
    private volatile boolean visible;
    @Override protected void onStart(){super.onStart();visible=true;}
    @Override protected void onStop(){visible=false;super.onStop();}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==5){
            if(grants.length>0 && grants[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)connect();
            else scramble(status,"Abilita le notifiche per la connessione in background");
            return;
        }
        if(request!=4)return;
        if(grants.length>0 && grants[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)requestHost();
        else scramble(status,"Abilita le notifiche per avviare la condivisione");
    }
    void scramble(TextView view,String target){int token=++scrambleGeneration;scrambleTokens.put(view,token);if(fx==null||!fx.isChecked()){view.setText(target);return;}int len=target.length();for(int k=0;k<=9;k++){final int step=k;ui.postDelayed(()->{if(dead||!java.util.Objects.equals(scrambleTokens.get(view),token))return;if(!fx.isChecked()){view.setText(target);return;}StringBuilder s=new StringBuilder();int resolved=(int)Math.ceil(len*step/9.0);for(int i=0;i<len;i++){char ch=target.charAt(i);s.append(i<resolved||Character.isWhitespace(ch)?ch:glyphs.charAt(rng.nextInt(glyphs.length())));}view.setText(s);},k*32L);}}
    void showPanel(){panel.setVisibility(View.VISIBLE);if(fx.isChecked()){panel.setPivotY(0);panel.setScaleX(.15f);panel.setScaleY(.05f);panel.setAlpha(.2f);panel.animate().scaleX(1).scaleY(1).alpha(1).setDuration(440).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();for(int i=0;i<5;i++){final int n=i;ui.postDelayed(()->{if(!dead&&fx.isChecked())panel.setTranslationX(n==4?0:dp(n%2==0?4:-4));},i*36L);}}}
    void requestHost(){if(connecting||link!=null||HostService.active){scramble(status,"[ BUSY ] Stop current session first");return;}hostControl=control.isChecked();if(hostControl&&TouchService.instance==null){new AlertDialog.Builder(this).setMessage("Enable AmpyLink's accessibility service first, or uncheck remote touches for view-only sharing.").setPositiveButton("OK",null).show();return;}if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},4);return;}hostCode=Link.newCode();MediaProjectionManager pm=getSystemService(MediaProjectionManager.class);Intent req=Build.VERSION.SDK_INT>=34?pm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()):pm.createScreenCaptureIntent();startActivityForResult(req,7);}
    void chooseContent(String mime){
        if(!HostService.active||!HostService.authenticated){new AlertDialog.Builder(this).setTitle("LIVE SHARE").setMessage("Start screen sharing and pair the receiver first. The selected file will open locally and its visible screen will be mirrored; the file itself is not sent.").setPositiveButton("OK",null).show();return;}
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime);startActivityForResult(i,8);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==8){if(result==RESULT_OK&&data!=null&&data.getData()!=null&&HostService.active&&HostService.authenticated){android.net.Uri uri=data.getData();String mime=getContentResolver().getType(uri);Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime==null?"application/octet-stream":mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{startActivity(view);}catch(ActivityNotFoundException e){new AlertDialog.Builder(this).setMessage("No compatible viewer installed for this content.").setPositiveButton("OK",null).show();}}return;}
        if(request!=7||result!=RESULT_OK||data==null||hostCode==null)return;Intent i=new Intent(this,HostService.class).putExtra("result",result).putExtra("data",data).putExtra("code",hostCode).putExtra("control",hostControl);startForegroundService(i);String ips=localIPs();AlertDialog pairing=new AlertDialog.Builder(this).setTitle("AMPYLINK // PAIR ONCE").setMessage("LAN: "+ips+"\nPort: "+Link.PORT+"\nSecret: "+hostCode+"\nShare only with the intended peer. Expires in 2 minutes.\n"+(hostControl?"Screen and gestures allowed":"View only")).setPositiveButton("OK",null).setNeutralButton("STOP",(d,w)->stopService(new Intent(this,HostService.class))).create();pairing.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);pairing.show();}
    String localIPs(){StringBuilder s=new StringBuilder();try{Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();while(all.hasMoreElements()){NetworkInterface n=all.nextElement();if(!n.isUp()||n.isLoopback())continue;Enumeration<InetAddress>a=n.getInetAddresses();while(a.hasMoreElements()){InetAddress ip=a.nextElement();if(ip instanceof Inet4Address&&ip.isSiteLocalAddress())s.append(ip.getHostAddress()).append(' ');}}}catch(Exception ignored){}return s.length()==0?"No private IPv4 address found":s.toString();}
    void connect(){if(connecting||link!=null||HostService.active)return;String ip=address.getText().toString().trim(),secret=code.getText().toString();try{Link.secret(secret);if(!ip.matches("[0-9.]+"))throw new IllegalArgumentException();InetAddress a=InetAddress.getByName(ip);if(!(a instanceof Inet4Address)||!a.isSiteLocalAddress())throw new IllegalArgumentException();}catch(Exception e){scramble(status,"[ ERROR ] Private IPv4 and 32 hex characters required");return;}if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},5);return;
    }
    try{ControllerService.begin(this,ip);}catch(RuntimeException e){scramble(status,"Avvio servizio non riuscito");return;}
    code.setText("");connecting=true;final int g=++generation;rxBytes=frameCount=lastFrame=lastPong=0;rtt=-1;jitter=0;prevBytes=prevFrames=0;scramble(status,"[ CONNECTING ] AUTHENTICATING");new Thread(()->receive(ip,secret,g),"ampylink-controller").start();}
    void receive(String ip,String secret,int g){Socket s=new Socket();try{pending=s;if(g!=generation)return;s.connect(new InetSocketAddress(ip,Link.PORT),5000);Link l=Link.open(s,secret,false);if(g!=generation)return;link=l;connecting=false;ui.post(()->{if(g==generation){ControllerService.connected(this,ip);showPanel();scramble(status,"[ CONNECTED ] AES-256-GCM");}});while(g==generation){byte[] b=l.read();rxBytes+=b.length+20;if(b[0]==Link.FRAME){if(!visible)continue;BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(b,1,b.length-1,opts);if(opts.outWidth<1||opts.outHeight<1||opts.outWidth>4096||opts.outHeight>4096||(long)opts.outWidth*opts.outHeight>8000000)throw new java.io.IOException("Frame dimensions exceed limits");opts.inJustDecodeBounds=false;Bitmap bitmap=BitmapFactory.decodeByteArray(b,1,b.length-1,opts);if(bitmap!=null){if(!visible){bitmap.recycle();continue;}lastFrame=SystemClock.elapsedRealtime();frameCount++;ui.post(()->{if(g==generation&&visible){screen.setImageBitmap(bitmap);}else bitmap.recycle();});}}else if(b[0]==Link.PONG&&b.length==9){long now=SystemClock.elapsedRealtime();double sample=now-ByteBuffer.wrap(b,1,8).getLong();if(sample>=0&&sample<60000){if(rtt>=0)jitter=.8*jitter+.2*Math.abs(sample-rtt);rtt=sample;lastPong=now;}}else if(b[0]==Link.END)break;}}
        catch(Exception e){ui.post(()->{if(g==generation)scramble(status,"[ CLOSED ] "+e.getClass().getSimpleName());});}finally{try{s.close();}catch(Exception ignored){}if(g==generation){link=null;pending=null;connecting=false;ui.post(()->{if(g==generation){ControllerService.remove(this);panel.setVisibility(View.GONE);}});}}}
    boolean touch(View v,android.view.MotionEvent e){if(link==null||SystemClock.elapsedRealtime()-lastFrame>1500||screen.getDrawable()==null)return false;float[] p={e.getX(),e.getY()};Matrix inv=new Matrix();if(!screen.getImageMatrix().invert(inv))return false;inv.mapPoints(p);float x=p[0]/screen.getDrawable().getIntrinsicWidth(),y=p[1]/screen.getDrawable().getIntrinsicHeight();if(x<0||x>1||y<0||y>1)return false;if(e.getActionMasked()==MotionEvent.ACTION_DOWN){sx=x;sy=y;down=SystemClock.elapsedRealtime();return true;}if(e.getActionMasked()==MotionEvent.ACTION_UP){final Link l=link;byte[] b=ByteBuffer.allocate(20).putFloat(sx).putFloat(sy).putFloat(x).putFloat(y).putInt((int)Math.min(700,SystemClock.elapsedRealtime()-down)).array();if(gesturePending.compareAndSet(false,true))sender.execute(()->{try{if(l==link)l.send(Link.TOUCH,b);}catch(Exception ignored){}finally{gesturePending.set(false);}});return true;}return true;}
    void disconnect(){ControllerService.remove(this);generation++;connecting=false;Link l=link;link=null;Socket s=pending;pending=null;try{if(l!=null)l.close();if(s!=null)s.close();}catch(Exception ignored){}if(panel!=null){panel.setVisibility(View.GONE);panel.setTranslationX(0);screen.setImageDrawable(null);}}
    final Runnable tick=new Runnable(){public void run(){if(dead)return;long now=SystemClock.elapsedRealtime();Link l=link;if(l!=null){sender.execute(()->{try{if(l==link)l.send(Link.PING,ByteBuffer.allocate(8).putLong(SystemClock.elapsedRealtime()).array());}catch(Exception ignored){}});double dt=Math.max(.001,(now-prevTick)/1000.0);metrics.setText(String.format(Locale.US,"RTT %s   JITTER %.1f ms\nRX %.2f Mb/s   FPS %.1f\nPONG %s",rtt<0?"--":String.format(Locale.US,"%.0f ms",rtt),jitter,(rxBytes-prevBytes)*8/dt/1e6,(frameCount-prevFrames)/dt,lastPong==0?"waiting":(now-lastPong)+" ms ago"));}else if(HostService.active){String s=HostService.status;if(!s.equals(lastStatus)){lastStatus=s;scramble(status,s);}}else{lastStatus="";metrics.setText("RTT --   JITTER --\nRX --    FPS --");}prevBytes=rxBytes;prevFrames=frameCount;prevTick=now;ui.postDelayed(this,1000);}};
    @Override protected void onDestroy(){dead=true;ui.removeCallbacksAndMessages(null);disconnect();sender.shutdownNow();super.onDestroy();}
    class MatrixView extends View {Paint p=new Paint(3);MatrixView(){super(MainActivity.this);}protected void onDraw(Canvas c){super.onDraw(c);p.setTypeface(Typeface.MONOSPACE);p.setTextSize(dp(12));long t=SystemClock.elapsedRealtime()/100;for(int x=0;x<getWidth()/dp(14);x++){int head=(int)((t+x*7)%12);for(int y=0;y<5;y++){int shade=220-Math.floorMod(head-y,12)*18;p.setColor(Color.rgb(20,Math.max(25,shade),65));c.drawText(String.valueOf(glyphs.charAt(Math.floorMod((int)t+x*11+y*3,glyphs.length()))),x*dp(14),dp(14)*(y+1),p);}}if(!dead&&fx!=null&&fx.isChecked()&&isShown())postInvalidateDelayed(100);}}
}
