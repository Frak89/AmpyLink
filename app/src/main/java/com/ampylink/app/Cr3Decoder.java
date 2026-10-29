/*
 * Canon CR3 reader for AmpyRaw.
 *
 * Java port of RawTherapee 5.13 rtengine/canon_cr3_decoder.cc (itself adapted from ART and
 * LibRaw's libraw_crxdec.cpp: Copyright (C) 2018-2019 Alexey Danilchenko, 2019 Alex Tutubalin,
 * LibRaw LLC) plus the Canon ColorData / SensorInfo makernote handling from RawTherapee's dcraw.cc
 * and colour matrices from RawTherapee's camconst.json / dcraw adobe_coeff.
 *
 * AmpyRaw is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, version 3 or later.
 *
 * Decodes the CRX (wavelet / Golomb-Rice) compressed Bayer data of the full-size raw track
 * into a 16-bit CFA plane that the GPU pipeline demosaics exactly like a DNG.
 */
package com.ampylink.app;

final class Cr3Decoder {

    static final class Cr3Exception extends Exception {
        Cr3Exception(String m) { super(m); }
    }

    private Cr3Decoder() {}

    // ===================================================================== container

    private static final byte[] UUID_CANON = {
        (byte) 0x85, (byte) 0xc0, (byte) 0xb6, (byte) 0x87, (byte) 0x82, 0x0f, 0x11, (byte) 0xe0,
        (byte) 0x81, 0x11, (byte) 0xf4, (byte) 0xce, 0x46, 0x2b, 0x6a, 0x48};

    /** CMP1 header of one CRAW track (crx_data_header_t). */
    static final class Header {
        int version, fWidth, fHeight, tileWidth, tileHeight, nBits, nPlanes, cfaLayout,
            encType, imageLevels, hasTileCols, hasTileRows, mdatHdrSize, medianBits;
        int mediaType; long mediaSize, mediaOffset;
        boolean valid; String format = "";
    }

    /** Everything we pull from the file. */
    static final class Info {
        final Header[] tracks = new Header[16];
        int nTrack = -1;
        String model = "";
        int orientation = 1;
        float[] camMul;           // R G B as-shot multipliers
        float black = -1, white = -1;
        int sensLeft = -1, sensTop = -1, sensRight = -1, sensBottom = -1;
    }

    static boolean isCr3(byte[] b) {
        return b.length > 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p'
            && b[8] == 'c' && b[9] == 'r' && b[10] == 'x' && b[11] == ' ';
    }

