package com.kendang.realpads

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.min

// ---------------------------------------------------------------------------------------------------------------------
// VISUALIZER - spektrum suara SEMUA app (mix output, Visualizer audio session 0 lewat MusicMeter.readSpectrum). Bagian ini tampil di bawah
// EQUALIZER dan DI ATAS FX saat "mode VIS" aktif (layar utama & floating). Urutan: mixer -> KOMPRESOR -> EQUALIZER -> VISUALIZER -> FX.
// Satu tampilan saja (tanpa pilihan gaya/warna): kolom LED bertingkat 64 pita + pantulan, warna mengikuti aksen tema.
// Saklar judul = jalan / jeda (jeda = Visualizer dilepas, hemat baterai).
// Butuh izin mikrofon (syarat Android untuk Visualizer). Respons LANGSUNG (tanpa efek telat) dan skala dB tetap: tinggi batang = keras tiap frekuensi.
// ---------------------------------------------------------------------------------------------------------------------

data class VizSettings(
    /** Bagian VISUALIZER tampil. */
    val mode: Boolean = false,
    /** Sedang berjalan (false = jeda). */
    val running: Boolean = true
)

object VizStore {
    private const val PREFS = "viz_settings"

    fun load(context: Context): VizSettings {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = VizSettings()
        return VizSettings(
            mode = p.getBoolean("mode", d.mode),
            running = p.getBoolean("running", d.running)
        )
    }

    fun save(context: Context, s: VizSettings) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("mode", s.mode).putBoolean("running", s.running).apply()
    }
}

/** State bersama layar utama + floating (sama pola dengan FxUi / CompUi / Eq31Ui). */
object VizUi {
    var s: VizSettings by mutableStateOf(VizSettings())
        private set
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (!loaded) {
            s = VizStore.load(context.applicationContext)
            loaded = true
        }
    }

    fun update(context: Context, f: (VizSettings) -> VizSettings) {
        ensureLoaded(context)
        val n = f(s)
        s = n
        VizStore.save(context, n)
    }

    fun setMode(context: Context, on: Boolean) = update(context) { it.copy(mode = on) }
}

private const val VIZ_BANDS = 64
private const val VIZ_WIDE_MIN_DP = 600

/** Data animasi: level tiap pita = keras suara frekuensi itu SAAT INI (tanpa penghalusan, tanpa peak tahan, tanpa AGC). */
private class VizState {
    val levels = FloatArray(VIZ_BANDS)

    fun step() {
        if (!MusicMeter.readSpectrum(levels)) java.util.Arrays.fill(levels, 0f)
    }
}

@Composable
fun VizSection(opacity: Float, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    remember { VizUi.ensureLoaded(ctx) }
    val s = VizUi.s
    val hasPerm = remember(s.running) { ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val err by MusicMeter.specError.collectAsState()
    val active = s.running && hasPerm
    val state = remember { VizState() }
    var frame by remember { mutableLongStateOf(0L) }
    val accent = ThemeState.accentColor

    DisposableEffect(active) {
        if (active) MusicMeter.acquireSpectrum()
        onDispose { if (active) MusicMeter.releaseSpectrum() }
    }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            val t = withFrameNanos { it }
            state.step() // baca sinkron di frame yang sama -> tanpa jeda tambahan
            frame = t
        }
    }

    BoxWithConstraints(modifier) {
        val wide = maxWidth >= VIZ_WIDE_MIN_DP.dp
        MixSection("VISUALIZER", s.running, { VizUi.update(ctx) { it.copy(running = !it.running) } }, opacity, Modifier.fillMaxWidth(), fill = false) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (wide) 230.dp else 190.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF101016), Color(0xFF07070A))))
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    frame // baca state supaya Canvas digambar ulang tiap frame
                    drawGrid()
                    drawBars(state, accent)
                }
                val note = when {
                    !hasPerm -> "Beri izin mikrofon agar visualizer jalan"
                    !s.running -> "JEDA"
                    err != null -> "Visualizer tidak didukung di HP ini"
                    else -> null
                }
                if (note != null) {
                    Text(
                        note, color = Color(0xFFFFB74D), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

// ---- warna ----

/** Warna segmen: aksen tema, makin tinggi makin terang. */
private fun vizColor(level: Float, accent: Color): Color = lerp(accent, Color.White, 0.12f + 0.5f * level.coerceIn(0f, 1f))

// ---- gambar ----

private fun DrawScope.drawGrid() {
    val w = size.width
    val h = size.height
    val line = Color(0x0FFFFFFF)
    for (i in 1..4) {
        val y = h * i / 5f
        drawLine(line, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
    }
}

/** BAR: kolom LED bertingkat, peak cap, pantulan di bawah garis dasar. */
private fun DrawScope.drawBars(st: VizState, accent: Color) {
    val w = size.width
    val h = size.height
    val n = VIZ_BANDS
    val padX = 8.dp.toPx()
    val base = h * 0.76f
    val slot = (w - 2 * padX) / n
    val bw = slot * 0.7f
    val segH = 4.dp.toPx()
    val gap = 1.6.dp.toPx()
    val step = segH + gap
    val maxH = base - 8.dp.toPx()
    val segs = (maxH / step).toInt().coerceAtLeast(4)
    val reflSegs = ((h - base - 4.dp.toPx()) / step).toInt().coerceAtLeast(1)
    for (i in 0 until n) {
        val x = padX + i * slot + (slot - bw) / 2f
        val lit = ceil(st.levels[i] * segs).toInt().coerceIn(0, segs)
        for (g in 0 until lit) {
            val c = vizColor(g / segs.toFloat(), accent)
            drawRect(c, Offset(x, base - (g + 1) * step + gap), Size(bw, segH))
        }
        val rl = min(lit, reflSegs)
        for (g in 0 until rl) {
            val c = vizColor(g / segs.toFloat(), accent)
            drawRect(c.copy(alpha = 0.26f * (1f - g / reflSegs.toFloat())), Offset(x, base + gap + g * step), Size(bw, segH))
        }
    }
    drawLine(Color(0x33FFFFFF), Offset(padX, base + 0.5f), Offset(w - padX, base + 0.5f), strokeWidth = 1f)
}
