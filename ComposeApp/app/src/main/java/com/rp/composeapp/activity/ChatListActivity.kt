package com.rp.composeapp.activity

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.rp.composeapp.*
import com.rp.composeapp.ui.theme.ComposeAppTheme
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
class ChatListActivity : ComponentActivity() {

    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }

        setContent {
            ComposeAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val chatListData = ChatRepository.allChats
                    val themeSettingsManager = remember { ThemeSettingsManager(this) }

                    // Pass the summary function to the main screen
                    MainScreen(
                        chatItems = chatListData,
                        onChatItemClick = { chatName ->
                            val intent = Intent(this, MainActivity::class.java).apply {
                                putExtra("CHAT_NAME", chatName)
                            }
                            startActivity(intent)
                        },
                        onSettingsClick = {
                            startActivity(Intent(this, SettingsActivity::class.java))
                        },
                        themeSettingsManager = themeSettingsManager,
                        onSpeakSummary = {
                            val summary = generateChatSummaryText(chatListData.take(5))
                            tts?.speak(summary, TextToSpeech.QUEUE_FLUSH, null, null)
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

// --- HELPER FUNCTION TO GENERATE SUMMARY TEXT (UNCHANGED) ---
fun generateChatSummaryText(chats: List<ChatListItemData>): String {
    if (chats.isEmpty()) return "You have no recent chats to summarize."

    val summaryBuilder = StringBuilder("Here is a summary of your top chats. ")
    chats.forEach { chat ->
        if (chat.unreadCount > 0) {
            val unread = if (chat.unreadCount == 1) "1 unread message" else "${chat.unreadCount} unread messages"
            summaryBuilder.append("You have $unread from ${chat.name}. The last message was: ${chat.lastMessage}. ")
        } else {
            summaryBuilder.append("Your last chat with ${chat.name} was about: ${chat.lastMessage}. ")
        }
    }
    return summaryBuilder.toString()
}


// --- DATA CLASS (UNCHANGED) ---
data class ChatListItemData(
    val id: String,
    val name: String,
    val lastMessage: String,
    val timestamp: String,
    val imageResId: Int,
    val unreadCount: Int
)

// --- ▼▼▼ MAIN SCREEN WITH SWIPEABLE TABS ▼▼▼ ---
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class) // ADDED ExperimentalFoundationApi
@Composable
fun MainScreen(
    chatItems: List<ChatListItemData>,
    onChatItemClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    themeSettingsManager: ThemeSettingsManager,
    onSpeakSummary: () -> Unit
) {
    val tabs = listOf("Chats", "Status", "Calls")
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()
    var showThemeDialog by remember { mutableStateOf(false) }

    if (showThemeDialog) {
        ThemePickerDialog(
            themeSettingsManager = themeSettingsManager,
            onDismiss = { showThemeDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ComposeApp") }, // App title
                actions = {
                    IconButton(onClick = onSpeakSummary) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = "Speak Chat Summary"
                        )
                    }
                    IconButton(onClick = { showThemeDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = "Chat Theme"
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            TabRow(selectedTabIndex = pagerState.currentPage) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        text = { Text(title) }
                    )
                }
            }
            // Content for each tab is now inside a HorizontalPager
            HorizontalPager(state = pagerState) { page ->
                when (page) {
                    0 -> ChatListScreen(chatItems, onChatItemClick)
                    1 -> StatusScreen() // Placeholder for Status
                    2 -> CallsScreen()  // Placeholder for Calls
                }
            }
        }
    }
}


// --- CHAT LIST SCREEN (NOW WITHOUT SCAFFOLD) ---
@Composable
fun ChatListScreen(
    chatItems: List<ChatListItemData>,
    onChatItemClick: (String) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(chatItems) { chat ->
            ChatListItem(
                chatData = chat,
                onClick = { onChatItemClick(chat.name) }
            )
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
        }
    }
}

// --- PLACEHOLDER SCREENS FOR TABS (UNCHANGED) ---

@Composable
fun StatusScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("Status Screen", fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
fun CallsScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("Calls Screen", fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}


// --- THEME PICKER DIALOG (UNCHANGED) ---
@Composable
fun ThemePickerDialog(
    themeSettingsManager: ThemeSettingsManager,
    onDismiss: () -> Unit
) {
    var leftColor by remember { mutableStateOf(Color(themeSettingsManager.getLeftBubbleColor())) }
    var rightColor by remember { mutableStateOf(Color(themeSettingsManager.getRightBubbleColor())) }
    val colorOptions = listOf(
        Color(0xFF2C3E50), // Dark Slate Blue
        Color(0xFF4A4A4A), // Charcoal Gray
        Color(0xFF005f56), // Dark Teal Green
        Color(0xFF6A057F), // Dark Purple
        Color(0xFF5D4037)  // Dark Brown
    )

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Chat Bubble Colors", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))

                // Left (Incoming) Bubble Color Picker
                Text("Incoming Message Color", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    colorOptions.forEach { color ->
                        ColorOption(color = color, isSelected = color == leftColor) {
                            leftColor = color
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))

                // Right (Outgoing) Bubble Color Picker
                Text("Outgoing Message Color", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    colorOptions.forEach { color ->
                        ColorOption(color = color, isSelected = color == rightColor) {
                            rightColor = color
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        themeSettingsManager.saveBubbleColors(leftColor.toArgb(), rightColor.toArgb())
                        onDismiss()
                    }) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

// --- COLOR OPTION (UNCHANGED) ---
@Composable
fun ColorOption(color: Color, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color.White.copy(alpha = 0.8f))
        }
    }
}


// --- CHAT LIST ITEM (UNCHANGED) ---
@Composable
fun ChatListItem(chatData: ChatListItemData, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = chatData.imageResId),
            contentDescription = "${chatData.name}'s profile picture",
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chatData.name,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = chatData.lastMessage,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = chatData.timestamp,
                fontSize = 12.sp,
                color = if (chatData.unreadCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (chatData.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = chatData.unreadCount.toString(),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
