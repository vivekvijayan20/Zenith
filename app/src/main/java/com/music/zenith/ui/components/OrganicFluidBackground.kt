package com.music.zenith.ui.components

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.flow.collectLatest
import org.intellij.lang.annotations.Language

/**
 * Warna bawaan Organic Fluid: hot pink, crimson, coral, peach, soft pink.
 * Urutannya penting: shader memutar warna blob melewati kelimanya secara berurutan.
 */
object OrganicFluidDefaults {
    val Palette: List<Color> = listOf(
        Color(0xFFFF0055),
        Color(0xFFF00035),
        Color(0xFFFF696F),
        Color(0xFFFF9B91),
        Color(0xFFFFB0B8),
    )
}

/**
 * Latar cairan abstrak yang terus mengalir, terinspirasi kartu promosi Apple Music.
 *
 * API 33+: satu fragment shader AGSL (domain warping + medan gaussian anisotropik
 * yang berputar dan "bernapas"), digambar lewat [ShaderBrush]. Di bawah API 33
 * rumus yang sama dihitung di CPU ke bitmap kecil lalu diperbesar bilinear.
 *
 * Hanya menggambar: tidak memengaruhi layout atau input. Animasi berhenti saat
 * lifecycle di bawah STARTED, saat composable keluar layar, saat [animationSpeed]
 * 0, atau saat animasi sistem dimatikan (skala durasi animator 0).
 *
 * Pemakaian:
 * ```
 * Box(Modifier.size(340.dp, 220.dp).clip(RoundedCornerShape(28.dp))) {
 *     OrganicFluidBackground(Modifier.matchParentSize(), animationSpeed = 0.5f)
 *     // konten kartu
 * }
 * ```
 *
 * @param colors palet; 5 warna dipakai berurutan (lebih sedikit akan diulang).
 * @param animationSpeed 1 = kecepatan dasar yang sudah pelan; 0 = diam.
 */
@Composable
fun OrganicFluidBackground(
    modifier: Modifier = Modifier,
    colors: List<Color> = OrganicFluidDefaults.Palette,
    animationSpeed: Float = 1f,
    /** 0..1, stabil per mix: mengubah arah aliran, kecepatan, putaran, dan posisi blob. */
    seed: Float = 0f,
) {
    val context = LocalContext.current
    val motionAllowed = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val speed = if (motionAllowed) animationSpeed else 0f
    val speedState = rememberUpdatedState(speed)
    val colorsState = rememberUpdatedState(colors.ifEmpty { OrganicFluidDefaults.Palette })

    val clock = remember { FluidClock(startAt = FluidTimeline.phase) }
    val agsl = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && OrganicFluidSupport.shaderSupported) {
            runCatching { AgslFluid() }.getOrNull()
        } else {
            null
        }
    }

    // Bitmap-nya baru dialokasikan saat dipakai, yaitu hanya tanpa AGSL.
    val raster = remember { FluidRaster() }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, clock) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { clock.visible && speedState.value > 0f }.collectLatest { running ->
                if (!running) return@collectLatest
                // Semua instance (kartu, cover terbang, sleeve halaman) membaca jam yang
                // sama, jadi fase blob tetap nyambung saat pindah kartu <-> halaman.
                while (true) {
                    val frameNanos = withFrameNanos { it }
                    clock.seconds = FluidTimeline.tick(frameNanos, speedState.value)
                }
            }
        }
    }

    val screenW = LocalConfiguration.current.screenWidthDp * LocalDensity.current.density
    val screenH = LocalConfiguration.current.screenHeightDp * LocalDensity.current.density

    Spacer(
        modifier
            .onGloballyPositioned { clock.visible = it.isOnScreen(screenW, screenH) }
            .drawBehind {
                // Satu-satunya state yang dibaca saat draw: tiap frame hanya redraw,
                // tanpa recomposition.
                val t = clock.seconds
                if (agsl != null) {
                    agsl.draw(this, t.toFloat(), colorsState.value, seed)
                } else {
                    raster.draw(this, t.toFloat(), colorsState.value)
                }
            },
    )
}

/**
 * Jam bersama untuk semua latar cairan. Fase bertambah hanya saat ada instance
 * yang berjalan (satu kali per frame, berapa pun jumlah instance), jadi kartu,
 * cover terbang, dan halaman menampilkan frame yang sama, dan saat semuanya
 * di-pause fase berhenti lalu lanjut dari frame terakhir.
 */
