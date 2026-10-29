/*
 * AmpyRaw GPU renderer (OpenGL ES 3.x). GPL v3.
 * Pass 1 (once per image): raw CFA -> demosaic -> linear RGBA16F base texture (+ mipmaps).
 * Pass 2 (every slider tick / gesture): one fragment pass for edit + before/after + zoom.
 */
package com.ampylink.app;

import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

final class GLRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "AmpyRaw";

    interface BitmapCallback { void onBitmap(Bitmap b); }
    interface ImageListener { void onImageReady(int w, int h); }
    interface HistogramListener { void onHistogram(int[] r, int[] g, int[] b, int[] l); }

    /** Shared, mutated from UI thread; read on GL thread under lock. */
    final Object lock = new Object();
    final float[] values = new float[Tool.values().length];
    float scale = 1f, cx, cy, split = 0.5f; // split as fraction of view width
    boolean showSplit = true;
    float density = 3f;
    boolean histogramOn = true;
    Runnable rerenderLater;

    private int curveTex;
    private volatile byte[] curveLut;     // pending LUT upload
    private volatile boolean curveActive;
    private HistogramListener histListener;
    private long lastHist;
    private boolean histDirty = true;
    private int histTex, histFbo, histW, histH;

    private int progDemosaic, progLinear, progEdit;
    private int vbo;
    private int baseTex, baseFbo;
    private int imgW, imgH;
    private int viewW = 1, viewH = 1;
    private int maxTex = 4096;
    private boolean halfFloat;
    private RawDecoder.ImageData pending, current;
    private ImageListener listener;

    GLRenderer() {
        for (Tool t : Tool.values()) values[t.ordinal()] = t.def;
    }

    void setImageListener(ImageListener l) { listener = l; }
    void setHistogramListener(HistogramListener l) { histListener = l; }

    /** Thread-safe: new tone-curve LUT (256x1 RGBA, a = master). */
    void setCurves(byte[] lut, boolean active) { curveLut = lut; curveActive = active; histDirty = true; }
    void invalidateHistogram() { histDirty = true; }
    int maxTextureSize() { return maxTex; }
    int imageW() { return imgW; }
    int imageH() { return imgH; }
    boolean hasImage() { return current != null; }
    int viewW() { return viewW; }
    int viewH() { return viewH; }

    /** Called on the GL thread (via queueEvent). */
    void setImage(RawDecoder.ImageData d) {
        pending = d;
        if (progEdit != 0) uploadPending();
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        int[] v = new int[1];
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, v, 0);
        maxTex = Math.min(v[0], 16384);
        String ext = GLES30.glGetString(GLES30.GL_EXTENSIONS);
        halfFloat = ext != null && (ext.contains("GL_EXT_color_buffer_half_float") || ext.contains("GL_EXT_color_buffer_float"));
        Log.i(TAG, "GPU " + GLES30.glGetString(GLES30.GL_RENDERER) + " / " + GLES30.glGetString(GLES30.GL_VERSION) + " maxTex=" + maxTex + " fp16=" + halfFloat);

        progDemosaic = program(Shaders.VERT, Shaders.DEMOSAIC);
        progLinear = program(Shaders.VERT, Shaders.LINEARIZE);
        progEdit = program(Shaders.VERT, Shaders.EDIT);
        float[] quad = {-1, -1, 1, -1, -1, 1, 1, 1};
        FloatBuffer fb = ByteBuffer.allocateDirect(quad.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(quad).position(0);
        GLES30.glGenBuffers(1, v, 0); vbo = v[0];
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.length * 4, fb, GLES30.GL_STATIC_DRAW);
        baseTex = 0; baseFbo = 0; histTex = 0; histFbo = 0;
        GLES30.glGenTextures(1, v, 0); curveTex = v[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveTex);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        if (curveLut == null) curveLut = new Curves().lut();
        if (pending == null && current != null) pending = current; // context was lost: re-upload
        if (pending != null) uploadPending();
    }

    @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
        viewW = w; viewH = h;
        GLES30.glViewport(0, 0, w, h);
    }

    @Override public void onDrawFrame(GL10 gl) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, viewW, viewH);
        if (baseTex == 0) {
            GLES30.glClearColor(0.027f, 0.027f, 0.047f, 1f);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            return;
        }
        float s, x, y, sp; boolean show;
        synchronized (lock) { s = scale; x = cx; y = cy; sp = split * viewW; show = showSplit; }
        drawEdit(viewW, viewH, s, x, y, sp, show, false, (float) Math.max(0.0, Math.log(1.0 / s) / Math.log(2) - 0.25));
        long now = System.currentTimeMillis();
        if (histogramOn && histListener != null && histDirty) {
            if (now - lastHist > 90) { lastHist = now; histDirty = false; computeHistogram(); }
            else if (rerenderLater != null) rerenderLater.run(); // throttled: make sure the final state is measured
        }
    }

    /** Edited image rendered at ~384px on the GPU, read back, binned on the CPU. */
    private void computeHistogram() {
        float k = 384f / Math.max(imgW, imgH);
        int w = Math.max(1, Math.round(imgW * k)), h = Math.max(1, Math.round(imgH * k));
        if (histTex == 0 || w != histW || h != histH) {
            int[] t = new int[1];
            if (histTex != 0) { GLES30.glDeleteTextures(1, new int[]{histTex}, 0); GLES30.glDeleteFramebuffers(1, new int[]{histFbo}, 0); }
            GLES30.glGenTextures(1, t, 0); histTex = t[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, histTex);
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, h);
            GLES30.glGenFramebuffers(1, t, 0); histFbo = t[0];
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, histFbo);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, histTex, 0);
            histW = w; histH = h;
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, histFbo);
        GLES30.glViewport(0, 0, w, h);
        drawEdit(w, h, k, imgW / 2f, imgH / 2f, 0, false, true, (float) (Math.log(1.0 / k) / Math.log(2)));
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1);
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, viewW, viewH);
        int[] r = new int[256], g = new int[256], b = new int[256], l = new int[256];
        for (int i = 0; i < w * h; i++) {
            int R = buf.get(i * 4) & 0xFF, G = buf.get(i * 4 + 1) & 0xFF, B = buf.get(i * 4 + 2) & 0xFF;
            r[R]++; g[G]++; b[B]++;
            l[(R * 54 + G * 183 + B * 19) >> 8]++;
        }
        histListener.onHistogram(r, g, b, l);
    }

    private void drawEdit(int vw, int vh, float s, float x, float y, float sp, boolean show, boolean export, float lod) {
        byte[] lut = curveLut;
        if (lut != null) {
            curveLut = null;
            ByteBuffer lb = ByteBuffer.allocateDirect(lut.length).order(ByteOrder.nativeOrder());
            lb.put(lut).position(0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveTex);
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 256, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, lb);
        }
        int p = progEdit;
        GLES30.glUseProgram(p);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, baseTex);
        GLES30.glUniform1i(loc(p, "u_base"), 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveTex);
        GLES30.glUniform1i(loc(p, "u_curve"), 1);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glUniform2f(loc(p, "u_imgSize"), imgW, imgH);
        GLES30.glUniform2f(loc(p, "u_viewSize"), vw, vh);
        GLES30.glUniform2f(loc(p, "u_center"), x, y);
        GLES30.glUniform1f(loc(p, "u_scale"), s);
        GLES30.glUniform1f(loc(p, "u_split"), sp);
        GLES30.glUniform1i(loc(p, "u_showSplit"), show ? 1 : 0);
        GLES30.glUniform1i(loc(p, "u_export"), export ? 1 : 0);
        GLES30.glUniform1f(loc(p, "u_density"), export ? 1f : density);
        GLES30.glUniform1f(loc(p, "u_lod"), Math.max(0f, lod));
        float maxDim = Math.max(imgW, imgH);
        GLES30.glUniform1f(loc(p, "u_lodSH"), (float) (Math.log(maxDim / 40f) / Math.log(2)));
        GLES30.glUniform1f(loc(p, "u_lodCl"), (float) (Math.log(maxDim / 260f) / Math.log(2)));
        float[] vals;
        synchronized (lock) { vals = values.clone(); }
        setParams(p, "u_edit", vals, curveActive);
        float[] def = new float[vals.length];
        for (Tool t : Tool.values()) def[t.ordinal()] = t.def;
        setParams(p, "u_orig", def, false);
        drawQuad();
    }

    private void setParams(int p, String n, float[] v, boolean curve) {
        for (Tool t : Tool.values()) if (t.glsl != null) GLES30.glUniform1f(loc(p, n + "." + t.glsl), v[t.ordinal()]);
        GLES30.glUniform1f(loc(p, n + ".curve"), curve ? 1f : 0f);
        float[] wb = ColorMath.wbGain(v[Tool.TEMP.ordinal()], v[Tool.TINT.ordinal()]);
        GLES30.glUniform3f(loc(p, n + ".wb"), wb[0], wb[1], wb[2]);
    }

    /** Full resolution render for save/print. Must run on GL thread. */
    void renderFull(BitmapCallback cb) {
        if (baseTex == 0) { cb.onBitmap(null); return; }
        int w = imgW, h = imgH;
        int[] t = new int[1], f = new int[1];
        GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, h);
        GLES30.glGenFramebuffers(1, f, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0]);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t[0], 0);
        GLES30.glViewport(0, 0, w, h);
        drawEdit(w, h, 1f, w / 2f, h / 2f, 0, false, true, 0f);
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1);
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glDeleteFramebuffers(1, f, 0);
        GLES30.glDeleteTextures(1, t, 0);
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        buf.position(0);
        bm.copyPixelsFromBuffer(buf);
        cb.onBitmap(bm);
    }

    // ------------------------------------------------------------------ upload / pass 1

    private void uploadPending() {
        RawDecoder.ImageData d = pending;
        pending = null;
        if (d == null) return;
        int w = d.width(), h = d.height();
        if (baseTex != 0) { GLES30.glDeleteTextures(1, new int[]{baseTex}, 0); GLES30.glDeleteFramebuffers(1, new int[]{baseFbo}, 0); }
        int levels = 1 + (int) Math.floor(Math.log(Math.max(w, h)) / Math.log(2));
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0); baseTex = t[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, baseTex);
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, levels, halfFloat ? GLES30.GL_RGBA16F : GLES30.GL_RGBA8, w, h);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glGenFramebuffers(1, t, 0); baseFbo = t[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, baseFbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, baseTex, 0);
        GLES30.glViewport(0, 0, w, h);

        int[] src = new int[1];
        GLES30.glGenTextures(1, src, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
        if (d.isRaw()) {
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2);
            ShortBuffer sb = ByteBuffer.allocateDirect(d.cfa.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
            sb.put(d.cfa).position(0);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16UI, d.rawW, d.rawH, 0, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, sb);
            int p = progDemosaic;
            GLES30.glUseProgram(p);
            GLES30.glUniform1i(loc(p, "u_raw"), 0);
            GLES30.glUniform2i(loc(p, "u_rawSize"), d.rawW, d.rawH);
            GLES30.glUniform1i(loc(p, "u_orient"), d.orientation);
            GLES30.glUniform1i(loc(p, "u_half"), d.half ? 1 : 0);
            GLES30.glUniform4i(loc(p, "u_cfa"), d.cfaPattern[0], d.cfaPattern[1], d.cfaPattern[2], d.cfaPattern[3]);
            GLES30.glUniform4f(loc(p, "u_black"), d.black[0], d.black[1], d.black[2], d.black[3]);
            GLES30.glUniform1f(loc(p, "u_white"), d.white);
            GLES30.glUniform3f(loc(p, "u_wb"), d.wb[0], d.wb[1], d.wb[2]);
            GLES30.glUniformMatrix3fv(loc(p, "u_cam2rgb"), 1, false, d.cam2rgb, 0);
        } else {
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4);
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, d.bitmap, 0);
            GLES30.glUseProgram(progLinear);
            GLES30.glUniform1i(loc(progLinear, "u_src"), 0);
        }
        drawQuad();
        GLES30.glDeleteTextures(1, src, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, baseTex);
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        int err = GLES30.glGetError();
        if (err != 0) Log.w(TAG, "GL error after upload: 0x" + Integer.toHexString(err));

        imgW = w; imgH = h;
        histDirty = true;
        // free CPU copy of the CFA but keep it for context loss only if small
        current = d;
        if (listener != null) listener.onImageReady(w, h);
    }

    private void drawQuad() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    private static int loc(int p, String n) { return GLES30.glGetUniformLocation(p, n); }

    private static int program(String vs, String fs) {
        int v = shader(GLES30.GL_VERTEX_SHADER, vs), f = shader(GLES30.GL_FRAGMENT_SHADER, fs);
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f);
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("link: " + GLES30.glGetProgramInfoLog(p));
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("compile: " + GLES30.glGetShaderInfoLog(s));
        return s;
    }
}
