package com.fujisim.cam

/**
 * One colour engine, two backends:
 *  - AGSL (RuntimeShader) for the live viewfinder and still photos
 *  - GLSL ES (Media3 effect) for video
 * The grading code (CORE) is shared so preview, photos and video match.
 */
object Shaders {

    private const val UNIFORMS = """
uniform vec2 uSize;
uniform float uScale;
uniform float uTime;
uniform float uMix;
uniform vec3 uWb;
uniform float uSat;
uniform float uContrast;
uniform float uLift;
uniform float uHighlight;
uniform float uShadow;
uniform vec3 uHueA;
uniform vec3 uHueB;
uniform vec3 uSatA;
uniform vec3 uSatB;
uniform vec3 uLumA;
uniform vec3 uLumB;
uniform vec3 uShadowTint;
uniform vec3 uHighTint;
uniform float uMono;
uniform vec3 uMonoW;
uniform vec3 uMonoTone;
uniform float uChrome;
uniform float uChromeBlue;
uniform float uGrain;
uniform float uGrainSize;
uniform float uSharp;
uniform float uClarity;
uniform float uWarmth;
uniform float uGlow;
uniform float uLutN;
uniform float uLutMix;
uniform vec2 uLutSize;
uniform float uMist;
uniform float uVignette;
uniform float uHalation;
uniform float uCornerSoft;
uniform float uDenoise;
uniform float uFineGrain;
uniform float uVibrance;
uniform vec3 uTintS;
uniform vec3 uTintM;
uniform vec3 uTintH;
uniform vec3 uLumSMH;
uniform float uZebra;
"""

