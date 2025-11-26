package com.rp.composeapp

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rp.composeapp.ui.theme.ComposeAppTheme
import java.io.File
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
class RecordVoiceActivity : ComponentActivity() {

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording by mutableStateOf(false)

    // Modern way to handle permission requests
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startRecording()
            } else {
                Log.e("RecordVoiceActivity", "Audio recording permission denied.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ComposeAppTheme {
                RecordVoiceScreen(
                    isRecording = isRecording,
                    onRecordToggle = {
                        if (isRecording) {
                            stopRecording()
                        } else {
                            checkPermissionAndRecord()
                        }
                    },
                    onBack = { finish() }
                )
            }
        }
    }

    private fun checkPermissionAndRecord() {
        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) -> {
                startRecording()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startRecording() {
        // Create a directory for voice recordings if it doesn't exist
        val recordingsDir = File(filesDir, "voices")
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs()
        }

        // Name the file based on the timestamp
        audioFile = File(recordingsDir, "voice_${System.currentTimeMillis()}.3gp")

        mediaRecorder = MediaRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(audioFile?.absolutePath)
            try {
                prepare()
                start()
                isRecording = true
                Log.d("RecordVoiceActivity", "Recording started to ${audioFile?.absolutePath}")
            } catch (e: IOException) {
                Log.e("RecordVoiceActivity", "MediaRecorder prepare() failed: ${e.message}")
            }
        }
    }

    private fun stopRecording() {
        mediaRecorder?.apply {
            stop()
            release()
        }
        mediaRecorder = null
        isRecording = false
        Log.d("RecordVoiceActivity", "Recording stopped. File saved.")
        // Set result so SettingsActivity knows to refresh
        setResult(RESULT_OK)
    }

    override fun onStop() {
        super.onStop()
        // Ensure recording is stopped if the user leaves the screen
        if (isRecording) {
            stopRecording()
        }
    }
}

// --- Composable UI for the Recording Screen ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordVoiceScreen(
    isRecording: Boolean,
    onRecordToggle: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Your Voice") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isRecording) "Recording in progress..." else "Press the button to start recording your voice.",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = onRecordToggle,
                modifier = Modifier.size(100.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isRecording) "Stop Recording" else "Start Recording",
                    modifier = Modifier.size(48.dp)
                )
            }
        }
    }
}
