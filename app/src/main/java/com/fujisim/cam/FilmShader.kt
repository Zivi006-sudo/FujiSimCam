package com.fujisim.cam

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.hardware.HardwareBuffer
import android.media.ImageReader

/**
 * GPU film-simulation shader (AGSL). The same shader runs on the live viewfinder
 * (as a RenderEffect on the PreviewView) and on the full-resolution captured photo.
 */
class FilmShader {
    private val shader = RuntimeShader(AGSL)

    fun set(r: Recipe, wbGains: FloatArray, width: Float, height: Float, scale: Float, mix: Float) {
        val p = r.film.p
        val drComp = when (r.dr) { 200 -> 0.75f; 400 -> 1.5f; else -> 0f }
        shader.setFloatUniform("uSize", width, height)
        shader.setFloatUniform("uScale", scale)
        shader.setFloatUniform("uMix", mix)
        shader.setFloatUniform("uWb", wbGains[0], wbGains[1], wbGains[2])
        shader.setFloatUniform("uSat", p.sat * (1f + 0.06f * r.color))
        shader.setFloatUniform("uContrast", p.contrast)
        shader.setFloatUniform("uLift", p.lift)
        shader.setFloatUniform("uHighlight", r.highlight - drComp)
        shader.setFloatUniform("uShadow", r.shadow)
        shader.setFloatUniform("uHueA", p.hue[0] / 360f, p.hue[1] / 360f, p.hue[2] / 360f)
        shader.setFloatUniform("uHueB", p.hue[3] / 360f, p.hue[4] / 360f, p.hue[5] / 360f)
        shader.setFloatUniform("uSatA", p.bandSat[0], p.bandSat[1], p.bandSat[2])
        shader.setFloatUniform("uSatB", p.bandSat[3], p.bandSat[4], p.bandSat[5])
        shader.setFloatUniform("uLumA", p.bandLum[0], p.bandLum[1], p.bandLum[2])
        shader.setFloatUniform("uLumB", p.bandLum[3], p.bandLum[4], p.bandLum[5])
        shader.setFloatUniform("uShadowTint", p.shadowTint[0], p.shadowTint[1], p.shadowTint[2])
        shader.setFloatUniform("uHighTint", p.highTint[0], p.highTint[1], p.highTint[2])
        shader.setFloatUniform("uMono", if (p.mono) 1f else 0f)
        shader.setFloatUniform("uMonoW", p.monoW[0], p.monoW[1], p.monoW[2])
        shader.setFloatUniform("uMonoTone", p.monoTone[0], p.monoTone[1], p.monoTone[2])
        shader.setFloatUniform("uChrome", r.chrome * 0.5f)
        shader.setFloatUniform("uChromeBlue", r.chromeBlue * 0.5f)
        shader.setFloatUniform("uGrain", when (r.grain) { 1 -> 0.045f; 2 -> 0.085f; else -> 0f })
        shader.setFloatUniform("uGrainSize", if (r.grainLarge) 2.2f else 1.2f)
        shader.setFloatUniform("uSharp", r.sharpness * 0.22f)
        shader.setFloatUniform("uClarity", r.clarity / 5f)
    }

    fun renderEffect(): RenderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")

