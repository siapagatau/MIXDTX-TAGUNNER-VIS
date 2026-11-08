package com.kendang.realpads

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------------------------------
// BAGIAN FX (gaya taGunner) - tampil MENYATU di bawah kompresor dan DI ATAS equalizer saat "mode FX" aktif (layar utama &
// floating). Memakai komponen mixer yang sama (MixSection, GroupCard, KnobTile) -> tampilan mengikuti APK.
//   FX RACK    : SUBSONIC, SUB BASS, TUBE DRIVE, 8D PAN
//   EQUALIZER PARAMETRIK : gaya Poweramp - band BEBAS (tambah/hapus 0..16, awal 7), jenis filter, Frekuensi/Gain/Q, sisi suara kiri/kanan, Preamp; titik selalu di kurva
// ---------------------------------------------------------------------------------------------------------------------

private const val FX_WIDE_MIN_DP = 600
private const val GRAPH_H_DP = 150
private const val GRAPH_DB = 20f

@Composable
fun FxSection(opacity: Float, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    remember { FxUi.ensureLoaded(ctx); Eq31Ui.ensureLoaded(ctx); CompUi.ensureLoaded(ctx) }
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= FX_WIDE_MIN_DP.dp
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FxRack(wide, opacity)
            FxParametric(wide, opacity)
        }
    }
}

// ---- komponen kecil ----

/** Tombol kecil dengan keadaan ON (hijau). onLong = tahan lama (opsional). warn = latar merah (minta konfirmasi). */
@Composable
private fun FxChip(label: String, on: Boolean, modifier: Modifier = Modifier, onLong: (() -> Unit)? = null, warn: Boolean = false, onTap: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .background(if (warn) Color(0xFFB3402E) else if (on) IndicatorGreen else PanelColor, shape)
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }, onLongPress = { long?.invoke() }) }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = if (warn) Color.White else if (on) IndicatorGreenText else Color.White,
            fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center
        )
    }
}

private fun fxKnob(v: Float) = v.coerceIn(0f, 1f)

// ---- FX RACK ----

