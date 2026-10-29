package com.ampylink.app;

import java.util.EnumMap;
import java.util.Map;

/** One-tap looks. Deltas are applied on top of the current edit, scaled by the strength slider. */
final class Preset {
    final String name; final int c1, c2;
    final Map<Tool, Float> delta = new EnumMap<>(Tool.class);
    Curves curves; // optional, relative to identity

    Preset(String name, int c1, int c2) { this.name = name; this.c1 = c1; this.c2 = c2; }

    Preset d(Tool t, float v) { delta.put(t, v); return this; }
    Preset curve(float[] master, float[] r, float[] g, float[] b) { curves = Curves.of(master, r, g, b); return this; }

    static final Preset[] ALL = {
        new Preset("ORIGINAL", 0xFF52606E, 0xFF07070C),
        new Preset("NEON CITY", 0xFF00F0FF, 0xFFFF2BD6)
            .d(Tool.NEON, 0.75f).d(Tool.CONTRAST, 0.25f).d(Tool.VIBRANCE, 0.3f).d(Tool.CLARITY, 0.3f)
            .d(Tool.TEMP, -900f).d(Tool.VIGNETTE, -0.35f).d(Tool.DEHAZE, 0.15f),
        new Preset("TEAL & AMBER", 0xFF00A3A3, 0xFFFFA43B)
            .d(Tool.CONTRAST, 0.2f).d(Tool.VIBRANCE, 0.25f).d(Tool.SATURATION, -0.05f)
            .curve(null, new float[]{0, 0, 0.25f, 0.2f, 0.75f, 0.82f, 1, 1}, null, new float[]{0, 0.06f, 0.25f, 0.3f, 0.75f, 0.68f, 1, 0.94f}),
        new Preset("NOIR", 0xFFEEEEEE, 0xFF111111)
            .d(Tool.MONO, 1f).d(Tool.CONTRAST, 0.45f).d(Tool.CLARITY, 0.35f).d(Tool.GRAIN, 0.3f).d(Tool.VIGNETTE, -0.45f)
            .curve(new float[]{0, 0, 0.25f, 0.18f, 0.75f, 0.84f, 1, 1}, null, null, null),
        new Preset("MATTE FILM", 0xFF8D8574, 0xFF39424A)
            .d(Tool.FADE, 0.3f).d(Tool.CONTRAST, -0.1f).d(Tool.SATURATION, -0.15f).d(Tool.GRAIN, 0.25f).d(Tool.TEMP, 400f)
            .curve(new float[]{0, 0.08f, 0.5f, 0.52f, 1, 0.94f}, null, null, null),
        new Preset("VIVID", 0xFFFF3B6B, 0xFF3BFFB0)
            .d(Tool.VIBRANCE, 0.6f).d(Tool.SATURATION, 0.15f).d(Tool.CONTRAST, 0.2f).d(Tool.CLARITY, 0.2f),
        new Preset("CHROME", 0xFFB8C4CC, 0xFF2D3A44)
            .d(Tool.CONTRAST, 0.3f).d(Tool.SATURATION, -0.1f).d(Tool.HIGHLIGHTS, -0.3f).d(Tool.SHADOWS, 0.15f)
            .curve(new float[]{0, 0, 0.3f, 0.24f, 0.7f, 0.78f, 1, 1}, null, null, null),
        new Preset("GOLDEN HOUR", 0xFFFFB13B, 0xFFFF6A3B)
            .d(Tool.TEMP, 1600f).d(Tool.TINT, 0.1f).d(Tool.VIBRANCE, 0.3f).d(Tool.HIGHLIGHTS, -0.2f).d(Tool.SHADOWS, 0.15f),
        new Preset("ARCTIC", 0xFFBDEBFF, 0xFF3B7BFF)
            .d(Tool.TEMP, -1200f).d(Tool.SATURATION, -0.2f).d(Tool.EXPOSURE, 0.2f).d(Tool.FADE, 0.1f).d(Tool.CLARITY, 0.15f),
        new Preset("SEPIA", 0xFFC79A5B, 0xFF4A3120)
            .d(Tool.MONO, 1f).d(Tool.CONTRAST, 0.1f).d(Tool.GRAIN, 0.2f).d(Tool.VIGNETTE, -0.3f)
            .curve(null, new float[]{0, 0.03f, 0.5f, 0.58f, 1, 1}, new float[]{0, 0.01f, 0.5f, 0.5f, 1, 0.96f}, new float[]{0, 0, 0.5f, 0.38f, 1, 0.84f}),
        new Preset("HDR PUNCH", 0xFFFFFF3B, 0xFF00F0FF)
            .d(Tool.SHADOWS, 0.6f).d(Tool.HIGHLIGHTS, -0.6f).d(Tool.CLARITY, 0.5f).d(Tool.DEHAZE, 0.3f).d(Tool.VIBRANCE, 0.3f).d(Tool.TEXTURE, 0.3f),
        new Preset("GLITCH", 0xFFFF2BD6, 0xFF3BFF6A)
            .d(Tool.NEON, 1f).d(Tool.CONTRAST, 0.4f).d(Tool.CA_RED, 0.9f).d(Tool.CA_BLUE, -0.9f).d(Tool.GRAIN, 0.45f).d(Tool.HUE, 0.05f),
        new Preset("NIGHT VISION", 0xFF3BFF6A, 0xFF062B10)
            .d(Tool.MONO, 1f).d(Tool.EXPOSURE, 0.6f).d(Tool.GRAIN, 0.6f).d(Tool.VIGNETTE, -0.7f).d(Tool.CONTRAST, 0.3f)
            .curve(null, new float[]{0, 0, 1, 0.25f}, new float[]{0, 0.04f, 1, 1}, new float[]{0, 0, 1, 0.2f}),
        new Preset("INSTANT", 0xFFF5E6C8, 0xFF7A9EA8)
            .d(Tool.FADE, 0.22f).d(Tool.TEMP, 900f).d(Tool.SATURATION, -0.1f).d(Tool.VIGNETTE, -0.25f).d(Tool.HIGHLIGHTS, -0.2f)
            .curve(null, null, null, new float[]{0, 0.1f, 1, 0.92f}),
        new Preset("CLEAN RAW", 0xFFC8F7FF, 0xFF52606E)
            .d(Tool.EXPOSURE, 0.35f).d(Tool.SHADOWS, 0.25f).d(Tool.HIGHLIGHTS, -0.25f).d(Tool.SHARPEN, 0.35f)
            .d(Tool.DENOISE, 0.25f).d(Tool.DEFRINGE, 0.5f).d(Tool.VIBRANCE, 0.15f),
    };
}
