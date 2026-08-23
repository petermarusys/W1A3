# Project Architecture Diagrams

This document contains the **Class Diagram** and **Sequence Diagrams** for the Jazz TV Android application.

---

## 1. Class Diagram

```mermaid
classDiagram
    direction TB

    class AppCompatActivity {
        <<Android Framework>>
    }

    class MainActivity {
        -ImageButton playPauseButton
        -ImageButton nextButton
        -TextView statusText
        -MusicGenerator generator
        -MusicSynthesizer synthesizer
        -ShortArray currentPcm
        -State state
        -CoroutineScope scope
        +onCreate(savedInstanceState: Bundle?)
        -onPlayPauseClicked()
        -onNextClicked()
        -generateThenPlay()
        -generateThenSetReady()
        -generateMusicAsync() ShortArray
        -playCurrent()
        -pauseCurrent()
        -resumeCurrent()
        -updateUi()
        +onDestroy()
    }

    class State {
        <<enumeration>>
        IDLE
        GENERATING
        READY_TO_PLAY
        PLAYING
        PAUSED
    }

    class MusicGenerator {
        -Interpreter interpreter
        -loadModelFile(context: Context, assetName: String) MappedByteBuffer
        +generateNoteSequence(steps: Int, temperature: Double) IntArray
        -sampleFromProbs(probs: FloatArray, temperature: Double) Int
        +close()
    }

    class MusicSynthesizer {
        -Map~Int, String~ indicesValues
        -AudioTrack audioTrack
        -ShortArray currentPcm
        -Thread writeJob
        -Boolean isCancelled
        -loadIndicesValues(context: Context) Map~Int, String~
        +synthesize(noteIndices: IntArray) ShortArray
        -parseNote(raw: String) Note
        -noteNameToMidi(letter: String, accidental: String, octave: Int) Int
        -midiToFrequency(midi: Int) Double
        -appendTone(samples: MutableList~Short~, freqsHz: List~Double~, durationSec: Double)
        +play(pcm: ShortArray, onComplete: () -> Unit)
        +pause()
        +resume()
        +stop()
    }

    class Note {
        <<data class>>
        +List~Double~ frequenciesHz
        +Double durationSec
    }

    class Interpreter {
        <<TensorFlow Lite>>
        +runForMultipleInputsOutputs(inputs, outputs)
        +close()
    }

    class AudioTrack {
        <<Android Media>>
        +play()
        +pause()
        +stop()
        +write(audioData, offsetInShorts, sizeInShorts)
        +release()
    }

    AppCompatActivity <|-- MainActivity
    MainActivity *-- State
    MainActivity o-- MusicGenerator : generates notes
    MainActivity o-- MusicSynthesizer : synthesizes & plays audio
    MusicGenerator o-- Interpreter : runs inference
    MusicSynthesizer *-- Note : parses into
    MusicSynthesizer o-- AudioTrack : plays PCM stream
```

---

## 2. Sequence Diagrams

### A. Music Generation & Playback Flow (`generateThenPlay`)

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Main as MainActivity
    participant Gen as MusicGenerator
    participant TFLite as Interpreter (TFLite)
    participant Synth as MusicSynthesizer
    participant Audio as AudioTrack

    User->>Main: Click Play/Pause Button (State: IDLE)
    activate Main
    Main->>Main: setState(GENERATING) & updateUi()

    Note over Main: Launch Coroutine (Dispatchers.Default)
    Main->>Gen: generateNoteSequence(steps=50, temperature)
    activate Gen
    loop 50 steps
        Gen->>TFLite: runForMultipleInputsOutputs(inputs, outputs)
        TFLite-->>Gen: probabilities & hidden states (a, c)
        Gen->>Gen: sampleFromProbs(outProbs, temperature)
    end
    Gen-->>Main: IntArray (note indices)
    deactivate Gen

    Main->>Synth: synthesize(noteIndices)
    activate Synth
    loop for each note index
        Synth->>Synth: parseNote(jsonValue) -> Note(freqs, duration)
        Synth->>Synth: appendTone(samples, harmonic freqs, duration)
    end
    Synth-->>Main: ShortArray (16-bit PCM samples)
    deactivate Synth

    Note over Main: Switch to UI / Main Thread
    Main->>Synth: play(pcm, onComplete)
    activate Synth
    Synth->>Synth: stop() previous playback if active
    Synth->>Audio: build AudioTrack & write(pcm)
    Synth->>Audio: play()
    Synth->>Synth: Start writeJob Thread (sleep durationMs)
    deactivate Synth

    Main->>Main: setState(PLAYING) & updateUi()
    deactivate Main

    Note over Synth,Audio: Audio playing asynchronously...

    Synth-->>Main: onComplete() callback
    activate Main
    Main->>Main: setState(READY_TO_PLAY) & updateUi()
    deactivate Main
```

### B. Playback Control Flow (Pause / Resume / Next)

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Main as MainActivity
    participant Synth as MusicSynthesizer
    participant Audio as AudioTrack

    alt Pause Clicked (State: PLAYING)
        User->>Main: Click Play/Pause Button
        Main->>Synth: pause()
        Synth->>Audio: pause()
        Main->>Main: setState(PAUSED) & updateUi()

    else Resume Clicked (State: PAUSED)
        User->>Main: Click Play/Pause Button
        Main->>Synth: resume()
        Synth->>Audio: play()
        Main->>Main: setState(PLAYING) & updateUi()

    else Next Clicked (State != GENERATING)
        User->>Main: Click Next Button
        Main->>Synth: stop()
        Synth->>Audio: pause(), stop(), release()
        Main->>Main: generateThenSetReady()
        Note over Main: Generates new track asynchronously & sets state to READY_TO_PLAY
    end
```

---

## 3. Related Code References
- [`MainActivity.kt`](app/src/main/java/com/example/jazztv/MainActivity.kt)
- [`MusicGenerator.kt`](app/src/main/java/com/example/jazztv/MusicGenerator.kt)
- [`MusicSynthesizer.kt`](app/src/main/java/com/example/jazztv/MusicSynthesizer.kt)