    private const val CORE = """
const vec3 LUMA = vec3(0.299, 0.587, 0.114);
const float PI = 3.14159265;

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

float bandW(float h, float c) {
    float d = abs(h - c);
    d = min(d, 1.0 - d);
    return max(0.0, 1.0 - d * 6.0);
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.x, p.y, p.x) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    float a = hash12(i);
    float b = hash12(i + vec2(1.0, 0.0));
    float c = hash12(i + vec2(0.0, 1.0));
    float d = hash12(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

vec3 toneCurve(vec3 x) {
    x = clamp(x, 0.0, 1.0);
    x = mix(x, x * x * (3.0 - 2.0 * x), uContrast);
    vec3 hw = sin(PI * clamp((x - 0.35) / 0.65, 0.0, 1.0));
    vec3 sw = sin(PI * clamp(x / 0.65, 0.0, 1.0));
    x = x + uHighlight * 0.035 * hw - uShadow * 0.035 * sw;
    x = clamp(x, 0.0, 1.0);
    return uLift + x * (1.0 - uLift);
}

vec3 brightPass(vec3 v) {
    return max(v - vec3(0.62), vec3(0.0)) / 0.38;
}

vec3 applyLut(vec3 c) {
    float n = uLutN;
    vec3 s3 = clamp(c, 0.0, 1.0) * (n - 1.0);
    float b0 = floor(s3.b);
    float b1 = min(b0 + 1.0, n - 1.0);
    float fb = s3.b - b0;
    vec2 xy = vec2(s3.r + 0.5, s3.g + 0.5);
    vec3 c0 = lutFetch(vec2(xy.x + b0 * n, xy.y));
    vec3 c1 = lutFetch(vec2(xy.x + b1 * n, xy.y));
    return mix(c0, c1, fb);
}

vec3 grade(vec3 orig, vec2 p) {
    vec3 c = orig;
    float s = uScale;

    // Light noise reduction: smooth colour noise strongly, luminance gently, but only across similar tones
    if (uDenoise > 0.0) {
        float od = (1.0 + 0.6 * uDenoise) * s;
        vec3 n0 = src(p + vec2(od, 0.0));
        vec3 n1 = src(p - vec2(od, 0.0));
        vec3 n2 = src(p + vec2(0.0, od));
        vec3 n3 = src(p - vec2(0.0, od));
        float L0 = dot(c, LUMA);
        float l0 = dot(n0, LUMA); float l1 = dot(n1, LUMA); float l2 = dot(n2, LUMA); float l3 = dot(n3, LUMA);
        float w0 = exp(-abs(l0 - L0) * 25.0); float w1 = exp(-abs(l1 - L0) * 25.0);
        float w2 = exp(-abs(l2 - L0) * 25.0); float w3 = exp(-abs(l3 - L0) * 25.0);
        float wsum = 1.0 + w0 + w1 + w2 + w3;
        vec3 chromaAvg = ((c - vec3(L0)) + (n0 - vec3(l0)) * w0 + (n1 - vec3(l1)) * w1 +
                          (n2 - vec3(l2)) * w2 + (n3 - vec3(l3)) * w3) / wsum;
        float lAvg = (L0 + l0 * w0 + l1 * w1 + l2 * w2 + l3 * w3) / wsum;
        float Ln = mix(L0, lAvg, 0.4 * min(uDenoise, 1.25));
        c = vec3(Ln) + mix(c - vec3(L0), chromaAvg, min(uDenoise, 1.0));
    }

    // Sharpness / detail
    if (uSharp != 0.0) {
        float o = 1.5 * s;
        vec3 b1 = (src(p + vec2(o, 0.0)) + src(p - vec2(o, 0.0)) +
                   src(p + vec2(0.0, o)) + src(p - vec2(0.0, o))) * 0.25;
        c += uSharp * (c - b1);
    }

    // Clarity (mid-tone local contrast)
    if (uClarity != 0.0) {
        float r = 12.0 * s;
        float r2 = r * 0.7071;
        vec3 b2 = (src(p + vec2(r, 0.0)) + src(p - vec2(r, 0.0)) +
                   src(p + vec2(0.0, r)) + src(p - vec2(0.0, r)) +
                   src(p + vec2(r2, r2)) + src(p - vec2(r2, r2)) +
                   src(p + vec2(r2, -r2)) + src(p - vec2(r2, -r2))) * 0.125;
        float L0 = dot(c, LUMA);
        float detail = L0 - dot(b2, LUMA);
        c += uClarity * 0.6 * detail * (4.0 * L0 * (1.0 - L0));
    }

    // Distance from centre (0 centre .. 1 corners) for lens effects
    vec2 rel = (p - 0.5 * uSize) / (0.5 * length(uSize));
    float edge = length(rel) * 1.414;

    // Corner softness (lens character)
    if (uCornerSoft > 0.0) {
        float o2 = 2.5 * s;
        vec3 soft = (src(p + vec2(o2, 0.0)) + src(p - vec2(o2, 0.0)) +
                     src(p + vec2(0.0, o2)) + src(p - vec2(0.0, o2))) * 0.25;
        c = mix(c, soft, uCornerSoft * smoothstep(0.45, 1.0, edge));
    }

    // Wide gather for glow (light halos), mist (Pro-Mist diffusion) and halation
    vec3 bloom = vec3(0.0);
    vec3 wide = vec3(0.0);
    if (uGlow > 0.0 || uMist > 0.0 || uHalation > 0.0) {
        float g1 = 7.0 * s;
        float g2 = 22.0 * s;
        float g3 = g2 * 0.7071;
        vec3 t0 = src(p + vec2(g1, 0.0)); vec3 t1 = src(p - vec2(g1, 0.0));
        vec3 t2 = src(p + vec2(0.0, g1)); vec3 t3 = src(p - vec2(0.0, g1));
        vec3 t4 = src(p + vec2(g2, 0.0)); vec3 t5 = src(p - vec2(g2, 0.0));
        vec3 t6 = src(p + vec2(0.0, g2)); vec3 t7 = src(p - vec2(0.0, g2));
        vec3 t8 = src(p + vec2(g3, g3)); vec3 t9 = src(p - vec2(g3, g3));
        vec3 t10 = src(p + vec2(g3, -g3)); vec3 t11 = src(p - vec2(g3, -g3));
        wide = (t0 + t1 + t2 + t3 + t4 + t5 + t6 + t7 + t8 + t9 + t10 + t11) / 12.0;
        bloom = (brightPass(t0) + brightPass(t1) + brightPass(t2) + brightPass(t3) +
                 brightPass(t4) + brightPass(t5) + brightPass(t6) + brightPass(t7) +
                 brightPass(t8) + brightPass(t9) + brightPass(t10) + brightPass(t11)) / 12.0;
    }

    c = max(c, vec3(0.0));

    // White balance (approx. linear light)
    c = pow(pow(c, vec3(2.2)) * uWb, vec3(1.0 / 2.2));

    // Film simulation 3D LUT
    if (uLutMix > 0.0) {
        c = mix(c, applyLut(c), uLutMix);
    }

    // Film simulation colour response per hue band
    vec3 hsv = rgb2hsv(clamp(c, 0.0, 1.0));
    float h = hsv.x;
    vec3 wA = vec3(bandW(h, 0.0), bandW(h, 1.0 / 6.0), bandW(h, 2.0 / 6.0));
    vec3 wB = vec3(bandW(h, 3.0 / 6.0), bandW(h, 4.0 / 6.0), bandW(h, 5.0 / 6.0));
    float hueShift = dot(uHueA, wA) + dot(uHueB, wB);
    float bandSat = dot(uSatA, wA) + dot(uSatB, wB);
    float bandLum = dot(uLumA, wA) + dot(uLumB, wB);
    float chroma = hsv.y;
    hsv.x = fract(hsv.x + hueShift + 1.0);
    c = hsv2rgb(hsv);
    float L = dot(c, LUMA);
    c = vec3(L) + (c - vec3(L)) * (uSat * bandSat);
    c += vec3(bandLum * chroma);

    // Vibrance: boost dull colours more than strong ones, protect skin tones
    if (uVibrance != 0.0) {
        float cmax = max(c.r, max(c.g, c.b));
        float cmin = min(c.r, min(c.g, c.b));
        float satNow = (cmax - cmin) / max(cmax, 1e-4);
        float skin = bandW(h, 0.07) * smoothstep(0.1, 0.5, satNow);
        float amt = uVibrance * (1.0 - satNow) * (1.0 - 0.7 * skin);
        float Lv = dot(c, LUMA);
        c = vec3(Lv) + (c - vec3(Lv)) * (1.0 + amt);
    }

    // Color Chrome Effect / FX Blue
    float cc = chroma * chroma * smoothstep(0.15, 0.6, L);
    c *= 1.0 - uChrome * 0.2 * cc;
    c *= 1.0 - uChromeBlue * 0.24 * chroma * (wB.y + 0.5 * wB.x);

    // Monochrome
    float m = dot(max(c, vec3(0.0)), uMonoW);
    c = mix(c, m * uMonoTone, uMono);

    // Tone curve
    c = toneCurve(c);

    // Split toning + golden highlight warmth
    float Lt = dot(c, LUMA);
    c += uShadowTint * (1.0 - smoothstep(0.0, 0.5, Lt)) + uHighTint * smoothstep(0.45, 1.0, Lt);
    c += uWarmth * smoothstep(0.35, 1.0, Lt) * vec3(0.075, 0.03, -0.065);

    // Colour grading wheels: Shadows / Midtones / Highlights (colour + brightness)
    float gwS = pow(1.0 - smoothstep(0.0, 0.55, Lt), 1.5);
    float gwH = smoothstep(0.45, 1.0, Lt);
    float gwM = max(0.0, 1.0 - gwS - gwH);
    c += uTintS * gwS + uTintM * gwM + uTintH * gwH;
    c += vec3(uLumSMH.x * gwS + uLumSMH.y * gwM + uLumSMH.z * gwH) * 0.18;

    // Glow, tinted warm like film halation
    c += uGlow * 0.65 * bloom * vec3(1.0, 0.78, 0.55);

    // Halation: red-orange fringe around bright edges
    float bpHere = dot(brightPass(orig), LUMA);
    c += uHalation * 0.9 * max(dot(bloom, LUMA) - bpHere, 0.0) * vec3(1.0, 0.32, 0.12);

    // Soft glow (Pro-Mist): screen a soft, highlight-weighted copy over the image
    if (uMist > 0.0) {
        float mw = smoothstep(0.3, 0.9, dot(wide, LUMA));
        vec3 m3 = wide * mw * uMist * 0.6;
        c = vec3(1.0) - (vec3(1.0) - c) * (vec3(1.0) - m3);
        c = mix(c, c * 0.94 + 0.03, uMist * 0.35);
    }

    // Vignette
    c *= 1.0 - uVignette * 0.45 * smoothstep(0.3, 1.05, edge);

    // Film grain (moves over time in video / live view)
    if (uGrain > 0.0 && uFineGrain < 0.5) {
        // Original grain texture (used by Styles)
        float cellO = uGrainSize * s;
        vec2 offO = vec2(fract(uTime * 0.371) * 517.0, fract(uTime * 0.613) * 331.0);
        float gO = vnoise(p / cellO + offO) + vnoise(p / cellO * 1.7 + offO.yx + vec2(13.1, 7.3)) - 1.0;
        c += vec3(gO * uGrain * (0.35 + 2.6 * Lt * (1.0 - Lt)));
    } else if (uGrain > 0.0) {
        // Fine, film-like grain: small specks that scale gently with resolution, strongest in mid-tones
        float cell = uGrainSize * sqrt(max(s, 1.0));
        vec2 off = vec2(fract(uTime * 0.371) * 517.0, fract(uTime * 0.613) * 331.0);
        float g = vnoise(p / cell + off) * 0.6 + hash12(floor(p / cell) + off.yx) * 0.4 - 0.5;
        c += vec3(g * 2.0 * uGrain * (0.2 + 2.4 * Lt * (1.0 - Lt)));
    }

    c = clamp(c, 0.0, 1.0);
    c = mix(orig, c, uMix);

    // Highlight warning (viewfinder only): diagonal stripes where highlights are clipping
    if (uZebra > 0.0 && dot(c, LUMA) > 0.965) {
        float stripe = step(0.5, fract((p.x + p.y) / (9.0 * max(uScale, 1.0))));
        c = mix(c, vec3(stripe), 0.55);
    }
    return c;
}
"""

