package io.ampy.link;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** LAN protocol v1. Ephemeral pairing secret, authenticated per-session directional keys. */
public final class Link implements Closeable {
    static final int PORT=43891, MAX=4*1024*1024;
    static final byte AUTH=1, ACK=2, FRAME=3, TOUCH=4, PING=5, PONG=6, END=7;
    private static final SecureRandom RNG=new SecureRandom();
    final Socket socket; final DataInputStream in; final DataOutputStream out;
    private final byte[] tx,rx; private long sendSeq,recvSeq;
    public static String newCode(){byte[] b=new byte[16];RNG.nextBytes(b);StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(java.util.Locale.ROOT,"%02X",v&255));return s.toString();}
    static byte[] secret(String s){s=s.replace(" ","").replace("-","");if(!s.matches("[0-9a-fA-F]{32}"))throw new IllegalArgumentException("Expected 32 hex characters");byte[] b=new byte[16];for(int i=0;i<16;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
    static byte[] derive(byte[] secret,byte[] c,byte[] h,String label)throws Exception{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));mac.update("AmpyLink-v1".getBytes(StandardCharsets.US_ASCII));mac.update(c);mac.update(h);return mac.doFinal(label.getBytes(StandardCharsets.US_ASCII));}
    private Link(Socket s,String code,boolean host)throws Exception{
        socket=s;s.setTcpNoDelay(true);s.setSoTimeout(10000);in=new DataInputStream(new BufferedInputStream(s.getInputStream()));out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        byte[] c=new byte[32],h=new byte[32];
        if(host){in.readFully(c);RNG.nextBytes(h);out.write(h);out.flush();}else{RNG.nextBytes(c);out.write(c);out.flush();in.readFully(h);}
        byte[] k=secret(code);tx=derive(k,c,h,host?"host-to-client":"client-to-host");rx=derive(k,c,h,host?"client-to-host":"host-to-client");Arrays.fill(k,(byte)0);
        if(host){if(!Arrays.equals(read(),new byte[]{AUTH}))throw new IOException("Authentication failed");send(ACK,new byte[0]);}else{send(AUTH,new byte[0]);if(!Arrays.equals(read(),new byte[]{ACK}))throw new IOException("Authentication failed");}
    }
    static Link open(Socket s,String code,boolean host)throws Exception{try{return new Link(s,code,host);}catch(Exception e){s.close();throw e;}}
    private static byte[] nonce(long n){if(n<0)throw new IllegalStateException("Counter exhausted");return ByteBuffer.allocate(12).putInt(0).putLong(n).array();}
    private static byte[] crypt(int mode,byte[] key,long n,byte[] data)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce(n)));return c.doFinal(data);}
    synchronized void send(byte kind,byte[] payload)throws Exception{if(payload.length>MAX-17)throw new IOException("Frame too large");byte[] plain=new byte[payload.length+1];plain[0]=kind;System.arraycopy(payload,0,plain,1,payload.length);byte[] enc=crypt(Cipher.ENCRYPT_MODE,tx,sendSeq++,plain);out.writeInt(enc.length);out.write(enc);out.flush();}
    byte[] read()throws Exception{int n=in.readInt();if(n<17||n>MAX)throw new IOException("Invalid frame size");byte[] b=new byte[n];in.readFully(b);return crypt(Cipher.DECRYPT_MODE,rx,recvSeq++,b);}
    public void close()throws IOException{socket.close();}
}
