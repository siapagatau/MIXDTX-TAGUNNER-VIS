package com.kendang.realpads

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------------------------------
// FX (fitur gaya taGunner yang belum ada di MIXDTX). Semuanya bekerja di suara SEMUA app lewat efek global yang SAMA dengan
// EQ / kompresor (GlobalEqEngine, audio session 0):
//   - SUBSONIC     : memotong bass terendah (di bawah frekuensi potong, 12 dB/oktaf) -> pre-EQ 31 band
//   - SUB BASS     : dorongan bass 40 Hz (0..+12 dB) -> pre-EQ 31 band
//   - TUBE DRIVE   : LoudnessEnhancer (hangat/padat ala tabung)
//   - 8D PAN       : suara berputar kiri-kanan (depth + kecepatan) lewat gain masuk per sisi
//   - PARAMETRIC   : gaya Poweramp - 7 band tetap, tiap band punya tipe filter (Low Pass, High Pass, Band Pass, Peak,
//                    Low Shelf, High Shelf), Frekuensi, Gain, Q, kanal (L+R / L / R) + Preamp. Respons filter dihitung dari
//                    rumus biquad (RBJ) dan dipasang ke post-EQ 96 band.
// "Master" FX (fx.enabled) memegang semua efek di atas. Nilai awal semua efek OFF.
// ---------------------------------------------------------------------------------------------------------------------
object Fx {
    /** Jumlah band maksimum (bebas tambah/hapus 0..16) dan jumlah band awal. */
    const val MAX_BANDS = 16
    const val DEFAULT_BANDS = 7
    const val PEQ_BANDS = 96
    /** Batas gain per band post-EQ (dB) di mesin audio dan grafik. */
    const val PEQ_MAX_DB = 20f
    /** Rentang knob Gain band dan Preamp (+-). */
    const val GAIN_RANGE_DB = 15f
    const val Q_MIN = 0.1f
    const val Q_MAX = 16f
    const val HZ_MIN = 20f
    const val HZ_MAX = 20000f
    private const val SAMPLE_RATE = 48000.0

    const val T_LOWPASS = 0
    const val T_HIGHPASS = 1
    const val T_BANDPASS = 2
    const val T_PEAK = 3
    const val T_LOWSHELF = 4
    const val T_HIGHSHELF = 5
    val TYPE_NAMES = listOf("LOW PASS", "HIGH PASS", "BAND PASS", "PEAK", "LOW SHELF", "HIGH SHELF")
    /** Hanya Peak dan rak yang memakai Gain. */
    fun usesGain(type: Int) = type >= T_PEAK

    val DEFAULT_HZ = floatArrayOf(60f, 170f, 400f, 1000f, 2500f, 6000f, 12000f)
    fun defaultBands(): List<PeqBand> = List(DEFAULT_BANDS) { PeqBand(T_PEAK, DEFAULT_HZ[it], 0f, 1.0f, 0) }

    // knob 0..1 -> nilai nyata
    fun subsonicHz(k: Float) = 15f + 45f * k.coerceIn(0f, 1f)
    fun subBassDb(k: Float) = 12f * k.coerceIn(0f, 1f)
    fun tubeDb(k: Float) = 12f * k.coerceIn(0f, 1f)
    fun panDepthDb(k: Float) = 30f * k.coerceIn(0f, 1f)
    fun panPeriodSec(k: Float) = 20f - 18f * k.coerceIn(0f, 1f)

    private fun f0(v: Float) = String.format(Locale("id", "ID"), "%.0f", v)
    private fun f1(v: Float) = String.format(Locale("id", "ID"), "%.1f", v)
    fun subsonicText(k: Float) = f0(subsonicHz(k)) + " Hz"
    fun dbText(db: Float) = f1(db) + " dB"
    fun panPeriodText(k: Float) = f1(panPeriodSec(k)) + " dtk"
    fun qText(q: Float) = String.format(Locale("id", "ID"), "%.2f", q)
    /** dB dengan tanda (+3,0 dB / -2,5 dB / 0,0 dB) supaya naik atau turun langsung terlihat. */
    fun dbSigned(db: Float): String = (if (db > 0.04f) "+" else "") + f1(if (kotlin.math.abs(db) < 0.05f) 0f else db) + " dB"

