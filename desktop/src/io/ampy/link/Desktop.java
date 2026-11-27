package io.ampy.link;

import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Interactive LAN-only companion. No services, elevation or unattended operation. */
public final class Desktop {
    static final Color BG=new Color(7,7,12), GREEN=new Color(84,249,204), DIM=new Color(145,173,178);
    static final Font MONO=new Font(Font.MONOSPACED,Font.PLAIN,13);
    final JFrame window=new JFrame("AmpyLink Fusion · AmpyOrb Core");
    final JLabel status=new JLabel("[ IDLE ]"), metrics=new JLabel("RTT -- | RX -- | FPS --");
    final JTextField ip=new JTextField(15);final JPasswordField secret=new JPasswordField(32);
    final JCheckBox allow=new JCheckBox("Allow remote mouse control (local consent)",false), effects=new JCheckBox("Matrix / Scramble / Glitch / Genie",true);
    final JButton host=new JButton("SHARE DESKTOP"), connect=new JButton("CONNECT TO PEER"), stop=new JButton("STOP SESSION"), open=new JButton("SHARE IMAGE / VIDEO / DOCUMENT");
    final CanvasView view=new CanvasView(); final Rain rain=new Rain();
    volatile Session current;
    int scrambleId; long tick=System.nanoTime();
    final javax.swing.Timer timer=new javax.swing.Timer(1000,e->updateMetrics());
    final javax.swing.Timer animation=new javax.swing.Timer(90,e->{if(effects.isSelected()){rain.repaint();if(view.reveal<1){view.reveal=Math.min(1,view.reveal+.2);view.repaint();}}});
    static long now(){return System.nanoTime()/1_000_000L;}
    public static void main(String[] args){SwingUtilities.invokeLater(()->new Desktop().show());}
    private TrayIcon tray;
    private void restoreWindow() {
        window.setVisible(true);
        window.setState(Frame.NORMAL);
        window.toFront();
    }
    private void quit() {
        end();
        timer.stop();
        animation.stop();
        if (tray != null) SystemTray.getSystemTray().remove(tray);
        window.dispose();
        System.exit(0);
    }
    private void installTray() {
        if (!SystemTray.isSupported()) return;
        BufferedImage icon = new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(BG); g.fillOval(1,1,30,30);
        g.setColor(GREEN); g.setStroke(new BasicStroke(3)); g.drawOval(5,5,22,22);
        g.setColor(new Color(255,43,214)); g.fillOval(12,12,8,8); g.dispose();
        PopupMenu menu = new PopupMenu();
        MenuItem openWindow = new MenuItem("Apri AmpyLink Fusion");
        MenuItem disconnect = new MenuItem("Disconnetti");
        MenuItem exit = new MenuItem("Esci");
        openWindow.addActionListener(e -> restoreWindow());
        disconnect.addActionListener(e -> end());
        exit.addActionListener(e -> quit());
        menu.add(openWindow); menu.add(disconnect); menu.addSeparator(); menu.add(exit);
        TrayIcon iconInTray = new TrayIcon(icon, "AmpyLink Fusion · In attesa", menu);
        iconInTray.setImageAutoSize(true);
        iconInTray.addActionListener(e -> restoreWindow());
        try { SystemTray.getSystemTray().add(iconInTray); tray = iconInTray; }
        catch (AWTException unavailable) { tray = null; }
    }
    private void notifyTray(String text) {
        if (tray == null) return;
        tray.setToolTip("AmpyLink Fusion · " + text);
        tray.displayMessage("AmpyLink Fusion", text, TrayIcon.MessageType.INFO);
    }
    void show(){
        installTray();
        UIManager.put("Label.font",MONO);UIManager.put("Button.font",MONO);UIManager.put("TextField.font",MONO);UIManager.put("CheckBox.font",MONO);
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);window.addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){if(tray!=null) window.setVisible(false); else quit();}});
        JPanel top=new JPanel();top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));top.setBorder(BorderFactory.createEmptyBorder(10,12,10,12));top.setBackground(BG);
        JLabel title=new JLabel("AMPYLINK FUSION  /  ORB CORE");title.setFont(MONO.deriveFont(Font.BOLD,19f));title.setForeground(GREEN);top.add(title);rain.setPreferredSize(new Dimension(560,45));rain.setMaximumSize(new Dimension(Integer.MAX_VALUE,45));top.add(rain);
        for(JLabel l:new JLabel[]{status,metrics}){l.setFont(MONO);l.setForeground(GREEN);top.add(l);}
        JPanel controls=new JPanel(new FlowLayout(FlowLayout.LEFT));controls.setBackground(BG);for(JComponent c:new JComponent[]{host,stop,allow,open,effects}){style(c);controls.add(c);}top.add(controls);
        JPanel pair=new JPanel(new FlowLayout(FlowLayout.LEFT));pair.setBackground(BG);for(JComponent c:new JComponent[]{new JLabel("LAN IP"),ip,new JLabel("PAIR SECRET"),secret,connect}){style(c);pair.add(c);}top.add(pair);
        host.addActionListener(e->startHost());connect.addActionListener(e->startClient());stop.addActionListener(e->end());open.addActionListener(e->openContent());effects.addActionListener(e->{rain.setVisible(effects.isSelected());if(!effects.isSelected()){view.reveal=1;view.repaint();}});
        window.add(top,BorderLayout.NORTH);window.add(view,BorderLayout.CENTER);window.setSize(1000,760);window.setMinimumSize(new Dimension(640,230));window.setLocationByPlatform(true);window.setVisible(true);timer.start();animation.start();buttons();
    }
    void style(JComponent c){c.setForeground(GREEN);c.setBackground(new Color(18,34,25));c.setFont(MONO);if(c instanceof JTextField)((JTextField)c).setCaretColor(GREEN);}
    void buttons(){boolean busy=current!=null;host.setEnabled(!busy);connect.setEnabled(!busy);allow.setEnabled(!busy);stop.setEnabled(busy);open.setEnabled(busy&&current.host&&current.link!=null);}
    void message(String msg){int id=++scrambleId;if(!effects.isSelected()){status.setText(msg);return;}final int[] step={0};javax.swing.Timer t=new javax.swing.Timer(28,null);t.addActionListener(e->{if(id!=scrambleId){t.stop();return;}String glyph="01ABCDEF#$%&+-";StringBuilder b=new StringBuilder();int resolved=msg.length()*step[0]/8;for(int i=0;i<msg.length();i++)b.append(i<resolved||Character.isWhitespace(msg.charAt(i))?msg.charAt(i):glyph.charAt(ThreadLocalRandom.current().nextInt(glyph.length())));status.setText(b.toString());if(++step[0]>8){status.setText(msg);t.stop();}});t.start();}
    void update(Session s,String msg){SwingUtilities.invokeLater(()->{if(current==s){message(msg);buttons();if(msg.startsWith("[ CONNECTED ]"))notifyTray("Connesso a "+s.socket.getInetAddress().getHostAddress());}});}
    static String addresses(){StringBuilder b=new StringBuilder();try{Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();while(all.hasMoreElements()){NetworkInterface n=all.nextElement();if(!n.isUp()||n.isLoopback())continue;Enumeration<InetAddress>a=n.getInetAddresses();while(a.hasMoreElements()){InetAddress i=a.nextElement();if(i instanceof Inet4Address&&i.isSiteLocalAddress())b.append(i.getHostAddress()).append(' ');}}}catch(Exception ignored){}return b.toString();}
    void startHost(){if(current!=null)return;boolean control=allow.isSelected();if(JOptionPane.showConfirmDialog(window,"Share your primary display with one paired LAN peer?\n"+(control?"Mouse control is enabled. Keep STOP SESSION available.":"View only.")+"\nDo not share confidential windows. Never run this app as administrator.","Confirm sharing",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        Session s=new Session(true,control);current=s;buttons();window.setAlwaysOnTop(true);window.setSize(760,230);String code=Link.newCode();message("[ WAITING ] PAIR ONCE");JOptionPane.showMessageDialog(window,"LAN: "+addresses()+"\nPort: "+Link.PORT+"\nPair secret: "+code+"\nExpires 2 minutes after this dialog closes. Share privately.","Pair one device",JOptionPane.INFORMATION_MESSAGE);if(current==s)s.work.execute(()->serve(s,code));}
    void startClient(){if(current!=null)return;String address=ip.getText().trim();char[] chars=secret.getPassword();String code=new String(chars);Arrays.fill(chars,'\0');secret.setText("");try{Link.secret(code);if(!address.matches("[0-9.]+")||!InetAddress.getByName(address).isSiteLocalAddress())throw new IllegalArgumentException();}catch(Exception e){message("[ ERROR ] Private IPv4 and 32 hex characters required");return;}Session s=new Session(false,false);current=s;buttons();view.reveal=effects.isSelected()?0:1;message("[ CONNECTING ] AUTHENTICATING");s.work.execute(()->receive(s,address,code));}
    void openContent(){Session s=current;if(s==null||!s.host||s.link==null)return;JFileChooser chooser=new JFileChooser();if(chooser.showOpenDialog(window)==JFileChooser.APPROVE_OPTION){try{if(current==s&&s.live&&java.awt.Desktop.isDesktopSupported())java.awt.Desktop.getDesktop().open(chooser.getSelectedFile());}catch(Exception e){message("[ ERROR ] No associated viewer");}}}
    void serve(Session s,String code){try{if(!s.live)return;GraphicsDevice dev=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();s.bounds=dev.getDefaultConfiguration().getBounds();s.robot=new Robot(dev);try(ServerSocket ss=new ServerSocket()){s.listener=ss;ss.setReuseAddress(true);ss.bind(new InetSocketAddress(Link.PORT));ss.setSoTimeout(120000);if(!s.live)return;try(Socket socket=ss.accept()){s.socket=socket;if(!s.live||!socket.getInetAddress().isSiteLocalAddress())return;try(Link l=Link.open(socket,code,true)){s.link=l;update(s,"[ CONNECTED ] HOST / ENCRYPTED");s.work.execute(()->capture(s,l));while(s.live){byte[] b=l.read();if(b[0]==Link.PING&&b.length==9)l.send(Link.PONG,Arrays.copyOfRange(b,1,9));else if(b[0]==Link.END)break;else if(b[0]==Link.TOUCH&&b.length==21&&s.control)gesture(s,b);}}}}}catch(Exception e){update(s,"[ CLOSED ] "+e.getClass().getSimpleName());}finally{finished(s);}}
    void capture(Session s,Link l){try{while(s.live){long begin=now();Rectangle bounds=s.bounds;if(!bounds.equals(GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds()))throw new IOException("Display changed");BufferedImage raw=s.robot.createScreenCapture(bounds);double scale=Math.min(1,1280.0/Math.max(raw.getWidth(),raw.getHeight()));BufferedImage resized=new BufferedImage(Math.max(2,(int)(raw.getWidth()*scale)),Math.max(2,(int)(raw.getHeight()*scale)),BufferedImage.TYPE_INT_RGB);Graphics2D g=resized.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(raw,0,0,resized.getWidth(),resized.getHeight(),null);g.dispose();ByteArrayOutputStream data=new ByteArrayOutputStream();ImageIO.write(resized,"jpg",data);byte[] bytes=data.toByteArray();l.send(Link.FRAME,bytes);s.bytes+=bytes.length+21;s.frames++;Thread.sleep(Math.max(5,150-(now()-begin)));}}catch(Exception e){s.close();}}
    void gesture(Session s,byte[] b){ByteBuffer p=ByteBuffer.wrap(b,1,20);float x=p.getFloat(),y=p.getFloat(),a=p.getFloat(),z=p.getFloat();int duration=p.getInt();if(!unit(x)||!unit(y)||!unit(a)||!unit(z)||!s.live||now()-s.lastInput<100)return;s.lastInput=now();Rectangle r=s.bounds;Robot robot=s.robot;int sx=r.x+(int)(x*(r.width-1)),sy=r.y+(int)(y*(r.height-1)),ex=r.x+(int)(a*(r.width-1)),ey=r.y+(int)(z*(r.height-1));robot.mouseMove(sx,sy);if(!s.live)return;robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);try{int steps=Math.max(1,Math.min(12,duration/40));for(int i=1;i<=steps&&s.live;i++){robot.mouseMove(sx+(ex-sx)*i/steps,sy+(ey-sy)*i/steps);Thread.sleep(25);}}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);}}
    static boolean unit(float f){return Float.isFinite(f)&&f>=0&&f<=1;}
    void receive(Session s,String address,String code){try(Socket socket=new Socket()){s.socket=socket;if(!s.live)return;socket.connect(new InetSocketAddress(address,Link.PORT),5000);try(Link l=Link.open(socket,code,false)){s.link=l;update(s,"[ CONNECTED ] RECEIVER / AES-GCM");while(s.live){byte[] b=l.read();s.bytes+=b.length+20;if(b[0]==Link.FRAME){BufferedImage im=decodeFrame(b);if(im!=null&&im.getWidth()<=4096&&im.getHeight()<=4096){s.frames++;s.lastFrame=now();view.image=im;view.repaint();}}else if(b[0]==Link.PONG&&b.length==9){long sample=now()-ByteBuffer.wrap(b,1,8).getLong();if(sample>=0&&sample<60000){if(s.rtt>=0)s.jitter=.8*s.jitter+.2*Math.abs(sample-s.rtt);s.rtt=sample;}}else if(b[0]==Link.END)break;}}}catch(Exception e){update(s,"[ CLOSED ] "+e.getClass().getSimpleName());}finally{finished(s);}}
    static BufferedImage decodeFrame(byte[] b)throws IOException {try(javax.imageio.stream.ImageInputStream stream=ImageIO.createImageInputStream(new ByteArrayInputStream(b,1,b.length-1))){if(stream==null)return null;java.util.Iterator<javax.imageio.ImageReader> readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())return null;javax.imageio.ImageReader reader=readers.next();try{reader.setInput(stream,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);if(w<1||h<1||w>4096||h>4096||(long)w*h>8_000_000)throw new IOException("Frame dimensions exceed limits");return reader.read(0);}finally{reader.dispose();}}}
    void updateMetrics(){Session s=current;long t=System.nanoTime();double dt=Math.max(.001,(t-tick)/1e9);tick=t;if(s==null)return;long bytes=s.bytes,frames=s.frames;metrics.setText(String.format(Locale.ROOT,"RTT %s | JITTER %.1f ms | %s %.2f Mb/s | FPS %.1f",s.rtt<0?"--":String.format(Locale.ROOT,"%.0f ms",s.rtt),s.jitter,s.host?"TX":"RX",(bytes-s.prevBytes)*8/dt/1e6,(frames-s.prevFrames)/dt));s.prevBytes=bytes;s.prevFrames=frames;Link l=s.link;if(l!=null&&!s.host&&s.pingPending.compareAndSet(false,true)){try{s.work.execute(()->{try{if(s.live)l.send(Link.PING,ByteBuffer.allocate(8).putLong(now()).array());}catch(Exception e){s.close();}finally{s.pingPending.set(false);}});}catch(RejectedExecutionException ignored){}}}
    void finished(Session s){s.close();SwingUtilities.invokeLater(()->{if(current==s){current=null;view.image=null;view.repaint();window.setAlwaysOnTop(false);window.setSize(1000,760);notifyTray("Sessione chiusa");message("[ SESSION CLOSED ]");buttons();}});}
    void end(){Session s=current;current=null;if(s!=null){s.close();notifyTray("Disconnesso");}view.image=null;view.repaint();window.setAlwaysOnTop(false);window.setSize(1000,760);message("[ STOPPED ]");metrics.setText("RTT -- | RX -- | FPS --");buttons();}
    static final class Session {final boolean host,control;volatile boolean live=true;volatile Link link;volatile Socket socket;volatile ServerSocket listener;volatile Rectangle bounds;volatile Robot robot;volatile long bytes,frames,lastFrame,lastInput;volatile double rtt=-1,jitter;long prevBytes,prevFrames;final AtomicBoolean gesturePending=new AtomicBoolean(),pingPending=new AtomicBoolean();final ExecutorService work=Executors.newFixedThreadPool(3);Session(boolean h,boolean c){host=h;control=c;}synchronized void close(){live=false;try{if(listener!=null)listener.close();}catch(Exception ignored){}try{if(socket!=null)socket.close();}catch(Exception ignored){}try{if(link!=null)link.close();}catch(Exception ignored){}work.shutdownNow();}}
    final class Rain extends JPanel {protected void paintComponent(Graphics g){g.setColor(BG);g.fillRect(0,0,getWidth(),getHeight());g.setFont(MONO);long t=now()/90;for(int x=0;x<getWidth()/12;x++)for(int y=0;y<3;y++){g.setColor(new Color(20,30+Math.floorMod((int)t+x*7-y*4,190),65));g.drawString(Integer.toHexString(Math.floorMod((int)t+x*13+y*7,16)).toUpperCase(),x*12,13+y*14);}}}
    final class CanvasView extends JPanel {volatile BufferedImage image;double reveal=1;float sx,sy;long down;Point marker;CanvasView(){setBackground(BG);MouseAdapter m=new MouseAdapter(){public void mousePressed(MouseEvent e){float[] p=point(e);if(p!=null){sx=p[0];sy=p[1];down=now();}}public void mouseReleased(MouseEvent e){Session s=current;float[] p=point(e);if(down==0||p==null||s==null||s.host||s.link==null||now()-s.lastFrame>1500){down=0;return;}byte[] b=ByteBuffer.allocate(20).putFloat(sx).putFloat(sy).putFloat(p[0]).putFloat(p[1]).putInt((int)Math.min(700,now()-down)).array();down=0;marker=e.getPoint();repaint();javax.swing.Timer clear=new javax.swing.Timer(350,a->{marker=null;repaint();});clear.setRepeats(false);clear.start();if(s.gesturePending.compareAndSet(false,true))try{s.work.execute(()->{try{if(s.live)s.link.send(Link.TOUCH,b);}catch(Exception ex){s.close();}finally{s.gesturePending.set(false);}});}catch(RejectedExecutionException ignored){}}};addMouseListener(m);}
        Rectangle area(){BufferedImage im=image;if(im==null)return new Rectangle();double scale=Math.min(getWidth()/(double)im.getWidth(),getHeight()/(double)im.getHeight());int w=(int)(im.getWidth()*scale),h=(int)(im.getHeight()*scale);return new Rectangle((getWidth()-w)/2,(getHeight()-h)/2,w,h);}
        float[] point(MouseEvent e){Rectangle r=area();if(!r.contains(e.getPoint())||reveal<1)return null;return new float[]{(e.getX()-r.x)/(float)r.width,(e.getY()-r.y)/(float)r.height};}
        protected void paintComponent(Graphics graphics){super.paintComponent(graphics);Graphics2D g=(Graphics2D)graphics.create();BufferedImage im=image;if(im==null){g.setFont(MONO);g.setColor(DIM);g.drawString("AMPYLINK // awaiting remote frame",20,30);}else{Rectangle r=area();int w=(int)(r.width*(.15+.85*reveal)),h=(int)(r.height*(.05+.95*reveal));int glitch=effects.isSelected()&&reveal<1?(int)(now()%7)-3:0;g.drawImage(im,r.x+(r.width-w)/2+glitch,r.y,w,h,null);if(marker!=null){g.setColor(GREEN);g.setStroke(new BasicStroke(2));g.drawOval(marker.x-14,marker.y-14,28,28);}}g.dispose();}
    }
}
