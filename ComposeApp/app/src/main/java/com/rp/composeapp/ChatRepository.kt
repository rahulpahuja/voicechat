package com.rp.composeapp


import androidx.compose.runtime.mutableStateListOf
import com.rp.composeapp.activity.ChatListItemData

/**
 * A singleton object to act as a simple in-memory database for our chats.
 */
object ChatRepository {

    // The list of chats is now a mutableStateListOf to trigger UI updates when it changes.
    private val _chats = mutableStateListOf(
        ChatListItemData(
            "1",
            "Alice",
            "That sounds awesome! You'll have to show me a picture.",
            "10:42 AM",
            R.drawable.t1,
            2
        ),
        ChatListItemData(
            "2",
            "John Smith",
            "Project deadline is tomorrow.",
            "9:30 AM",
            R.drawable.t1,
            0
        ),
        ChatListItemData(
            "3",
            "Dr. Evans",
            "Your appointment is confirmed.",
            "Yesterday",
            R.drawable.t1,
            0
        ),
        ChatListItemData("4", "Mom", "Don't forget to buy milk!", "Yesterday", R.drawable.t1, 1),
        ChatListItemData(
            "5",
            "Work Group",
            "Sarah: Let's sync up at 3 PM.",
            "Friday",
            R.drawable.t1,
            5
        )
    )

    // Expose the list as a read-only List
    val allChats: List<ChatListItemData>
        get() = _chats

    /**
     * Finds a chat by its name and sets its unread count to 0.
     */
    fun markAsRead(chatName: String) {
        val chatIndex = _chats.indexOfFirst { it.name == chatName }
        if (chatIndex != -1) {
            val oldChat = _chats[chatIndex]
            // Only update if it's currently unread to avoid unnecessary recompositions
            if (oldChat.unreadCount > 0) {
                _chats[chatIndex] = oldChat.copy(unreadCount = 0)
            }
        }
    }
}
