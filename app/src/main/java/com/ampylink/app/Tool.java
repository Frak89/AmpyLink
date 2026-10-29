package com.ampylink.app;

import java.util.Locale;

/** One editing tool = one slider = one GPU uniform (u_edit.<glsl>). */
enum Tool {
    // ---- ADJUST
    EXPOSURE  ("EXP", "EXPOSURE",      "exposure",   -3f, 3f, 0f, 0),
    CONTRAST  ("CON", "CONTRAST",      "contrast",   -1f, 1f, 0f, 0),
    HIGHLIGHTS("HLT", "HIGHLIGHTS",    "highlights", -1f, 1f, 0f, 0),
    SHADOWS   ("SHD", "SHADOWS",       "shadows",    -1f, 1f, 0f, 0),
    WHITES    ("WHT", "WHITES",        "whites",     -1f, 1f, 0f, 0),
    BLACKS    ("BLK", "BLACKS",        "blacks",     -1f, 1f, 0f, 0),
    TEMP      ("TMP", "TEMPERATURE",   null,       2000f, 12000f, 6504f, 0),
    TINT      ("TNT", "TINT",          "tint",       -1f, 1f, 0f, 0),
    VIBRANCE  ("VIB", "VIBRANCE",      "vibrance",   -1f, 1f, 0f, 0),
    SATURATION("SAT", "SATURATION",    "saturation", -1f, 1f, 0f, 0),
    HUE       ("HUE", "HUE SHIFT",     "hue",        -1f, 1f, 0f, 0),
    CLARITY   ("CLR", "CLARITY",       "clarity",    -1f, 1f, 0f, 0),
    TEXTURE   ("TXT", "TEXTURE",       "texture",    -1f, 1f, 0f, 0),
    DEHAZE    ("DHZ", "DEHAZE",        "dehaze",     -1f, 1f, 0f, 0),
    SHARPEN   ("SHP", "SHARPEN",       "sharpen",     0f, 1f, 0f, 0),
    DENOISE   ("DNS", "NOISE REDUCT.", "denoise",     0f, 1f, 0f, 0),
    MONO      ("B&W", "MONOCHROME",    "mono",        0f, 1f, 0f, 0),
    NEON      ("NEO", "NEON GRADE",    "neon",        0f, 1f, 0f, 0),
    FADE      ("FAD", "FADE",          "fade",        0f, 1f, 0f, 0),
    VIGNETTE  ("VIG", "VIGNETTE",      "vignette",   -1f, 1f, 0f, 0),
    GRAIN     ("GRN", "GRAIN",         "grain",       0f, 1f, 0f, 0),
    // ---- LENS
    DISTORTION("DST", "DISTORTION",    "distortion", -1f, 1f, 0f, 1),
    CA_RED    ("CAR", "CA RED/CYAN",   "caRed",      -1f, 1f, 0f, 1),
    CA_BLUE   ("CAB", "CA BLUE/YELLOW","caBlue",     -1f, 1f, 0f, 1),
    DEFRINGE  ("DFR", "DEFRINGE",      "defringe",    0f, 1f, 0f, 1),
    LENS_VIG  ("LVG", "LENS VIGNETTING","lensVig",   -1f, 1f, 0f, 1),
    ROTATE    ("ROT", "STRAIGHTEN",    "rotate",    -10f, 10f, 0f, 1),
    SCALE     ("SCL", "SCALE / CROP",  "zoom",       -1f, 1f, 0f, 1);

    static final int TAB_ADJUST = 0, TAB_LENS = 1;

    final String code, label, glsl;
    final float min, max, def;
    final int tab;

    Tool(String code, String label, String glsl, float min, float max, float def, int tab) {
        this.code = code; this.label = label; this.glsl = glsl; this.min = min; this.max = max; this.def = def; this.tab = tab;
    }

    /** Slider position 0..1 -> value. Temperature is linear in mired, like RawTherapee's slider feel. */
    float fromPos(float t) {
        if (this == TEMP) {
            float a = 1e6f / min, b = 1e6f / max;
            return 1e6f / (a + (b - a) * t);
        }
        return min + (max - min) * t;
    }

    float toPos(float v) {
        if (this == TEMP) {
            float a = 1e6f / min, b = 1e6f / max;
            return ((1e6f / v) - a) / (b - a);
        }
        return (v - min) / (max - min);
    }

    float clamp(float v) { return Math.max(Math.min(min, max), Math.min(Math.max(min, max), v)); }

    String format(float v) {
        switch (this) {
            case EXPOSURE: return String.format(Locale.US, "%+.2f EV", v);
            case TEMP: return Math.round(v / 10f) * 10 + " K";
            case ROTATE: return String.format(Locale.US, "%+.1f°", v);
            default:
                int p = Math.round(v * 100f);
                return (min < 0 && p > 0 ? "+" : "") + p;
        }
    }
}
