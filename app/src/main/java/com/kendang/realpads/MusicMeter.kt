package com.kendang.realpads

import android.media.audiofx.Visualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.log10
import kotlin.math.sqrt

// Pengukur level suara MUSIK (app lain: YouTube, TikTok, dll).
//
// Pakai android.media.audiofx.Visualizer di audio session 0 (output mix) -
// pasangan dari EQ global. Visualizer membaca MIX AKHIR, jadi suara mic
// karaoke kita sendiri ikut terbaca. Untuk memisahkan, UI mengurangi daya
// output engine mic (lihat nativeGetOutputMeanSquare) dari daya mix ini -
// hasilnya PERKIRAAN level musik, bukan ukuran yang presisi.
//
// Visualizer cuma dibuat selama ada layar yang menampilkan meter (acquire /
// release dihitung referensi) supaya hemat baterai.
object MusicMeter {
    private var viz: Visualizer? = null
    private var users = 0
    private var buf = ByteArray(0)
    // Visualizer KHUSUS spektrum (terpisah dari meter RMS supaya meter tidak berubah): capture terbesar + AS_PLAYED
    // (amplitudo apa adanya sesuai volume putar, TIDAK dinormalisasi) -> tinggi batang = keras suara tiap frekuensi.
    private var specViz: Visualizer? = null
    private var specUsers = 0
    private var fftBuf = ByteArray(0)
    private var sampleRateHz = 44100
    private val _specError = MutableStateFlow<String?>(null)
    val specError = _specError.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    @Synchronized
    fun acquire() {
        users++
        if (viz != null) return
        try {
            val v = Visualizer(0)
            val size = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(128)
            v.captureSize = size
            buf = ByteArray(size)
            v.enabled = true
            viz = v
            _error.value = null
        } catch (t: Throwable) {
            viz = null
            _error.value = "Meter musik tidak didukung di HP ini"
        }
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            runCatching { viz?.enabled = false }
            runCatching { viz?.release() }
            viz = null
        }
    }

    // RMS (0..1) dari mix output saat ini; -1 kalau Visualizer tidak tersedia.
    @Synchronized
    fun readMixRms(): Float {
        val v = viz ?: return -1f
        return try {
            if (v.getWaveForm(buf) != Visualizer.SUCCESS) return -1f
            var sum = 0.0
            for (b in buf) {
                val x = ((b.toInt() and 0xFF) - 128) / 128.0 // 8-bit unsigned, pusat 128
                sum += x * x
            }
            sqrt(sum / buf.size).toFloat()
        } catch (t: Throwable) {
            -1f
        }
    }

    @Synchronized
    fun acquireSpectrum() {
        specUsers++
        if (specViz != null) return
        try {
            val v = Visualizer(0)
            val size = Visualizer.getCaptureSizeRange()[1].coerceIn(128, 1024)
            v.captureSize = size
            runCatching { v.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED }
            fftBuf = ByteArray(size)
            sampleRateHz = (v.samplingRate / 1000).takeIf { it in 8000..192000 } ?: 44100
            v.enabled = true
            specViz = v
            _specError.value = null
        } catch (t: Throwable) {
            specViz = null
            _specError.value = "Visualizer tidak didukung di HP ini"
        }
    }

    @Synchronized
    fun releaseSpectrum() {
        specUsers = (specUsers - 1).coerceAtLeast(0)
        if (specUsers == 0) {
            runCatching { specViz?.enabled = false }
            runCatching { specViz?.release() }
            specViz = null
        }
    }

    // Spektrum untuk VISUALIZER: isi out[0 until out.size] dengan level 0..1 per pita (skala log 40 Hz .. 16 kHz).
    // Skala TETAP: level = (dB + 54) / 48 dengan dB = 20*log10(magnitudo/128), TANPA normalisasi otomatis dan TANPA
    // kemiringan treble -> tiap pita menunjukkan keras suara frekuensi itu apa adanya. Pita rendah diinterpolasi antar
    // bin FFT, pita tinggi memakai bin terkuat. false = Visualizer tidak tersedia.
    @Synchronized
    fun readSpectrum(out: FloatArray): Boolean {
        val v = specViz ?: return false
        val n = fftBuf.size
        if (n < 128 || out.isEmpty()) return false
        try {
            if (v.getFft(fftBuf) != Visualizer.SUCCESS) return false
        } catch (t: Throwable) {
            return false
        }
        val half = n / 2
        val mag = FloatArray(half + 1)
        mag[0] = Math.abs(fftBuf[0].toInt()).toFloat()
        for (k in 1 until half) {
            val re = fftBuf[2 * k].toInt().toFloat()
            val im = fftBuf[2 * k + 1].toInt().toFloat()
            mag[k] = sqrt(re * re + im * im)
        }
        mag[half] = Math.abs(fftBuf[1].toInt()).toFloat()
        val binHz = sampleRateHz.toFloat() / n
        val bands = out.size
        val fMin = 40f
        val fMax = minOf(16000f, sampleRateHz / 2f - 1f)
        val ratio = fMax / fMin
        for (i in 0 until bands) {
            val lo = fMin * Math.pow(ratio.toDouble(), i.toDouble() / bands).toFloat()
            val hi = fMin * Math.pow(ratio.toDouble(), (i + 1).toDouble() / bands).toFloat()
            val bLo = lo / binHz
            val bHi = hi / binHz
            val m: Float
            if (bHi - bLo <= 1f) {
                val c = Math.sqrt((bLo * bHi).toDouble()).toFloat().coerceIn(1f, (half - 1).toFloat())
                val k0 = c.toInt()
                val fr = c - k0
                m = mag[k0] * (1f - fr) + mag[minOf(k0 + 1, half)] * fr
            } else {
                var mx = 0f
                val k0 = bLo.toInt().coerceAtLeast(1)
                val k1 = Math.ceil(bHi.toDouble()).toInt().coerceAtMost(half)
                for (k in k0..k1) if (mag[k] > mx) mx = mag[k]
                m = mx
            }
            val db = 20f * log10(m / 128f + 1e-6f)
            out[i] = ((db + 54f) / 48f).coerceIn(0f, 1f)
        }
        return true
    }
}
