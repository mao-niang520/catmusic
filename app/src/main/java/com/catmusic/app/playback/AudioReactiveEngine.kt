package com.catmusic.app.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * A snapshot of the decoded audio. Written on the playback thread and read
 * only from particle drawing code; no samples or files leave the process.
 */
data class AudioEnergy(
    val level: Float = 0f,
    val bass: Float = 0f,
    val treble: Float = 0f,
    val beat: Float = 0f,
    /** Sub-bass, bass, low-mid, mid, high-mid, presence, brilliance and air. */
    val bands: FloatArray = FloatArray(BAND_COUNT),
    val active: Boolean = false
) {
    companion object {
        const val BAND_COUNT = 8
        const val SUB_BASS = 0
        const val BASS = 1
        const val LOW_MID = 2
        const val MID = 3
        const val HIGH_MID = 4
        const val PRESENCE = 5
        const val BRILLIANCE = 6
        const val AIR = 7
    }
}

object AudioReactiveSignal {

    @Volatile
    var current: AudioEnergy = AudioEnergy()
        private set

    /** Elapsed-realtime timestamp of the last published PCM snapshot. */
    @Volatile
    var updatedAt: Long = 0L
        private set

    /**
     * The native lyric stage needs band/beat data even when the optional
     * 音浪 visuals are off. It raises this gate only while it is composed so
     * the PCM tap is not kept busy in the library or on the cover page.
     */
    @Volatile
    var uiRequested: Boolean = false

    internal fun publish(energy: AudioEnergy) {
        current = energy
        updatedAt = SystemClock.elapsedRealtime()
    }

    internal fun reset() {
        current = AudioEnergy()
        updatedAt = 0L
    }
}

/**
 * Sits in ExoPlayer's audio pipeline, observes the decoded PCM buffer and
 * produces a perceptual envelope plus a beat pulse. Because the tap is before
 * the speaker volume, it reacts to the song itself and never needs the
 * microphone permission or the platform Visualizer.
 */
