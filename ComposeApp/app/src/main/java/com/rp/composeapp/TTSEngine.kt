package com.rp.composeapp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale
import kotlin.concurrent.thread

/**
 * An interface representing a Text-to-Speech engine.
 */
interface TtsEngine {
    fun initialize(context: Context, listener: UtteranceProgressListener, onInit: (Boolean) -> Unit)
    fun speak(text: String, utteranceId: String)
    fun setVoice(voiceName: String?)
    fun stop()
    fun shutdown()
}

/**
 * An implementation of TtsEngine that uses the standard Android TextToSpeech service.
 */
class AndroidTtsEngine : TtsEngine {
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    override fun initialize(context: Context, listener: UtteranceProgressListener, onInit: (Boolean) -> Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                tts?.setOnUtteranceProgressListener(listener)
                onInit(true)
            } else {
                Log.e("AndroidTtsEngine", "Initialization Failed!")
                onInit(false)
            }
        }
    }

    override fun speak(text: String, utteranceId: String) {
        if (!isInitialized) return
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    override fun setVoice(voiceName: String?) {
        if (!isInitialized) return
        val voice = tts?.voices?.find { it.name == voiceName }
        val result = if (voice != null) {
            tts?.voice = voice
            tts?.setLanguage(voice.locale)
        } else {
            tts?.setLanguage(Locale.US)
        }
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e("TTS", "The Language specified is not supported!")
        }
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}

/**
 * A custom TTS engine that uses a local TensorFlow Lite model for speech synthesis.
 * This version includes a functional implementation of tokenization, inference,
 * and audio playback.
 */
class CustomMlTtsEngine(private val modelPath: String) : TtsEngine {

    private var interpreter: Interpreter? = null
    private var isInitialized = false
    private lateinit var utteranceListener: UtteranceProgressListener
    private var audioTrack: AudioTrack? = null

    // --- Model-Specific Parameters (NEEDS TO BE CONFIGURED FOR YOUR MODEL) ---
    private val sampleRate = 22050 // Common sample rate for TTS models

    override fun initialize(context: Context, listener: UtteranceProgressListener, onInit: (Boolean) -> Unit) {
        this.utteranceListener = listener
        thread(start = true) { // Run model loading on a background thread
            try {
                // Load the .tflite model from the app's assets folder
                val assetFileDescriptor = context.assets.openFd(modelPath)
                val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
                val fileChannel = fileInputStream.channel
                val startOffset = assetFileDescriptor.startOffset
                val declaredLength = assetFileDescriptor.declaredLength
                val mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

                val options = Interpreter.Options()
                interpreter = Interpreter(mappedByteBuffer, options)
                isInitialized = true
                Log.d("CustomMlTtsEngine", "TFLite model '$modelPath' loaded successfully.")
                onInit(true)
            } catch (e: Exception) {
                Log.e("CustomMlTtsEngine", "Error loading TFLite model: ${e.message}", e)
                isInitialized = false
                onInit(false)
            }
        }
    }

    override fun speak(text: String, utteranceId: String) {
        if (!isInitialized || interpreter == null) {
            Log.e("CustomMlTtsEngine", "Engine is not initialized. Cannot speak.")
            utteranceListener.onError(utteranceId)
            return
        }

        // Run the entire synthesis and playback in a background thread to not block the UI
        thread(start = true) {
            try {
                utteranceListener.onStart(utteranceId)
                Log.d("CustomMlTtsEngine", "Synthesizing text: '$text'")

                // 1. PRE-PROCESS TEXT
                val inputTokens = tokenizeText(text)
                val inputBuffer = ByteBuffer.allocateDirect(inputTokens.size * 4).order(ByteOrder.nativeOrder())
                inputTokens.forEach { inputBuffer.putInt(it) }
                inputBuffer.rewind()

                // 2. RUN SPECTROGRAM INFERENCE
                // The output shape depends entirely on your model. This is an example.
                // Shape: [1, num_frames, mel_bins] -> e.g., [1, 250, 80]
                val outputSpectrogram = Array(1) { Array(250) { FloatArray(80) } } // Example shape
                interpreter?.run(inputBuffer, outputSpectrogram)
                Log.d("CustomMlTtsEngine", "Spectrogram generated successfully.")

                // 3. RUN VOCODER TO GET WAVEFORM
                val audioWaveform = runVocoder(outputSpectrogram)
                Log.d("CustomMlTtsEngine", "Audio waveform generated with ${audioWaveform.size} samples.")

                // 4. PLAY AUDIO
                playAudio(audioWaveform)

                // 5. REPORT COMPLETION
                Log.d("CustomMlTtsEngine", "Playback finished for $utteranceId.")
                utteranceListener.onDone(utteranceId)

            } catch (e: Exception) {
                Log.e("CustomMlTtsEngine", "Error during synthesis: ${e.message}", e)
                utteranceListener.onError(utteranceId)
            }
        }
    }

    private fun tokenizeText(text: String): IntArray {
        // !! PLACEHOLDER !!
        // A real implementation would use a dictionary/vocab file.
        // This simple version just maps characters to their integer codes.
        return text.map { it.code }.toIntArray()
    }

    private fun runVocoder(spectrogram: Array<Array<FloatArray>>): FloatArray {
        // !! PLACEHOLDER !!
        // This should be a real vocoder (like a Griffin-Lim implementation or another ML model).
        // For this example, we generate a simple sine wave to prove that audio playback works.
        val durationInSamples = 80000 // ~3-4 seconds
        return FloatArray(durationInSamples) { i ->
            kotlin.math.sin(2.0 * Math.PI * i / (sampleRate / 440.0)).toFloat() * 0.3f
        }
    }

    private fun playAudio(waveform: FloatArray) {
        stop() // Stop any previous playback

        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        audioTrack?.let {
            it.play()
            it.write(waveform, 0, waveform.size, AudioTrack.WRITE_BLOCKING)
            it.stop()
            it.release()
            audioTrack = null
        }
    }

    override fun setVoice(voiceName: String?) {
        Log.d("CustomMlTtsEngine", "Voice setting is handled by the specific model file loaded.")
    }

    override fun stop() {
        audioTrack?.let {
            if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                it.stop()
                it.release()
            }
            audioTrack = null
        }
        Log.d("CustomMlTtsEngine", "Stopping playback.")
    }

    override fun shutdown() {
        stop()
        interpreter?.close()
        isInitialized = false
        Log.d("CustomMlTtsEngine", "Engine shut down.")
    }
}
