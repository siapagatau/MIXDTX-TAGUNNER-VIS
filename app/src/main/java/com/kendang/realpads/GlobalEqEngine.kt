package com.kendang.realpads

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.pow
import kotlinx.coroutines.flow.asStateFlow

// EQ GLOBAL: ngubah suara SEMUA app (YouTube, TikTok, app musik, dll).
//
// Caranya sama kayak Equalizer314 / Wavelet: pasang efek DynamicsProcessing
// (API resmi Android 9+) di audio session 0 = "output mix" - mixer akhir
// sebelum suara keluar ke speaker/earphone. Efeknya berlaku ke semua app
// tanpa app lain perlu ikut kerja sama.
//
// Isinya: 31 band PER SISI (L dan R terpisah, 1/3 oktaf ISO) + limiter tipis biar boost gak bikin suara
// pecah (clipping). Dua fitur memakai SATU efek ini dan dijumlahkan dalam dB per band:
//   - EQ AUDIO  (gain + Sub/Low/Mid/High)  -> nilainya disebar ke band yang masuk kelompoknya, sama untuk L dan R
//   - EQ 31 BAND (Eq31.kt, knob GAIN L/R + 31 band L + 31 band R) -> langsung per band per sisi
// Masing-masing hanya ikut bila saklarnya ON. Efek hanya dilepas bila keduanya OFF (lihat syncGlobalEqService).
//
// Catatan jujur: sebagian HP/ROM (terutama beberapa merek tertentu) mengabaikan
// efek di session 0. Kalau itu kejadian, efek gagal dibuat / gak kedengeran
// dan alasannya ditampilin lewat [error] ke UI - bukan crash.
object GlobalEqEngine {
    // Batas ATAS tiap band (Hz). DynamicsProcessing membagi spektrum jadi 31 band berurutan (1/3 oktaf) per sisi.
    private val CUTOFFS_HZ = Eq31.CUTOFFS_HZ
    private const val BAND_COUNT = Eq31.BANDS
    private const val CHANNELS = 2
    // Batas aman gain gabungan per band (dB): EQ AUDIO + EQ 31 band + gain sisi bisa menumpuk.
    private const val MAX_BAND_DB = 20f

    private var dp: DynamicsProcessing? = null

    // Pengaturan EQ AUDIO terakhir (untuk refresh() saat EQ 31 band berubah) + nilai yang SUDAH terkirim ke efek.
    // Hanya band yang berubah yang dikirim ulang -> menggeser satu slider = 1 panggilan, bukan 62.
    private var lastS: ReverbSettings? = null
    private val sent = Array(CHANNELS) { FloatArray(BAND_COUNT) { Float.NaN } }
    private val sentInGain = FloatArray(CHANNELS) { Float.NaN }
    private var baseInputGainDb = 0f
    // FX (FxUi): post-EQ parametrik, LoudnessEnhancer, 8D pan.
    private val sentPeq = FloatArray(CHANNELS * Fx.PEQ_BANDS) { Float.NaN }
    private var loud: LoudnessEnhancer? = null
    private var sentLoudMb = Int.MIN_VALUE
    private var panThread: HandlerThread? = null
    private var panHandler: Handler? = null
    private val _fxStatus = MutableStateFlow<String?>(null)
    /** Pesan efek FX yang tidak didukung HP (null = tidak ada masalah). */
    val fxStatus = _fxStatus.asStateFlow()
    // Kompresor (MBC) + post-gain limiter per sisi: tanda tangan nilai yang SUDAH terkirim (null = belum pernah).
    private var sentCompKey: String? = null
    private val sentPostGain = FloatArray(CHANNELS) { Float.NaN }

    private val _active = MutableStateFlow(false)
    val active = _active.asStateFlow()

    // Pesan kenapa EQ global gak bisa nyala (null = gak ada masalah).
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    // Knob 0..1 -> dB. 0.5 = tengah = 0 dB (flat).
    fun gainKnobToDb(knob: Float): Float = (knob - 0.5f) * 24f // +-12 dB
    fun bandKnobToDb(knob: Float): Float = (knob - 0.5f) * 24f // +-12 dB

