package com.example.jazz

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.roundToInt


class MusicSynthesizer(context: Context) {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val TARGET_SECONDS = 15.0
        private const val BPM = 120.0
        private const val QUARTER_NOTE_SEC = 60.0 / BPM // 0.5초
        private val PITCH_REGEX = Regex("""([A-Ga-g])([#-])?(-?\d)""")
        private val DURATION_REGEX = Regex("""(\d+\.\d+)""")

        private val HARMONIC_WEIGHTS = doubleArrayOf(1.0, 0.5, 0.25, 0.12)
    }

    private val indicesValues: Map<Int, String> = loadIndicesValues(context)
    private var audioTrack: AudioTrack? = null
    private var currentPcm: ShortArray? = null
    private var writeJob: Thread? = null
    @Volatile private var isCancelled = false

    private fun loadIndicesValues(context: Context): Map<Int, String> {
        val json = context.assets.open("indices_values.json").bufferedReader().use { it.readText() }
        val obj = JSONObject(json)
        val map = mutableMapOf<Int, String>()
        obj.keys().forEach { key ->
            map[key.toInt()] = obj.getString(key)
        }
        return map
    }

    /** note 인덱스 배열을 15초 분량의 16bit PCM 모노 파형으로 변환합니다. */
    fun synthesize(noteIndices: IntArray): ShortArray {
        val notes = noteIndices.map { parseNote(indicesValues[it] ?: "") }

        val samples = ArrayList<Short>((SAMPLE_RATE * TARGET_SECONDS).toInt() + SAMPLE_RATE)
        var elapsedSec = 0.0
        var i = 0

        while (elapsedSec < TARGET_SECONDS) {
            val note = notes[i % notes.size]
            val durSec = min(note.durationSec, TARGET_SECONDS - elapsedSec)
            appendTone(samples, note.frequenciesHz, durSec)
            elapsedSec += note.durationSec
            i++
        }
        return samples.toShortArray()
    }

    private data class Note(val frequenciesHz: List<Double>, val durationSec: Double)

    private fun parseNote(raw: String): Note {
        // 피치 추출
        val pitchMatches = PITCH_REGEX.findAll(raw).toList()
        val freqs = if (pitchMatches.isNotEmpty()) {
            pitchMatches.map { m ->
                val letter = m.groupValues[1].uppercase()
                val accidental = m.groupValues[2]
                val octave = m.groupValues[3].toIntOrNull() ?: 4
                midiToFrequency(noteNameToMidi(letter, accidental, octave))
            }
        } else {
            listOf(261.63) // 기본값: C4
        }

        // 길이 추출 (4분음표 단위, 기본값: 1)
        val durMatch = DURATION_REGEX.find(raw)
        val quarterLengths = durMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
        val durationSec = (quarterLengths.coerceIn(0.125, 4.0)) * QUARTER_NOTE_SEC

        return Note(freqs, durationSec)
    }

    private fun noteNameToMidi(letter: String, accidental: String, octave: Int): Int {
        val base = mapOf("C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11)
        val semitone = base[letter] ?: 0
        val shift = when (accidental) {
            "#" -> 1
            "-" -> -1
            else -> 0
        }
        return 12 * (octave + 1) + semitone + shift
    }

    private fun midiToFrequency(midi: Int): Double = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)

    /**
     * 화음(freqsHz 리스트)을 배음(HARMONIC_WEIGHTS)까지 섞어서 합성하고,
     * 어택은 짧게 / 릴리즈는 지수적으로 감쇠하는 엔벨로프를 적용해
     * samples 뒤에 이어붙인다.
     *
     * - 화음: freqsHz의 각 음을 동시에 더하고, 음 개수만큼 커지는 볼륨을 다시 나눠서 보정
     * - 배음: 각 음마다 기본 주파수(1x)뿐 아니라 2x, 3x, 4x 배음도 약하게 섞음
     * - 엔벨로프: 어택(=소리가 커지는 구간)은 아주 짧게, 그 뒤로는 지수 감쇠(decay)로
     *   자연스럽게 잦아들게 함 (기존의 대칭 삼각형 모양보다 관악기/현악기 소리에 가까움)
     */
    private fun appendTone(samples: MutableList<Short>, freqsHz: List<Double>, durationSec: Double) {
        val totalFrames = (durationSec * SAMPLE_RATE).roundToInt().coerceAtLeast(1)
        val attackFrames = (totalFrames * 0.04).roundToInt().coerceAtLeast(1)

        val decayRate = 3.5 / totalFrames.toDouble()

        val harmonicSum = HARMONIC_WEIGHTS.sum()
        val normalizer = 0.6 / (freqsHz.size * harmonicSum)

        for (n in 0 until totalFrames) {
            val t = n / SAMPLE_RATE.toDouble()

            val attackEnv = if (n < attackFrames) n / attackFrames.toDouble() else 1.0
            val decayEnv = exp(-decayRate * n)
            val envelope = attackEnv * decayEnv

            var mixed = 0.0
            for (freq in freqsHz) {
                for ((h, weight) in HARMONIC_WEIGHTS.withIndex()) {
                    val harmonicFreq = freq * (h + 1) // 1x, 2x, 3x, 4x
                    mixed += sin(2.0 * PI * harmonicFreq * t) * weight
                }
            }

            val sample = mixed * normalizer * envelope
            samples.add((sample * Short.MAX_VALUE).toInt().toShort())
        }
    }


    fun play(pcm: ShortArray, onComplete: () -> Unit) {
        stop()
        currentPcm = pcm
        isCancelled = false

        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(bufferSize, pcm.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(pcm, 0, pcm.size)
        audioTrack = track
        track.play()

        writeJob = Thread {
            val durationMs = (pcm.size.toDouble() / SAMPLE_RATE * 1000).toLong()
            try {
                Thread.sleep(durationMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!isCancelled) onComplete()
        }.also { it.start() }
    }

    fun pause() {
        audioTrack?.pause()
    }


    fun resume() {
        audioTrack?.play()
    }

    fun stop() {
        isCancelled = true
        writeJob?.interrupt()
        writeJob = null
        audioTrack?.let {
            try {
                it.pause()
                it.flush()
                it.stop()
            } catch (_: IllegalStateException) {
                // 이미 정지된 상태일 경우
            }
            it.release()
        }
        audioTrack = null
    }
}
