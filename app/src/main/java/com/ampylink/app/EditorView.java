package com.ampylink.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.RectF;
import android.opengl.GLSurfaceView;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

/** GPU canvas: pinch-to-zoom, pan, double-tap 1:1, draggable before/after divider. */
@SuppressLint("ViewConstructor")
final class EditorView extends GLSurfaceView {
    final GLRenderer renderer;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestures;
    private final RectF safe = new RectF();
    private boolean draggingSplit, scaling;
    private float lastX, lastY;
    private final float dp;
    Runnable onSplitMoved;
    /** Pan / zoom / fit changed (frame overlay follows the image). */
    Runnable onViewChanged;

    private void viewChanged() { if (onViewChanged != null) onViewChanged.run(); }

    EditorView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        renderer = new GLRenderer();
        renderer.density = dp;
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        renderer.setImageListener((w, h) -> post(this::fit));
        renderer.rerenderLater = () -> postDelayed(this::requestRender, 110);

        scaleDetector = new ScaleGestureDetector(c, new ScaleListener());
        gestures = new GestureDetector(c, new TapListener());
    }

    // member classes (not anonymous) keep the toolchain's D8 happy
    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override public boolean onScaleBegin(ScaleGestureDetector d) { scaling = true; return true; }
        @Override public boolean onScale(ScaleGestureDetector d) {
            zoomAt(d.getFocusX(), d.getFocusY(), d.getScaleFactor());
            return true;
        }
    }

    private final class TapListener extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onDoubleTap(MotionEvent e) {
            if (!renderer.hasImage()) return false;
            float fit = fitScale();
            float s; synchronized (renderer.lock) { s = renderer.scale; }
            float target = s > fit * 1.05f ? fit : Math.max(1f, fit * 2f);
            if (target == fit) fit(); else zoomAt(e.getX(), e.getY(), target / s);
            return true;
        }
    }

    /** Area not covered by the floating UI (px), used for "fit to screen". */
    void setSafeArea(float l, float t, float r, float b) {
        if (safe.left == l && safe.top == t && safe.right == r && safe.bottom == b) return;
        safe.set(l, t, r, b);
        if (renderer.hasImage()) fit();
    }

    private float fitScale() {
        float aw = getWidth() - safe.left - safe.right, ah = getHeight() - safe.top - safe.bottom;
        if (aw <= 0 || ah <= 0 || renderer.imageW() == 0) return 1f;
        return Math.min(aw / renderer.imageW(), ah / renderer.imageH()) * 0.97f;
    }

    void fit() {
        if (!renderer.hasImage() || getWidth() == 0) return;
        float s = fitScale();
        // centre of safe area -> image centre
        float sx = (safe.left + getWidth() - safe.right) / 2f, sy = (safe.top + getHeight() - safe.bottom) / 2f;
        synchronized (renderer.lock) {
            renderer.scale = s;
            renderer.cx = renderer.imageW() / 2f - (sx - getWidth() / 2f) / s;
            renderer.cy = renderer.imageH() / 2f - (sy - getHeight() / 2f) / s;
        }
        requestRender();
        viewChanged();
    }

    private void zoomAt(float fx, float fy, float factor) {
        float fit = fitScale();
        synchronized (renderer.lock) {
            float s = renderer.scale;
            float ns = Math.max(fit * 0.5f, Math.min(s * factor, 16f));
            // keep image point under the focus fixed
            float ix = (fx - getWidth() / 2f) / s + renderer.cx;
            float iy = (fy - getHeight() / 2f) / s + renderer.cy;
            renderer.scale = ns;
            renderer.cx = ix - (fx - getWidth() / 2f) / ns;
            renderer.cy = iy - (fy - getHeight() / 2f) / ns;
        }
        requestRender();
        viewChanged();
    }

    float splitX() { synchronized (renderer.lock) { return renderer.split * getWidth(); } }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        gestures.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                scaling = false;
                lastX = e.getX(); lastY = e.getY();
                boolean show; synchronized (renderer.lock) { show = renderer.showSplit; }
                draggingSplit = show && renderer.hasImage() && Math.abs(e.getX() - splitX()) < 28 * dp;
                if (draggingSplit) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                draggingSplit = false;
                break;
            case MotionEvent.ACTION_MOVE: {
                if (draggingSplit && e.getPointerCount() == 1) {
                    synchronized (renderer.lock) { renderer.split = Math.max(0f, Math.min(1f, e.getX() / getWidth())); }
                    if (onSplitMoved != null) onSplitMoved.run();
                    requestRender();
                } else if (!scaling && e.getPointerCount() == 1) {
                    float dx = e.getX() - lastX, dy = e.getY() - lastY;
                    synchronized (renderer.lock) {
                        renderer.cx -= dx / renderer.scale;
                        renderer.cy -= dy / renderer.scale;
                    }
                    requestRender();
                    viewChanged();
                }
                lastX = e.getX(); lastY = e.getY();
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int keep = e.getActionIndex() == 0 ? 1 : 0;
                lastX = e.getX(keep); lastY = e.getY(keep);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                draggingSplit = false; scaling = false;
                break;
        }
        return true;
    }
}