private object FluidTimeline {
    var phase: Double = 30.0
        private set
    private var lastFrameNanos = 0L

    fun tick(frameNanos: Long, speed: Float): Double {
        if (frameNanos > lastFrameNanos) {
            // Dibatasi supaya jeda (pause / frame drop) tidak jadi lompatan.
            val dt = if (lastFrameNanos == 0L) 0.0 else ((frameNanos - lastFrameNanos) / 1_000_000_000.0).coerceAtMost(0.1)
            lastFrameNanos = frameNanos
            phase += dt * speed
        }
        return phase
    }
}

private class FluidClock(startAt: Double) {
    var seconds by mutableDoubleStateOf(startAt)
    var visible by mutableStateOf(true)
}

private fun LayoutCoordinates.isOnScreen(screenW: Float, screenH: Float): Boolean {
    if (!isAttached) return false
    val r = boundsInWindow()
    return r.width > 0f && r.height > 0f && r.right > 0f && r.bottom > 0f &&
        r.left < screenW && r.top < screenH
}

private val UNIFORM_NAMES = arrayOf("c0", "c1", "c2", "c3", "c4")

/**
 * Apakah shader AGSL Organic Fluid bisa dipakai di perangkat ini. False di bawah
 * API 33, atau bila shader gagal dikompilasi (beberapa GPU/ROM). Dicek sekali, lalu di-cache.
 */
object OrganicFluidSupport {
    val shaderSupported: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { RuntimeShader(FLUID_AGSL) }.isSuccess
    }
}

/** Shader dan brush dibuat sekali; per frame hanya uniform yang diubah. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AgslFluid {
    private val shader = RuntimeShader(FLUID_AGSL)
    private val brush = ShaderBrush(shader)

    fun draw(scope: DrawScope, time: Float, colors: List<Color>, seed: Float) {
        shader.setFloatUniform("iResolution", scope.size.width, scope.size.height)
        shader.setFloatUniform("iTime", time)
        shader.setFloatUniform("iSeed", seed)
        val n = colors.size
        for (i in 0 until 5) {
            val c = colors[i % n]
            shader.setFloatUniform(UNIFORM_NAMES[i], c.red, c.green, c.blue)
        }
        scope.drawRect(brush = brush)
    }
}

/** Sisi terpanjang bitmap fallback; blob-nya lembut, jadi resolusi kecil sudah cukup. */
private const val RASTER_LONG_SIDE = 72

/** Selisih fase minimum sebelum bitmap dihitung ulang (sekitar 30 fps pada speed 2.2). */
private const val RASTER_TIME_STEP = 0.066f

private const val BLOB_COUNT = 6

private class FluidFrame(val bitmap: Bitmap) {
    val image: ImageBitmap = bitmap.asImageBitmap()
}

/**
 * Fallback < API 33: versi CPU dari [FLUID_AGSL] (domain warping + blob gaussian),
 * jadi gradasinya melebur sehalus shader. Ubah keduanya bersamaan.
 */
private class FluidRaster {
    // Dua buffer bergantian agar bitmap yang sedang digambar tidak ditimpa.
    private var frames: List<FluidFrame> = emptyList()
    private var front = 0
    private var pixels = IntArray(0)
    private var lastTime = 0f
    private var lastColors: List<Color>? = null

    // Nilai per frame, dipakai ulang supaya tidak ada alokasi saat animasi.
    private val blobX = FloatArray(BLOB_COUNT)
    private val blobY = FloatArray(BLOB_COUNT)
    private val blobRx = FloatArray(BLOB_COUNT)
    private val blobRy = FloatArray(BLOB_COUNT)
    private val blobCos = FloatArray(BLOB_COUNT)
    private val blobSin = FloatArray(BLOB_COUNT)
    private val blobColor = FloatArray(BLOB_COUNT * 3)
    private val baseColor = FloatArray(6)

    fun draw(scope: DrawScope, time: Float, colors: List<Color>) {
        val w = scope.size.width
        val h = scope.size.height
        if (w <= 0f || h <= 0f) return
        val longSide = maxOf(w, h)
        val bw = (RASTER_LONG_SIDE * w / longSide).roundToInt().coerceAtLeast(2)
        val bh = (RASTER_LONG_SIDE * h / longSide).roundToInt().coerceAtLeast(2)
        val resized = frames.isEmpty() || frames[0].bitmap.width != bw || frames[0].bitmap.height != bh
        if (resized) {
            frames = List(2) { FluidFrame(Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)) }
            pixels = IntArray(bw * bh)
        }
        if (resized || colors != lastColors || abs(time - lastTime) >= RASTER_TIME_STEP) {
            render(bw, bh, w, h, time, colors)
            front = 1 - front
            frames[front].bitmap.setPixels(pixels, 0, bw, 0, 0, bw, bh)
            lastTime = time
            lastColors = colors
        }
        scope.drawImage(
            image = frames[front].image,
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
    }