    private static int be2(byte[] b, long o) { int i = (int) o; return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); }
    private static long be4(byte[] b, long o) { int i = (int) o; return ((long) (b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF); }
    private static String fourcc(byte[] b, long o) { int i = (int) o; return new String(new char[]{(char) (b[i] & 0xFF), (char) (b[i + 1] & 0xFF), (char) (b[i + 2] & 0xFF), (char) (b[i + 3] & 0xFF)}); }

    static Info parse(byte[] b) throws Cr3Exception {
        Info info = new Info();
        walk(b, 0, b.length, "", info, 0);
        // Canon timed metadata track: records 7/8 carry makernotes with ColorData (WB, levels)
        for (int i = 0; i <= info.nTrack && i < info.tracks.length; i++) {
            Header h = info.tracks[i];
            if (h != null && "CTMD".equals(h.format) && h.mediaOffset > 0 && h.mediaSize > 0) parseCtmd(b, h, info);
        }
        return info;
    }

    private static void walk(byte[] b, long start, long end, String path, Info info, int depth) throws Cr3Exception {
        if (depth > 31) return;
        long o = start;
        while (o + 8 <= end) {
            long size = be4(b, o);
            String name = fourcc(b, o + 4);
            long content = o + 8;
            if (size == 1) { size = (be4(b, o + 8) << 32) | be4(b, o + 12); content = o + 16; }
            else if (size == 0) size = end - o;
            if (size < 8 || o + size > end) return;
            long cEnd = o + size;
            String p = path + name;
            switch (name) {
                case "moov": case "mdia": case "minf": case "stbl":
                    walk(b, content, cEnd, p, info, depth + 1); break;
                case "trak":
                    info.nTrack++;
                    if (info.nTrack >= info.tracks.length) return;
                    info.tracks[info.nTrack] = new Header();
                    walk(b, content, cEnd, p, info, depth + 1);
                    break;
                case "uuid":
                    if (p.equals("moovuuid") && matches(b, content, UUID_CANON)) walk(b, content + 16, cEnd, p, info, depth + 1);
                    break;
                case "CMT1": parseCmt1(b, content, info); break;
                case "CMT3": parseCmt3(b, content, cEnd, info); break;
                case "stsd": {                                  // version/flags + entry count, then sample entries
                    Header h = cur(info);
                    if (h != null && cEnd - content >= 16) h.format = fourcc(b, content + 12);
                    walk(b, content + 8, cEnd, p, info, depth + 1);
                    break;
                }
                case "CRAW": walk(b, content + 82, cEnd, p, info, depth + 1); break;
                case "CMP1": {
                    Header h = cur(info);
                    if (h != null && cEnd - content >= 40) {
                        parseCmp1(b, content, (int) Math.min(85, cEnd - content), h);
                        if (h.valid) h.mediaType = 1;
                    }
                    break;
                }
                case "JPEG": { Header h = cur(info); if (h != null && h.mediaType == 0) h.mediaType = 2; break; }
                case "stsz": {
                    Header h = cur(info);
                    long cs = cEnd - content;
                    if (h != null) h.mediaSize = cs == 12 ? be4(b, content + 4) : cs == 16 ? be4(b, content + 12) : 0;
                    break;
                }
                case "co64": {
                    Header h = cur(info);
                    if (h != null && cEnd - content == 16) h.mediaOffset = (be4(b, content + 8) << 32) | be4(b, content + 12);
                    break;
                }
                default: break;
            }
            o = cEnd;
        }
    }

    private static Header cur(Info i) { return i.nTrack >= 0 && i.nTrack < i.tracks.length ? i.tracks[i.nTrack] : null; }

    private static boolean matches(byte[] b, long o, byte[] m) {
        if (o + m.length > b.length) return false;
        for (int i = 0; i < m.length; i++) if (b[(int) o + i] != m[i]) return false;
        return true;
    }

    /** DCraw::crxParseImageHeader */
    private static void parseCmp1(byte[] b, long o, int size, Header h) {
        h.version = be2(b, o + 4);
        h.fWidth = (int) be4(b, o + 8);
        h.fHeight = (int) be4(b, o + 12);
        h.tileWidth = (int) be4(b, o + 16);
        h.tileHeight = (int) be4(b, o + 20);
        h.nBits = b[(int) o + 24] & 0xFF;
        h.nPlanes = (b[(int) o + 25] & 0xFF) >> 4;
        h.cfaLayout = b[(int) o + 25] & 0xF;
        h.encType = (b[(int) o + 26] & 0xFF) >> 4;
        h.imageLevels = b[(int) o + 26] & 0xF;
        h.hasTileCols = (b[(int) o + 27] & 0xFF) >> 7;
        h.hasTileRows = ((b[(int) o + 27] & 0xFF) >> 6) & 1;
        h.mdatHdrSize = (int) be4(b, o + 28);
        int extHeader = (b[(int) o + 32] & 0xFF) >> 7;
        int useMedianBits = 0;
        h.medianBits = h.nBits;
        if (extHeader != 0 && size >= 57 && h.nPlanes == 4) useMedianBits = ((b[(int) o + 56] & 0xFF) >> 6) & 1;
        if (useMedianBits != 0 && size >= 85) h.medianBits = b[(int) o + 84] & 0xFF;

        h.valid = false;
        if ((h.version != 0x100 && h.version != 0x200) || h.mdatHdrSize == 0) return;
        if (h.encType == 1) { if (h.nBits > 15) return; }
        else { if (h.encType != 0 && h.encType != 3) return; if (h.nBits > 14) return; }
        if (h.nPlanes == 1) { if (h.cfaLayout != 0 || h.encType != 0 || h.nBits != 8) return; }
        else if (h.nPlanes != 4 || (h.fWidth & 1) != 0 || (h.fHeight & 1) != 0 || (h.tileWidth & 1) != 0
            || (h.tileHeight & 1) != 0 || h.cfaLayout > 3 || h.nBits == 8) return;
        if (h.tileWidth > h.fWidth || h.tileHeight > h.fHeight) return;
        if (h.imageLevels > 3 || h.hasTileCols > 1 || h.hasTileRows > 1) return;
        h.valid = true;
    }

    // --- TIFF helpers for CMT1 / CMT3 (offsets relative to box content)
    private static final class T {
        final byte[] b; final long base; final boolean le;
        T(byte[] b, long base) { this.b = b; this.base = base; le = b[(int) base] == 'I'; }
        int u2(long off) { int i = (int) (base + off); return le ? ((b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8)) : (((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF)); }
        long u4(long off) { int i = (int) (base + off); return le
            ? ((b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) | ((b[i + 2] & 0xFF) << 16) | ((long) (b[i + 3] & 0xFF) << 24))
            : (((long) (b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF)); }
    }

    private static void parseCmt1(byte[] b, long c, Info info) {
        try {
            T t = new T(b, c);
            long ifd = t.u4(4);
            int n = t.u2(ifd);
            for (int i = 0; i < n; i++) {
                long e = ifd + 2 + 12L * i;
                int tag = t.u2(e), type = t.u2(e + 2); long cnt = t.u4(e + 4);
                long val = (cnt * (type == 3 ? 2 : 1) <= 4) ? e + 8 : t.u4(e + 8);
                if (tag == 0x110 && type == 2) {
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < cnt; k++) { int ch = b[(int) (c + val + k)] & 0xFF; if (ch == 0) break; sb.append((char) ch); }
                    info.model = sb.toString().trim();
                } else if (tag == 0x112) {
                    info.orientation = t.u2(val);
                }
            }
        } catch (RuntimeException ignored) {}
    }

    private static long le4(byte[] b, long o) { int i = (int) o; return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) | ((b[i + 2] & 0xFF) << 16) | ((long) (b[i + 3] & 0xFF) << 24); }
    private static int le2(byte[] b, long o) { int i = (int) o; return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8); }

    /** DCraw::parseCR3 CTMD branch. */
    private static void parseCtmd(byte[] b, Header h, Info info) {
        long base = h.mediaOffset, size = Math.min(h.mediaSize, b.length - base);
        long rel = 0;
        try {
            while (rel + 6 < size) {
                long szItem = le4(b, base + rel);
                int type = le2(b, base + rel + 4);
                if (szItem == 0 || rel + szItem > size) return;
                if (type == 7 || type == 8 || type == 9) {
                    long box = rel + 12;
                    while (box + 8 < rel + szItem) {
                        long lTag = le4(b, base + box), tag = le4(b, base + box + 4);
                        if (lTag < 8 || box + lTag > rel + szItem) return;
                        if (tag == 0x927C && (type == 7 || type == 8)) {
                            long mn = base + box + 8;
                            if (b[(int) mn] == b[(int) mn + 1] && (b[(int) mn] == 'I' || b[(int) mn] == 'M'))
                                parseCmt3(b, mn, base + box + lTag, info);
                        }
                        box += lTag;
                    }
                }
                rel += szItem;
            }
        } catch (RuntimeException ignored) {}
    }

    /** Canon makernote: 0x4001 ColorData (WB, black/white levels), 0x00e0 SensorInfo (crop). */
    private static void parseCmt3(byte[] b, long c, long end, Info info) {
        try {
            T t = new T(b, c);
            long ifd = t.u4(4);
            int n = t.u2(ifd);
            for (int i = 0; i < n; i++) {
                long e = ifd + 2 + 12L * i;
                int tag = t.u2(e), type = t.u2(e + 2); long cnt = t.u4(e + 4);
                long sz = cnt * (type == 3 || type == 8 ? 2 : type == 4 ? 4 : 1);
                long val = sz <= 4 ? e + 8 : t.u4(e + 8);
                if (c + val + sz > end) continue;
                if (tag == 0x00e0 && cnt >= 9) {
                    info.sensLeft = t.u2(val + 5 * 2); info.sensTop = t.u2(val + 6 * 2);
                    info.sensRight = t.u2(val + 7 * 2); info.sensBottom = t.u2(val + 8 * 2);
                } else if (tag == 0x4001 && cnt > 500) {
                    colorData(t, val, (int) cnt, info);
                }
            }
        } catch (RuntimeException ignored) {}
    }

    private static void colorData(T t, long s, int len, Info info) {
        int wbOff = -1, blackOff = -1, black2Off = -1, whiteOff = -1;
        switch (len) {
            case 1560: case 1592: case 1353: case 1602: {
                int sub = t.u2(s);
                wbOff = 0x3f;
                if (sub == 14) { blackOff = 0x22c; whiteOff = 0x230; } else { blackOff = 0x30a; whiteOff = 0x30e; }
                break;
            }
            case 1820: case 1824: case 1816: wbOff = 0x47; blackOff = 0x318; black2Off = 0x149; whiteOff = 0x31c; break;
            case 2024: case 3656: wbOff = 0x55; blackOff = 0x326; black2Off = 0x157; whiteOff = 0x32a; break;
            case 3973: case 3778: wbOff = 0x69; black2Off = 0x69 + 0x102; blackOff = 0x69 + 0x213; whiteOff = 0x69 + 0x217; break;
            default:
                // newer bodies (R5 II, R1, R6 III …): LibRaw places WB_RGGBLevelsAsShot at 0x69 as well
                if (len > 3600) { wbOff = 0x69; black2Off = 0x69 + 0x102; blackOff = 0x69 + 0x213; whiteOff = 0x69 + 0x217; }
        }
        if (wbOff >= 0) {
            float r = t.u2(s + 2L * wbOff), g = t.u2(s + 2L * wbOff + 2), bl = t.u2(s + 2L * wbOff + 6);
            if (r > 0 && g > 0 && bl > 0) info.camMul = new float[]{r, g, bl};
        }
        float avg = 0;
        if (blackOff >= 0) { for (int k = 0; k < 4; k++) avg += t.u2(s + 2L * (blackOff + k)); avg /= 4; }
        if (avg <= 0 && black2Off >= 0) { avg = 0; for (int k = 0; k < 4; k++) avg += t.u2(s + 2L * (black2Off + k)); avg /= 4; }
        if (avg > 0 && avg < 4096) info.black = avg;
        if (whiteOff >= 0) {
            int normal = t.u2(s + 2L * whiteOff), specular = t.u2(s + 2L * whiteOff + 2);
            int w = specular > 0 ? specular : normal;
            if (w > 1000) info.white = w;
        }
    }

    // ===================================================================== public entry

    /** Full (uncropped) CFA frame of the selected raw track. */
    static short[] decodeRaw(byte[] file, Header sel) throws Cr3Exception {
        short[] raw = new short[sel.fWidth * sel.fHeight];
        new Crx(file, sel, raw).load();
        return raw;
    }

    static Header selectTrack(Info info) {
        Header sel = null; long best = 0;
        for (int i = 0; i <= info.nTrack && i < info.tracks.length; i++) {
            Header h = info.tracks[i];
            if (h != null && h.mediaType == 1 && h.valid) {
                long bits = (long) h.nBits * h.fWidth * h.fHeight;
                if (bits > best) { best = bits; sel = h; }
            }
        }
        return sel;
    }

    /** Decode a CR3 into AmpyRaw's CFA ImageData (cropped to the active sensor area). */
    static RawDecoder.ImageData decode(byte[] file, int maxTex) throws Cr3Exception {
        Info info = parse(file);
        Header sel = null; long best = 0;
        for (int i = 0; i <= info.nTrack && i < info.tracks.length; i++) {
            Header h = info.tracks[i];
            if (h != null && h.mediaType == 1 && h.valid) {
                long bits = (long) h.nBits * h.fWidth * h.fHeight;
                if (bits > best) { best = bits; sel = h; }
            }
        }
        if (sel == null || sel.mediaOffset <= 0 || sel.mediaSize <= 0) throw new Cr3Exception("No CRX raw track");
        if (sel.nPlanes != 4) throw new Cr3Exception("Unsupported CR3 layout (" + sel.nPlanes + " planes)");
        int rawW = sel.fWidth, rawH = sel.fHeight;
        short[] raw = decodeRaw(file, sel);

        // ---- crop to active area (SensorInfo); fall back to full frame
        int l = 0, tp = 0, r = rawW - 1, bt = rawH - 1;
        if (info.sensRight > info.sensLeft && info.sensBottom > info.sensTop && info.sensRight < rawW && info.sensBottom < rawH) {
            l = info.sensLeft; tp = info.sensTop; r = info.sensRight; bt = info.sensBottom;
        }
        // black level: makernote, else average of the left masked border
        float black = info.black;
        if (black <= 0 && l > 8) {
            double s = 0; long n = 0;
            for (int y = tp; y <= bt; y += 7) for (int x = 2; x < l - 2; x++) { s += raw[y * rawW + x] & 0xFFFF; n++; }
            black = n > 0 ? (float) (s / n) : 0;
        }
        int w = (r - l + 1) & ~1, h = (bt - tp + 1) & ~1;
        short[] crop = new short[w * h];
        for (int y = 0; y < h; y++) System.arraycopy(raw, (y + tp) * rawW + l, crop, y * w, w);

        int[] base;
        switch (sel.cfaLayout) {
            case 1: base = new int[]{1, 0, 2, 1}; break; // G R / B G
            case 2: base = new int[]{1, 2, 0, 1}; break; // G B / R G
            case 3: base = new int[]{2, 1, 1, 0}; break; // B G / G R
            default: base = new int[]{0, 1, 1, 2};       // R G / G B
        }
        int[] pat = new int[4];
        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++)
            pat[y * 2 + x] = base[((y + tp) & 1) * 2 + ((x + l) & 1)];

        RawDecoder.ImageData d = new RawDecoder.ImageData();
        d.cfa = crop; d.rawW = w; d.rawH = h; d.cfaPattern = pat;
        float bl = Math.max(black, 0);
        d.black = new float[]{bl, bl, bl, bl};
        d.white = info.white > 0 ? info.white : (1 << sel.nBits) - 1;
        if (info.white > 0) { // sanity check the makernote white level against the data (C-RAW overshoot / unknown layouts)
            long above = 0;
            for (int i = 0; i < crop.length; i += 7) if ((crop[i] & 0xFFFF) > d.white) above++;
            if (above * 7 > crop.length / 500) d.white = (1 << sel.nBits) - 1;
        }
        if (info.camMul != null) d.wb = new float[]{info.camMul[0] / info.camMul[1], 1f, info.camMul[2] / info.camMul[1]};
        String key = info.model.replace("Canon EOS ", "").trim();
        double[] m = matrixFor(key);
        boolean exact = exactMatrix(key);
        if (m != null) d.cam2rgb = ColorMath.camToSrgb(m);
        d.orientation = (info.orientation == 3 || info.orientation == 6 || info.orientation == 8) ? info.orientation : 1;
        d.half = (long) w * h > 26_000_000L || Math.max(w, h) > maxTex;
        d.source = "CR3 RAW · " + (info.model.isEmpty() ? "CANON" : info.model.toUpperCase()) + (exact ? "" : " (MATRIX≈)") + " · GPU " + (d.half ? "SUPERPIXEL" : "DEMOSAIC");
        return d;
    }

    // colour matrices (XYZ->camera ×10000, D65) from RawTherapee camconst.json / dcraw adobe_coeff
    private static final String[][] MATRICES = {
        {"R6 Mark II", "9539,-2795,-1224,-4175,11998,2458,-465,1755,6048"},
        {"R6m2", "9539,-2795,-1224,-4175,11998,2458,-465,1755,6048"},
        {"R6", "8293,-1611,-1132,-4759,12710,2275,-1013,2415,5508"},
        {"R5", "9766,-2953,-1254,-4276,12116,2433,-437,1336,5131"},
        {"R3", "9423,-2839,-1195,-4532,12377,2415,-483,1374,5276"},
        {"R50", "9269,-2012,-1107,-3990,11762,2527,-569,2093,4913"},
        {"R100", "8230,-1515,-1032,-4179,12005,2454,-649,2076,4711"},
        {"R10", "9269,-2012,-1107,-3990,11762,2527,-569,2093,4913"},
        {"R8", "9539,-2795,-1224,-4175,11998,2458,-465,1755,6048"},
        {"R7", "10424,-3138,-1300,-4221,11938,2584,-547,1658,6183"},
        {"RP", "8608,-2097,-1178,-5425,13265,2383,-1149,2238,5680"},
        {"Ra", "22880,-11531,-2223,-2034,10469,1809,316,1401,5769"},
        {"R", "8293,-1789,-1094,-5025,12925,2327,-1199,2769,6108"},
        {"M6 Mark II", "11498,-3759,-1516,-5073,12954,2349,-892,1867,6118"},
        {"M50 Mark II", "10463,-2173,-1437,-4856,12635,2482,-1216,2915,7237"},
        {"M50", "8532,-701,-1167,-4095,11879,2508,-797,2424,7010"},
        {"M200", "10463,-2173,-1437,-4856,12635,2482,-1216,2915,7237"},
        {"90D", "11498,-3759,-1516,-5073,12954,2349,-892,1867,6118"},
        {"850D", "9079,-1923,-1236,-4677,12454,2492,-922,2319,5565"},
        {"Rebel T8i", "9079,-1923,-1236,-4677,12454,2492,-922,2319,5565"},
        {"250D", "9079,-1923,-1236,-4677,12454,2492,-922,2319,5565"},
        {"Rebel SL3", "9079,-1923,-1236,-4677,12454,2492,-922,2319,5565"},
    };

    private static boolean exactMatrix(String model) {
        for (String[] e : MATRICES) if (e[0].equals(model)) return true;
        return false;
    }

    static double[] matrixFor(String model) {
        String[] hit = null;
        for (String[] e : MATRICES) if (e[0].equals(model)) { hit = e; break; }
        if (hit == null) for (String[] e : MATRICES) // longest prefix, e.g. "R5 Mark II" -> "R5"
            if (model.startsWith(e[0]) && (hit == null || e[0].length() > hit[0].length())) hit = e;
        if (hit == null) hit = MATRICES[3]; // EOS R5 as a generic modern Canon sensor
        String[] p = hit[1].split(",");
        double[] m = new double[9];
        for (int i = 0; i < 9; i++) m[i] = Integer.parseInt(p[i].trim()) / 10000.0;
        return m;
    }

    // ===================================================================== CRX codec

    private static final int E_RIGHT = 1, E_LEFT = 2, E_BOTTOM = 4, E_TOP = 8;

    private static final int[] EX_COEF = {
        1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0,
        0, 1, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 1, 0, 1, 0,
        0, 0, 1, 2, 2, 1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 0, 1, 0, 0, 0, 1, 2, 2,
        1, 0, 0, 1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0, 1, 0, 1, 0, 1, 2, 2, 2, 2, 1, 1, 1,
        1, 2, 2, 1, 1, 1, 1, 2, 2, 1, 1, 0, 1, 1, 1, 1, 1, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1};
    private static final int[] Q_STEP_TBL = {0x28, 0x2D, 0x33, 0x39, 0x40, 0x48};
    private static final int[] JS = {
        0x0001, 0x0001, 0x0001, 0x0001, 0x0002, 0x0002, 0x0002, 0x0002,
        0x0004, 0x0004, 0x0004, 0x0004, 0x0008, 0x0008, 0x0008, 0x0008,
        0x0010, 0x0010, 0x0020, 0x0020, 0x0040, 0x0040, 0x0080, 0x0080,
        0x0100, 0x0200, 0x0400, 0x0800, 0x1000, 0x2000, 0x4000, 0x8000};
    private static final int[] J = {
        0x0, 0x0, 0x0, 0x0, 0x1, 0x1, 0x1, 0x1, 0x2, 0x2, 0x2,
        0x2, 0x3, 0x3, 0x3, 0x3, 0x4, 0x4, 0x5, 0x5, 0x6, 0x6,
        0x7, 0x7, 0x8, 0x9, 0xA, 0xB, 0xC, 0xD, 0xE, 0xF};

    private static int lim(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** Bit reader over [pos, end) of the file buffer (CrxBitstream). */
    private static final class Bits {
        final byte[] d; int pos; final int end; int bitData; int bitsLeft;
        Bits(byte[] d, long off, long size) {
            this.d = d; pos = (int) Math.min(off, d.length); end = (int) Math.min(off + size, d.length);
        }
        private long word(int p) { return ((long) (d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF); }

        int zeros() {
            int result;
            if (bitData != 0) {
                int nz = 31 - Integer.numberOfLeadingZeros(bitData);
                result = 31 - nz;
                bitData <<= 32 - nz;
                bitsLeft -= 32 - nz;
                return result;
            }
            int left = bitsLeft;
            long next = 0;
            while (true) {
                while (pos + 4 <= end) {
                    next = word(pos); pos += 4;
                    if (next != 0) {
                        int nz = 63 - Long.numberOfLeadingZeros(next);
                        result = left + 31 - nz;
                        bitData = (int) (next << (32 - nz));
                        bitsLeft = nz;
                        return result;
                    }
                    left += 32;
                }
                if (end < pos + 1) break;
                next = d[pos++] & 0xFF;
                if (next != 0) break;
                left += 8;
            }
            if (next == 0) throw new IllegalStateException("CRX bitstream underrun");
            int nz = 63 - Long.numberOfLeadingZeros(next);
            result = left + 7 - nz;
            bitData = (int) (next << (32 - nz));
            bitsLeft = nz;
            return result;
        }

        int bits(int bits) {
            int left = bitsLeft, data = bitData;
            if (left < bits) {
                if (pos + 4 <= end) {
                    int w = (int) word(pos); pos += 4;
                    bitsLeft = 32 - (bits - left);
                    int res = ((w >>> left) | data) >>> (32 - bits);
                    bitData = w << (bits - left);
                    return res;
                }
                do {
                    if (pos >= end) break;
                    left += 8;
                    int nb = d[pos++] & 0xFF;
                    data |= nb << (32 - left);
                } while (left < bits);
            }
            int res = data >>> (32 - bits);
            bitData = data << bits;
            bitsLeft = left - bits;
            return res;
        }
    }

    private static int predictK(int prevK, int bitCode, int maxVal) {
        int k = prevK - (bitCode < ((1 << prevK) >> 1) ? 1 : 0) + ((bitCode >> prevK) > 2 ? 1 : 0) + ((bitCode >> prevK) > 5 ? 1 : 0);
        return (maxVal == 0 || k < maxVal) ? k : maxVal;
    }

    /** CrxBandParam: line buffers live in pd[] (paramData) and np[] (nonProgrData); lb0/lb1/lb2 are indices. */
    private static final class BandParam {
        Bits bs; int width, height, roundedBitsMask, roundedBits, curLine;
        int[] pd, np; int lb0, lb1, lb2; int sParam, kParam; boolean supportsPartial;
    }

    private static final class Subband {
        BandParam bp; long mdatOffset; int bandBuf; int width, height;
        int qParam, kParam, qStepBase; int qStepMult; boolean supportsPartial;
        long dataSize; long dataOffset; int rowStartAddOn, rowEndAddOn, colStartAddOn, colEndAddOn, levelShift;
    }

    private static final class Wavelet {
        int sb0, sb1, sb2, sb3; final int[] lineBuf = new int[8];
        int curLine, curH, fltTapH, height, width;
    }

    private static final class Comp {
        int[] mem; Subband[] bands; Wavelet[] wt; int compNumber; long dataOffset; long compSize;
        boolean supportsPartial; int roundedBitsMask; int tileFlag;
    }

    private static final class QStep { int[] tbl; int off, width, height; }

    private static final class Tile {
        Comp[] comps; int tileFlag, tileNumber; long dataOffset, tileSize; int width, height;
        boolean hasQPData; QStep[] qStep; long mdatQPDataSize; int mdatExtraSize;
    }

    /** One CRX image decode (CrxImage). */
    private static final class Crx {
        final byte[] f; final Header hdr; final short[] out;
        int nPlanes, planeWidth, planeHeight, samplePrecision, medianBits, subbandCount, levels, nBits, encType, tileCols, tileRows;
        Tile[] tiles; long mdatOffset, mdatSize; final int[] outBase = new int[4]; short[] planeBuf;

        Crx(byte[] f, Header h, short[] out) { this.f = f; this.hdr = h; this.out = out; }

        void load() throws Cr3Exception {
            int fw = hdr.fWidth, fh = hdr.fHeight, tw = hdr.tileWidth, th = hdr.tileHeight;
            if (hdr.nPlanes == 4) { fw >>= 1; fh >>= 1; tw >>= 1; th >>= 1; }
            int hs = hdr.mdatHdrSize;
            byte[] hdrBuf = new byte[hs * 2];
            System.arraycopy(f, (int) hdr.mediaOffset, hdrBuf, 0, (int) Math.min(hs, f.length - hdr.mediaOffset));
            if (!setupImageData(fw, fh, tw, th, hdrBuf, hs * 2)) throw new Cr3Exception("CRX header error");
            // the 4 CFA planes are independent bitstreams -> decode them in parallel (like RT's OpenMP loop)
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, Math.min(nPlanes, Runtime.getRuntime().availableProcessors())));
            try {
                java.util.List<java.util.concurrent.Future<Boolean>> res = new java.util.ArrayList<>();
                for (int p = 0; p < nPlanes; p++) { final int plane = p; res.add(pool.submit(() -> decodePlane(plane))); }
                for (int p = 0; p < nPlanes; p++) if (!res.get(p).get()) throw new Cr3Exception("CRX decode error (plane " + p + ")");
            } catch (java.util.concurrent.ExecutionException e) {
                throw new Cr3Exception("CRX decode error: " + e.getCause());
            } catch (InterruptedException e) {
                throw new Cr3Exception("CRX decode interrupted");
            } finally { pool.shutdownNow(); }
            if (encType == 3) for (int i = 0; i < planeHeight; i++) convertPlaneLineE3(i);
        }

        // ---------------------------------------------------------- setup

        boolean setupImageData(int fw, int fh, int tw, int th, byte[] hdrBuf, int hdrSize) {
            final boolean[] incr = {false, false, false, false, false, false, false, false, false, true, true, false, false, false, true, false};
            planeWidth = fw; planeHeight = fh;
            if (tw < 0x16 || th < 0x16 || planeWidth > 0x7FFF || planeHeight > 0x7FFF) return false;
            tileCols = (planeWidth + tw - 1) / tw;
            tileRows = (planeHeight + th - 1) / th;
            if (planeWidth - tw * (tileCols - 1) < 0x16 || planeHeight - th * (tileRows - 1) < 0x16) return false;
            levels = hdr.imageLevels;
            subbandCount = 3 * levels + 1;
            nPlanes = hdr.nPlanes;
            nBits = hdr.nBits;
            encType = hdr.encType;
            int ii = 4 * hdr.encType + 2;
            samplePrecision = hdr.nBits + (ii < incr.length && incr[ii] ? 1 : 0) + 1;
            mdatOffset = hdr.mediaOffset + hdr.mdatHdrSize;
            mdatSize = hdr.mediaSize;
            medianBits = hdr.medianBits;
            if (encType == 3 && nPlanes == 4 && nBits > 8) planeBuf = new short[planeHeight * planeWidth * nPlanes];
            int rowSize = 2 * planeWidth;
            if (nPlanes == 1) outBase[0] = 0;
            else switch (hdr.cfaLayout) {
                case 0: outBase[0] = 0; outBase[1] = 1; outBase[2] = rowSize; outBase[3] = rowSize + 1; break;
                case 1: outBase[1] = 0; outBase[0] = 1; outBase[3] = rowSize; outBase[2] = rowSize + 1; break;
                case 2: outBase[2] = 0; outBase[3] = 1; outBase[0] = rowSize; outBase[1] = rowSize + 1; break;
                case 3: outBase[3] = 0; outBase[2] = 1; outBase[1] = rowSize; outBase[0] = rowSize + 1; break;
            }
            return readImageHeaders(tw, th, hdrBuf, hdrSize);
        }

        private int sget(byte[] b, int o, int n) { int r = 0; for (int i = 0; i < n; i++) r = (r << 8) | (b[o + i] & 0xFF); return r; }

        boolean readImageHeaders(int tw, int th, byte[] p, int hdrSize) {
            int nTiles = tileRows * tileCols;
            if (nTiles == 0) return false;
            tiles = new Tile[nTiles];
            for (int t = 0; t < nTiles; t++) {
                Tile tile = new Tile(); tiles[t] = tile;
                tile.tileNumber = t;
                if ((t + 1) % tileCols != 0) {
                    tile.width = tw;
                    if (tileCols > 1) { tile.tileFlag = E_RIGHT; if (t % tileCols != 0) tile.tileFlag |= E_LEFT; }
                } else {
                    tile.width = planeWidth - tw * (tileCols - 1);
                    if (tileCols > 1) tile.tileFlag = E_LEFT;
                }
                if (t < nTiles - tileCols) {
                    tile.height = th;
                    if (tileRows > 1) { tile.tileFlag |= E_BOTTOM; if (t >= tileCols) tile.tileFlag |= E_TOP; }
                } else {
                    tile.height = planeHeight - th * (tileRows - 1);
                    if (tileRows > 1) tile.tileFlag |= E_TOP;
                }
                tile.comps = new Comp[nPlanes];
                for (int c = 0; c < nPlanes; c++) {
                    Comp comp = new Comp(); tile.comps[c] = comp;
                    comp.compNumber = c; comp.supportsPartial = true; comp.tileFlag = tile.tileFlag;
                    comp.bands = new Subband[subbandCount];
                    for (int b = 0; b < subbandCount; b++) { Subband s = new Subband(); s.qParam = 4; comp.bands[b] = s; }
                }
            }
            long tileOffset = 0;
            int dataSize = hdrSize, ptr = 0;
            for (int t = 0; t < nTiles; t++) {
                Tile tile = tiles[t];
                if (dataSize < 4) return false;
                int sign = sget(p, ptr, 2), hsz = sget(p, ptr + 2, 2);
                if ((sign != 0xFF01 || hsz != 8) && (sign != 0xFF11 || (hsz != 8 && hsz != 16))) return false;
                if (dataSize < hsz + 4) return false;
                int tail = sget(p, ptr + 10, 2);
                if ((hsz == 8 && tail != 0) || (hsz == 16 && tail != 0x4000)) return false;
                if (sget(p, ptr + 8, 2) != t) return false;
                dataSize -= hsz + 4;
                tile.tileSize = sget(p, ptr + 4, 4) & 0xFFFFFFFFL;
                tile.dataOffset = tileOffset;
                if (hsz == 16) {
                    if (sget(p, ptr + 18, 2) != 0) return false;
                    tile.hasQPData = true;
                    tile.mdatQPDataSize = sget(p, ptr + 12, 4) & 0xFFFFFFFFL;
                    tile.mdatExtraSize = sget(p, ptr + 16, 2);
                }
                ptr += hsz + 4;
                tileOffset += tile.tileSize;
                long compOffset = 0;
                for (int c = 0; c < nPlanes; c++) {
                    Comp comp = tile.comps[c];
                    if (dataSize < 0xC) return false;
                    sign = sget(p, ptr, 2); hsz = sget(p, ptr + 2, 2);
                    if ((sign != 0xFF02 && sign != 0xFF12) || hsz != 8) return false;
                    if (c != ((p[ptr + 8] & 0xFF) >> 4)) return false;
                    if (sget(p, ptr + 9, 3) != 0) return false;
                    comp.compSize = sget(p, ptr + 4, 4) & 0xFFFFFFFFL;
                    int roundedBits = ((p[ptr + 8] & 0xFF) >> 1) & 3;
                    comp.supportsPartial = (p[ptr + 8] & 8) != 0;
                    comp.dataOffset = compOffset;
                    comp.tileFlag = tile.tileFlag;
                    compOffset += comp.compSize;
                    dataSize -= 0xC; ptr += 0xC;
                    comp.roundedBitsMask = 0;
                    if (roundedBits != 0) {
                        if (levels != 0 || !comp.supportsPartial) return false;
                        comp.roundedBitsMask = 1 << (roundedBits - 1);
                    }
                    int[] st = readSubbandHeaders(tile, comp, p, ptr, dataSize);
                    if (st == null) return false;
                    ptr = st[0]; dataSize = st[1];
                }
            }
            if (hdr.version != 0x200) return true;
            for (Tile tile : tiles) {
                if (!tile.hasQPData) continue;
                Bits bs = new Bits(f, mdatOffset + tile.dataOffset, tile.mdatQPDataSize);
                int qpWidth = (tile.width >> 3) + ((tile.width & 7) != 0 ? 1 : 0);
                int qpHeight = (tile.height >> 1) + (tile.height & 1);
                int totalQP = qpHeight * qpWidth;
                int[] qp = new int[totalQP + 2 * (qpWidth + 2)];
                int cur = 0, lineBuf = totalQP;
                int[] kParam = {0};
                for (int r = 0; r < qpHeight; r++) {
                    int l0 = (r & 1) != 0 ? lineBuf + qpWidth + 2 : lineBuf;
                    int l1 = (r & 1) != 0 ? lineBuf : lineBuf + qpWidth + 2;
                    if (r != 0) golombNormal(bs, qpWidth, qp, l0, l1, kParam);
                    else golombTop(bs, qpWidth, qp, l1, kParam);
                    for (int c = 0; c < qpWidth; c++) qp[cur++] = qp[l1 + c + 1] + 4;
                }
                if (!makeQStep(tile, qp)) return false;
            }
            return true;
        }

        private int readQP(Bits bs, int k) {
            int q = bs.zeros();
            if (q >= 23) q = bs.bits(8);
            else if (k != 0) q = bs.bits(k) | (q << k);
            return q;
        }

        private void golombTop(Bits bs, int width, int[] a, int lb, int[] k) {
            a[lb] = 0;
            while (width-- > 0) {
                a[lb + 1] = a[lb];
                int q = readQP(bs, k[0]);
                a[lb + 1] += -(q & 1) ^ (q >>> 1);
                k[0] = predictK(k[0], q, 7);
                lb++;
            }
            a[lb + 1] = a[lb] + 1;
        }

        private static int prediction(int left, int top, int deltaH, int deltaV) {
            int[] symb = {left + deltaH, left + deltaH, left, top};
            return symb[(((deltaV < 0) ^ (deltaH < 0)) ? 2 : 0) + (((left < top) ^ (deltaH < 0)) ? 1 : 0)];
        }

        private void golombNormal(Bits bs, int width, int[] a, int l0, int l1, int[] k) {
            a[l1] = a[l0 + 1];
            int deltaH = a[l0 + 1] - a[l0];
            while (width-- > 0) {
                a[l1 + 1] = prediction(a[l1], a[l0 + 1], deltaH, a[l0] - a[l1]);
                int q = readQP(bs, k[0]);
                a[l1 + 1] += -(q & 1) ^ (q >>> 1);
                if (width != 0) {
                    deltaH = a[l0 + 2] - a[l0 + 1];
                    k[0] = predictK(k[0], (q + 2 * Math.abs(deltaH)) >> 1, 7);
                    l0++;
                } else k[0] = predictK(k[0], q, 7);
                l1++;
            }
            a[l1 + 1] = a[l1] + 1;
        }

        private int qv(int v) {
            if (v / 6 >= 6) return Q_STEP_TBL[Math.floorMod(v, 6)] * (1 << (v / 6 + 26));
            return Q_STEP_TBL[Math.floorMod(v, 6)] >> (6 - v / 6);
        }

        boolean makeQStep(Tile tile, int[] qpTable) {
            if (levels > 3 || levels < 1) return false;
            int qpWidth = (tile.width >> 3) + ((tile.width & 7) != 0 ? 1 : 0);
            int qpHeight = (tile.height >> 1) + (tile.height & 1);
            int qpHeight4 = (tile.height >> 2) + ((tile.height & 3) != 0 ? 1 : 0);
            int qpHeight8 = (tile.height >> 3) + ((tile.height & 7) != 0 ? 1 : 0);
            int total = qpHeight + (levels > 1 ? qpHeight4 : 0) + (levels > 2 ? qpHeight8 : 0);
            int[] tbl = new int[total * qpWidth];
            tile.qStep = new QStep[levels];
            int t = 0, qi = 0, q = 0;
            if (levels == 3) {
                QStep s = new QStep(); s.tbl = tbl; s.off = t; s.width = qpWidth; s.height = qpHeight8; tile.qStep[qi++] = s;
                for (int r = 0; r < qpHeight8; r++) {
                    int r0 = qpWidth * Math.min(4 * r, qpHeight - 1), r1 = qpWidth * Math.min(4 * r + 1, qpHeight - 1);
                    int r2 = qpWidth * Math.min(4 * r + 2, qpHeight - 1), r3 = qpWidth * Math.min(4 * r + 3, qpHeight - 1);
                    for (int c = 0; c < qpWidth; c++, t++) {
                        int v = qpTable[r0++] + qpTable[r1++] + qpTable[r2++] + qpTable[r3++];
                        v = ((v < 0 ? 3 : 0) + v) >> 2;
                        tbl[t] = qv(v);
                    }
                }
            }
            if (levels >= 2) {
                QStep s = new QStep(); s.tbl = tbl; s.off = t; s.width = qpWidth; s.height = qpHeight4; tile.qStep[qi++] = s;
                for (int r = 0; r < qpHeight4; r++) {
                    int r0 = qpWidth * Math.min(2 * r, qpHeight - 1), r1 = qpWidth * Math.min(2 * r + 1, qpHeight - 1);
                    for (int c = 0; c < qpWidth; c++, t++) tbl[t] = qv((qpTable[r0++] + qpTable[r1++]) / 2);
                }
            }
            QStep s = new QStep(); s.tbl = tbl; s.off = t; s.width = qpWidth; s.height = qpHeight; tile.qStep[qi] = s;
            for (int r = 0; r < qpHeight; r++) for (int c = 0; c < qpWidth; c++, t++, q++) tbl[t] = qv(qpTable[q]);
            return true;
        }

        private void setupIdx(Subband band, int level, int colStart, int bandWidthEx, int rowStart, int bandHeightEx) {
            if (hdr.version == 0x200) {
                band.rowStartAddOn = rowStart; band.rowEndAddOn = bandHeightEx;
                band.colStartAddOn = colStart; band.colEndAddOn = bandWidthEx;
                band.levelShift = 3 - level;
            } else {
                band.rowStartAddOn = band.rowEndAddOn = band.colStartAddOn = band.colEndAddOn = band.levelShift = 0;
            }
        }

        /** returns {ptr, dataSize} or null */
        int[] readSubbandHeaders(Tile tile, Comp comp, byte[] p, int ptr, int mdatSize) {
            int bi = subbandCount - 1;
            Subband[] bands = comp.bands;
            int bandHeight = tile.height, bandWidth = tile.width;
            int bandWidthExCoef = 0, bandHeightExCoef = 0;
            if (levels != 0) {
                int rowEx = 0x30 * (levels - 1) + 6 * (tile.width & 7);
                int colEx = 0x30 * (levels - 1) + 6 * (tile.height & 7);
                for (int level = 0; level < levels; level++) {
                    int wOdd = bandWidth & 1, hOdd = bandHeight & 1;
                    bandWidth = (wOdd + bandWidth) >> 1;
                    bandHeight = (hOdd + bandHeight) >> 1;
                    int bwe0 = 0, bwe1 = 0, bhe0 = 0, bhe1 = 0, colStart = 0, rowStart = 0;
                    if ((tile.tileFlag & E_RIGHT) != 0) { bwe0 = EX_COEF[rowEx + 2 * level]; bwe1 = EX_COEF[rowEx + 2 * level + 1]; }
                    if ((tile.tileFlag & E_LEFT) != 0) { ++bwe0; colStart = 1; }
                    if ((tile.tileFlag & E_BOTTOM) != 0) { bhe0 = EX_COEF[colEx + 2 * level]; bhe1 = EX_COEF[colEx + 2 * level + 1]; }
                    if ((tile.tileFlag & E_TOP) != 0) { ++bhe0; rowStart = 1; }
                    bands[bi].width = bandWidth + bwe0 - wOdd;
                    bands[bi].height = bandHeight + bhe0 - hOdd;
                    setupIdx(bands[bi], level + 1, colStart, bwe0 - colStart, rowStart, bhe0 - rowStart);
                    bands[bi - 1].width = bandWidth + bwe1;
                    bands[bi - 1].height = bandHeight + bhe0 - hOdd;
                    setupIdx(bands[bi - 1], level + 1, 0, bwe1, rowStart, bhe0 - rowStart);
                    bands[bi - 2].width = bandWidth + bwe0 - wOdd;
                    bands[bi - 2].height = bandHeight + bhe1;
                    setupIdx(bands[bi - 2], level + 1, colStart, bwe0 - colStart, 0, bhe1);
                    bi -= 3;
                }
                if ((tile.tileFlag & E_RIGHT) != 0) bandWidthExCoef = EX_COEF[rowEx + 2 * levels - 1];
                if ((tile.tileFlag & E_BOTTOM) != 0) bandHeightExCoef = EX_COEF[colEx + 2 * levels - 1];
            }
            bands[bi].width = bandWidthExCoef + bandWidth;
            bands[bi].height = bandHeightExCoef + bandHeight;
            if (levels != 0) setupIdx(bands[bi], levels, 0, bandWidthExCoef, 0, bandHeightExCoef);

            long subbandOffset = 0;
            for (int cs = 0; cs < subbandCount; cs++) {
                Subband band = bands[cs];
                if (mdatSize < 4) return null;
                int sign = sget(p, ptr, 2), hsz = sget(p, ptr + 2, 2);
                if (mdatSize < hsz + 4) return null;
                if ((sign != 0xFF03 || hsz != 8) && (sign != 0xFF13 || hsz != 16)) return null;
                long size = sget(p, ptr + 4, 4) & 0xFFFFFFFFL;
                if (cs != ((p[ptr + 8] & 0xF0) >> 4)) { band.dataSize = size; return null; }
                band.dataOffset = subbandOffset;
                band.kParam = 0; band.bp = null;
                if (sign == 0xFF03) {
                    long bitData = sget(p, ptr + 8, 4) & 0xFFFFFFFFL;
                    band.dataSize = size - (bitData & 0x7FFFF);
                    band.supportsPartial = (bitData & 0x8000000) != 0;
                    band.qParam = (int) ((bitData >> 19) & 0xFF);
                    band.qStepBase = 0; band.qStepMult = 0;
                } else {
                    if ((sget(p, ptr + 8, 2) & 0xFFF) != 0) return null;
                    if (sget(p, ptr + 18, 2) != 0) return null;
                    band.supportsPartial = false;
                    band.qParam = 0;
                    band.dataSize = size - sget(p, ptr + 16, 2);
                    band.qStepBase = sget(p, ptr + 12, 4);
                    band.qStepMult = sget(p, ptr + 10, 2);
                }
                subbandOffset += size;
                ptr += hsz + 4;
                mdatSize -= hsz + 4;
            }
            return new int[]{ptr, mdatSize};
        }

        boolean setupSubbandData(Comp comp, Tile tile, long mdatOff) {
            int toSub = 3 * levels + 1;
            Subband[] sb = comp.bands;
            int size = 0;
            for (int i = 0; i < toSub; i++) size += sb[i].width;
            int waveletOff = size + 8; // gap (C stores the wavelet structs here)
            int total = waveletOff;
            if (levels != 0) {
                for (int level = 0; level < levels; level++) {
                    if (level < levels - 1) total += 8 * sb[3 * (level + 1) + 2].width;
                    else total += 8 * tile.width;
                }
            }
            comp.mem = new int[total + 16];
            long base = mdatOffset + mdatOff;
            int buf = 0;
            for (int i = 0; i < toSub; i++) {
                sb[i].bandBuf = buf;
                buf += sb[i].width;
                sb[i].mdatOffset = base + sb[i].dataOffset;
            }
            if (levels != 0) {
                comp.wt = new Wavelet[levels];
                int pd = waveletOff;
                for (int level = 0; level < levels; level++) {
                    Wavelet w = new Wavelet(); comp.wt[level] = w;
                    int band = 3 * level + 1;
                    int tw;
                    if (level >= levels - 1) { w.height = tile.height; tw = tile.width; }
                    else { w.height = sb[band + 3].height; tw = sb[band + 4].width; }
                    w.width = tw;
                    for (int k = 0; k < 8; k++) w.lineBuf[k] = pd + k * tw;
                    w.curLine = 0; w.curH = 0; w.fltTapH = 0;
                    w.sb1 = sb[band].bandBuf; w.sb2 = sb[band + 1].bandBuf; w.sb3 = sb[band + 2].bandBuf;
                    pd += 8 * tw;
                }
                comp.wt[0].sb0 = sb[0].bandBuf;
            }
            for (int i = 0; i < toSub; i++) {
                if (sb[i].dataSize != 0) {
                    boolean partial = false; int rbm = 0;
                    if (comp.supportsPartial && i == 0) { rbm = comp.roundedBitsMask; partial = true; }
                    BandParam bp = new BandParam();
                    bp.pd = new int[2 * sb[i].width + 4];
                    bp.np = partial ? null : new int[sb[i].width + 2];
                    bp.width = sb[i].width; bp.height = sb[i].height;
                    bp.roundedBitsMask = rbm; bp.supportsPartial = partial;
                    bp.bs = new Bits(f, sb[i].mdatOffset, sb[i].dataSize);
                    sb[i].bp = bp;
                }
            }
            return true;
        }

        // ---------------------------------------------------------- line decoding

        private int code(BandParam p) {
            int bc = p.bs.zeros();
            if (bc >= 41) bc = p.bs.bits(21);
            else if (p.kParam != 0) bc = p.bs.bits(p.kParam) | (bc << p.kParam);
            return bc;
        }

        private int runLength(BandParam p, int length) {
            // returns nSyms (>=1) or -1 on error; caller already consumed the first '1' bit
            int nSyms = 1;
            while (p.bs.bits(1) != 0) {
                nSyms += JS[p.sParam];
                if (nSyms > length) { nSyms = length; break; }
                if (p.sParam < 31) ++p.sParam;
                if (nSyms == length) break;
            }
            if (nSyms < length) {
                if (J[p.sParam] != 0) nSyms += p.bs.bits(J[p.sParam]);
                if (p.sParam > 0) --p.sParam;
                if (nSyms > length) return -1;
            }
            return nSyms;
        }

        private void symbolL1(BandParam p, boolean median, boolean notEOL) {
            int[] a = p.pd;
            if (median) {
                int delta = a[p.lb0 + 1] - a[p.lb0];
                int[] symb = {delta + a[p.lb1], delta + a[p.lb1], a[p.lb1], a[p.lb0 + 1]};
                a[p.lb1 + 1] = symb[(((a[p.lb0] < a[p.lb1]) ^ (delta < 0)) ? 2 : 0) + (((a[p.lb1] < a[p.lb0 + 1]) ^ (delta < 0)) ? 1 : 0)];
            } else a[p.lb1 + 1] = a[p.lb0 + 1];
            int bc = code(p);
            a[p.lb1 + 1] += -(bc & 1) ^ (bc >>> 1);
            if (notEOL) {
                int nd = (a[p.lb0 + 2] - a[p.lb0 + 1]) << 1;
                bc = (bc + Math.abs(nd)) >>> 1;
                ++p.lb0;
            }
            p.kParam = predictK(p.kParam, bc, 15);
            ++p.lb1;
        }

        private boolean decodeLineNormal(BandParam p) {
            int[] a = p.pd;
            int length = p.width;
            a[p.lb1] = a[p.lb0 + 1];
            for (; length > 1; --length) {
                if (a[p.lb1] != a[p.lb0 + 1] || a[p.lb1] != a[p.lb0 + 2]) {
                    symbolL1(p, true, true);
                } else {
                    if (p.bs.bits(1) != 0) {
                        int nSyms = runLength(p, length);
                        if (nSyms < 0) return false;
                        length -= nSyms;
                        p.lb0 += nSyms;
                        while (nSyms-- > 0) { a[p.lb1 + 1] = a[p.lb1]; ++p.lb1; }
                    }
                    if (length > 0) symbolL1(p, false, length > 1);
                }
            }
            if (length == 1) symbolL1(p, true, false);
            a[p.lb1 + 1] = a[p.lb1] + 1;
            return true;
        }

        private void symbolL1Rounded(BandParam p, boolean doSym, boolean doCode) {
            int[] a = p.pd;
            int sym = a[p.lb0 + 1];
            if (doSym) {
                int dH = a[p.lb0 + 1] - a[p.lb0];
                int[] symb = {dH + a[p.lb1], dH + a[p.lb1], a[p.lb1], a[p.lb0 + 1]};
                sym = symb[(((a[p.lb0] < a[p.lb1]) ^ (dH < 0)) ? 2 : 0) + (((a[p.lb1] < a[p.lb0 + 1]) ^ (dH < 0)) ? 1 : 0)];
            }
            int bc = code(p);
            int c = -(bc & 1) ^ (bc >>> 1);
            a[p.lb1 + 1] = p.roundedBitsMask * 2 * c + (c >> 31) + sym;
            if (doCode) {
                if (a[p.lb0 + 2] > a[p.lb0 + 1]) c = (a[p.lb0 + 2] - a[p.lb0 + 1] + p.roundedBitsMask - 1) >> p.roundedBits;
                else c = -((a[p.lb0 + 1] - a[p.lb0 + 2] + p.roundedBitsMask) >> p.roundedBits);
                p.kParam = predictK(p.kParam, (bc + 2 * Math.abs(c)) >>> 1, 15);
            } else p.kParam = predictK(p.kParam, bc, 15);
            ++p.lb1;
        }

        private boolean decodeLineRounded(BandParam p) {
            int[] a = p.pd;
            boolean reached = false;
            a[p.lb0] = a[p.lb0 + 1];
            a[p.lb1] = a[p.lb0 + 1];
            int length = p.width;
            for (; length > 1; --length) {
                if (Math.abs(a[p.lb0 + 2] - a[p.lb0 + 1]) > p.roundedBitsMask) {
                    symbolL1Rounded(p, true, true); ++p.lb0; reached = true;
                } else if (reached || Math.abs(a[p.lb0] - a[p.lb1]) > p.roundedBitsMask) {
                    symbolL1Rounded(p, true, true); ++p.lb0; reached = false;
                } else {
                    int nSyms = 0;
                    if (p.bs.bits(1) != 0) {
                        nSyms = 1;
                        while (p.bs.bits(1) != 0) {
                            nSyms += JS[p.sParam];
                            if (nSyms > length) { nSyms = length; break; }
                            if (p.sParam < 31) ++p.sParam;
                            if (nSyms == length) break;
                        }
                        if (nSyms < length) {
                            if (J[p.sParam] != 0) nSyms += p.bs.bits(J[p.sParam]);
                            if (p.sParam > 0) --p.sParam;
                        }
                        if (nSyms > length) return false;
                    }
                    length -= nSyms;
                    p.lb0 += nSyms;
                    while (nSyms-- > 0) { a[p.lb1 + 1] = a[p.lb1]; ++p.lb1; }
                    if (length > 1) {
                        symbolL1Rounded(p, false, true); ++p.lb0;
                        reached = Math.abs(a[p.lb0 + 1] - a[p.lb0]) > p.roundedBitsMask;
                    } else if (length == 1) symbolL1Rounded(p, false, false);
                }
            }
            if (length == 1) symbolL1Rounded(p, true, false);
            a[p.lb1 + 1] = a[p.lb1] + 1;
            return true;
        }

        private boolean decodeLineNoRef(BandParam p) {
            int[] a = p.pd, n2 = p.np;
            int b0 = p.lb0, b1 = p.lb1, b2 = p.lb2;
            int i = 0;
            for (; i < p.width - 1; ++i) {
                if (a[b0 + i + 2] != 0 || a[b0 + i + 1] != 0 || a[b1 + i] != 0) {
                    int bc = code(p);
                    a[b1 + i + 1] = -(bc & 1) ^ (bc >>> 1);
                    p.kParam = predictK(p.kParam, bc, 0);
                    if (n2[b2 + i + 1] - p.kParam <= 1) { if (p.kParam >= 15) p.kParam = 15; }
                    else ++p.kParam;
                } else {
                    int nSyms = 0;
                    if (p.bs.bits(1) != 0) {
                        nSyms = 1;
                        if (i != p.width - 1) {
                            while (p.bs.bits(1) != 0) {
                                nSyms += JS[p.sParam];
                                if (i + nSyms > p.width) { nSyms = p.width - i; break; }
                                if (p.sParam < 31) ++p.sParam;
                                if (i + nSyms == p.width) break;
                            }
                            if (i + nSyms < p.width) {
                                if (J[p.sParam] != 0) nSyms += p.bs.bits(J[p.sParam]);
                                if (p.sParam > 0) --p.sParam;
                            }
                            if (i + nSyms > p.width) return false;
                        }
                    } else if (i > p.width) return false;
                    if (nSyms > 0) {
                        for (int k = 0; k < nSyms; k++) { a[b1 + i + 1 + k] = 0; n2[b2 + i + k] = 0; }
                        i += nSyms;
                    }
                    if (i >= p.width - 1) {
                        if (i == p.width - 1) {
                            int bc = code(p);
                            a[b1 + i + 1] = -((bc + 1) & 1) ^ ((bc + 1) >>> 1);
                            p.kParam = predictK(p.kParam, bc, 15);
                            n2[b2 + i] = p.kParam;
                        }
                        continue;
                    } else {
                        int bc = code(p);
                        a[b1 + i + 1] = -((bc + 1) & 1) ^ ((bc + 1) >>> 1);
                        p.kParam = predictK(p.kParam, bc, 0);
                        if (n2[b2 + i + 1] - p.kParam <= 1) { if (p.kParam >= 15) p.kParam = 15; }
                        else ++p.kParam;
                    }
                }
                n2[b2 + i] = p.kParam;
            }
            if (i == p.width - 1) {
                int bc = code(p);
                a[b1 + i + 1] = -(bc & 1) ^ (bc >>> 1);
                p.kParam = predictK(p.kParam, bc, 15);
                n2[b2 + i] = p.kParam;
            }
            return true;
        }

        private boolean decodeTopLine(BandParam p) {
            int[] a = p.pd;
            a[p.lb1] = 0;
            int length = p.width;
            for (; length > 1; --length) {
                if (a[p.lb1] != 0) a[p.lb1 + 1] = a[p.lb1];
                else {
                    if (p.bs.bits(1) != 0) {
                        int nSyms = runLength(p, length);
                        if (nSyms < 0) return false;
                        length -= nSyms;
                        while (nSyms-- > 0) { a[p.lb1 + 1] = a[p.lb1]; ++p.lb1; }
                        if (length <= 0) break;
                    }
                    a[p.lb1 + 1] = 0;
                }
                int bc = code(p);
                a[p.lb1 + 1] += -(bc & 1) ^ (bc >>> 1);
                p.kParam = predictK(p.kParam, bc, 15);
                ++p.lb1;
            }
            if (length == 1) {
                a[p.lb1 + 1] = a[p.lb1];
                int bc = code(p);
                a[p.lb1 + 1] += -(bc & 1) ^ (bc >>> 1);
                p.kParam = predictK(p.kParam, bc, 15);
                ++p.lb1;
            }
            a[p.lb1 + 1] = a[p.lb1] + 1;
            return true;
        }

        private boolean decodeTopLineRounded(BandParam p) {
            int[] a = p.pd;
            a[p.lb1] = 0;
            int length = p.width;
            for (; length > 1; --length) {
                if (Math.abs(a[p.lb1]) > p.roundedBitsMask) a[p.lb1 + 1] = a[p.lb1];
                else {
                    int nSyms = 0;
                    if (p.bs.bits(1) != 0) {
                        nSyms = runLength(p, length);
                        if (nSyms < 0) return false;
                    }
                    length -= nSyms;
                    while (nSyms-- > 0) { a[p.lb1 + 1] = a[p.lb1]; ++p.lb1; }
                    if (length <= 0) break;
                    a[p.lb1 + 1] = 0;
                }
                int bc = code(p);
                int sVal = -(bc & 1) ^ (bc >>> 1);
                a[p.lb1 + 1] += p.roundedBitsMask * 2 * sVal + (sVal >> 31);
                p.kParam = predictK(p.kParam, bc, 15);
                ++p.lb1;
            }
            if (length == 1) {
                int bc = code(p);
                int sVal = -(bc & 1) ^ (bc >>> 1);
                a[p.lb1 + 1] += p.roundedBitsMask * 2 * sVal + (sVal >> 31);
                p.kParam = predictK(p.kParam, bc, 15);
                ++p.lb1;
            }
            a[p.lb1 + 1] = a[p.lb1] + 1;
            return true;
        }

        private boolean decodeTopLineNoRef(BandParam p) {
            int[] a = p.pd, n2 = p.np;
            a[p.lb0] = 0; a[p.lb1] = 0;
            int length = p.width;
            for (; length > 1; --length) {
                if (a[p.lb1] != 0) {
                    int bc = code(p);
                    a[p.lb1 + 1] = -(bc & 1) ^ (bc >>> 1);
                    p.kParam = predictK(p.kParam, bc, 15);
                } else {
                    int nSyms = 0;
                    if (p.bs.bits(1) != 0) {
                        nSyms = runLength(p, length);
                        if (nSyms < 0) return false;
                    }
                    length -= nSyms;
                    while (nSyms-- > 0) { n2[p.lb2] = 0; a[p.lb1 + 1] = 0; ++p.lb1; ++p.lb2; }
                    if (length <= 0) break;
                    int bc = code(p);
                    a[p.lb1 + 1] = -((bc + 1) & 1) ^ ((bc + 1) >>> 1);
                    p.kParam = predictK(p.kParam, bc, 15);
                }
                n2[p.lb2] = p.kParam;
                ++p.lb2; ++p.lb1;
            }
            if (length == 1) {
                int bc = code(p);
                a[p.lb1 + 1] = -(bc & 1) ^ (bc >>> 1);
                p.kParam = predictK(p.kParam, bc, 15);
                n2[p.lb2] = p.kParam;
                ++p.lb1;
            }
            a[p.lb1 + 1] = 0;
            return true;
        }

        /** crxDecodeLine(param, bandBuf): decoded line copied to dst[dstOff..] */
        private boolean decodeLine(BandParam p, int[] dst, int dstOff) {
            if (p == null || p.curLine >= p.height) return false;
            int lineLength = p.width + 2;
            int src;
            if (p.curLine == 0) {
                p.sParam = 0; p.kParam = 0;
                if (p.supportsPartial) {
                    if (p.roundedBitsMask <= 0) {
                        p.lb0 = 0; p.lb1 = lineLength; src = p.lb1 + 1;
                        if (!decodeTopLine(p)) return false;
                    } else {
                        p.roundedBits = 1;
                        if ((p.roundedBitsMask & ~1) != 0) while ((p.roundedBitsMask >> p.roundedBits) != 0) ++p.roundedBits;
                        p.lb0 = 0; p.lb1 = lineLength; src = p.lb1 + 1;
                        if (!decodeTopLineRounded(p)) return false;
                    }
                } else {
                    p.lb2 = 0; p.lb0 = 0; p.lb1 = lineLength; src = p.lb1 + 1;
                    if (!decodeTopLineNoRef(p)) return false;
                }
            } else {
                if ((p.curLine & 1) != 0) { p.lb1 = 0; p.lb0 = lineLength; }
                else { p.lb0 = 0; p.lb1 = lineLength; }
                src = p.lb1 + 1;
                if (!p.supportsPartial) {
                    p.lb2 = 0;
                    if (!decodeLineNoRef(p)) return false;
                } else if (p.roundedBitsMask <= 0) {
                    if (!decodeLineNormal(p)) return false;
                } else {
                    if (!decodeLineRounded(p)) return false;
                }
            }
            System.arraycopy(p.pd, src, dst, dstOff, p.width);
            ++p.curLine;
            return true;
        }

        /** uint32 (tbl * mult) >> 3, as in C */
        private static int qmul(int tbl, long mult) { return (int) ((((tbl & 0xFFFFFFFFL) * mult) & 0xFFFFFFFFL) >>> 3); }
        private static int clampIdx(int i, int max) { return i < 0 ? 0 : (i > max ? max : i); }

        private static int subbandRow(Subband b, int row) {
            return row < b.rowStartAddOn ? 0
                : (row < b.height - b.rowEndAddOn ? row - b.rowEndAddOn : b.height - b.rowEndAddOn - b.rowStartAddOn - 1);
        }

        private boolean decodeLineIQ(Comp comp, Subband sb, QStep q) {
            int[] m = comp.mem;
            if (sb.dataSize == 0) { java.util.Arrays.fill(m, sb.bandBuf, sb.bandBuf + sb.width, 0); return true; }
            if (sb.supportsPartial && q == null) {
                int bc = sb.bp.bs.zeros();
                if (bc >= 23) bc = sb.bp.bs.bits(8);
                else if (sb.kParam != 0) bc = sb.bp.bs.bits(sb.kParam) | (bc << sb.kParam);
                sb.qParam += -(bc & 1) ^ (bc >>> 1);
                sb.kParam = predictK(sb.kParam, bc, 0);
                if (sb.kParam > 7) return false;
            }
            if (!decodeLine(sb.bp, m, sb.bandBuf)) return false;
            if (sb.width <= 0) return true;
            int bb = sb.bandBuf;
            if (q != null) {
                // same table walk as the C code (tables are contiguous), only guarded against running off the array
                int tp = q.off + q.width * subbandRow(sb, sb.bp.curLine - 1);
                int maxIdx = q.tbl.length - 1;
                long mult = sb.qStepMult & 0xFFFFFFFFL;
                for (int i = 0; i < sb.colStartAddOn; ++i)
                    m[bb + i] *= lim(sb.qStepBase + qmul(q.tbl[clampIdx(tp, maxIdx)], mult), 1, 0x168000);
                for (int i = sb.colStartAddOn; i < sb.width - sb.colEndAddOn; ++i)
                    m[bb + i] *= lim(sb.qStepBase + qmul(q.tbl[clampIdx(tp + ((i - sb.colStartAddOn) >> sb.levelShift), maxIdx)], mult), 1, 0x168000);
                int last = (sb.width - sb.colEndAddOn - sb.colStartAddOn - 1) >> sb.levelShift;
                for (int i = sb.width - sb.colEndAddOn; i < sb.width; ++i)
                    m[bb + i] *= lim(sb.qStepBase + qmul(q.tbl[clampIdx(tp + last, maxIdx)], mult), 1, 0x168000);
            } else {
                int qs = qv(sb.qParam);
                if (qs != 1) for (int i = 0; i < sb.width; ++i) m[bb + i] *= qs;
            }
            return true;
        }

        // ---------------------------------------------------------- inverse 5/3 wavelet

        private void horizontal53(int[] m, int la, int lbB, Wavelet w, int tileFlag) {
            int b0 = w.sb0, b1 = w.sb1, b2 = w.sb2, b3 = w.sb3;
            if (w.width <= 1) { m[la] = m[b0]; m[lbB] = m[b2]; return; }
            if ((tileFlag & E_LEFT) != 0) {
                m[la] = m[b0] - ((m[b1] + m[b1 + 1] + 2) >> 2);
                m[lbB] = m[b2] - ((m[b3] + m[b3 + 1] + 2) >> 2);
                ++b1; ++b3;
            } else {
                m[la] = m[b0] - ((m[b1] + 1) >> 1);
                m[lbB] = m[b2] - ((m[b3] + 1) >> 1);
            }
            ++b0; ++b2;
            for (int i = 0; i < w.width - 3; i += 2) {
                int d = m[b0] - ((m[b1] + m[b1 + 1] + 2) >> 2);
                m[la + 1] = m[b1] + ((d + m[la]) >> 1); m[la + 2] = d;
                d = m[b2] - ((m[b3] + m[b3 + 1] + 2) >> 2);
                m[lbB + 1] = m[b3] + ((d + m[lbB]) >> 1); m[lbB + 2] = d;
                ++b0; ++b1; ++b2; ++b3; la += 2; lbB += 2;
            }
            if ((tileFlag & E_RIGHT) != 0) {
                int dA = m[b0] - ((m[b1] + m[b1 + 1] + 2) >> 2);
                m[la + 1] = m[b1] + ((dA + m[la]) >> 1);
                int dB = m[b2] - ((m[b3] + m[b3 + 1] + 2) >> 2);
                m[lbB + 1] = m[b3] + ((dB + m[lbB]) >> 1);
                if ((w.width & 1) != 0) { m[la + 2] = dA; m[lbB + 2] = dB; }
            } else if ((w.width & 1) != 0) {
                m[la + 1] = m[b1] + ((m[la] + m[b0] - ((m[b1] + 1) >> 1)) >> 1);
                m[la + 2] = m[b0] - ((m[b1] + 1) >> 1);
                m[lbB + 1] = m[b3] + ((m[lbB] + m[b2] - ((m[b3] + 1) >> 1)) >> 1);
                m[lbB + 2] = m[b2] - ((m[b3] + 1) >> 1);
            } else {
                m[la + 1] = m[la] + m[b1];
                m[lbB + 1] = m[lbB] + m[b3];
            }
        }

        /** horizontal pass for one L line (band0/band1 pair) into dst */
        private void lPass(int[] m, int dst, int b0, int b1, int width, int tileFlag, boolean rightWritesDelta) {
            if (width <= 1) { m[dst] = m[b0]; return; }
            if ((tileFlag & E_LEFT) != 0) { m[dst] = m[b0] - ((m[b1] + m[b1 + 1] + 2) >> 2); ++b1; }
            else m[dst] = m[b0] - ((m[b1] + 1) >> 1);
            ++b0;
            for (int i = 0; i < width - 3; i += 2) {
                int d = m[b0] - ((m[b1] + m[b1 + 1] + 2) >> 2);
                m[dst + 1] = m[b1] + ((m[dst] + d) >> 1);
                m[dst + 2] = d;
                ++b0; ++b1; dst += 2;
            }
            if ((tileFlag & E_RIGHT) != 0) {
                int d = m[b0] - ((m[b1] + m[b1 + 1] + 2) >> 2);
                m[dst + 1] = m[b1] + ((m[dst] + d) >> 1);
                if (rightWritesDelta || (width & 1) != 0) m[dst + 2] = d;
            } else if ((width & 1) != 0) {
                int d = m[b0] - ((m[b1] + 1) >> 1);
                m[dst + 1] = m[b1] + ((m[dst] + d) >> 1);
                m[dst + 2] = d;
            } else {
                m[dst + 1] = m[b1] + m[dst];
            }
        }

        private int getLine(Comp c, int level) {
            Wavelet w = c.wt[level];
            int r = w.lineBuf[(w.fltTapH - w.curH + 5) % 5 + 3];
            --w.curH;
            return r;
        }

        private boolean filterDecode(Comp c, int level, QStep[] q) {
            Wavelet w = c.wt[level];
            if (w.curH != 0) return true;
            int sb = 3 * level;
            QStep ql = q != null ? q[level] : null;
            Subband[] B = c.bands;
            if (w.height - 3 <= w.curLine && (c.tileFlag & E_BOTTOM) == 0) {
                if ((w.height & 1) != 0) {
                    if (level != 0) { if (!filterDecode(c, level - 1, q)) return false; }
                    else if (!decodeLineIQ(c, B[sb], ql)) return false;
                    if (!decodeLineIQ(c, B[sb + 1], ql)) return false;
                }
            } else {
                if (level != 0) { if (!filterDecode(c, level - 1, q)) return false; }
                else if (!decodeLineIQ(c, B[sb], ql)) return false;
                if (!decodeLineIQ(c, B[sb + 1], ql) || !decodeLineIQ(c, B[sb + 2], ql) || !decodeLineIQ(c, B[sb + 3], ql)) return false;
            }
            return true;
        }

        private boolean filterTransform(Comp c, int level) {
            Wavelet w = c.wt[level];
            int[] m = c.mem;
            if (w.curH != 0) return true;
            if (w.curLine >= w.height - 3) {
                if ((c.tileFlag & E_BOTTOM) == 0) {
                    if ((w.height & 1) != 0) {
                        if (level != 0) {
                            if (c.wt[level - 1].curH == 0) if (!filterTransform(c, level - 1)) return false;
                            w.sb0 = getLine(c, level - 1);
                        }
                        int h0 = w.lineBuf[w.fltTapH + 3];
                        int h1 = w.lineBuf[(w.fltTapH + 1) % 5 + 3];
                        int h2 = w.lineBuf[(w.fltTapH + 2) % 5 + 3];
                        int l0 = w.lineBuf[0];
                        int l1 = w.lineBuf[1];
                        w.lineBuf[1] = w.lineBuf[2];
                        w.lineBuf[2] = l1;
                        lPass(m, l0, w.sb0, w.sb1, w.width, c.tileFlag, false);
                        l0 = w.lineBuf[0]; l1 = w.lineBuf[1];
                        for (int i = 0; i < w.width; ++i) {
                            int d = m[l0 + i] - ((m[l1 + i] + 1) >> 1);
                            m[h1 + i] = m[l1 + i] + ((d + m[h0 + i]) >> 1);
                            m[h2 + i] = d;
                        }
                        w.curH += 3; w.curLine += 3; w.fltTapH = (w.fltTapH + 3) % 5;
                    } else {
                        int l2 = w.lineBuf[2];
                        int h0 = w.lineBuf[w.fltTapH + 3];
                        int h1 = w.lineBuf[(w.fltTapH + 1) % 5 + 3];
                        w.lineBuf[1] = l2;
                        w.lineBuf[2] = w.lineBuf[1];
                        for (int i = 0; i < w.width; ++i) m[h1 + i] = m[h0 + i] + m[l2 + i];
                        w.curH += 2; w.curLine += 2; w.fltTapH = (w.fltTapH + 2) % 5;
                    }
                }
            } else {
                if (level != 0) {
                    if (c.wt[level - 1].curH == 0 && !filterTransform(c, level - 1)) return false;
                    w.sb0 = getLine(c, level - 1);
                }
                int l0 = w.lineBuf[0], l1 = w.lineBuf[1];
                int h0 = w.lineBuf[w.fltTapH + 3];
                int h1 = w.lineBuf[(w.fltTapH + 1) % 5 + 3];
                int h2 = w.lineBuf[(w.fltTapH + 2) % 5 + 3];
                w.lineBuf[1] = w.lineBuf[2];
                w.lineBuf[2] = l1;
                horizontal53(m, l0, l1, w, c.tileFlag);
                l0 = w.lineBuf[0]; l1 = w.lineBuf[1];
                int l2 = w.lineBuf[2];
                for (int i = 0; i < w.width; ++i) {
                    int d = m[l0 + i] - ((m[l2 + i] + m[l1 + i] + 2) >> 2);
                    m[h1 + i] = m[l1 + i] + ((d + m[h0 + i]) >> 1);
                    m[h2 + i] = d;
                }
                if (w.curLine >= w.height - 3 && (w.height & 1) != 0) {
                    w.curH += 3; w.curLine += 3; w.fltTapH = (w.fltTapH + 3) % 5;
                } else {
                    w.curH += 2; w.curLine += 2; w.fltTapH = (w.fltTapH + 2) % 5;
                }
            }
            return true;
        }

        private boolean filterInitialize(Comp c, int prevLevel, QStep[] q) {
            if (prevLevel == 0) return true;
            int[] m = c.mem;
            for (int lv = 0, band = 0; lv < prevLevel; lv++, band += 3) {
                QStep ql = q != null ? q[lv] : null;
                Wavelet w = c.wt[lv];
                Subband[] B = c.bands;
                if (lv != 0) w.sb0 = getLine(c, lv - 1);
                else if (!decodeLineIQ(c, B[band], ql)) return false;
                int h0 = w.lineBuf[w.fltTapH + 3];
                if (w.height > 1) {
                    if (!decodeLineIQ(c, B[band + 1], ql) || !decodeLineIQ(c, B[band + 2], ql) || !decodeLineIQ(c, B[band + 3], ql)) return false;
                    int l0 = w.lineBuf[0], l1 = w.lineBuf[1], l2 = w.lineBuf[2];
                    if ((c.tileFlag & E_TOP) != 0) {
                        horizontal53(m, l0, w.lineBuf[1], w, c.tileFlag);
                        if (!decodeLineIQ(c, B[band + 3], ql) || !decodeLineIQ(c, B[band + 2], ql)) return false;
                        lPass(m, l2, w.sb2, w.sb3, w.width, c.tileFlag, false);
                        for (int i = 0; i < w.width; ++i) m[h0 + i] = m[l0 + i] - ((m[l1 + i] + m[l2 + i] + 2) >> 2);
                    } else {
                        horizontal53(m, l0, w.lineBuf[2], w, c.tileFlag);
                        for (int i = 0; i < w.width; ++i) m[h0 + i] = m[l0 + i] - ((m[l2 + i] + 1) >> 1);
                    }
                    if (!filterDecode(c, lv, q) || !filterTransform(c, lv)) return false;
                } else {
                    if (!decodeLineIQ(c, B[band + 1], ql)) return false;
                    lPass(m, h0, w.sb0, w.sb1, w.width, c.tileFlag, true);
                    ++w.curLine; ++w.curH; w.fltTapH = (w.fltTapH + 1) % 5;
                }
            }
            return true;
        }

        // ---------------------------------------------------------- output

        private void convertPlaneLine(int row, int col, int plane, int[] src, int srcOff, int len) {
            if (encType == 1) {
                int off = 4 * planeWidth * row + 2 * col + outBase[plane];
                int maxV = 1 << (nBits - 1), minV = -maxV;
                for (int i = 0; i < len; i++) out[off + 2 * i] = (short) lim(src[srcOff + i], minV, maxV - 1);
            } else if (encType == 3) {
                int off = plane * planeWidth * planeHeight + planeWidth * row + col;
                for (int i = 0; i < len; i++) planeBuf[off + i] = (short) src[srcOff + i];
            } else if (nPlanes == 4) {
                int off = 4 * planeWidth * row + 2 * col + outBase[plane];
                int median = 1 << (nBits - 1), maxV = (1 << nBits) - 1;
                for (int i = 0; i < len; i++) out[off + 2 * i] = (short) lim(median + src[srcOff + i], 0, maxV);
            } else if (nPlanes == 1) {
                int off = planeWidth * row + col;
                int median = 1 << (nBits - 1), maxV = (1 << nBits) - 1;
                for (int i = 0; i < len; i++) out[off + i] = (short) lim(median + src[srcOff + i], 0, maxV);
            }
        }

        private void convertPlaneLineE3(int row) {
            if (planeBuf == null) return;
            int ps = planeWidth * planeHeight;
            int p0 = row * planeWidth, p1 = p0 + ps, p2 = p1 + ps, p3 = p2 + ps;
            int median = (1 << (medianBits - 1)) << 10;
            int maxV = (1 << medianBits) - 1;
            int line = 4 * planeWidth * row;
            for (int i = 0; i < planeWidth; i++) {
                int gr = median + (planeBuf[p0 + i] << 10) - 168 * planeBuf[p1 + i] - 585 * planeBuf[p3 + i];
                gr = gr < 0 ? -(((Math.abs(gr) + 512) >> 9) & ~1) : (((Math.abs(gr) + 512) >> 9) & ~1);
                int v = (median + (planeBuf[p0 + i] << 10) + 1510 * planeBuf[p3 + i] + 512) >> 10;
                out[line + outBase[0] + 2 * i] = (short) lim(v, 0, maxV);
                v = (planeBuf[p2 + i] + gr + 1) >> 1;
                out[line + outBase[1] + 2 * i] = (short) lim(v, 0, maxV);
                v = (gr - planeBuf[p2 + i] + 1) >> 1;
                out[line + outBase[2] + 2 * i] = (short) lim(v, 0, maxV);
                v = (median + (planeBuf[p0 + i] << 10) + 1927 * planeBuf[p1 + i] + 512) >> 10;
                out[line + outBase[3] + 2 * i] = (short) lim(v, 0, maxV);
            }
        }

        boolean decodePlane(int plane) {
            int imageRow = 0;
            for (int tr = 0; tr < tileRows; tr++) {
                int imageCol = 0;
                for (int tc = 0; tc < tileCols; tc++) {
                    Tile tile = tiles[tr * tileCols + tc];
                    Comp comp = tile.comps[plane];
                    long tileMdat = tile.dataOffset + tile.mdatQPDataSize + tile.mdatExtraSize + comp.dataOffset;
                    if (!setupSubbandData(comp, tile, tileMdat)) return false;
                    if (levels != 0) {
                        if (!filterInitialize(comp, levels, tile.qStep)) return false;
                        for (int i = 0; i < tile.height; i++) {
                            if (!filterDecode(comp, levels - 1, tile.qStep) || !filterTransform(comp, levels - 1)) return false;
                            int line = getLine(comp, levels - 1);
                            convertPlaneLine(imageRow + i, imageCol, plane, comp.mem, line, tile.width);
                        }
                    } else {
                        Subband sb = comp.bands[0];
                        if (sb.dataSize == 0) return true;
                        for (int i = 0; i < tile.height; i++) {
                            if (!decodeLine(sb.bp, comp.mem, sb.bandBuf)) return false;
                            convertPlaneLine(imageRow + i, imageCol, plane, comp.mem, sb.bandBuf, tile.width);
                        }
                    }
                    comp.mem = null; comp.bands = null; comp.wt = null; // free tile buffers
                    imageCol += tile.width;
                }
                imageRow += tiles[tr * tileCols].height;
            }
            return true;
        }
    }
}
