package com.rp.composeapp

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rp.composeapp.ui.theme.ComposeAppTheme
import java.io.File
import java.util.Locale

// Represents a selectable voice option in the UI
private data class VoiceOption(
    val id: String,
    val displayName: String,
    val type: Type,
    val isCustom: Boolean = false
) {
    enum class Type { ANDROID, CUSTOM }
}

class SettingsActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var settingsManager: TtsSettingsManager

    // Holds the list of available voices to display
    private val voiceOptions = mutableStateOf<List<VoiceOption>>(emptyList())
    // Holds the ID of the currently selected voice
    private var selectedVoiceId by mutableStateOf("")

    // Engine for playing samples from custom ML models
    private var customSampleEngine: TtsEngine? = null

    // Launcher for starting the voice recording activity
    private val recordVoiceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // When we return from recording, reload the voice list to show the new file
            if (result.resultCode == RESULT_OK) {
                loadVoiceOptions()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        settingsManager = TtsSettingsManager(this)
        // Load the currently saved configuration to highlight the correct item
        loadCurrentSelection()

        // We need a temporary TTS instance to get the list of available system voices
        tts = TextToSpeech(this, this)

        setContent {
            ComposeAppTheme {
                // `voiceOptions.value` is used to trigger recomposition when the list loads
                SettingsScreen(
                    voiceOptions = voiceOptions.value,
                    selectedId = selectedVoiceId,
                    onVoiceSelected = { voiceOption ->
                        // 1. Save the new selection
                        saveSelection(voiceOption)
                        selectedVoiceId = voiceOption.id

                        // 2. Set a result to notify the previous screen that a change was made
                        setResult(RESULT_OK)

                        // 3. Close the settings screen automatically
                        finish()
                    },
                    onPlaySample = { voiceOption ->
                        playSample(voiceOption)
                    },
                    onBack = { finish() },
                    onAddVoice = {
                        val intent = Intent(this, RecordVoiceActivity::class.java)
                        recordVoiceLauncher.launch(intent)
                    }
                )
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // TTS is ready, now we can dynamically load all available voices
            loadVoiceOptions()
        }
    }

    private fun loadVoiceOptions() {
        // 1. Get standard system voices
        val systemVoices = tts.voices
            .filter { it.locale == Locale.US || it.locale == Locale.UK || it.locale == Locale.ENGLISH }
            .map { VoiceOption(it.name, "Android: ${it.name}", VoiceOption.Type.ANDROID) }

        // 2. Get the pre-packaged ML model from assets
        val assetVoices = listOf(
            VoiceOption(
                id = "models/my_cloned_voice.tflite",
                displayName = "Custom Voice (ML Model)",
                type = VoiceOption.Type.CUSTOM,
                isCustom = true
            )
        )

        // 3. Scan for user-recorded voices in internal storage
        val recordingsDir = File(filesDir, "voices")
        val recordedVoices = if (recordingsDir.exists() && recordingsDir.isDirectory) {
            recordingsDir.listFiles { _, name -> name.endsWith(".3gp") }
                ?.sortedByDescending { it.lastModified() } // Show newest first
                ?.map { file ->
                    // The ID is the file path. The name is more user-friendly.
                    VoiceOption(
                        id = file.absolutePath,
                        displayName = "My Voice (${file.nameWithoutExtension.substringAfter('_')})",
                        type = VoiceOption.Type.CUSTOM,
                        isCustom = true
                    )
                } ?: emptyList()
        } else {
            emptyList()
        }

        // Combine all lists and update the UI
        voiceOptions.value = assetVoices + recordedVoices + systemVoices
    }


    private fun loadCurrentSelection() {
        when (val config = settingsManager.getEngineConfig()) {
            is TtsConfiguration.Android -> selectedVoiceId = config.voiceName ?: ""
            is TtsConfiguration.Custom -> selectedVoiceId = config.modelAssetPath
        }
    }

    private fun saveSelection(option: VoiceOption) {
        stopAllPlayback()
        when (option.type) {
            VoiceOption.Type.ANDROID -> {
                // Find the original Voice object and save it
                val androidVoice = tts.voices.find { it.name == option.id }
                if (androidVoice != null) {
                    settingsManager.saveAndroidVoice(androidVoice)
                }
            }
            VoiceOption.Type.CUSTOM -> {
                // Save the custom model path
                settingsManager.saveCustomVoice(option.displayName, option.id)
            }
        }
    }

    private fun playSample(option: VoiceOption) {
        stopAllPlayback() // Stop any previous sample

        val sampleText = "Hello, you can use this voice to listen to messages."
        val utteranceId = "sample_playback"

        when (option.type) {
            VoiceOption.Type.ANDROID -> {
                // Use the existing TTS engine for Android voices
                val voice = tts.voices.find { it.name == option.id }
                if (voice != null) {
                    tts.voice = voice
                    tts.speak(sampleText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                }
            }
            VoiceOption.Type.CUSTOM -> {
                // For custom voices, create a temporary engine instance
                val engine = CustomMlTtsEngine(option.id)
                customSampleEngine = engine
                engine.initialize(this, object : UtteranceProgressListener() {
                    override fun onStart(p0: String?) {}
                    override fun onDone(p0: String?) {}
                    override fun onError(p0: String?) {}
                }) { success ->
                    if (success) {
                        engine.speak(sampleText, utteranceId)
                    } else {
                        Log.e("SettingsActivity", "Failed to initialize custom sample engine for path: ${option.id}")
                    }
                }
            }
        }
    }

    private fun stopAllPlayback() {
        // Stop both potential sources of audio
        tts.stop()
        customSampleEngine?.stop()
        customSampleEngine?.shutdown()
        customSampleEngine = null
    }

    override fun onDestroy() {
        stopAllPlayback()
        // Shut down the main TTS instance that's part of the activity
        if (::tts.isInitialized) {
            tts.shutdown()
        }
        super.onDestroy()
    }
}

// --- Composable UI for the Settings Screen ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    voiceOptions: List<VoiceOption>,
    selectedId: String,
    onVoiceSelected: (VoiceOption) -> Unit,
    onPlaySample: (VoiceOption) -> Unit,
    onBack: () -> Unit,
    onAddVoice: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddVoice) {
                Icon(Icons.Default.Mic, contentDescription = "Add My Voice")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Add padding at the bottom to avoid the FAB from covering the last item
                .padding(bottom = 80.dp)
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Select a Voice", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Choose a voice and tap the play icon to hear a sample.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(voiceOptions) { option ->
                VoiceItem(
                    option = option,
                    isSelected = option.id == selectedId,
                    onClick = { onVoiceSelected(option) },
                    onPlay = { onPlaySample(option) }
                )
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            }
        }
    }
}

@Composable
private fun VoiceItem(
    option: VoiceOption,
    isSelected: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 16.dp)
        ) {
            Text(
                text = option.displayName,
                fontWeight = if (option.isCustom) FontWeight.Bold else FontWeight.Normal,
                fontSize = 17.sp,
                color = if (option.isCustom) MaterialTheme.colorScheme.primary else Color.Unspecified
            )
            Text(
                text = if (option.isCustom) "Machine Learning Model" else "System Voice",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Play Button
        IconButton(onClick = onPlay) {
            Icon(
                imageVector = Icons.Default.PlayCircleOutline,
                contentDescription = "Play Sample",
                tint = MaterialTheme.colorScheme.secondary
            )
        }

        // Checkmark Icon
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp)
            )
        } else {
            // Add a spacer to keep alignment consistent when not selected
            Spacer(modifier = Modifier.width(40.dp))
        }
    }
}
