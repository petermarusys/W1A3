package com.example.jazztv

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private enum class State { IDLE, GENERATING, READY_TO_PLAY, PLAYING, PAUSED }

    private lateinit var playPauseButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var statusText: TextView

    private lateinit var generator: MusicGenerator
    private lateinit var synthesizer: MusicSynthesizer

    private var currentPcm: ShortArray? = null
    private var state: State = State.IDLE

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playPauseButton = findViewById(R.id.playPauseButton)
        nextButton = findViewById(R.id.nextButton)
        statusText = findViewById(R.id.statusText)

        generator = MusicGenerator(this)
        synthesizer = MusicSynthesizer(this)

        playPauseButton.setOnClickListener { onPlayPauseClicked() }
        nextButton.setOnClickListener { onNextClicked() }

        playPauseButton.requestFocus()
        updateUi()
    }


    private fun onPlayPauseClicked() {
        when (state) {
            State.IDLE -> generateThenPlay()
            State.READY_TO_PLAY -> playCurrent()
            State.PLAYING -> pauseCurrent()
            State.PAUSED -> resumeCurrent()
            State.GENERATING -> Unit
        }
    }


    private fun onNextClicked() {
        if (state == State.GENERATING) return
        synthesizer.stop()
        generateThenSetReady()
    }

    private fun generateThenPlay() {
        state = State.GENERATING
        updateUi()
        scope.launch {
            val pcm = generateMusicAsync()
            currentPcm = pcm
            playCurrent()
        }
    }

    private fun generateThenSetReady() {
        state = State.GENERATING
        updateUi()
        scope.launch {
            val pcm = generateMusicAsync()
            currentPcm = pcm
            state = State.READY_TO_PLAY
            updateUi()
        }
    }

    private suspend fun generateMusicAsync(): ShortArray = withContext(Dispatchers.Default) {
        val randomTemperature = 0.7 + Random.nextDouble() * 0.8
        val indices = generator.generateNoteSequence(steps = 50, temperature = randomTemperature)
        synthesizer.synthesize(indices)
    }

    private fun playCurrent() {
        val pcm = currentPcm ?: return
        synthesizer.play(pcm) {
            runOnUiThread {
                state = State.READY_TO_PLAY
                updateUi()
            }
        }
        state = State.PLAYING
        updateUi()
    }

    private fun pauseCurrent() {
        synthesizer.pause()
        state = State.PAUSED
        updateUi()
    }

    private fun resumeCurrent() {
        synthesizer.resume()
        state = State.PLAYING
        updateUi()
    }

    private fun updateUi() {
        when (state) {
            State.IDLE -> {
                playPauseButton.setImageResource(R.drawable.ic_play)
                playPauseButton.contentDescription = getString(R.string.btn_play)
                statusText.text = getString(R.string.status_idle)
                playPauseButton.isEnabled = true
                nextButton.isEnabled = true
            }
            State.GENERATING -> {
                statusText.text = getString(R.string.status_generating)
                playPauseButton.isEnabled = false
                nextButton.isEnabled = false
            }
            State.READY_TO_PLAY -> {
                playPauseButton.setImageResource(R.drawable.ic_play)
                playPauseButton.contentDescription = getString(R.string.btn_play)
                statusText.text = getString(R.string.status_idle)
                playPauseButton.isEnabled = true
                nextButton.isEnabled = true
            }
            State.PLAYING -> {
                playPauseButton.setImageResource(R.drawable.ic_pause)
                playPauseButton.contentDescription = getString(R.string.btn_pause)
                statusText.text = getString(R.string.status_playing)
                playPauseButton.isEnabled = true
                nextButton.isEnabled = true
            }
            State.PAUSED -> {
                playPauseButton.setImageResource(R.drawable.ic_play)
                playPauseButton.contentDescription = getString(R.string.btn_play)
                statusText.text = getString(R.string.status_paused)
                playPauseButton.isEnabled = true
                nextButton.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        synthesizer.stop()
        generator.close()
    }
}