    private fun render(bw: Int, bh: Int, w: Float, h: Float, t: Float, colors: List<Color>) {
        prepareFrame(t, colors)
        val unit = minOf(w * 1.35f, h)
        var index = 0
        for (y in 0 until bh) {
            val py = ((y + 0.5f) * h / bh - 0.5f * h) / unit
            for (x in 0 until bw) {
                val px = ((x + 0.5f) * w / bw - 0.5f * w) / unit

                // Domain warping, tiga oktaf seperti di shader.
                var wx = px + 0.30f * sin(py * 2.3f + t * 0.23f)
                var wy = py + 0.30f * cos(px * 2.1f - t * 0.19f)
                var dx = 0.17f * sin(wy * 3.7f - t * 0.31f + 1.3f)
                var dy = 0.17f * cos(wx * 3.3f + t * 0.27f + 2.1f)
                wx += dx
                wy += dy
                dx = 0.08f * sin(wy * 6.1f + t * 0.41f + 0.7f)
                dy = 0.08f * cos(wx * 5.5f - t * 0.37f + 4.0f)
                wx += dx
                wy += dy

                val g = (0.5f + wy * 0.85f + 0.18f * sin(wx * 2.0f + t * 0.13f)).coerceIn(0f, 1f)
                var r = baseColor[0] + (baseColor[3] - baseColor[0]) * g
                var gr = baseColor[1] + (baseColor[4] - baseColor[1]) * g
                var b = baseColor[2] + (baseColor[5] - baseColor[2]) * g

                for (i in 0 until BLOB_COUNT) {
                    val ox = wx - blobX[i]
                    val oy = wy - blobY[i]
                    val ex = (blobCos[i] * ox - blobSin[i] * oy) / blobRx[i]
                    val ey = (blobSin[i] * ox + blobCos[i] * oy) / blobRy[i]
                    val d = ex * ex + ey * ey
                    // Di luar jarak ini pengaruhnya di bawah satu tingkat warna.
                    if (d > 6f) continue
                    val a = exp(-d) * 0.9f
                    r += (blobColor[i * 3] - r) * a
                    gr += (blobColor[i * 3 + 1] - gr) * a
                    b += (blobColor[i * 3 + 2] - b) * a
                }

                val wave = 0.5f + 0.5f * sin(wx * 2.6f + wy * 1.9f + t * 0.17f)
                val ridge = 0.10f * wave * wave * wave
                r += (1.0f - r) * ridge
                gr += (0.86f - gr) * ridge
                b += (0.86f - b) * ridge

                pixels[index++] = (0xFF shl 24) or
                    (channel(r) shl 16) or
                    (channel(gr) shl 8) or
                    channel(b)
            }
        }
    }

    /** Bagian shader yang sama untuk semua piksel: warna dasar dan bentuk tiap blob. */
    private fun prepareFrame(t: Float, colors: List<Color>) {
        samplePalette(2.0f + t * 0.012f, colors, baseColor, 0)
        samplePalette(3.55f + t * 0.012f, colors, baseColor, 3)
        for (i in 0 until BLOB_COUNT) {
            val k = i + 1f
            val h1 = fract(sin(k * 91.7f) * 437.5f)
            val h2 = fract(sin(k * 37.3f) * 951.1f)
            val h3 = fract(sin(k * 53.9f) * 173.3f)
            val h4 = fract(sin(k * 17.1f) * 683.7f)
            blobX[i] = 0.55f * sin(t * (0.06f + 0.08f * h1) + k * 2.3f) +
                0.20f * sin(t * (0.15f + 0.10f * h2) + k * 5.1f)
            blobY[i] = 0.50f * sin(t * (0.07f + 0.08f * h2) + k * 1.7f + 1.0f) +
                0.20f * sin(t * (0.13f + 0.10f * h3) + k * 3.3f)
            blobRx[i] = 0.26f + 0.32f * h3 + 0.08f * sin(t * (0.10f + 0.10f * h4) + k)
            blobRy[i] = 0.11f + 0.13f * h4 + 0.05f * sin(t * (0.12f + 0.10f * h1) + k * 2.0f)
            val dir = if (h2 > 0.5f) 1f else -1f
            val angle = t * (0.04f + 0.06f * h1) * dir + k * 1.3f
            blobCos[i] = cos(angle)
            blobSin[i] = sin(angle)
            samplePalette(k * 0.83f + t * 0.022f + h1 * 2.0f, colors, blobColor, i * 3)
        }
    }

