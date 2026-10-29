/*
 * Colour maths ported from RawTherapee 5.13 rtengine/colortemp.cc (ColorTemp::temp2mulxyz,
 * ColorTemp::temp2mul) and the dcraw/RawTherapee rgb_cam construction. GPL v3.
 */
package com.ampylink.app;

final class ColorMath {
    private ColorMath() {}

    /** sRGBd65_xyz from rtengine/iccmatrices.h (XYZ -> linear sRGB). */
    static final double[][] SRGB_XYZ = {
        { 3.2404542, -1.5371385, -0.4985314},
        {-0.9692660,  1.8760108,  0.0415560},
        { 0.0556434, -0.2040259,  1.0572252}};
    /** linear sRGB (D65) -> XYZ. */
    static final double[][] XYZ_SRGB = {
        {0.4124564, 0.3575761, 0.1804375},
        {0.2126729, 0.7151522, 0.0721750},
        {0.0193339, 0.1191920, 0.9503041}};

    /** ColorTemp::temp2mulxyz -> {X/Y, Z/Y}. Daylight locus >4000K exactly as RT; below, a Planckian
     *  locus approximation (Kim et al. 2002) replaces RT's spectral blackbody integration. */
    static double[] temp2xz(double temp) {
        double x, y;
        if (temp <= 4000) {
            double t = temp, t2 = t * t, t3 = t2 * t;
            x = -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910;
            if (temp <= 2222) y = -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683;
            else y = -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
        } else {
            if (temp <= 7000) x = -4.6070e9 / (temp * temp * temp) + 2.9678e6 / (temp * temp) + 0.09911e3 / temp + 0.244063;
            else x = -2.0064e9 / (temp * temp * temp) + 1.9018e6 / (temp * temp) + 0.24748e3 / temp + 0.237040;
            y = -3.0 * x * x + 2.87 * x - 0.275;
        }
        return new double[]{x / y, (1.0 - x - y) / y};
    }

    /** ColorTemp::temp2mul: RGB of the illuminant's white (green divided by 'green' factor). */
    static double[] temp2mul(double temp, double green) {
        double[] xz = temp2xz(temp);
        double r = SRGB_XYZ[0][0] * xz[0] + SRGB_XYZ[0][1] + SRGB_XYZ[0][2] * xz[1];
        double g = SRGB_XYZ[1][0] * xz[0] + SRGB_XYZ[1][1] + SRGB_XYZ[1][2] * xz[1];
        double b = SRGB_XYZ[2][0] * xz[0] + SRGB_XYZ[2][1] + SRGB_XYZ[2][2] * xz[1];
        g /= green;
        double m = Math.max(r, Math.max(g, b));
        return new double[]{r / m, g / m, b / m};
    }

    /** Per-channel gain that neutralises an illuminant of 'temp' (K) relative to D65; tint -1..1 (+ = magenta). */
    static float[] wbGain(float temp, float tint) {
        double[] ref = temp2mul(6504, 1.0);
        double[] cur = temp2mul(temp, Math.pow(2.0, -tint * 0.45));
        double r = ref[0] / cur[0], g = ref[1] / cur[1], b = ref[2] / cur[2];
        double l = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return new float[]{(float) (r / l), (float) (g / l), (float) (b / l)};
    }

    static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
            for (int k = 0; k < 3; k++) r[i][j] += a[i][k] * b[k][j];
        return r;
    }

    static double[][] inv(double[][] m) {
        double a = m[0][0], b = m[0][1], c = m[0][2], d = m[1][0], e = m[1][1], f = m[1][2], g = m[2][0], h = m[2][1], i = m[2][2];
        double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        double det = a * A + b * B + c * C;
        return new double[][]{
            {A / det, -(b * i - c * h) / det, (b * f - c * e) / det},
            {B / det, (a * i - c * g) / det, -(a * f - c * d) / det},
            {C / det, -(a * h - b * g) / det, (a * e - b * d) / det}};
    }

    /** dcraw/RT: cam_rgb = cam_xyz * xyz_rgb, rows normalised to 1, rgb_cam = inverse(cam_rgb). */
    static float[] camToSrgb(double[] colorMatrix /* XYZ->cam, row major 9 */) {
        double[][] camXyz = new double[3][3];
        for (int i = 0; i < 9; i++) camXyz[i / 3][i % 3] = colorMatrix[i];
        double[][] camRgb = mul(camXyz, XYZ_SRGB);
        for (int i = 0; i < 3; i++) {
            double s = camRgb[i][0] + camRgb[i][1] + camRgb[i][2];
            if (Math.abs(s) > 1e-9) for (int j = 0; j < 3; j++) camRgb[i][j] /= s;
        }
        double[][] r = inv(camRgb);
        // GLSL mat3 is column-major
        return new float[]{
            (float) r[0][0], (float) r[1][0], (float) r[2][0],
            (float) r[0][1], (float) r[1][1], (float) r[2][1],
            (float) r[0][2], (float) r[1][2], (float) r[2][2]};
    }

    static final float[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};
}
