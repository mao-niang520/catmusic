package com.catmusic.app.ui.components

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.roundToInt

/**
 * Android 13+ GPU rendition of the MetaBall fluid background.
 *
 * The shader was authored as GLSL and then ported to AGSL, Android's runtime
 * shader language, because RuntimeShader is the only GPU shader that can be
 * recorded by both the Compose UI and the QWEA0 liquid-glass backdrop capture.
 * Keeping the backdrop and the visible scene in the same rendering pipeline is
 * what lets the glass lens refract the moving liquids instead of a stale bitmap.
 *
 * The SkSL source is deliberately kept to SDFs, one smooth-min, two value-noise
 * samples and a handful of smoothsteps: no loops, no texture fetches, and only
 * two trig calls per fragment. AGSL does not expose GLSL's built-in
 * smoothstep, so [sstep] is defined locally.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class MetaballShaderPainter {
    private val shader = RuntimeShader(METABALL_SHADER)
    private val paint = Paint().apply {
        isAntiAlias = false
        isDither = true
        // This is an enhancing overlay on top of the guaranteed Canvas base.
        // SCREEN means a failed/empty runtime shader cannot black out the app.
        blendMode = BlendMode.SCREEN
        alpha = 210
        this.shader = shader
    }

    fun draw(canvas: Canvas, width: Int, height: Int, seconds: Float) {
        if (width <= 0 || height <= 0) return
        shader.setFloatUniform("u_time", seconds)
        shader.setFloatUniform(
            "u_resolution",
            width.toFloat(),
            height.toFloat()
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

/**
 * Player background.
 *
 * The Canvas renderer is the primary, unconditionally visible layer. On
 * Android 13+ the RuntimeShader is composited over it with SCREEN blending as
 * an optional enhancement. If a particular OEM cannot compile or draw AGSL,
 * the Canvas liquid is still present — the previous implementation returned
 * after the shader call and could leave a pure-black screen on such devices.
 */
