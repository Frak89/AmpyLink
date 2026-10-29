/*
 * AmpyRaw - GPU raw developer for Android, derived from RawTherapee 5.13.
 * Copyright (C) 2026 AmpyRaw contributors. Licensed under GPL v3 (see LICENSE).
 *
 * All pixel processing runs on the GPU (OpenGL ES 3.x, Mali-G710 on Pixel 7 Pro).
 */
package com.ampylink.app;

final class Shaders {
    private Shaders() {}

    static final String VERT =
        "#version 300 es\n" +
        "layout(location=0) in vec2 a_pos;\n" +
        "void main(){ gl_Position = vec4(a_pos,0.0,1.0); }\n";

    /* Common orientation helper: output (top-left origin) -> source coords. */
    private static final String ORIENT =
        "ivec2 srcCoord(ivec2 o, ivec2 S, int orient){\n" +
        "  if(orient==3) return ivec2(S.x-1-o.x, S.y-1-o.y);\n" +
        "  if(orient==6) return ivec2(o.y, S.y-1-o.x);\n" +
        "  if(orient==8) return ivec2(S.x-1-o.y, o.x);\n" +
        "  return o;\n" +
        "}\n";

    /*
     * Pass 1a: Bayer CFA -> linear sRGB (scene referred), on the GPU.
     * Black/white level, as-shot white balance (DNG AsShotNeutral), highlight clip,
     * Malvar-He-Cutler 5x5 demosaic (full mode) or 2x2 superpixel (half mode for 50MP),
     * camera->sRGB matrix built from DNG ColorMatrix like RawTherapee/dcraw (rgb_cam).
     */
    static final String DEMOSAIC =
        "#version 300 es\n" +
        "precision highp float; precision highp int; precision highp usampler2D;\n" +
        "uniform usampler2D u_raw;\n" +
        "uniform ivec2 u_rawSize;\n" +   // raw (source) size in CFA pixels
        "uniform int u_orient;\n" +
        "uniform int u_half;\n" +
        "uniform ivec4 u_cfa;\n" +       // color (0R 1G 2B) at (0,0),(1,0),(0,1),(1,1)
        "uniform vec4 u_black;\n" +      // black level per CFA position
        "uniform float u_white;\n" +
        "uniform vec3 u_wb;\n" +
        "uniform mat3 u_cam2rgb;\n" +
        "out vec4 o;\n" +
        ORIENT +
        "int cfaAt(ivec2 p){ int i=(p.y&1)*2+(p.x&1); return i==0?u_cfa.x:(i==1?u_cfa.y:(i==2?u_cfa.z:u_cfa.w)); }\n" +
        "float blackAt(ivec2 p){ int i=(p.y&1)*2+(p.x&1); return i==0?u_black.x:(i==1?u_black.y:(i==2?u_black.z:u_black.w)); }\n" +
        "float F(ivec2 p){ p=clamp(p,ivec2(0),u_rawSize-1); float b=blackAt(p);\n" +
        "  return max(float(texelFetch(u_raw,p,0).r)-b,0.0)/(u_white-b); }\n" +
        "vec3 finish(vec3 cam){ cam = min(cam*u_wb, vec3(1.0)); return max(u_cam2rgb*cam, vec3(0.0)); }\n" +
        "void main(){\n" +
        "  ivec2 oc = ivec2(gl_FragCoord.xy);\n" +
        "  if(u_half==1){\n" +
        "    ivec2 s = srcCoord(oc, u_rawSize/2, u_orient)*2;\n" +
        "    vec3 c=vec3(0.0); vec3 n=vec3(0.0);\n" +
        "    for(int j=0;j<2;j++) for(int i=0;i<2;i++){ ivec2 p=s+ivec2(i,j); int k=cfaAt(p); float v=F(p);\n" +
        "      if(k==0){c.r+=v;n.r+=1.0;} else if(k==1){c.g+=v;n.g+=1.0;} else {c.b+=v;n.b+=1.0;} }\n" +
        "    o = vec4(finish(c/max(n,vec3(1.0))),1.0); return;\n" +
        "  }\n" +
        "  ivec2 p = srcCoord(oc, u_rawSize, u_orient);\n" +
        "  int k = cfaAt(p);\n" +
        "  float C=F(p);\n" +
        "  float N=F(p+ivec2(0,-1)), S=F(p+ivec2(0,1)), W=F(p+ivec2(-1,0)), E=F(p+ivec2(1,0));\n" +
        "  float NN=F(p+ivec2(0,-2)), SS=F(p+ivec2(0,2)), WW=F(p+ivec2(-2,0)), EE=F(p+ivec2(2,0));\n" +
        "  float NW=F(p+ivec2(-1,-1)), NE=F(p+ivec2(1,-1)), SW=F(p+ivec2(-1,1)), SE=F(p+ivec2(1,1));\n" +
        "  float cross=N+S+W+E, far4=NN+SS+WW+EE, diag=NW+NE+SW+SE;\n" +
        "  vec3 rgb;\n" +
        "  if(k==1){\n" +
        "    float h = (5.0*C - diag - (WW+EE) + 0.5*(NN+SS) + 4.0*(W+E))/8.0;\n" +
        "    float v = (5.0*C - diag + 0.5*(WW+EE) - (NN+SS) + 4.0*(N+S))/8.0;\n" +
        "    int hk = cfaAt(p+ivec2(1,0));\n" +
        "    rgb = (hk==0) ? vec3(h,C,v) : vec3(v,C,h);\n" +
        "  } else {\n" +
        "    float g = (4.0*C + 2.0*cross - far4)/8.0;\n" +
        "    float x = (6.0*C + 2.0*diag - 1.5*far4)/8.0;\n" +
        "    rgb = (k==0) ? vec3(C,g,x) : vec3(x,g,C);\n" +
        "  }\n" +
        "  o = vec4(finish(max(rgb,vec3(0.0))),1.0);\n" +
        "}\n";