@Composable
private fun FxRack(wide: Boolean, opacity: Float) {
    val ctx = LocalContext.current
    val s = FxUi.s
    val status by GlobalEqEngine.fxStatus.collectAsState()
    val master = s.enabled
    val cardH = cardHeight(1)

    @Composable
    fun subsonic(m: Modifier) = GroupCard("SUBSONIC", s.subsonicOn, { FxUi.update(ctx) { it.copy(subsonicOn = !it.subsonicOn) } }, opacity, m) {
        KnobTile("Potong", s.subsonic, Fx.subsonicText(s.subsonic), opacity, Modifier.weight(1f).fillMaxWidth(), dim = !(master && s.subsonicOn),
            onReset = { FxUi.update(ctx) { it.copy(subsonic = 0.3f) } }) { v -> FxUi.update(ctx) { it.copy(subsonic = fxKnob(v)) } }
    }

    @Composable
    fun subBass(m: Modifier) = GroupCard("SUB BASS", s.subBassOn, { FxUi.update(ctx) { it.copy(subBassOn = !it.subBassOn) } }, opacity, m) {
        KnobTile("40 Hz", s.subBass, "+" + Fx.dbText(Fx.subBassDb(s.subBass)), opacity, Modifier.weight(1f).fillMaxWidth(), dim = !(master && s.subBassOn),
            onReset = { FxUi.update(ctx) { it.copy(subBass = 0.4f) } }) { v -> FxUi.update(ctx) { it.copy(subBass = fxKnob(v)) } }
    }

    @Composable
    fun tube(m: Modifier) = GroupCard("TUBE DRIVE", s.tubeOn, { FxUi.update(ctx) { it.copy(tubeOn = !it.tubeOn) } }, opacity, m) {
        KnobTile("Drive", s.tube, "+" + Fx.dbText(Fx.tubeDb(s.tube)), opacity, Modifier.weight(1f).fillMaxWidth(), dim = !(master && s.tubeOn),
            onReset = { FxUi.update(ctx) { it.copy(tube = 0.3f) } }) { v -> FxUi.update(ctx) { it.copy(tube = fxKnob(v)) } }
    }

    @Composable
    fun pan8d(m: Modifier) = GroupCard("8D PAN", s.panOn, { FxUi.update(ctx) { it.copy(panOn = !it.panOn) } }, opacity, m) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            KnobTile("Depth", s.panDepth, Fx.dbText(Fx.panDepthDb(s.panDepth)), opacity, Modifier.weight(1f).fillMaxHeight(), dim = !(master && s.panOn),
                onReset = { FxUi.update(ctx) { it.copy(panDepth = 0.7f) } }) { v -> FxUi.update(ctx) { it.copy(panDepth = fxKnob(v)) } }
            KnobTile("Putaran", s.panCycle, Fx.panPeriodText(s.panCycle), opacity, Modifier.weight(1f).fillMaxHeight(), dim = !(master && s.panOn),
                onReset = { FxUi.update(ctx) { it.copy(panCycle = 0.4f) } }) { v -> FxUi.update(ctx) { it.copy(panCycle = fxKnob(v)) } }
        }
    }

    MixSection("FX RACK", master, { FxUi.setEnabled(ctx, !master) }, opacity, Modifier.fillMaxWidth(), fill = false) {
        if (wide) {
            Row(Modifier.fillMaxWidth().height(cardH), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                subsonic(Modifier.weight(1f).fillMaxHeight()); subBass(Modifier.weight(1f).fillMaxHeight())
                tube(Modifier.weight(1f).fillMaxHeight()); pan8d(Modifier.weight(2f).fillMaxHeight())
            }
        } else {
            Row(Modifier.fillMaxWidth().height(cardH), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                subsonic(Modifier.weight(1f).fillMaxHeight()); subBass(Modifier.weight(1f).fillMaxHeight())
            }
            Row(Modifier.fillMaxWidth().height(cardH), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tube(Modifier.weight(1f).fillMaxHeight()); pan8d(Modifier.weight(2f).fillMaxHeight())
            }
        }
        status?.let {
            Text(it, color = Color(0xFFFFB74D), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

// ---- PARAMETRIC (gaya Poweramp) ----

private val WarnBg = Color(0xFF3A2E14)
private val WarnText = Color(0xFFFFB74D)
private val RightChannelColor = Color(0xFF4C8DFF)

/** Penanda singkat yang bisa diketuk (FX RACK masih OFF). */
@Composable
private fun FxNotice(text: String, onTap: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    Box(
        Modifier.fillMaxWidth().height(30.dp).background(Color(0xFF3A2E14), RoundedCornerShape(8.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) },
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color(0xFFFFB74D), fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
}

/** Frekuensi band baru: di tengah (skala log) celah terlebar antar band yang sudah ada, supaya tidak menumpuk. */
private fun suggestHz(bands: List<PeqBand>): Float {
    if (bands.isEmpty()) return 1000f
    val pts = (listOf(Fx.HZ_MIN, Fx.HZ_MAX) + bands.map { it.hz }).sorted()
    var bestGap = 0f
    var best = 1000f
    for (i in 0 until pts.size - 1) {
        val gap = ln(pts[i + 1] / pts[i])
        if (gap > bestGap) { bestGap = gap; best = kotlin.math.sqrt(pts[i] * pts[i + 1]) }
    }
    return best
}

@Composable
private fun FxParametric(wide: Boolean, opacity: Float) {
    val ctx = LocalContext.current
    val s = FxUi.s
    val sel = if (s.bands.isEmpty()) -1 else FxUi.selected.coerceIn(0, s.bands.size - 1)
    val b = s.bands.getOrNull(sel)
    val on = s.enabled && s.peqOn
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(confirmReset) { if (confirmReset) { delay(3000); confirmReset = false } }
    val rowH = 34.dp

    MixSection("PARAMETRIC", s.peqOn, { FxUi.update(ctx) { it.copy(peqOn = !it.peqOn) } }, opacity, Modifier.fillMaxWidth(), fill = false) {
        if (!s.enabled) FxNotice("FX RACK OFF") { FxUi.setEnabled(ctx, true) }

        PeqGraph(s.bands, sel, Eq31Ui.s, on, Modifier.fillMaxWidth().height(GRAPH_H_DP.dp))

        // band, urut dari frekuensi terendah (tanpa nomor)
        if (s.bands.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(rowH).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in s.bands.indices.sortedBy { s.bands[it].hz }) {
                    FxChip(Fx.hzText(s.bands[i].hz), i == sel, Modifier.fillMaxHeight().width(78.dp)) { FxUi.selected = i }
                }
            }
        }

        // aksi
        Row(Modifier.fillMaxWidth().height(rowH), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FxIconChip(FxIcon.PLUS, Modifier.weight(1f).fillMaxHeight()) { addPeqBand(ctx, suggestHz(s.bands), 0f) }
            FxIconChip(FxIcon.TRASH, Modifier.weight(1f).fillMaxHeight().alpha(if (b != null) 1f else 0.4f)) { if (b != null) FxUi.removeSelected(ctx) }
            FxIconChip(if (confirmReset) FxIcon.CONFIRM else FxIcon.RESET, Modifier.weight(1f).fillMaxHeight(), warn = confirmReset) {
                if (confirmReset) { confirmReset = false; FxUi.resetBands(ctx) } else confirmReset = true
            }
        }

        if (b != null) {
            val gainOn = Fx.usesGain(b.type)
            FxSegment(listOf("L+R", "L", "R"), b.ch, Modifier.fillMaxWidth().height(rowH)) { c -> FxUi.setBand(ctx, sel) { it.copy(ch = c) } }
            val names = Fx.TYPE_NAMES
            if (wide) {
                FxSegment(names, b.type, Modifier.fillMaxWidth().height(rowH), TYPE_ICONS) { t -> FxUi.setBand(ctx, sel) { it.copy(type = t) } }
            } else {
                FxSegment(names.subList(0, 3), b.type, Modifier.fillMaxWidth().height(rowH), TYPE_ICONS.subList(0, 3)) { t -> FxUi.setBand(ctx, sel) { it.copy(type = t) } }
                FxSegment(names.subList(3, 6), b.type - 3, Modifier.fillMaxWidth().height(rowH), TYPE_ICONS.subList(3, 6)) { t -> FxUi.setBand(ctx, sel) { it.copy(type = t + 3) } }
            }
            Row(Modifier.fillMaxWidth().height(KNOB_ROW_DP.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KnobTile("Frekuensi", Fx.hzToKnob(b.hz), Fx.hzText(b.hz), opacity, Modifier.weight(1f).fillMaxHeight(), dim = !on) { v ->
                    FxUi.setBand(ctx, sel) { it.copy(hz = Fx.hzOf(v)) }
                }
                KnobTile("Gain", Fx.gainToKnob(b.db), Fx.dbSigned(b.db), opacity, Modifier.weight(1f).fillMaxHeight(), bipolar = true, dim = !(on && gainOn),
                    onReset = { FxUi.setBand(ctx, sel) { it.copy(db = 0f) } }) { v ->
                    FxUi.setBand(ctx, sel) { it.copy(db = Fx.gainOf(v)) }
                }
                KnobTile("Q", Fx.qToKnob(b.q), Fx.qText(b.q), opacity, Modifier.weight(1f).fillMaxHeight(), dim = !on,
                    onReset = { FxUi.setBand(ctx, sel) { it.copy(q = 1.0f) } }) { v ->
                    FxUi.setBand(ctx, sel) { it.copy(q = Fx.qOf(v)) }
                }
                KnobTile("Preamp", s.peqPre, Fx.dbSigned(Fx.gainOf(s.peqPre)), opacity, Modifier.weight(1f).fillMaxHeight(), bipolar = true, dim = !on,
                    onReset = { FxUi.update(ctx) { it.copy(peqPre = 0.5f) } }) { v ->
                    FxUi.update(ctx) { it.copy(peqPre = v.coerceIn(0f, 1f)) }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().height(KNOB_ROW_DP.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KnobTile("Preamp", s.peqPre, Fx.dbSigned(Fx.gainOf(s.peqPre)), opacity, Modifier.weight(1f).fillMaxHeight(), bipolar = true, dim = !on,
                    onReset = { FxUi.update(ctx) { it.copy(peqPre = 0.5f) } }) { v ->
                    FxUi.update(ctx) { it.copy(peqPre = v.coerceIn(0f, 1f)) }
                }
                Box(Modifier.weight(3f))
            }
        }
    }
}

// ---- ikon (digambar sendiri, tanpa pustaka tambahan) ----

private enum class FxIcon { PLUS, TRASH, RESET, CONFIRM, LOW_PASS, HIGH_PASS, BAND_PASS, PEAK, LOW_SHELF, HIGH_SHELF }

/** Ikon tiap tipe filter, indeks = Fx.T_*. */
private val TYPE_ICONS = listOf(FxIcon.LOW_PASS, FxIcon.HIGH_PASS, FxIcon.BAND_PASS, FxIcon.PEAK, FxIcon.LOW_SHELF, FxIcon.HIGH_SHELF)

private fun DrawScope.drawFxIcon(icon: FxIcon, color: Color) {
    val w = size.width
    val h = size.height
    val sw = 2f * density
    val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(b: Path.(Float, Float) -> Unit): Path = Path().also { it.b(w, h) }
    when (icon) {
        FxIcon.PLUS -> {
            drawLine(color, Offset(0.5f * w, 0.18f * h), Offset(0.5f * w, 0.82f * h), sw, StrokeCap.Round)
            drawLine(color, Offset(0.18f * w, 0.5f * h), Offset(0.82f * w, 0.5f * h), sw, StrokeCap.Round)
        }
        FxIcon.TRASH -> {
            drawLine(color, Offset(0.15f * w, 0.26f * h), Offset(0.85f * w, 0.26f * h), sw, StrokeCap.Round)
            drawPath(p { a, b -> moveTo(0.38f * a, 0.26f * b); lineTo(0.38f * a, 0.12f * b); lineTo(0.62f * a, 0.12f * b); lineTo(0.62f * a, 0.26f * b) }, color, style = st)
            drawPath(p { a, b -> moveTo(0.26f * a, 0.26f * b); lineTo(0.31f * a, 0.88f * b); lineTo(0.69f * a, 0.88f * b); lineTo(0.74f * a, 0.26f * b) }, color, style = st)
        }
        FxIcon.RESET -> {
            val cx = 0.5f * w; val cy = 0.5f * h; val r = 0.32f * w
            drawArc(color, -50f, 280f, false, Offset(cx - r, cy - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = st)
            val ang = Math.toRadians(230.0)
            val ex = cx + r * kotlin.math.cos(ang).toFloat(); val ey = cy + r * kotlin.math.sin(ang).toFloat()
            val tx = -kotlin.math.sin(ang).toFloat(); val ty = kotlin.math.cos(ang).toFloat()
            val nx = kotlin.math.cos(ang).toFloat(); val ny = kotlin.math.sin(ang).toFloat()
            val a = 0.13f * w
            drawPath(Path().apply {
                moveTo(ex + tx * a * 1.4f, ey + ty * a * 1.4f)
                lineTo(ex + nx * a, ey + ny * a); lineTo(ex - nx * a, ey - ny * a); close()
            }, color)
        }
        FxIcon.CONFIRM -> drawPath(p { a, b -> moveTo(0.2f * a, 0.55f * b); lineTo(0.42f * a, 0.76f * b); lineTo(0.8f * a, 0.28f * b) }, color, style = st)
        FxIcon.LOW_PASS -> drawPath(p { a, b -> moveTo(0.05f * a, 0.3f * b); lineTo(0.4f * a, 0.3f * b); cubicTo(0.6f * a, 0.3f * b, 0.6f * a, 0.8f * b, 0.8f * a, 0.8f * b); lineTo(0.95f * a, 0.8f * b) }, color, style = st)
        FxIcon.HIGH_PASS -> drawPath(p { a, b -> moveTo(0.05f * a, 0.8f * b); lineTo(0.2f * a, 0.8f * b); cubicTo(0.4f * a, 0.8f * b, 0.4f * a, 0.3f * b, 0.6f * a, 0.3f * b); lineTo(0.95f * a, 0.3f * b) }, color, style = st)
        FxIcon.BAND_PASS -> drawPath(p { a, b -> moveTo(0.05f * a, 0.8f * b); lineTo(0.2f * a, 0.8f * b); cubicTo(0.4f * a, 0.8f * b, 0.35f * a, 0.2f * b, 0.5f * a, 0.2f * b); cubicTo(0.65f * a, 0.2f * b, 0.6f * a, 0.8f * b, 0.8f * a, 0.8f * b); lineTo(0.95f * a, 0.8f * b) }, color, style = st)
        FxIcon.PEAK -> drawPath(p { a, b -> moveTo(0.05f * a, 0.65f * b); lineTo(0.2f * a, 0.65f * b); cubicTo(0.4f * a, 0.65f * b, 0.37f * a, 0.15f * b, 0.5f * a, 0.15f * b); cubicTo(0.63f * a, 0.15f * b, 0.6f * a, 0.65f * b, 0.8f * a, 0.65f * b); lineTo(0.95f * a, 0.65f * b) }, color, style = st)
        FxIcon.LOW_SHELF -> drawPath(p { a, b -> moveTo(0.05f * a, 0.25f * b); lineTo(0.35f * a, 0.25f * b); cubicTo(0.55f * a, 0.25f * b, 0.5f * a, 0.7f * b, 0.7f * a, 0.7f * b); lineTo(0.95f * a, 0.7f * b) }, color, style = st)
        FxIcon.HIGH_SHELF -> drawPath(p { a, b -> moveTo(0.05f * a, 0.7f * b); lineTo(0.3f * a, 0.7f * b); cubicTo(0.5f * a, 0.7f * b, 0.45f * a, 0.25f * b, 0.65f * a, 0.25f * b); lineTo(0.95f * a, 0.25f * b) }, color, style = st)
    }
}

@Composable
private fun FxIconView(icon: FxIcon, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawFxIcon(icon, color) }
}

/** Tombol ikon saja (tanpa teks). */
@Composable
private fun FxIconChip(icon: FxIcon, modifier: Modifier = Modifier, warn: Boolean = false, onTap: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    Box(
        modifier
            .background(if (warn) Color(0xFFB3402E) else PanelColor, RoundedCornerShape(8.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) },
        contentAlignment = Alignment.Center
    ) { FxIconView(icon, Color.White, Modifier.size(20.dp)) }
}

/** Pilihan menyatu (segmented): satu kotak abu, pilihan aktif hijau. selected di luar rentang = tidak ada yang aktif. */
@Composable
private fun FxSegment(labels: List<String>, selected: Int, modifier: Modifier = Modifier, icons: List<FxIcon>? = null, onSelect: (Int) -> Unit) {
    val cb by rememberUpdatedState(onSelect)
    Row(modifier.background(PanelColor, RoundedCornerShape(8.dp)).padding(2.dp)) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            val fg = if (on) IndicatorGreenText else Color.White
            Row(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (on) IndicatorGreen else Color.Transparent, RoundedCornerShape(6.dp))
                    .pointerInput(i) { detectTapGestures(onTap = { cb(i) }) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
            ) {
                icons?.getOrNull(i)?.let { FxIconView(it, fg, Modifier.size(18.dp).padding(end = 0.dp)) }
                Text(
                    l, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = if (icons != null) 4.dp else 0.dp, end = 2.dp)
                )
            }
        }
    }
}

/** Tambah band bebas di (hz, gain target di kurva). Gain dihitung supaya kurva lewat tepat di titik yang diketuk. */
private fun addPeqBand(ctx: Context, hz: Float, targetDb: Float) {
    FxUi.ensureLoaded(ctx)
    val ok = FxUi.addBand(ctx, hz, 0f)
    if (!ok) { Toast.makeText(ctx, "Maksimal ${Fx.MAX_BANDS} band", Toast.LENGTH_SHORT).show(); return }
    val i = FxUi.s.bands.size - 1
    val others = FxUi.s.bands.filterIndexed { idx, _ -> idx != i }
    val eq = Eq31Ui.s
    val on = FxUi.s.enabled && FxUi.s.peqOn
    val db = (targetDb - curveDbRaw(others, eq, on, hz, 0)).coerceIn(-Fx.GAIN_RANGE_DB, Fx.GAIN_RANGE_DB)
    FxUi.setBand(ctx, i) { it.copy(db = db) }
}

/** Respons EQ 31 band (rata-rata L dan R) di frekuensi f, dB - ditampilkan bersama kurva parametrik di grafik. */
private fun eq31Db(eq: Eq31Settings, f: Float, ch: Int): Float {
    if (!eq.enabled) return 0f
    val c = Eq31.CENTERS
    fun at(i: Int) = Eq31.knobToDb(eq.band(ch, i))
    if (f <= c[0]) return at(0)
    if (f >= c[c.size - 1]) return at(c.size - 1)
    var i = 0
    while (i < c.size - 2 && f > c[i + 1]) i++
    val t = (kotlin.math.ln(f / c[i]) / kotlin.math.ln(c[i + 1] / c[i])).coerceIn(0f, 1f)
    return at(i) + (at(i + 1) - at(i)) * t
}

/** Kurva yang didengar kanal ch di frekuensi f: EQ 31 band + parametrik (dibatasi seperti grafik). */
private fun curveDb(bands: List<PeqBand>, eq: Eq31Settings, on: Boolean, f: Float, ch: Int): Float {
    val peq = if (on) Fx.totalDb(bands, f, ch) else 0f
    return (peq + eq31Db(eq, f, ch)).coerceIn(-GRAPH_DB, GRAPH_DB)
}

@Composable
private fun PeqGraph(bands: List<PeqBand>, selected: Int, eq: Eq31Settings, on: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    val accent = ThemeState.accentColor
    val cur by rememberUpdatedState(bands)
    val eqNow by rememberUpdatedState(eq)
    val onNow by rememberUpdatedState(on)
    Canvas(
        modifier
            .background(CtrlPanelColor, RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                val hitPx = 32.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val list = cur
                    // Titik berada DI KURVA: y titik = nilai kurva di frekuensinya (kanal band itu).
                    fun dotX(b: PeqBand) = Fx.hzToKnob(b.hz) * w
                    fun dotY(b: PeqBand): Float {
                        val ch = if (b.ch == 2) 1 else 0
                        return (0.5f - curveDb(list, eqNow, onNow, b.hz, ch) / (2f * GRAPH_DB)) * h
                    }
                    var hit = -1
                    var best = hitPx
                    list.forEachIndexed { i, b ->
                        val d = hypot(dotX(b) - down.position.x, dotY(b) - down.position.y)
                        if (d < best) { best = d; hit = i }
                    }
                    if (hit >= 0) {
                        FxUi.selected = hit
                        down.consume()
                        drag(down.id) { change ->
                            val b = cur[hit]
                            val hz = Fx.hzOf((change.position.x / w).coerceIn(0f, 1f))
                            val target = ((0.5f - change.position.y / h) * 2f * GRAPH_DB).coerceIn(-GRAPH_DB, GRAPH_DB)
                            var db = b.db
                            if (Fx.usesGain(b.type)) {
                                // gain baru supaya kurva lewat tepat di titik yang diseret
                                val ch = if (b.ch == 2) 1 else 0
                                val others = curveDbRaw(cur.filterIndexed { i, _ -> i != hit }, eqNow, onNow, hz, ch)
                                val k = Fx.bandDb(b.copy(hz = hz, db = 1f), hz).let { if (kotlin.math.abs(it) < 0.05f) 0.5f else it }
                                db = ((target - others) / k).coerceIn(-Fx.GAIN_RANGE_DB, Fx.GAIN_RANGE_DB)
                            }
                            FxUi.setBand(ctx, hit) { it.copy(hz = hz, db = db) }
                            change.consume()
                        }
                    } else {
                        // ketuk area kosong = tambah band baru tepat di situ (kurva lewat titik itu)
                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            val hz = Fx.hzOf((up.position.x / w).coerceIn(0f, 1f))
                            val target = ((0.5f - up.position.y / h) * 2f * GRAPH_DB).coerceIn(-GRAPH_DB, GRAPH_DB)
                            addPeqBand(ctx, hz, target)
                        }
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val grid = Color(0xFF3A3A3E)
        fun yOf(db: Float) = (0.5f - db / (2f * GRAPH_DB)) * h
        // garis bantu: dB (sumbu tegak) dan frekuensi (sumbu mendatar)
        for (db in floatArrayOf(-10f, 0f, 10f)) {
            drawLine(if (db == 0f) Color(0xFF6A6A70) else grid, Offset(0f, yOf(db)), Offset(w, yOf(db)), strokeWidth = 1f)
        }
                for (hz in floatArrayOf(100f, 1000f, 10000f)) {
            val x = Fx.hzToKnob(hz) * w
            drawLine(grid, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }
        val nc = drawContext.canvas.nativeCanvas
        val label = android.graphics.Paint().apply { color = 0xFF8C8C90.toInt(); textSize = 10.sp.toPx(); isAntiAlias = true }
        // label sumbu: dB di kiri, Hz di bawah
        for ((db, t) in listOf(10f to "+10", 0f to "0", -10f to "-10")) nc.drawText(t, 4f, yOf(db) - 3f, label)
        for ((hz, t) in listOf(100f to "100", 1000f to "1k", 10000f to "10k")) {
            nc.drawText(t, Fx.hzToKnob(hz) * w + 3f, h - 4f, label)
        }
        val n = 240
        val hasR = bands.any { it.ch != 0 }
        fun curve(ch: Int, close: Boolean): androidx.compose.ui.graphics.Path {
            val path = androidx.compose.ui.graphics.Path()
            if (close) path.moveTo(0f, yOf(0f))
            for (k in 0..n) {
                val x = w * k / n
                val y = yOf(curveDb(bands, eq, on, Fx.hzOf(k / n.toFloat()), ch))
                if (k == 0 && !close) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (close) { path.lineTo(w, yOf(0f)); path.close() }
            return path
        }
        val lineColor = if (on) IndicatorGreen else Color(0xFF6A6A70)
        // garis putus-putus di frekuensi band terpilih
        bands.getOrNull(selected)?.let { sb ->
            val x = Fx.hzToKnob(sb.hz) * w
            drawLine(
                accent.copy(alpha = 0.55f), Offset(x, 0f), Offset(x, h), strokeWidth = 1.5f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            )
        }
        drawPath(curve(0, true), lineColor.copy(alpha = 0.18f))
        drawPath(curve(0, false), lineColor, style = Stroke(width = 3f))
        if (hasR) drawPath(curve(1, false), RightChannelColor, style = Stroke(width = 3f))
        // titik: tepat di kurva; band terpilih lebih besar dan berwarna tema
        bands.forEachIndexed { i, b ->
            val ch = if (b.ch == 2) 1 else 0
            val c = Offset(Fx.hzToKnob(b.hz) * w, yOf(curveDb(bands, eq, on, b.hz, ch)))
            val isSel = i == selected
            val r = (if (isSel) 8f else 5.5f) * density
            drawCircle(if (isSel) accent else Color.White, radius = r, center = c)
            drawCircle(Color.Black, radius = r, center = c, style = Stroke(width = 1.5f))
        }
    }
}

/** Seperti curveDb tapi tanpa pembatas grafik: dipakai menghitung gain saat titik diseret. */
private fun curveDbRaw(bands: List<PeqBand>, eq: Eq31Settings, on: Boolean, f: Float, ch: Int): Float {
    var t = 0f
    if (on) for (b in bands) if (Fx.inChannel(b, ch)) t += Fx.bandDb(b, f)
    return t + eq31Db(eq, f, ch)
}
