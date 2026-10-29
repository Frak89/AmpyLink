/*
 * Live print-frame overlay on top of the GPU editor: shows the KODAK card (640×1024) around the
 * exact crop that will be printed, with the chosen frame (BORDER, RETRO, INSTANT, FILM, NEON)
 * drawn by the same code that renders the print. GPL v3.
 */
package com.ampylink.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

final class FrameOverlay extends View {
    private final EditorView editor;
    Frames.Layout layout;      // null = off
    Frames.Meta meta = Frames.Meta.of(null, null);
    private final Paint dim = new Paint(), edge = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF card = new RectF();

    FrameOverlay(Context c, EditorView editor) {
        super(c);
        this.editor = editor;
        dim.setColor(0xC007070C);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Cyber.dp(c, 1.5f));
        edge.setColor(Cyber.CYAN);
        edge.setShadowLayer(Cyber.dp(c, 6), 0, 0, Cyber.CYAN);
        txt.setTypeface(Cyber.MONO);
        txt.setTextSize(Cyber.dp(c, 9));
        txt.setColor(Cyber.CYAN);
        txt.setLetterSpacing(0.12f);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setWillNotDraw(false);
    }

    @Override protected void onDraw(Canvas c) {
        if (layout == null || !editor.renderer.hasImage()) return;
        float s, cx, cy;
        GLRenderer r = editor.renderer;
        synchronized (r.lock) { s = r.scale; cx = r.cx; cy = r.cy; }
        int iw = r.imageW(), ih = r.imageH();
        if (iw <= 0 || ih <= 0 || s <= 0) return;
        float W = getWidth(), H = getHeight();
        float il = (0 - cx) * s + W / 2f, it = (0 - cy) * s + H / 2f;
        boolean land = iw > ih;
        int cw = land ? Frames.CARD_H : Frames.CARD_W, ch = land ? Frames.CARD_W : Frames.CARD_H;
        RectF box = Frames.box(layout, cw, ch);
        Rect crop = Frames.crop(iw, ih, box.width() / box.height(), 0, 0, 1);
        float k = crop.width() * s / box.width();
        card.set(il + crop.left * s - box.left * k, it + crop.top * s - box.top * k, 0, 0);
        card.right = card.left + cw * k;
        card.bottom = card.top + ch * k;
        c.save();
        c.clipOutRect(card);
        c.drawRect(0, 0, W, H, dim);
        c.restore();
        c.save();
        c.translate(card.left, card.top);
        c.scale(k, k);
        Frames.drawFrame(c, layout, cw, ch, meta);
        c.restore();
        c.drawRect(card, edge);
        String label = "FRAME // " + layout.label + " · KODAK 2.1×3.4";
        float ty = card.top - Cyber.dp(getContext(), 6);
        if (ty < txt.getTextSize() * 2) ty = card.bottom + txt.getTextSize() * 1.4f;
        c.drawText(label, card.left, ty, txt);
    }
}