    companion object {
        /** Apply the recipe to a full-size bitmap on the GPU (call from a background thread). */
        fun process(src: Bitmap, r: Recipe, wbGains: FloatArray, scale: Float): Bitmap {
            val w = src.width
            val h = src.height
            val fs = FilmShader()
            fs.set(r, wbGains, w.toFloat(), h.toFloat(), scale, 1f)

            val reader = ImageReader.newInstance(
                w, h, PixelFormat.RGBA_8888, 1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
            )
            val node = RenderNode("film")
            val renderer = HardwareRenderer()
            var result: Bitmap? = null
            try {
                renderer.setSurface(reader.surface)
                renderer.setContentRoot(node)
                node.setPosition(0, 0, w, h)
                node.setRenderEffect(fs.renderEffect())
                val canvas = node.beginRecording()
                canvas.drawBitmap(src, 0f, 0f, null)
                node.endRecording()
                renderer.createRenderRequest().setWait(true).syncAndDraw()

                val image = reader.acquireNextImage()
                if (image != null) {
                    val hb = image.hardwareBuffer
                    if (hb != null) {
                        val hw = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB))
                        result = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hb.close()
                    }
                    image.close()
                }
            } finally {
                node.discardDisplayList()
                renderer.destroy()
                reader.close()
            }
            return result ?: throw IllegalStateException("GPU processing failed")
        }

        private const val AGSL = """
uniform shader content;
uniform float2 uSize;
uniform float uScale;
uniform float uMix;
uniform float3 uWb;
uniform float uSat;
uniform float uContrast;
uniform float uLift;
uniform float uHighlight;
uniform float uShadow;
uniform float3 uHueA;
uniform float3 uHueB;
uniform float3 uSatA;
uniform float3 uSatB;
uniform float3 uLumA;
uniform float3 uLumB;
uniform float3 uShadowTint;
uniform float3 uHighTint;
uniform float uMono;
uniform float3 uMonoW;
uniform float3 uMonoTone;
uniform float uChrome;
uniform float uChromeBlue;
uniform float uGrain;
uniform float uGrainSize;
uniform float uSharp;
uniform float uClarity;

const float3 LUMA = float3(0.299, 0.587, 0.114);
const float PI = 3.14159265;

float3 rgb2hsv(float3 c) {
    float4 K = float4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    float4 p = mix(float4(c.bg, K.wz), float4(c.gb, K.xy), step(c.b, c.g));
    float4 q = mix(float4(p.xyw, c.r), float4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return float3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

float3 hsv2rgb(float3 c) {
    float4 K = float4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    float3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

float bandW(float h, float c) {
    float d = abs(h - c);
    d = min(d, 1.0 - d);
    return max(0.0, 1.0 - d * 6.0);
}

float hash(float2 p) {
    float3 p3 = fract(float3(p.x, p.y, p.x) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float3 src(float2 p) {
    float2 q = clamp(p, float2(0.5, 0.5), uSize - float2(0.5, 0.5));
    float4 c = float4(content.eval(q));
    return c.a > 0.0 ? c.rgb / c.a : float3(0.0);
}

float3 toneCurve(float3 x) {
    x = clamp(x, 0.0, 1.0);
    x = mix(x, x * x * (3.0 - 2.0 * x), uContrast);
    float3 hw = sin(PI * clamp((x - 0.35) / 0.65, 0.0, 1.0));
    float3 sw = sin(PI * clamp(x / 0.65, 0.0, 1.0));
    x = x + uHighlight * 0.035 * hw - uShadow * 0.035 * sw;
    x = clamp(x, 0.0, 1.0);
    return uLift + x * (1.0 - uLift);
}

half4 main(float2 p) {
    float4 raw = float4(content.eval(p));
    float a = raw.a;
    float3 orig = a > 0.0 ? raw.rgb / a : float3(0.0);
    float3 c = orig;
    float s = uScale;

    // Sharpness (unsharp mask, negative = softer)
    if (uSharp != 0.0) {
        float o = 1.5 * s;
        float3 b1 = (src(p + float2(o, 0.0)) + src(p - float2(o, 0.0)) +
                     src(p + float2(0.0, o)) + src(p - float2(0.0, o))) * 0.25;
        c += uSharp * (c - b1);
    }

    // Clarity (mid-tone local contrast, negative = glow)
    if (uClarity != 0.0) {
        float r = 12.0 * s;
        float r2 = r * 0.7071;
        float3 b2 = (src(p + float2(r, 0.0)) + src(p - float2(r, 0.0)) +
                     src(p + float2(0.0, r)) + src(p - float2(0.0, r)) +
                     src(p + float2(r2, r2)) + src(p - float2(r2, r2)) +
                     src(p + float2(r2, -r2)) + src(p - float2(r2, -r2))) * 0.125;
        float L0 = dot(c, LUMA);
        float detail = L0 - dot(b2, LUMA);
        float mid = 4.0 * L0 * (1.0 - L0);
        c += uClarity * 0.6 * detail * mid;
    }
    c = max(c, float3(0.0));

    // White balance (in approx. linear light)
    c = pow(pow(c, float3(2.2)) * uWb, float3(1.0 / 2.2));

    // Film simulation colour response, per hue band
    float3 hsv = rgb2hsv(clamp(c, 0.0, 1.0));
    float h = hsv.x;
    float3 wA = float3(bandW(h, 0.0), bandW(h, 1.0 / 6.0), bandW(h, 2.0 / 6.0));
    float3 wB = float3(bandW(h, 3.0 / 6.0), bandW(h, 4.0 / 6.0), bandW(h, 5.0 / 6.0));
    float hueShift = dot(uHueA, wA) + dot(uHueB, wB);
    float bandSat = dot(uSatA, wA) + dot(uSatB, wB);
    float bandLum = dot(uLumA, wA) + dot(uLumB, wB);
    float chroma = hsv.y;
    hsv.x = fract(hsv.x + hueShift + 1.0);
    c = hsv2rgb(hsv);
    float L = dot(c, LUMA);
    c = float3(L) + (c - float3(L)) * (uSat * bandSat);
    c += float3(bandLum * chroma);

    // Color Chrome Effect / FX Blue: deepen saturated tones
    float cc = chroma * chroma * smoothstep(0.15, 0.6, L);
    c *= 1.0 - uChrome * 0.18 * cc;
    c *= 1.0 - uChromeBlue * 0.22 * chroma * (wB.y + 0.5 * wB.x);

    // Monochrome
    float m = dot(max(c, float3(0.0)), uMonoW);
    c = mix(c, m * uMonoTone, uMono);

    // Tone curve: contrast, Highlight, Shadow, black lift
    c = toneCurve(c);

    // Split toning of shadows / highlights
    float Lt = dot(c, LUMA);
    c += uShadowTint * (1.0 - smoothstep(0.0, 0.5, Lt)) + uHighTint * smoothstep(0.45, 1.0, Lt);

    // Grain
    if (uGrain > 0.0) {
        float cell = uGrainSize * s;
        float g = vnoise(p / cell) + vnoise(p / cell * 1.7 + float2(13.1, 7.3)) - 1.0;
        c += float3(g * uGrain * (0.35 + 2.6 * Lt * (1.0 - Lt)));
    }

    c = clamp(c, 0.0, 1.0);
    c = mix(orig, c, uMix);
    return half4(half3(c * a), half(a));
}
"""
    }
}