    // Parametrik: knob 0..1 <-> nilai nyata
    fun hzOf(k: Float): Float = HZ_MIN * (HZ_MAX / HZ_MIN).pow(k.coerceIn(0f, 1f))
    fun hzToKnob(hz: Float): Float = (ln(hz.coerceIn(HZ_MIN, HZ_MAX) / HZ_MIN) / ln(HZ_MAX / HZ_MIN)).coerceIn(0f, 1f)
    fun gainOf(k: Float): Float = (k.coerceIn(0f, 1f) - 0.5f) * 2f * GAIN_RANGE_DB
    fun gainToKnob(db: Float): Float = (db / (2f * GAIN_RANGE_DB) + 0.5f).coerceIn(0f, 1f)
    fun qOf(k: Float): Float = Q_MIN * (Q_MAX / Q_MIN).pow(k.coerceIn(0f, 1f))
    fun qToKnob(q: Float): Float = (ln(q.coerceIn(Q_MIN, Q_MAX) / Q_MIN) / ln(Q_MAX / Q_MIN)).coerceIn(0f, 1f)
    fun hzText(hz: Float): String = if (hz >= 1000f) f1(hz / 1000f) + " kHz" else f0(hz) + " Hz"

    /** 96 pusat band post-EQ (20 Hz .. 20 kHz, log). */
    val PEQ_CENTERS: FloatArray = FloatArray(PEQ_BANDS) { i -> HZ_MIN * (HZ_MAX / HZ_MIN).pow(i / (PEQ_BANDS - 1f)) }

    /** Batas ATAS tiap band post-EQ (rata-rata geometrik pusat bertetangga; band terakhir 20 kHz). */
    val PEQ_CUTOFFS: FloatArray = FloatArray(PEQ_BANDS) { i ->
        if (i == PEQ_BANDS - 1) 20000f else sqrt(PEQ_CENTERS[i] * PEQ_CENTERS[i + 1])
    }

    /** Respons magnitudo SATU band di frekuensi f (dB): biquad RBJ sesuai tipe. */
    fun bandDb(b: PeqBand, f: Float): Float {
        val w0 = 2.0 * PI * b.hz / SAMPLE_RATE
        val cw = cos(w0)
        val sw = sin(w0)
        val q = b.q.coerceAtLeast(0.1f).toDouble()
        val alpha = sw / (2.0 * q)
        val a = 10.0.pow(b.db.toDouble() / 40.0)
        val sa = sqrt(a)
        val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
        when (b.type) {
            T_LOWPASS -> { b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha }
            T_HIGHPASS -> { b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha }
            T_BANDPASS -> { b0 = alpha; b1 = 0.0; b2 = -alpha; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha }
            T_LOWSHELF -> {
                b0 = a * ((a + 1) - (a - 1) * cw + 2 * sa * alpha); b1 = 2 * a * ((a - 1) - (a + 1) * cw)
                b2 = a * ((a + 1) - (a - 1) * cw - 2 * sa * alpha)
                a0 = (a + 1) + (a - 1) * cw + 2 * sa * alpha; a1 = -2 * ((a - 1) + (a + 1) * cw); a2 = (a + 1) + (a - 1) * cw - 2 * sa * alpha
            }
            T_HIGHSHELF -> {
                b0 = a * ((a + 1) + (a - 1) * cw + 2 * sa * alpha); b1 = -2 * a * ((a - 1) + (a + 1) * cw)
                b2 = a * ((a + 1) + (a - 1) * cw - 2 * sa * alpha)
                a0 = (a + 1) - (a - 1) * cw + 2 * sa * alpha; a1 = 2 * ((a - 1) - (a + 1) * cw); a2 = (a + 1) - (a - 1) * cw - 2 * sa * alpha
            }
            else -> { b0 = 1 + alpha * a; b1 = -2 * cw; b2 = 1 - alpha * a; a0 = 1 + alpha / a; a1 = -2 * cw; a2 = 1 - alpha / a }
        }
        val w = 2.0 * PI * f / SAMPLE_RATE
        val c1 = cos(w)
        val c2 = cos(2 * w)
        val num = b0 * b0 + b1 * b1 + b2 * b2 + 2 * (b0 * b1 + b1 * b2) * c1 + 2 * b0 * b2 * c2
        val den = a0 * a0 + a1 * a1 + a2 * a2 + 2 * (a0 * a1 + a1 * a2) * c1 + 2 * a0 * a2 * c2
        if (num <= 0.0 || den <= 0.0) return -40f
        return (10.0 * log10(num / den)).toFloat().coerceIn(-40f, 40f)
    }

    /** Apakah band ikut di kanal ch (0 = kiri, 1 = kanan). PeqBand.ch: 0 = L+R, 1 = L, 2 = R. */
    fun inChannel(b: PeqBand, ch: Int) = b.ch == 0 || b.ch == ch + 1