    private fun samplePalette(x: Float, colors: List<Color>, out: FloatArray, offset: Int) {
        val wrapped = x.mod(5f)
        val i = wrapped.toInt().coerceIn(0, 4)
        val f = wrapped - i
        val s = f * f * (3f - 2f * f)
        val a = colors[i % colors.size]
        val b = colors[((i + 1) % 5) % colors.size]
        out[offset] = a.red + (b.red - a.red) * s
        out[offset + 1] = a.green + (b.green - a.green) * s
        out[offset + 2] = a.blue + (b.blue - a.blue) * s
    }

    private fun fract(v: Float): Float = v - floor(v)

    private fun channel(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}

@Language("AGSL")
private const val FLUID_AGSL = """
uniform float2 iResolution;
uniform float iTime;
uniform float iSeed;
uniform float3 c0;
uniform float3 c1;
uniform float3 c2;
uniform float3 c3;
uniform float3 c4;

float3 pal(float x) {
    x = mod(x, 5.0);
    float3 a = c0;
    float3 b = c1;
    float f = x;
    if (x >= 4.0) { a = c4; b = c0; f = x - 4.0; }
    else if (x >= 3.0) { a = c3; b = c4; f = x - 3.0; }
    else if (x >= 2.0) { a = c2; b = c3; f = x - 2.0; }
    else if (x >= 1.0) { a = c1; b = c2; f = x - 1.0; }
    return mix(a, b, f * f * (3.0 - 2.0 * f));
}

float2 rot(float2 v, float a) {
    float s = sin(a);
    float c = cos(a);
    return float2(c * v.x - s * v.y, s * v.x + c * v.y);
}

// Aliran terdistorsi satu lapisan. fq = frekuensi, wa = arah aliran, sw = putaran pusaran.
float2 flow(float2 p, float t, float fq, float wa, float sw) {
    float2 w = p;
    w += 0.30 * rot(float2(sin(p.y * 2.3 * fq + t * 0.23), cos(p.x * 2.1 * fq - t * 0.19)), wa);
    w += 0.17 * rot(float2(sin(w.y * 3.7 * fq - t * 0.31 + 1.3), cos(w.x * 3.3 * fq + t * 0.27 + 2.1)), wa);
    w += 0.08 * float2(sin(w.y * 6.1 * fq + t * 0.41 + 0.7), cos(w.x * 5.5 * fq - t * 0.37 + 4.0));
    return rot(w, sw * exp(-dot(p, p) * 2.2) * sin(t * 0.09));
}

half4 main(float2 fc) {
    float2 p = (fc - 0.5 * iResolution) / min(iResolution.x * 1.35, iResolution.y);

    // Karakter gerak tiap kartu dari seed: kecepatan, arah aliran, frekuensi, putaran pusaran.
    float s1 = fract(iSeed * 7.31);
    float s2 = fract(iSeed * 13.7 + 0.3);
    float s3 = fract(iSeed * 3.9 + 0.6);
    float t = iTime * (0.75 + 0.8 * s1) + iSeed * 240.0;
    float fq = 0.85 + 0.6 * s3;
    float wa = s2 * 6.2832;
    float sw = (s1 > 0.5 ? 1.0 : -1.0) * (0.5 + s3);

    // Dua lapisan dengan paralaks: lapisan jauh lebih lambat dan bergeser berlawanan.
    float2 par = 0.10 * float2(sin(t * 0.05 + s1 * 6.0), cos(t * 0.043 + s2 * 6.0));
    float2 wNear = flow(p + par, t, fq, wa, sw);
    float2 wFar = flow(p - par * 1.6, t * 0.55 + 17.0, fq * 0.8, wa + 1.0, -sw * 0.6);

    float g = clamp(0.5 + wFar.y * 0.85 + 0.18 * sin(wFar.x * 2.0 + t * 0.13), 0.0, 1.0);
    float3 col = mix(pal(2.0 + t * 0.012), pal(3.55 + t * 0.012), g);
    // Palet ikut dibaca di sepanjang medan aliran, jadi tidak pernah ada area satu warna rata.
    float sweep = wFar.x * 1.1 + wFar.y * 0.8 + 0.5 * sin(wNear.y * 2.4 + t * 0.11);
    col = mix(col, pal(sweep * 1.6 + t * 0.018 + iSeed * 5.0), 0.55);

    float H = 0.0;
    float2 G = float2(0.0);
    for (int i = 0; i < 6; i++) {
        float k = float(i) + 1.0;
        float near = step(3.5, k);
        float ks = k + iSeed * 11.0;
        float tl = mix(t * 0.6, t, near);
        float h1 = fract(sin(ks * 91.7) * 437.5);
        float h2 = fract(sin(ks * 37.3) * 951.1);
        float h3 = fract(sin(ks * 53.9) * 173.3);
        float h4 = fract(sin(ks * 17.1) * 683.7);
        float2 c = float2(
            0.55 * sin(tl * (0.06 + 0.08 * h1) + ks * 2.3) + 0.20 * sin(tl * (0.15 + 0.10 * h2) + ks * 5.1),
            0.50 * sin(tl * (0.07 + 0.08 * h2) + ks * 1.7 + 1.0) + 0.20 * sin(tl * (0.13 + 0.10 * h3) + ks * 3.3)
        );
        float2 r = float2(
            0.26 + 0.32 * h3 + 0.08 * sin(tl * (0.10 + 0.10 * h4) + ks),
            0.11 + 0.13 * h4 + 0.05 * sin(tl * (0.12 + 0.10 * h1) + ks * 2.0)
        ) * mix(1.5, 1.15, near);
        // Pusat blob ditarik ke dalam kartu supaya tidak ada saat semuanya keluar bingkai.
        c *= 0.72;
        float dir = h2 > 0.5 ? 1.0 : -1.0;
        float ang = tl * (0.04 + 0.06 * h1) * dir + ks * 1.3;
        float2 ww = mix(wFar, wNear, near);
        float2 dv = ww - c;
        float2 d = rot(dv, ang) / r;
        float a = exp(-dot(d, d));

        // Bayangan jatuh blob dekat ke lapisan di bawahnya.
        float2 d2 = rot(dv - float2(0.04, 0.06), ang) / r;
        col *= 1.0 - near * 0.16 * exp(-dot(d2, d2)) * (1.0 - clamp(a, 0.0, 1.0));

        float3 pc = pal(ks * 0.83 + tl * 0.022 + h1 * 2.0) * mix(0.78, 1.0, near);
        col = mix(col, pc, clamp(a, 0.0, 1.0) * mix(0.70, 0.92, near));

        // Medan ketebalan + gradiennya (hanya blob dekat) untuk pencahayaan.
        H += near * a;
        G += near * (-2.0 * a) * rot(d / r, -ang);
    }

    // Pencahayaan: terang dari kiri atas, sisi sebaliknya menggelap, sorot spekular dan rim.
    float3 L = normalize(float3(-0.55, -0.65, 0.62));
    float3 n = normalize(float3(-G * 0.18, 1.0));
    float shade = smoothstep(0.0, 1.0, H);
    col *= 1.0 + (dot(n, L) - L.z) * 0.8 * shade;
    float3 hv = normalize(L + float3(0.0, 0.0, 1.0));
    float spec = pow(clamp(dot(n, hv), 0.0, 1.0), 14.0) * smoothstep(0.1, 0.9, H);
    col += float3(1.0, 0.96, 0.94) * spec * 0.20;
    col += pow(1.0 - n.z, 2.0) * smoothstep(0.1, 0.8, H) * 0.10;
    col *= mix(0.93, 1.0, smoothstep(0.0, 0.7, H));

    // Kilau lembut seperti cahaya yang menembus cairan.
    float ridge = pow(0.5 + 0.5 * sin(wNear.x * 2.6 + wNear.y * 1.9 + t * 0.17), 3.0);
    col = mix(col, float3(1.0, 0.86, 0.86), 0.08 * ridge);

    // Vignette tepi dan sheen tipis dari atas, supaya kartu terasa cembung.
    float2 q = fc / iResolution - 0.5;
    col *= 1.0 - 0.20 * smoothstep(0.30, 0.75, length(q));
    col += 0.05 * (1.0 - smoothstep(0.0, 0.45, fc.y / iResolution.y));

    // Dither agar tidak ada banding pada gradien halus.
    col += (fract(sin(dot(fc, float2(12.9898, 78.233))) * 43758.5453) - 0.5) / 255.0;
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""