@UnstableApi
class AudioReactivePcmTap(
    private val isEnabled: () -> Boolean
) : TeeAudioProcessor.AudioBufferSink {

    private var sampleRate = 44_100
    private var encoding = C.ENCODING_PCM_16BIT
    private var lowPassCutoffAlpha = 0.025f

    private var lowPass = 0f
    private var smoothedLevel = 0f
    private var smoothedBass = 0f
    private var smoothedTreble = 0f
    private var levelBaseline = 0f
    private var bassBaseline = 0f
    private var lastBeatAt = 0L
    private var beatPulse = 0f
    private var lastAnalysisAt = 0L

    private val bandFilters = FloatArray(AudioEnergy.BAND_COUNT)
    private val bandAlpha = FloatArray(AudioEnergy.BAND_COUNT)
    private val bandSquares = DoubleArray(AudioEnergy.BAND_COUNT)
    private val smoothedBands = FloatArray(AudioEnergy.BAND_COUNT)

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz.coerceAtLeast(8_000)
        this.encoding = encoding
        lowPassCutoffAlpha = (
            2.0 * PI * 180.0 / sampleRate.toDouble()
            ).toFloat().coerceIn(0.002f, 0.20f)
        lowPass = 0f
        for (index in 0 until AudioEnergy.BAND_COUNT) {
            val cutoff = BAND_CUTOFFS[index]
            bandAlpha[index] = (
                1.0 - kotlin.math.exp(-2.0 * PI * cutoff / sampleRate)
                ).toFloat().coerceIn(0.001f, 0.995f)
            bandFilters[index] = 0f
            bandSquares[index] = 0.0
            smoothedBands[index] = 0f
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!isEnabled()) {
            AudioReactiveSignal.reset()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalysisAt < ANALYSIS_INTERVAL_MS) return
        lastAnalysisAt = now

        val stats = readEnergies(buffer) ?: return
        if (stats.count <= 0) return

        val rawLevel = perceptual(stats.level, 16f)
        val rawBass = perceptual(stats.bass, 22f)
        val rawTreble = perceptual(stats.treble, 55f)

        smoothedLevel = smooth(smoothedLevel, rawLevel, 0.55f, 0.14f)
        smoothedBass = smooth(smoothedBass, rawBass, 0.50f, 0.12f)
        smoothedTreble = smooth(smoothedTreble, rawTreble, 0.62f, 0.18f)

        for (index in 0 until AudioEnergy.BAND_COUNT) {
            val rawBand = perceptual(stats.bands[index], BAND_GAINS[index])
            smoothedBands[index] = smooth(
                smoothedBands[index],
                rawBand,
                BAND_ATTACK[index],
                BAND_RELEASE[index]
            )
        }

        val levelStep = (smoothedLevel - levelBaseline) *
            if (smoothedLevel > levelBaseline) 0.04f else 0.01f
        levelBaseline = (levelBaseline + levelStep).coerceIn(0f, 1f)
        val bassStep = (smoothedBass - bassBaseline) *
            if (smoothedBass > bassBaseline) 0.08f else 0.02f
        bassBaseline = (bassBaseline + bassStep).coerceIn(0f, 1f)

        val bassTransient = smoothedBass > bassBaseline * 1.16f + 0.02f &&
            smoothedBass > 0.15f
        val levelTransient = smoothedLevel > levelBaseline * 1.20f + 0.025f &&
            smoothedLevel > 0.20f
        val isBeat = (bassTransient || levelTransient) && now - lastBeatAt > 200L
        if (isBeat) lastBeatAt = now
        beatPulse = if (isBeat) 1f else beatPulse * 0.82f

        AudioReactiveSignal.publish(
            AudioEnergy(
                level = smoothedLevel,
                bass = smoothedBass,
                treble = smoothedTreble,
                beat = beatPulse,
                bands = smoothedBands.copyOf(),
                active = true
            )
        )
    }

    private data class Energies(
        val level: Float,
        val bass: Float,
        val treble: Float,
        val bands: FloatArray,
        val count: Int
    )

    /**
     * Eight adjacent one-pole low-passes. Subtracting neighbours gives a cheap
     * octave-ish spectrum that is stable and much lighter than a full FFT at the
     * 25 Hz analysis cadence.
     */
    private fun accumulateBands(sample: Float) {
        var low = 0f
        for (index in 0 until AudioEnergy.BAND_COUNT) {
            val filter = bandFilters[index]
            val next = filter + bandAlpha[index] * (sample - filter)
            bandFilters[index] = next
            val component = next - low
            bandSquares[index] += (component * component).toDouble()
            low = next
        }
    }

    private fun readEnergies(buffer: ByteBuffer): Energies? {
        val readBuffer = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var sumSquares = 0.0
        var lowSquares = 0.0
        var highSquares = 0.0
        var low = lowPass
        var count = 0
        val alpha = lowPassCutoffAlpha
        java.util.Arrays.fill(bandSquares, 0.0)

        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val samples = readBuffer.asShortBuffer()
                while (samples.hasRemaining()) {
                    val sample = samples.get() / 32768f
                    sumSquares += sample * sample
                    low += alpha * (sample - low)
                    lowSquares += low * low
                    val high = sample - low
                    highSquares += high * high
                    accumulateBands(sample)
                    count++
                }
            }

            C.ENCODING_PCM_FLOAT -> {
                val samples = readBuffer.asFloatBuffer()
                while (samples.hasRemaining()) {
                    val sample = samples.get().coerceIn(-1f, 1f)
                    sumSquares += sample * sample
                    low += alpha * (sample - low)
                    lowSquares += low * low
                    val high = sample - low
                    highSquares += high * high
                    accumulateBands(sample)
                    count++
                }
            }

            C.ENCODING_PCM_8BIT -> {
                while (readBuffer.hasRemaining()) {
                    val sample = ((readBuffer.get().toInt() and 0xFF) - 128) / 128f
                    sumSquares += sample * sample
                    low += alpha * (sample - low)
                    lowSquares += low * low
                    val high = sample - low
                    highSquares += high * high
                    accumulateBands(sample)
                    count++
                }
            }

            C.ENCODING_PCM_32BIT -> {
                val samples = readBuffer.asIntBuffer()
                while (samples.hasRemaining()) {
                    val sample = samples.get() / 2_147_483_648f
                    sumSquares += sample * sample
                    low += alpha * (sample - low)
                    lowSquares += low * low
                    val high = sample - low
                    highSquares += high * high
                    accumulateBands(sample)
                    count++
                }
            }

            else -> return null
        }
        lowPass = low
        if (count == 0) return null
        val bands = FloatArray(AudioEnergy.BAND_COUNT) { index ->
            sqrt(bandSquares[index] / count).toFloat()
        }
        return Energies(
            level = sqrt(sumSquares / count).toFloat(),
            bass = sqrt(lowSquares / count).toFloat(),
            treble = sqrt(highSquares / count).toFloat(),
            bands = bands,
            count = count
        )
    }

    private fun smooth(previous: Float, next: Float, attack: Float, release: Float): Float {
        val factor = if (next > previous) attack else release
        return previous + (next - previous) * factor
    }

    /**
     * RMS energy covers a very wide range. A logarithmic curve keeps quiet
     * passages visible and prevents loud masters from pinning every value at 1.
     */
    private fun perceptual(value: Float, gain: Float): Float {
        val scaled = (value * gain).coerceIn(0f, 1f)
        return (ln(1.0 + scaled.toDouble()) / ln(1.0 + gain.toDouble()))
            .toFloat()
            .coerceIn(0f, 1f)
    }

    private companion object {
        const val ANALYSIS_INTERVAL_MS = 40L
        val BAND_CUTOFFS = floatArrayOf(55f, 140f, 350f, 900f, 2_200f, 4_500f, 8_000f, 14_000f)
        val BAND_GAINS = floatArrayOf(22f, 16f, 13f, 11f, 12f, 15f, 20f, 26f)
        val BAND_ATTACK = floatArrayOf(0.55f, 0.52f, 0.50f, 0.50f, 0.55f, 0.58f, 0.60f, 0.62f)
        val BAND_RELEASE = floatArrayOf(0.12f, 0.11f, 0.10f, 0.10f, 0.13f, 0.15f, 0.18f, 0.20f)
    }
}

/**
 * Wraps the default audio sink with a tee processor. Silence skipping, playback
 * speed and the rest of Media3's default audio processing are preserved.
 */
@UnstableApi
class AudioReactiveRenderersFactory(
    context: Context,
    private val tap: AudioReactivePcmTap
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        val tee = TeeAudioProcessor(tap)
        val chain = DefaultAudioSink.DefaultAudioProcessorChain(tee)
        return DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(chain)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}