@Composable
fun MetaballFluidBackdrop(modifier: Modifier = Modifier) {
    val motionNanos = LocalAdaptiveMotionNanos.current
    val renderer = remember { MetaballCanvasRenderer() }
    Box(modifier.fillMaxSize()) {
        ComposeCanvas(Modifier.fillMaxSize()) {
            if (size.width <= 0f || size.height <= 0f) return@ComposeCanvas
            renderer.draw(
                canvas = drawContext.canvas.nativeCanvas,
                width = size.width.roundToInt(),
                height = size.height.roundToInt(),
                seconds = motionNanos.value / 1_000_000_000f
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            MetaballShaderOverlay()
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun MetaballShaderOverlay() {
    val painter = remember {
        runCatching { MetaballShaderPainter() }
            .onFailure { android.util.Log.w("MetaballShader", "AGSL init failed", it) }
            .getOrNull()
    } ?: return
    val motionNanos = LocalAdaptiveMotionNanos.current
    ComposeCanvas(Modifier.fillMaxSize()) {
        if (size.width <= 0f || size.height <= 0f) return@ComposeCanvas
        painter.draw(
            canvas = drawContext.canvas.nativeCanvas,
            width = size.width.roundToInt(),
            height = size.height.roundToInt(),
            seconds = motionNanos.value / 1_000_000_000f
        )
    }
}

/**
 * AGSL translation of `metaball.sksl` (also the GLSL shader from the prompt).
 * Keep the uniform names in sync with [MetaballShaderPainter].
 */
private const val METABALL_SHADER = """
uniform float u_time;
uniform float2 u_resolution;

const float TAU = 6.28318530718;
const float LOOP_SECONDS = 28.0;
const float3 COLOR_MAGENTA = float3(0.94, 0.045, 0.53);
const float3 COLOR_PURPLE = float3(0.30, 0.035, 0.62);
const float3 COLOR_HIGHLIGHT = float3(1.00, 0.23, 0.72);
const float3 BG_BLACK = float3(0.0025, 0.0015, 0.0050);
const float3 BG_PLUM = float3(0.0080, 0.0030, 0.0160);
const float EDGE_SOFTNESS = 0.012;
const float SMIN_K = 0.22;
const float RIM_SOFTNESS = 0.085;
const float HALO_SOFTNESS = 0.30;
const float SEPARATION = 0.36;
const float DRIFT_X = 0.13;
const float DRIFT_Y = 0.10;
const float RADIUS_A = 0.50;
const float RADIUS_B = 0.485;
const float CENTER_DIM = 0.40;
const float SIN_BLOB_PHASE = 0.83665564;
const float COS_BLOB_PHASE = -0.54772926;

float hash21(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float valueNoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + float2(1.0, 0.0));
    float c = hash21(i + float2(0.0, 1.0));
    float d = hash21(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// AGSL runtime effects do not expose GLSL's smoothstep; implement it once.
float sstep(float edge0, float edge1, float x) {
    float t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

float smin(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
    return mix(b, a, h) - k * h * (1.0 - h);
}

half4 main(float2 fragCoord) {
    float2 res = max(u_resolution, float2(1.0));
    float minRes = min(res.x, res.y);
    float2 p = (2.0 * fragCoord - res) / minRes;

    float angle = TAU * fract(u_time / LOOP_SECONDS);
    float sinA = sin(angle);
    float cosA = cos(angle);
    float breath = 2.0 * sinA * cosA;
    float2 driftA = float2(cosA, sinA);
    float2 driftB = float2(
        sinA * COS_BLOB_PHASE + cosA * SIN_BLOB_PHASE,
        cosA * COS_BLOB_PHASE - sinA * SIN_BLOB_PHASE
    );

    float2 centerA = float2(-SEPARATION, -0.10) + driftA * float2(DRIFT_X, DRIFT_Y);
    float2 centerB = float2( SEPARATION,  0.08) + driftB * float2(DRIFT_X, DRIFT_Y);

    float2 noiseDrift = driftA * 0.62;
    float noiseA = valueNoise(p * 3.75 + noiseDrift + float2(4.3, 1.7));
    float noiseB = valueNoise(p * 4.15 + noiseDrift * 1.17 + float2(11.2, 7.9));

    float radiusA = RADIUS_A *
        (1.0 + (noiseA - 0.5) * 0.38 + (noiseB - 0.5) * 0.10 + breath * 0.018);
    float radiusB = RADIUS_B *
        (1.0 + (noiseB - 0.5) * 0.38 + (noiseA - 0.5) * 0.10 - breath * 0.018);

    float2 qA = p - centerA;
    float2 qB = p - centerB;
    float sdfA = length(qA) - radiusA;
    float sdfB = length(qB) - radiusB;
    float sdf = smin(sdfA, sdfB, SMIN_K);

    float colorWeight = sstep(-0.12, 0.12, sdfA - sdfB);
    float3 liquidColor = mix(COLOR_MAGENTA, COLOR_PURPLE, colorWeight);
    float3 rimColor = mix(liquidColor, COLOR_HIGHLIGHT, 0.30);

    float aa = max(EDGE_SOFTNESS, 2.0 / minRes);
    float fill = 1.0 - sstep(-aa, aa, sdf);
    float inside = sstep(0.0, 0.62, max(-sdf, 0.0));
    float rim = 1.0 - sstep(0.0, RIM_SOFTNESS, abs(sdf));
    float rimHot = rim * rim;
    float halo = (1.0 - sstep(0.0, HALO_SOFTNESS, max(sdf, 0.0))) * (1.0 - fill);
    float flow = sstep(0.16, 0.9, noiseA);
    float sheenA = 1.0 - sstep(0.0, radiusA * 0.54,
        length(qA - float2(-radiusA * 0.22, -radiusA * 0.20)));
    float sheenB = 1.0 - sstep(0.0, radiusB * 0.50,
        length(qB - float2(-radiusB * 0.18, -radiusB * 0.18)));
    float sheen = (sheenA + sheenB) * fill;

    float2 screenUV = fragCoord / res;
    float3 color = mix(BG_BLACK, BG_PLUM, screenUV.y);
    float3 liquidLayer =
          liquidColor * fill * mix(0.42, 0.16, inside)
        + liquidColor * fill * flow * 0.12
        + rimColor * rim * 0.29
        + rimColor * rimHot * 0.10
        + liquidColor * halo * 0.09
        + mix(liquidColor, COLOR_HIGHLIGHT, 0.28) * sheen * 0.10;

    float2 centered = (screenUV - float2(0.5, 0.52)) * float2(1.08, 1.16);
    float centerMask = sstep(0.08, 0.66, length(centered));
    float uiDim = mix(CENTER_DIM, 1.0, centerMask);
    color = mix(color, BG_BLACK, (1.0 - centerMask) * 0.08);
    color += liquidLayer * uiDim;

    float edgeFade = sstep(0.0, 0.18, screenUV.y) *
                     (1.0 - sstep(0.82, 1.0, screenUV.y));
    color *= mix(0.72, 1.0, edgeFade);
    color += (hash21(fragCoord) - 0.5) / 255.0;

    return half4(half3(clamp(color, 0.0, 1.0)), half(1.0));
}
"""