    const val AGSL = """
uniform shader content;
uniform shader lut;
""" + UNIFORMS + """
vec3 lutFetch(vec2 px) {
    return vec3(lut.eval(px).rgb);
}
vec3 src(vec2 p) {
    vec2 q = clamp(p, vec2(0.5), uSize - vec2(0.5));
    vec4 c = vec4(content.eval(q));
    return c.a > 0.0 ? c.rgb / c.a : vec3(0.0);
}
""" + CORE + """
half4 main(vec2 p) {
    vec4 raw = vec4(content.eval(p));
    float a = raw.a;
    vec3 o = a > 0.0 ? raw.rgb / a : vec3(0.0);
    vec3 c = grade(o, p);
    return half4(half3(c * a), half(a));
}
"""

    const val VERTEX_GL = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
    gl_Position = aFramePosition;
    vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

    const val FRAGMENT_GL = """
precision highp float;
uniform sampler2D uTexSampler;
uniform sampler2D uLut;
uniform sampler2D uPrev;
uniform float uTemporal;
varying vec2 vTexSamplingCoord;
""" + UNIFORMS + """
vec3 lutFetch(vec2 px) {
    return texture2D(uLut, px / uLutSize).rgb;
}
vec3 src(vec2 p) {
    vec2 q = clamp(p, vec2(0.5), uSize - vec2(0.5));
    return texture2D(uTexSampler, q / uSize).rgb;
}
""" + CORE + """
void main() {
    vec2 p = vTexSamplingCoord * uSize;
    vec3 o = texture2D(uTexSampler, vTexSamplingCoord).rgb;
    vec3 g = grade(o, p);
    // Temporal noise reduction: blend with the previous output where nothing moved
    if (uTemporal > 0.0) {
        vec3 prev = texture2D(uPrev, vTexSamplingCoord).rgb;
        float diff = abs(dot(g - prev, LUMA));
        float still = 1.0 - smoothstep(0.02, 0.08, diff);
        g = mix(g, prev, uTemporal * 0.55 * still);
    }
    gl_FragColor = vec4(g, 1.0);
}
"""
}
