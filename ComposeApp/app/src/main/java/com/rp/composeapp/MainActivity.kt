package com.rp.composeapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.rp.composeapp.activity.ChatListActivity
import com.rp.composeapp.ui.theme.ComposeAppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

// --- STATE MACHINE FOR PLAYBACK ---
sealed class PlaybackState {
    object Idle : PlaybackState()
    data class Playing(val utteranceId: String) : PlaybackState()
    data class Paused(val utteranceId: String) : PlaybackState()
}

// --- STATE FOR MESSAGE ACTIONS ---
sealed class MessageActionState {
    object Hidden : MessageActionState()
    data class Visible(val messageId: Int) : MessageActionState()
}

// --- STATE FOR INPUT MODE ---
sealed class InputMode {
    object Normal : InputMode()
    data class Editing(val message: ChatMessage) : InputMode()
}


class MainActivity : ComponentActivity() {

    private lateinit var ttsEngine: TtsEngine
    private lateinit var settingsManager: TtsSettingsManager
    private var playbackState by mutableStateOf<PlaybackState>(PlaybackState.Idle)
    private lateinit var themeSettingsManager: ThemeSettingsManager

    // --- FOR VOICE & AUDIO PLAYBACK ---
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var mediaPlayer: MediaPlayer? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (!isGranted) {
                Log.e("MainActivity", "Audio recording permission was denied.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val chatName = intent.getStringExtra("CHAT_NAME") ?: "Chat"
        settingsManager = TtsSettingsManager(this)
        themeSettingsManager = ThemeSettingsManager(this)

        ChatRepository.markAsRead(chatName)

        val config = settingsManager.getEngineConfig()
        ttsEngine = when (config) {
            is TtsConfiguration.Android -> {
                Log.d("MainActivity", "Using standard Android TTS Engine.")
                AndroidTtsEngine()
            }
            is TtsConfiguration.Custom -> {
                Log.d("MainActivity", "Using Custom ML TTS Engine with model: ${config.modelAssetPath}")
                CustomMlTtsEngine(config.modelAssetPath)
            }
        }
        setupTts(config)

        setContent {
            ComposeAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChatScreen(
                        chatName = chatName,
                        playbackState = playbackState,
                        onPlayMessage = { message -> playMessage(message) },
                        onPauseMessage = { pauseAudio() },
                        leftBubbleColor = Color(themeSettingsManager.getLeftBubbleColor()),
                        rightBubbleColor = Color(themeSettingsManager.getRightBubbleColor()),
                        onStartRecording = { startRecording() },
                        onStopRecording = { stopRecording() }
                    )
                }
            }
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val recordingsDir = File(filesDir, "recordings")
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs()
        }
        audioFile = File(recordingsDir, "voice_note_${System.currentTimeMillis()}.3gp")

        mediaRecorder = MediaRecorder(applicationContext).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(audioFile?.absolutePath)
            try {
                prepare()
                start()
                Log.d("MainActivity", "Recording started.")
            } catch (e: IOException) {
                Log.e("MainActivity", "MediaRecorder prepare() failed: ${e.message}")
            } catch (e: IllegalStateException) {
                Log.e("MainActivity", "MediaRecorder start() failed: ${e.message}")
            }
        }
    }

    private fun stopRecording(): File? {
        if (mediaRecorder == null) return null
        return try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            Log.d("MainActivity", "Recording stopped. File: ${audioFile?.path}")
            audioFile
        } catch (e: Exception) {
            Log.e("MainActivity", "MediaRecorder stop failed: ${e.message}")
            audioFile?.delete()
            audioFile = null
            null
        }
    }

    private fun setupTts(config: TtsConfiguration) {
        val progressListener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != null) {
                    playbackState = PlaybackState.Playing(utteranceId)
                }
            }

            override fun onDone(utteranceId: String?) {
                playbackState = PlaybackState.Idle
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                playbackState = PlaybackState.Idle
            }
        }

        ttsEngine.initialize(this, progressListener) { success ->
            if (success) {
                Log.d("MainActivity", "TTS Engine initialized successfully.")
                if (config is TtsConfiguration.Android) {
                    ttsEngine.setVoice(config.voiceName)
                }
            } else {
                Log.e("MainActivity", "TTS Engine failed to initialize.")
            }
        }
    }

    private fun playMessage(message: ChatMessage) {
        val utteranceId = message.id.toString()
        pauseAudio() // Stop any currently playing audio first

        when (message.type) {
            MessageType.TEXT -> {
                ttsEngine.speak(message.content, utteranceId)
            }
            MessageType.VOICE -> {
                message.audioPath?.let { path ->
                    mediaPlayer = MediaPlayer().apply {
                        try {
                            setDataSource(path)
                            prepare()
                            start()
                            playbackState = PlaybackState.Playing(utteranceId)
                            setOnCompletionListener {
                                playbackState = PlaybackState.Idle
                                it.release() // Release player on completion
                            }
                        } catch (e: IOException) {
                            Log.e("MainActivity", "MediaPlayer failed for path: $path", e)
                            playbackState = PlaybackState.Idle
                        }
                    }
                }
            }
        }
    }

    private fun pauseAudio() {
        val currentState = playbackState
        if (currentState is PlaybackState.Playing) {
            ttsEngine.stop()
            mediaPlayer?.pause()
            playbackState = PlaybackState.Paused(currentState.utteranceId)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsEngine.shutdown()
        mediaRecorder?.release()
        mediaRecorder = null
        mediaPlayer?.release()
        mediaPlayer = null
    }
}