    /** Jumlah respons semua band di kanal ch pada frekuensi f (dB), dibatasi +-PEQ_MAX_DB seperti di mesin audio. */
    fun totalDb(bands: List<PeqBand>, f: Float, ch: Int): Float {
        var t = 0f
        for (b in bands) if (inChannel(b, ch)) t += bandDb(b, f)
        return t.coerceIn(-PEQ_MAX_DB, PEQ_MAX_DB)
    }

    /** Gain tiap band post-EQ (dB) untuk kanal ch. */
    fun peqBandDb(bands: List<PeqBand>, ch: Int): FloatArray = FloatArray(PEQ_BANDS) { i -> totalDb(bands, PEQ_CENTERS[i], ch) }

    /** Tambahan dB per band pre-EQ 31 band dari SUBSONIC + SUB BASS (sama untuk L dan R). */
    fun preBandDb(s: FxSettings, band: Int): Float {
        if (!s.enabled) return 0f
        val c = Eq31.CENTERS[band]
        var db = 0f
        if (s.subsonicOn) {
            val hz = subsonicHz(s.subsonic)
            if (c < hz) db -= minOf(20f, 12f * (ln(hz / c) / 0.6931f))
        }
        if (s.subBassOn) {
            val d = ln(c / 40f) / 0.5f
            db += subBassDb(s.subBass) * kotlin.math.exp(-0.5f * d * d)
        }
        return db
    }
}

/** Satu band parametrik (gaya Poweramp). type: lihat Fx.T_*. ch: 0 = L+R, 1 = L, 2 = R. */
data class PeqBand(val type: Int, val hz: Float, val db: Float, val q: Float, val ch: Int)

data class FxSettings(
    /** Master FX (butuh GlobalEqService). Default OFF. */
    val enabled: Boolean = false,
    /** Bagian FX tampil di bawah kompresor. */
    val mode: Boolean = false,
    val subsonicOn: Boolean = false, val subsonic: Float = 0.3f,
    val subBassOn: Boolean = false, val subBass: Float = 0.4f,
    val tubeOn: Boolean = false, val tube: Float = 0.3f,
    val panOn: Boolean = false, val panDepth: Float = 0.7f, val panCycle: Float = 0.4f,
    val peqOn: Boolean = true,
    /** Preamp parametrik, knob 0..1 (0,5 = 0 dB, +-15 dB). */
    val peqPre: Float = 0.5f,
    val bands: List<PeqBand> = Fx.defaultBands(),
)

object FxStore {
    private const val PREFS = "fx_settings"

    fun encodeBands(list: List<PeqBand>): String =
        if (list.isEmpty()) "none" else list.joinToString(";") { "%d,%.2f,%.2f,%.3f,%d".format(Locale.US, it.type, it.hz, it.db, it.q, it.ch) }

    /** Belum pernah disimpan / salah format -> bawaan (7 band). "none" = pengguna menghapus semua band. */
    fun decodeBands(s: String?): List<PeqBand> {
        if (s.isNullOrBlank()) return Fx.defaultBands()
        if (s == "none") return emptyList()
        val list = s.split(';').mapNotNull { part ->
            val v = part.split(',').mapNotNull { it.trim().toFloatOrNull() }
            if (v.size == 5) PeqBand(
                v[0].toInt().coerceIn(0, Fx.TYPE_NAMES.size - 1), v[1].coerceIn(Fx.HZ_MIN, Fx.HZ_MAX),
                v[2].coerceIn(-Fx.GAIN_RANGE_DB, Fx.GAIN_RANGE_DB), v[3].coerceIn(Fx.Q_MIN, Fx.Q_MAX), v[4].toInt().coerceIn(0, 2)
            ) else null
        }
        return if (list.isEmpty()) Fx.defaultBands() else list.take(Fx.MAX_BANDS)
    }

    fun load(context: Context): FxSettings {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = FxSettings()
        return FxSettings(
            enabled = p.getBoolean("enabled", d.enabled), mode = p.getBoolean("mode", d.mode),
            subsonicOn = p.getBoolean("subsonicOn", d.subsonicOn), subsonic = p.getFloat("subsonic", d.subsonic).coerceIn(0f, 1f),
            subBassOn = p.getBoolean("subBassOn", d.subBassOn), subBass = p.getFloat("subBass", d.subBass).coerceIn(0f, 1f),
            tubeOn = p.getBoolean("tubeOn", d.tubeOn), tube = p.getFloat("tube", d.tube).coerceIn(0f, 1f),
            panOn = p.getBoolean("panOn", d.panOn), panDepth = p.getFloat("panDepth", d.panDepth).coerceIn(0f, 1f),
            panCycle = p.getFloat("panCycle", d.panCycle).coerceIn(0f, 1f),
            peqOn = p.getBoolean("peqOn", d.peqOn), peqPre = p.getFloat("peqPre", d.peqPre).coerceIn(0f, 1f),
            bands = decodeBands(p.getString("bands", null)),
        )
    }

