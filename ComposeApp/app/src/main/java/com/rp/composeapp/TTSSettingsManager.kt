package com.rp.composeapp

import android.content.Context
import android.content.SharedPreferences
import android.speech.tts.Voice

/**
 * Manages saving and retrieving TTS settings from SharedPreferences.
 * This has been updated to support selecting different TTS engines and custom models.
 */
class TtsSettingsManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("TtsPrefs", Context.MODE_PRIVATE)

    companion object {
        // Key to store the ID of the selected engine (e.g., "android_tts")
        private const val KEY_ENGINE_ID = "engine_id"
        // Key to store the name of the selected standard Android voice
        private const val KEY_ANDROID_VOICE_NAME = "android_voice_name"
        // Key to store the path of the selected custom ML model file
        private const val KEY_CUSTOM_MODEL_PATH = "custom_model_path"

        const val ANDROID_ENGINE = "android_tts"
        const val CUSTOM_ML_ENGINE = "custom_ml_tts"
    }

    /**
     * Saves the setting to use the standard Android TTS engine with a specific voice.
     * @param voice The standard Android Voice object.
     */
    fun saveAndroidVoice(voice: Voice) {
        prefs.edit()
            .putString(KEY_ENGINE_ID, ANDROID_ENGINE)
            .putString(KEY_ANDROID_VOICE_NAME, voice.name)
            .apply()
    }

    /**
     * Saves the setting to use a custom, trained ML model.
     * @param modelName A user-friendly name for the model (e.g., "Cloned Voice 1").
     * @param modelAssetPath The path to the .tflite model file in the app's `assets` folder.
     */
    fun saveCustomVoice(modelName: String, modelAssetPath: String) {
        // In a real app, you might save the user-friendly name too, but the path is essential.
        prefs.edit()
            .putString(KEY_ENGINE_ID, CUSTOM_ML_ENGINE)
            .putString(KEY_CUSTOM_MODEL_PATH, modelAssetPath)
            .apply()
    }

    /**
     * Retrieves the configuration for the currently selected TTS engine.
     * @return A TtsConfiguration object representing the saved choice.
     */
    fun getEngineConfig(): TtsConfiguration {
        val engineId = prefs.getString(KEY_ENGINE_ID, ANDROID_ENGINE)

        return when (engineId) {
            CUSTOM_ML_ENGINE -> {
                val modelPath = prefs.getString(KEY_CUSTOM_MODEL_PATH, "models/default_voice.tflite")
                TtsConfiguration.Custom(modelPath!!) // Assume a default model exists
            }
            else -> { // Default to Android TTS
                val voiceName = prefs.getString(KEY_ANDROID_VOICE_NAME, null)
                TtsConfiguration.Android(voiceName)
            }
        }
    }
}

/**
 * A sealed class to represent the different types of TTS configurations available.
 */
sealed class TtsConfiguration {
    /**
     * Configuration for the standard Android TTS engine.
     * @param voiceName The unique name of the voice to use (e.g., "en-us-x-sfg-local"), or null for default.
     */
    data class Android(val voiceName: String?) : TtsConfiguration()

    /**
     * Configuration for a custom ML-based TTS engine.
     * @param modelAssetPath The path to the model file within the app's `assets` directory.
     */
    data class Custom(val modelAssetPath: String) : TtsConfiguration()
}