enum class MessageType { TEXT, VOICE }

data class ChatMessage(
    val id: Int,
    val sender: String,
    var content: String, // Made content a 'var' to allow editing
    val isFromCurrentUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(), // Added timestamp
    val type: MessageType = MessageType.TEXT,
    val audioPath: String? = null,
    var isEdited: Boolean = false // Track if message has been edited
)

@Composable
fun ChatScreen(
    chatName: String,
    playbackState: PlaybackState,
    onPlayMessage: (ChatMessage) -> Unit,
    onPauseMessage: () -> Unit,
    leftBubbleColor: Color,
    rightBubbleColor: Color,
    onStartRecording: () -> Unit,
    onStopRecording: () -> File?
) {
    var messageActionState by remember { mutableStateOf<MessageActionState>(MessageActionState.Hidden) }
    var inputMode by remember { mutableStateOf<InputMode>(InputMode.Normal) }

    val messages = remember {
        mutableStateListOf(
            ChatMessage(1, chatName, "Hi Bob, how are you doing?", false),
            ChatMessage(2, "Bob", "I'm doing great! Just finished setting up my new workspace.", true),
            ChatMessage(3, chatName, "That's awesome! You should show me a picture sometime.", false)
        )
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    BackHandler {
        context.startActivity(Intent(context, ChatListActivity::class.java))
        (context as? ComponentActivity)?.finish()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Surface(shadowElevation = 4.dp) {
                Text(
                    text = chatName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                        .padding(16.dp),
                    style = MaterialTheme.typography.titleLarge
                )
            }
        },
        bottomBar = {
            ChatInputBar(
                inputMode = inputMode,
                onMessageSent = { messageContent ->
                    when (val mode = inputMode) {
                        is InputMode.Editing -> {
                            // Find the message and update its content
                            val messageToEdit = messages.find { it.id == mode.message.id }
                            messageToEdit?.let {
                                it.content = messageContent
                                it.isEdited = true
                            }
                            inputMode = InputMode.Normal // Reset to normal mode
                        }
                        is InputMode.Normal -> {
                            // Add a new message
                            messages.add(
                                ChatMessage(id = (messages.maxOfOrNull { it.id } ?: 0) + 1, sender = "Bob", content = messageContent, isFromCurrentUser = true)
                            )
                        }
                    }
                    coroutineScope.launch { listState.animateScrollToItem(messages.size - 1) }
                },
                onStartRecording = onStartRecording,
                onStopRecording = {
                    val recordedFile = onStopRecording()
                    if (recordedFile != null && recordedFile.exists() && recordedFile.length() > 0) {
                        messages.add(
                            ChatMessage(
                                id = (messages.maxOfOrNull { it.id } ?: 0) + 1,
                                sender = "Bob",
                                content = "Voice Note",
                                isFromCurrentUser = true,
                                type = MessageType.VOICE,
                                audioPath = recordedFile.absolutePath
                            )
                        )
                        coroutineScope.launch { listState.animateScrollToItem(messages.size - 1) }
                    } else {
                        Log.w("ChatScreen", "Recording was too short or failed, not adding message.")
                    }
                },
                onCancelEdit = { inputMode = InputMode.Normal }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                val messageId = message.id.toString()
                val isPlaying = playbackState is PlaybackState.Playing && playbackState.utteranceId == messageId
                val isPaused = playbackState is PlaybackState.Paused && playbackState.utteranceId == messageId

                MessageBubble(
                    message = message,
                    isPlaying = isPlaying,
                    isPaused = isPaused,
                    isMenuVisible = (messageActionState as? MessageActionState.Visible)?.messageId == message.id,
                    onPlay = { onPlayMessage(message) },
                    onPause = onPauseMessage,
                    onShowMenu = { messageActionState = MessageActionState.Visible(message.id) },
                    onHideMenu = { messageActionState = MessageActionState.Hidden },
                    onDelete = {
                        messages.remove(message)
                        messageActionState = MessageActionState.Hidden
                    },
                    onEdit = {
                        inputMode = InputMode.Editing(message)
                        messageActionState = MessageActionState.Hidden
                    },
                    leftBubbleColor = leftBubbleColor,
                    rightBubbleColor = rightBubbleColor
                )
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    isPlaying: Boolean,
    isPaused: Boolean,
    isMenuVisible: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onShowMenu: () -> Unit,
    onHideMenu: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    leftBubbleColor: Color,
    rightBubbleColor: Color
) {
    val simpleDateFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val time = simpleDateFormat.format(Date(message.timestamp))
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            // Estimate duration: 70ms per character for text, 5 seconds for voice notes.
            val duration = if (message.type == MessageType.TEXT) (message.content.length * 70).toLong() else 5000L
            val startTime = System.currentTimeMillis()
            while (this.isActive) {
                val elapsedTime = System.currentTimeMillis() - startTime
                progress = (elapsedTime.toFloat() / duration).coerceIn(0f, 1f)
                if (progress >= 1f) break
                delay(100) // Update every 100ms
            }
        } else if (!isPaused) {
            // Reset progress if not playing or paused
            progress = 0f
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isFromCurrentUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!message.isFromCurrentUser) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = "Profile Picture",
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .padding(end = 8.dp)
            )
        }

        Column(horizontalAlignment = if (message.isFromCurrentUser) Alignment.End else Alignment.Start) {
            if (isMenuVisible) {
                MessageActionMenu(
                    onDelete = onDelete,
                    onEdit = onEdit,
                    onDismiss = onHideMenu
                )
            }

            Card(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .combinedClickable(
                        onClick = { /* Can add short-click action here if needed */ },
                        onLongClick = onShowMenu
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (message.isFromCurrentUser) rightBubbleColor else leftBubbleColor
                )
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(text = message.sender, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        if (message.type == MessageType.TEXT) {
                            Text(text = message.content, fontSize = 16.sp)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Mic, contentDescription = "Voice Note")
                                Spacer(Modifier.width(8.dp))
                                Text(text = "Voice Note", fontSize = 16.sp)
                            }
                        }
                    }
                    if (message.type == MessageType.TEXT || message.audioPath != null) {
                        Spacer(modifier = Modifier.width(12.dp))
                        IconButton(onClick = { if (isPlaying) onPause() else onPlay() }) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row {
                Text(
                    text = time,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                if (message.isEdited) {
                    Text(
                        text = " (edited)",
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Italic,
                        color = Color.Gray,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
            if (isPlaying || isPaused) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .width(200.dp)
                        .padding(top = 4.dp)
                )
            }
        }

        if (message.isFromCurrentUser) {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = "My Profile Picture",
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .padding(start = 8.dp)
            )
        }
    }
}