    /* Pass 1b: 8-bit sRGB bitmap (JPEG/HEIC/embedded preview) -> linear base. */
    static final String LINEARIZE =
        "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D u_src;\n" +
        "out vec4 o;\n" +
        "vec3 lin(vec3 c){ return mix(c/12.92, pow((c+0.055)/1.055, vec3(2.4)), step(0.04045,c)); }\n" +
        "void main(){ o = vec4(lin(texelFetch(u_src, ivec2(gl_FragCoord.xy),0).rgb),1.0); }\n";

    /*
     * Pass 2: interactive edit + display / histogram / full-res export, one fragment pass.
     * Lens geometry -> detail -> WB/exposure -> dehaze -> local tone -> colour -> curves -> effects.
     */
    static final String EDIT =
        "#version 300 es\n" +
        "precision highp float;\n" +
        "struct P { float exposure, contrast, highlights, shadows, whites, blacks, vibrance, saturation, hue, clarity, texture,\n" +
        "  dehaze, sharpen, denoise, mono, neon, fade, vignette, grain, distortion, caRed, caBlue, defringe, lensVig, rotate, zoom, curve; vec3 wb; };\n" +
        "uniform P u_edit; uniform P u_orig;\n" +
        "uniform sampler2D u_base;\n" +
        "uniform sampler2D u_curve;\n" +
        "uniform vec2 u_imgSize, u_viewSize, u_center;\n" +
        "uniform float u_scale, u_split, u_lodSH, u_lodCl, u_density, u_lod;\n" +
        "uniform int u_showSplit, u_export;\n" +
        "out vec4 o;\n" +
        "float luma(vec3 c){ return dot(c, vec3(0.2126,0.7152,0.0722)); }\n" +
        "vec3 oetf(vec3 c){ c=clamp(c,0.0,1.0); return mix(c*12.92, 1.055*pow(c,vec3(1.0/2.4))-0.055, step(0.0031308,c)); }\n" +
        "float hash(vec2 p){ p=fract(p*vec2(443.897,441.423)); p+=dot(p,p.yx+19.19); return fract((p.x+p.y)*p.x); }\n" +
        "bool outside(vec2 u){ return any(lessThan(u,vec2(0.0))) || any(greaterThan(u,vec2(1.0))); }\n" +
        "vec3 process(vec2 uv0, P p){\n" +
        "  // ---------------- lens geometry: straighten, scale, distortion, lateral CA (RawTherapee 'Lens / Geometry')\n" +
        "  float md = max(u_imgSize.x,u_imgSize.y);\n" +
        "  vec2 ax = u_imgSize/md;\n" +
        "  vec2 q = (uv0-0.5)*ax;\n" +
        "  float rc2 = dot(0.5*ax,0.5*ax);\n" +
        "  if(p.rotate!=0.0){\n" +
        "    float a = radians(p.rotate), ca = cos(a), sa = sin(a);\n" +
        "    q = mat2(ca,-sa,sa,ca)*q;\n" +
        "    q /= abs(ca) + abs(sa)*max(ax.x/ax.y, ax.y/ax.x);\n" +
        "  }\n" +
        "  q *= exp2(-p.zoom);\n" +
        "  float r2 = dot(q,q)/rc2;\n" +
        "  float k = p.distortion*0.3;\n" +
        "  if(k!=0.0) q = q*(1.0 + k*r2)/(1.0 + max(k,0.0));\n" +
        "  vec2 uv  = q/ax + 0.5;\n" +
        "  vec2 uvR = (q*(1.0 + p.caRed*0.004))/ax + 0.5;\n" +
        "  vec2 uvB = (q*(1.0 + p.caBlue*0.004))/ax + 0.5;\n" +
        "  if(outside(uv)) return vec3(0.0);\n" +
        "  vec2 px = 1.0/u_imgSize;\n" +
        "  vec3 c = textureLod(u_base, uv, u_lod).rgb;\n" +
        "  if(p.caRed!=0.0) c.r = textureLod(u_base, uvR, u_lod).r;\n" +
        "  if(p.caBlue!=0.0) c.b = textureLod(u_base, uvB, u_lod).b;\n" +
        "  // ---------------- detail (linear)\n" +
        "  if(p.sharpen>0.0 && u_lod < 0.5){\n" +
        "    vec3 b = (textureLod(u_base,uv+vec2(px.x,0.0),0.0).rgb+textureLod(u_base,uv-vec2(px.x,0.0),0.0).rgb+\n" +
        "              textureLod(u_base,uv+vec2(0.0,px.y),0.0).rgb+textureLod(u_base,uv-vec2(0.0,px.y),0.0).rgb)*0.25;\n" +
        "    float d = luma(c)-luma(b); c *= max(1.0 + p.sharpen*1.6*d/max(luma(c),1e-4), 0.0);\n" +
        "  }\n" +
        "  if(p.denoise>0.0){\n" +
        "    vec3 nb = textureLod(u_base, uv, u_lod + 0.8 + 1.2*p.denoise).rgb;\n" +
        "    float lc = luma(c), ln = luma(nb);\n" +
        "    float diff = abs(lc-ln)/(ln+0.02);\n" +
        "    float wl = p.denoise*(1.0 - smoothstep(0.03, 0.30, diff));\n" +
        "    float L1 = mix(lc, ln, wl*0.85);\n" +
        "    vec3 chroma = mix(c - lc, nb - ln, min(1.0, p.denoise*1.2));\n" +
        "    c = max(vec3(L1) + chroma, 0.0);\n" +
        "  }\n" +
        "  if(p.defringe>0.0){\n" +
        "    vec3 bl = textureLod(u_base, uv, u_lod + 1.5).rgb;\n" +
        "    float edge = smoothstep(0.04, 0.35, abs(luma(c)-luma(bl))/(luma(bl)+0.01));\n" +
        "    float mx = max(c.r,max(c.g,c.b))+1e-4;\n" +
        "    float purple = smoothstep(0.03, 0.25, (min(c.r,c.b)-c.g)/mx);\n" +
        "    float green  = smoothstep(0.05, 0.30, (c.g-max(c.r,c.b))/mx);\n" +
        "    float f = max(purple, green*0.7)*edge;\n" +
        "    c = mix(c, vec3(luma(c)), clamp(p.defringe*f*1.5,0.0,1.0));\n" +
        "  }\n" +
        "  if(p.lensVig!=0.0) c *= max(1.0 + p.lensVig*1.1*r2*r2*(2.0-r2), 0.0);\n" +
        "  // ---------------- white balance + exposure\n" +
        "  float gain = exp2(p.exposure);\n" +
        "  c *= p.wb * gain;\n" +
        "  if(p.dehaze!=0.0){\n" +
        "    vec3 hb = textureLod(u_base, uv, u_lodSH - 1.0).rgb*p.wb*gain;\n" +
        "    float h = clamp(min(hb.r,min(hb.g,hb.b)), 0.0, 0.95);\n" +
        "    if(p.dehaze>0.0){ float t = p.dehaze*0.75*h; c = max((c - t)/(1.0 - t), 0.0); }\n" +
        "    else c = mix(c, vec3(0.75*gain), -p.dehaze*0.45);\n" +
        "  }\n" +
        "  // ---------------- local tone (blurred-luminance masks from mipmaps)\n" +
        "  float L = max(luma(c),1e-5);\n" +
        "  float Lsh = max(luma(textureLod(u_base,uv,u_lodSH).rgb*p.wb)*gain,1e-5);\n" +
        "  float Lcl = max(luma(textureLod(u_base,uv,u_lodCl).rgb*p.wb)*gain,1e-5);\n" +
        "  float lp = pow(clamp(Lsh,0.0,1.0), 1.0/2.2);\n" +
        "  float ws = (1.0-lp)*(1.0-lp), wh = lp*lp;\n" +
        "  float ev = p.shadows*1.6*ws + p.highlights*1.4*wh;\n" +
        "  ev += p.clarity * clamp(log2(L) - log2(Lcl),-2.0,2.0) * 0.9 * (1.0 - 0.6*wh);\n" +
        "  if(p.texture!=0.0){\n" +
        "    float Lt = max(luma(textureLod(u_base,uv,u_lod + 2.0).rgb*p.wb)*gain,1e-5);\n" +
        "    ev += p.texture * clamp(log2(L) - log2(Lt), -1.0, 1.0) * 0.8;\n" +
        "  }\n" +
        "  c *= exp2(ev);\n" +
        "  L = max(luma(c),1e-6);\n" +
        "  c *= 0.18*pow(L/0.18, 1.0 + p.contrast*0.55)/L;\n" +
        "  float wp = exp2(-p.whites*0.8), bp = p.blacks*-0.025;\n" +
        "  c = (c - bp)/(wp - bp);\n" +
        "  vec3 kk = vec3(0.86);\n" +
        "  c = mix(c, kk + (1.0-kk)*(1.0-exp(-(c-kk)/(1.0-kk))), step(kk,c));\n" +
        "  c = max(c, 0.0);\n" +
        "  // ---------------- colour\n" +
        "  L = luma(c);\n" +
        "  float mx2=max(c.r,max(c.g,c.b)), mn2=min(c.r,min(c.g,c.b));\n" +
        "  float sat = (mx2-mn2)/max(mx2,1e-5);\n" +
        "  float skin = smoothstep(0.0,0.25,c.r-c.b)*smoothstep(0.0,0.2,c.r-c.g)*0.5;\n" +
        "  float vib = p.vibrance*(1.0-sat)*(1.0-skin*step(0.0,p.vibrance));\n" +
        "  c = max(L + (c-L)*(1.0+p.saturation+vib+0.25*max(p.dehaze,0.0)), 0.0);\n" +
        "  if(p.mono>0.0) c = mix(c, vec3(dot(c, vec3(0.30,0.59,0.11))), p.mono);\n" +
        "  if(p.neon>0.0){\n" +
        "    float t = smoothstep(0.0,1.0,pow(clamp(L,0.0,1.0),0.45));\n" +
        "    vec3 tone = mix(vec3(0.0,0.85,1.0), vec3(1.0,0.17,0.84), t);\n" +
        "    c = mix(c, c*(0.55+0.9*tone), p.neon*0.6);\n" +
        "  }\n" +
        "  vec3 s = oetf(c);\n" +
        "  if(p.hue!=0.0){\n" +
        "    float a = p.hue*3.14159265, cs = cos(a), sn = sin(a);\n" +
        "    vec3 kx = vec3(0.57735);\n" +
        "    s = clamp(s*cs + cross(kx,s)*sn + kx*dot(kx,s)*(1.0-cs), 0.0, 1.0);\n" +
        "  }\n" +
        "  s = mix(s, vec3(0.08) + s*0.88, p.fade);\n" +
        "  // ---------------- tone curves (LUT: r,g,b per channel, a = master)\n" +
        "  if(p.curve>0.5){\n" +
        "    vec3 m = vec3(texture(u_curve, vec2(s.r*0.99609375+0.001953125,0.5)).a,\n" +
        "                  texture(u_curve, vec2(s.g*0.99609375+0.001953125,0.5)).a,\n" +
        "                  texture(u_curve, vec2(s.b*0.99609375+0.001953125,0.5)).a);\n" +
        "    s = vec3(texture(u_curve, vec2(m.r*0.99609375+0.001953125,0.5)).r,\n" +
        "             texture(u_curve, vec2(m.g*0.99609375+0.001953125,0.5)).g,\n" +
        "             texture(u_curve, vec2(m.b*0.99609375+0.001953125,0.5)).b);\n" +
        "  }\n" +
        "  if(p.vignette!=0.0){\n" +
        "    vec2 qq = (uv0-0.5)*ax*2.0;\n" +
        "    float r = smoothstep(0.35, 1.45, length(qq));\n" +
        "    s *= (p.vignette<0.0) ? (1.0 + p.vignette*r) : 1.0; s = (p.vignette>0.0) ? mix(s, vec3(1.0), p.vignette*r*0.8) : s;\n" +
        "  }\n" +
        "  if(p.grain>0.0){\n" +
        "    float gsz = max(1.0,u_imgSize.x/2400.0);\n" +
        "    vec2 g = floor(uv0*u_imgSize/gsz);\n" +
        "    float n = hash(g)+hash(g+17.0)-1.0;\n" +
        "    float m4 = 4.0*luma(s)*(1.0-luma(s));\n" +
        "    float zoomAmp = min(1.0, sqrt(max(u_scale,1e-3)*gsz));\n" +   // zoomed-out preview ~ what the print shows
        "    s += n*p.grain*0.16*m4*zoomAmp;\n" +
        "  }\n" +
        "  return clamp(s,0.0,1.0);\n" +
        "}\n" +
        "void main(){\n" +
        "  vec2 fc = gl_FragCoord.xy;\n" +
        "  vec2 sp = (u_export==1) ? fc : vec2(fc.x, u_viewSize.y - fc.y);\n" +
        "  vec2 ip = (sp - 0.5*u_viewSize)/u_scale + u_center;\n" +
        "  vec2 uv = ip/u_imgSize;\n" +
        "  vec3 col;\n" +
        "  if(outside(uv)){\n" +
        "    vec2 gq = abs(fract(sp/(28.0*u_density))-0.5);\n" +
        "    float grid = step(0.47, max(gq.x,gq.y));\n" +
        "    col = vec3(0.027,0.027,0.047) + grid*vec3(0.0,0.06,0.08);\n" +
        "  } else {\n" +
        "    bool before = (u_showSplit==1) && (sp.x < u_split);\n" +
        "    col = before ? process(uv,u_orig) : process(uv,u_edit);\n" +
        "  }\n" +
        "  if(u_showSplit==1 && u_export==0){\n" +
        "    float d = abs(sp.x-u_split);\n" +
        "    float w = 1.2*u_density;\n" +
        "    col = mix(col, vec3(0.0,0.94,1.0), smoothstep(w+1.0,w,d));\n" +
        "    col += vec3(0.0,0.5,0.6)*exp(-d/(6.0*u_density))*0.5;\n" +
        "    vec2 h = sp - vec2(u_split, 0.5*u_viewSize.y);\n" +
        "    float r = length(h);\n" +
        "    float R = 17.0*u_density;\n" +
        "    col = mix(col, vec3(0.03,0.03,0.06), smoothstep(R+1.0,R,r));\n" +
        "    col = mix(col, vec3(1.0,0.17,0.84), smoothstep(1.5*u_density,0.0,abs(r-R)));\n" +
        "    float ar = step(abs(h.y), 6.0*u_density - abs(abs(h.x)-6.0*u_density)*1.0) * step(2.0*u_density,abs(h.x)) * step(abs(h.x),11.0*u_density);\n" +
        "    col = mix(col, vec3(0.0,0.94,1.0), ar*step(r,R));\n" +
        "  }\n" +
        "  o = vec4(col,1.0);\n" +
        "}\n";
}