    @Synchronized
    fun start(s: ReverbSettings): Boolean {
        _error.value = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            _error.value = "EQ Audio butuh Android 9 ke atas"
            return false
        }
        if (dp == null) {
            try {
                val effect = DynamicsProcessing(0, 0, buildConfig())
                val r = effect.setEnabled(true)
                if (r != AudioEffect.SUCCESS) {
                    runCatching { effect.release() }
                    _error.value = "HP ini menolak EQ global (kode $r)"
                    return false
                }
                dp = effect
                resetSent()
            } catch (t: Throwable) {
                _error.value = "HP ini tidak mendukung EQ global"
                return false
            }
        }
        applySettings(s)
        _active.value = true
        return true
    }

    @Synchronized
    fun stop() {
        stopPan()
        runCatching { loud?.setEnabled(false) }
        runCatching { loud?.release() }
        loud = null
        runCatching { dp?.setEnabled(false) }
        runCatching { dp?.release() }
        dp = null
        resetSent()
        _active.value = false
    }

    private fun resetSent() {
        for (c in sent) c.fill(Float.NaN)
        sentInGain.fill(Float.NaN)
        sentPeq.fill(Float.NaN)
        sentLoudMb = Int.MIN_VALUE
        sentCompKey = null
        sentPostGain.fill(Float.NaN)
    }

    // Simpan pengaturan EQ AUDIO lalu kirim semuanya (EQ AUDIO + EQ 31 band) ke efek yang lagi jalan.
    // Aman dipanggil kapan pun (kalau efek belum nyala, gak ngapa-ngapain).
    @Synchronized
    fun applySettings(s: ReverbSettings) {
        lastS = s
        apply()
    }

    // Dipanggil saat EQ 31 band berubah (Eq31Ui.update): pakai pengaturan EQ AUDIO terakhir.
    @Synchronized
    fun refresh() {
        apply()
    }

    private fun apply() {
        val effect = dp ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val s = lastS
        val eq = Eq31Ui.s
        val broadOn = s != null && s.gEqEnabled
        val fineOn = eq.enabled
        // EQ AUDIO: dB per kelompok (Sub / Low / Mid / High); gain masuk dipakai di semua sisi.
        val broadDb = if (broadOn && s != null) floatArrayOf(
            bandKnobToDb(s.gEqSub), bandKnobToDb(s.gEqLow), bandKnobToDb(s.gEqMid), bandKnobToDb(s.gEqHigh)
        ) else FloatArray(4)
        val fxs = FxUi.s
        // Preamp PARAMETRIC (FX) menambah gain masuk, hanya bila FX + parametrik aktif.
        val peqPre = if (fxs.enabled && fxs.peqOn) (fxs.peqPre - 0.5f) * 2f * Fx.GAIN_RANGE_DB else 0f
        baseInputGainDb = (if (broadOn && s != null) gainKnobToDb(s.gEqGain) else 0f) + peqPre
        try {
            pushInputGains(effect)
            for (ch in 0 until CHANNELS) {
                val side = if (ch == 0) Eq31.LEFT else Eq31.RIGHT
                // Knob GAIN sisi ini dilipat ke semua band sisi itu (hasilnya sama dengan gain keluaran per sisi).
                val sideGain = if (fineOn) Eq31.knobToDb(eq.gain(side)) else 0f
                for (i in 0 until BAND_COUNT) {
                    val fine = if (fineOn) Eq31.knobToDb(eq.band(side, i)) else 0f
                    val db = (broadDb[Eq31.groupOf(i)] + fine + sideGain + Fx.preBandDb(fxs, i)).coerceIn(-MAX_BAND_DB, MAX_BAND_DB)
                    val prev = sent[ch][i]
                    if (!prev.isNaN() && kotlin.math.abs(prev - db) < 0.05f) continue
                    effect.setPreEqBandByChannelIndex(ch, i, DynamicsProcessing.EqBand(true, CUTOFFS_HZ[i], db))
                    sent[ch][i] = db
                }
            }
            applyCompressor(effect)
            applyParametric(effect, fxs)
        } catch (t: Throwable) {
            // Efek bisa "dicabut" sistem (mis. app lain ambil alih) - jangan crash.
            _error.value = "EQ global terputus, nyalakan ulang"
        }
        applyFxEffects(fxs)
    }

    // ---- gain masuk per sisi (EQ AUDIO GAIN + 8D PAN) ----
    private fun panOffsetDb(ch: Int, f: FxSettings): Float {
        if (!f.enabled || !f.panOn) return 0f
        val period = Fx.panPeriodSec(f.panCycle)
        val t = SystemClock.elapsedRealtime() / 1000.0
        val pos = kotlin.math.sin(2.0 * Math.PI * t / period).toFloat()   // -1 kiri .. +1 kanan
        val atten = if (ch == 0) kotlin.math.max(0f, pos) else kotlin.math.max(0f, -pos)
        return -Fx.panDepthDb(f.panDepth) * atten
    }

    private fun pushInputGains(effect: DynamicsProcessing) {
        val f = FxUi.s
        for (ch in 0 until CHANNELS) {
            val g = baseInputGainDb + panOffsetDb(ch, f)
            val prev = sentInGain[ch]
            if (!prev.isNaN() && kotlin.math.abs(prev - g) < 0.05f) continue
            effect.setInputGainbyChannel(ch, g)
            sentInGain[ch] = g
        }
    }

    @Synchronized
    private fun panTick() {
        val effect = dp ?: return
        val f = FxUi.s
        if (!f.enabled || !f.panOn) return
        runCatching { pushInputGains(effect) }
        panHandler?.postDelayed({ panTick() }, 40L)
    }

    private fun startPan() {
        if (panThread != null) return
        val t = HandlerThread("fx-pan").also { it.start() }
        panThread = t
        panHandler = Handler(t.looper).also { it.post { panTick() } }
    }

    private fun stopPan() {
        panHandler?.removeCallbacksAndMessages(null)
        panHandler = null
        panThread?.quitSafely()
        panThread = null
    }

    // ---- PARAMETRIC (post-EQ 96 band; kiri dan kanan bisa berbeda lewat kanal tiap band) ----
    private fun applyParametric(effect: DynamicsProcessing, f: FxSettings) {
        val active = f.enabled && f.peqOn
        for (ch in 0 until CHANNELS) {
            val db = if (active) Fx.peqBandDb(f.bands, ch) else FloatArray(Fx.PEQ_BANDS)
            for (i in 0 until Fx.PEQ_BANDS) {
                val k = ch * Fx.PEQ_BANDS + i
                val prev = sentPeq[k]
                if (!prev.isNaN() && kotlin.math.abs(prev - db[i]) < 0.05f) continue
                effect.setPostEqBandByChannelIndex(ch, i, DynamicsProcessing.EqBand(true, Fx.PEQ_CUTOFFS[i], db[i]))
                sentPeq[k] = db[i]
            }
        }
    }

    // ---- TUBE DRIVE (LoudnessEnhancer) + 8D PAN: efek terpisah di session 0 ----
    private fun applyFxEffects(f: FxSettings) {
        val msgs = ArrayList<String>()
        // TUBE DRIVE
        val wantLoud = f.enabled && f.tubeOn
        if (wantLoud) {
            if (loud == null) loud = runCatching { LoudnessEnhancer(0) }.getOrNull()
            val l = loud
            if (l == null) {
                msgs += "TUBE DRIVE tidak didukung HP ini"
            } else {
                val mb = (Fx.tubeDb(f.tube) * 100f).toInt()
                if (mb != sentLoudMb) {
                    runCatching { l.setTargetGain(mb) }
                    if (runCatching { l.setEnabled(true) }.getOrDefault(AudioEffectError) != AudioEffect.SUCCESS) msgs += "TUBE DRIVE ditolak HP ini"
                    sentLoudMb = mb
                }
            }
        } else if (loud != null) {
            runCatching { loud?.setEnabled(false) }
            runCatching { loud?.release() }
            loud = null
            sentLoudMb = Int.MIN_VALUE
        }
        // 8D PAN
        if (f.enabled && f.panOn) startPan() else {
            stopPan()
            dp?.let { runCatching { pushInputGains(it) } }
        }
        _fxStatus.value = if (msgs.isEmpty()) null else msgs.joinToString(" · ")
    }
    private const val AudioEffectError = -1

    // KOMPRESOR: 2 band MBC. Band 0 (0..Frekuensi) = dikompresi (Attack/Release/Rasio/Threshold + Gain sebagai makeup);
    // band 1 (Frekuensi..20 kHz) = lewat apa adanya. Keseimbangan + Volume = post-gain limiter tiap sisi (hanya meredam).
    private const val MBC_BANDS = 2
    private const val LIMITER_THRESHOLD_DB = -2f

    private fun neutralBand(cutoff: Float, enabled: Boolean) =
        DynamicsProcessing.MbcBand(enabled, cutoff, 1f, 100f, 1f, 0f, 0f, -90f, 1f, 0f, 0f)

    private fun limiter(postGainDb: Float) =
        DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, LIMITER_THRESHOLD_DB, postGainDb)

    private fun applyCompressor(effect: DynamicsProcessing) {
        val c = CompUi.s
        val on = c.enabled
        val key = if (!on) "off" else listOf(c.attack, c.release, c.ratio, c.freq, c.threshold, c.gain)
            .joinToString(",") { "%.4f".format(java.util.Locale.US, it) }
        if (key != sentCompKey) {
            val band0 = if (on) DynamicsProcessing.MbcBand(
                true, Comp.freqHz(c.freq), Comp.attackMs(c.attack), Comp.releaseMs(c.release),
                Comp.ratio(c.ratio), Comp.thresholdDb(c.threshold), 6f, -90f, 1f, 0f, Comp.gainDb(c.gain)
            ) else neutralBand(Comp.FREQ_MAX_HZ, false)
            val band1 = neutralBand(20000f, on)
            for (ch in 0 until CHANNELS) {
                effect.setMbcBandByChannelIndex(ch, 0, band0)
                effect.setMbcBandByChannelIndex(ch, 1, band1)
            }
            sentCompKey = key
        }
        for (ch in 0 until CHANNELS) {
            val side = if (ch == 0) Eq31.LEFT else Eq31.RIGHT
            val pg = if (on) Comp.sideAttenDb(c, side) else 0f
            val prev = sentPostGain[ch]
            if (!prev.isNaN() && kotlin.math.abs(prev - pg) < 0.05f) continue
            effect.setLimiterByChannelIndex(ch, limiter(pg))
            sentPostGain[ch] = pg
        }
    }

    private fun buildConfig(): DynamicsProcessing.Config {
        val eq = DynamicsProcessing.Eq(true, true, BAND_COUNT)
        for (i in 0 until BAND_COUNT) {
            eq.setBand(i, DynamicsProcessing.EqBand(true, CUTOFFS_HZ[i], 0f))
        }
        // Limiter pengaman: boost gain/band gak bikin suara pecah.
        val lim = limiter(0f)
        // Kompresor: MBC 2 band, awalnya netral + dimatikan (diatur lewat applyCompressor).
        val mbc = DynamicsProcessing.Mbc(true, true, MBC_BANDS)
        mbc.setBand(0, neutralBand(Comp.FREQ_MAX_HZ, false))
        mbc.setBand(1, neutralBand(20000f, false))
        // Post-EQ 64 band: dipakai PARAMETRIC (FX), awalnya flat.
        val peq = DynamicsProcessing.Eq(true, true, Fx.PEQ_BANDS)
        for (i in 0 until Fx.PEQ_BANDS) peq.setBand(i, DynamicsProcessing.EqBand(true, Fx.PEQ_CUTOFFS[i], 0f))
        return DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
            true, BAND_COUNT,   // pre-EQ: dipakai
            true, MBC_BANDS,    // MBC: dipakai kompresor (2 band)
            true, Fx.PEQ_BANDS, // post-EQ: dipakai PARAMETRIC (FX)
            true                // limiter: dipakai
        )
            .setPreEqAllChannelsTo(eq)
            .setPostEqAllChannelsTo(peq)
            .setMbcAllChannelsTo(mbc)
            .setLimiterAllChannelsTo(lim)
            .build()
    }
}