@Composable
fun MessageActionMenu(
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit
) {
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text("Edit") },
            onClick = onEdit,
            leadingIcon = { Icon(Icons.Default.Edit, "Edit") }
        )
        DropdownMenuItem(
            text = { Text("Delete") },
            onClick = onDelete,
            leadingIcon = { Icon(Icons.Default.Delete, "Delete") }
        )
    }
}

@Composable
fun ChatInputBar(
    inputMode: InputMode,
    onMessageSent: (String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelEdit: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }

    // When inputMode changes to Editing, update the text field
    LaunchedEffect(inputMode) {
        if (inputMode is InputMode.Editing) {
            text = inputMode.message.content
        } else {
            text = ""
        }
    }

    // This LaunchedEffect is the key to fixing the recording gesture.
    LaunchedEffect(isRecording) {
        if (isRecording) {
            onStartRecording()
        }
    }

    Surface(shadowElevation = 8.dp) {
        Column {
            // Show an "Editing" banner if in edit mode
            if (inputMode is InputMode.Editing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Edit, "Editing", tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Editing message...",
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                    IconButton(onClick = onCancelEdit) {
                        Icon(Icons.Default.Close, "Cancel Edit")
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .animateContentSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") },
                    shape = RoundedCornerShape(24.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Microphone button for recording (ALWAYS VISIBLE)
                IconButton(
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                // Only allow press-and-hold to start if in normal mode and user is not typing.
                                if (text.isBlank() && inputMode is InputMode.Normal) {
                                    isRecording = true
                                    try {
                                        awaitRelease()
                                    } finally {
                                        isRecording = false
                                        onStopRecording()
                                    }
                                }
                            }
                        )
                    },
                    // The button itself is only "enabled" for gestures under these conditions.
                    enabled = text.isBlank() && inputMode is InputMode.Normal,
                    onClick = { /* onClick is not needed for press-and-hold */ }
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Record Voice Note",
                        // The tint provides visual feedback on the state.
                        tint = when {
                            isRecording -> MaterialTheme.colorScheme.error // Red when recording
                            text.isNotBlank() || inputMode is InputMode.Editing -> Color.Gray // Grayed out
                            else -> LocalContentColor.current // Default color
                        },
                        modifier = if (isRecording) Modifier.size(30.dp) else Modifier.size(24.dp)
                    )
                }

                // Send button (ALWAYS VISIBLE)
                IconButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            onMessageSent(text)
                        }
                    },
                    // The button is only enabled for clicks when there is text.
                    enabled = text.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send Message",
                        // The tint provides visual feedback on the enabled state.
                        tint = if (text.isNotBlank()) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }
            }
        }
    }
}


@Preview(showBackground = true)
@Composable
fun ChatScreenPreview() {
    ComposeAppTheme {
        ChatScreen(
            "Alice",
            PlaybackState.Idle, {}, {},
            leftBubbleColor = MaterialTheme.colorScheme.secondaryContainer,
            rightBubbleColor = MaterialTheme.colorScheme.primaryContainer,
            onStartRecording = {},
            onStopRecording = { null }
        )
    }
}