    fun save(context: Context, s: FxSettings) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", s.enabled).putBoolean("mode", s.mode)
            .putBoolean("subsonicOn", s.subsonicOn).putFloat("subsonic", s.subsonic)
            .putBoolean("subBassOn", s.subBassOn).putFloat("subBass", s.subBass)
            .putBoolean("tubeOn", s.tubeOn).putFloat("tube", s.tube)
            .putBoolean("panOn", s.panOn).putFloat("panDepth", s.panDepth).putFloat("panCycle", s.panCycle)
            .putBoolean("peqOn", s.peqOn).putFloat("peqPre", s.peqPre).putString("bands", encodeBands(s.bands))
            .apply()
    }
}

/** Keadaan FX yang dipakai BERSAMA oleh layar utama dan panel floating (satu proses), state Compose. */
object FxUi {
    var s: FxSettings by mutableStateOf(FxSettings())
        private set
    /** Band parametrik yang sedang dipilih (-1 = tidak ada; tidak disimpan). */
    var selected: Int by mutableIntStateOf(0)
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (!loaded) {
            s = FxStore.load(context.applicationContext)
            loaded = true
        }
    }

    fun update(context: Context, f: (FxSettings) -> FxSettings) {
        ensureLoaded(context)
        val n = f(s)
        s = n
        FxStore.save(context, n)
        GlobalEqEngine.refresh()
    }

    fun setEnabled(context: Context, on: Boolean) {
        update(context) { it.copy(enabled = on) }
        syncGlobalEqService(context)
    }

    /** Mode dinyalakan -> master ikut dinyalakan. Dimatikan -> hanya menyembunyikan bagian FX. */
    fun setMode(context: Context, on: Boolean) {
        ensureLoaded(context)
        val enableNow = on && !s.enabled
        update(context) { it.copy(mode = on, enabled = it.enabled || on) }
        if (enableNow) syncGlobalEqService(context)
    }

    fun forceDisabled(context: Context) {
        update(context) { it.copy(enabled = false) }
    }

    fun setBand(context: Context, i: Int, f: (PeqBand) -> PeqBand) {
        ensureLoaded(context)
        if (i !in s.bands.indices) return
        update(context) { cur ->
            cur.copy(bands = cur.bands.mapIndexed { idx, b ->
                if (idx == i) f(b).let {
                    PeqBand(
                        it.type.coerceIn(0, Fx.TYPE_NAMES.size - 1), it.hz.coerceIn(Fx.HZ_MIN, Fx.HZ_MAX),
                        it.db.coerceIn(-Fx.GAIN_RANGE_DB, Fx.GAIN_RANGE_DB), it.q.coerceIn(Fx.Q_MIN, Fx.Q_MAX), it.ch.coerceIn(0, 2)
                    )
                } else b
            })
        }
    }

    /** Tambah band bebas di mana saja; mengembalikan false bila sudah penuh. */
    fun addBand(context: Context, hz: Float, db: Float): Boolean {
        ensureLoaded(context)
        if (s.bands.size >= Fx.MAX_BANDS) return false
        update(context) {
            it.copy(bands = it.bands + PeqBand(
                Fx.T_PEAK, hz.coerceIn(Fx.HZ_MIN, Fx.HZ_MAX), db.coerceIn(-Fx.GAIN_RANGE_DB, Fx.GAIN_RANGE_DB), 1.0f, 0
            ))
        }
        selected = s.bands.size - 1
        return true
    }

    fun removeSelected(context: Context) {
        ensureLoaded(context)
        val i = selected
        if (i !in s.bands.indices) return
        update(context) { it.copy(bands = it.bands.filterIndexed { idx, _ -> idx != i }) }
        selected = if (s.bands.isEmpty()) -1 else (i - 1).coerceAtLeast(0)
    }

    fun resetBands(context: Context) {
        update(context) { it.copy(bands = Fx.defaultBands(), peqPre = 0.5f) }
        selected = 0
    }
}
