package com.example.openclawclient

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.UUID

private const val DEFAULT_USER_ID = "user001"
private const val DEFAULT_CHAT_ID = "default_chat"
private const val PREFS_NAME = "openclaw_client_state"
private const val ACTIVE_CHAT_PREFIX = "active_chat_"
private const val CHAT_HISTORY_PREFIX = "chat_history_"
private const val KNOWN_CHAT_IDS_PREFIX = "known_chat_ids_"
private const val INITIAL_RECONNECT_DELAY_MS = 500L
private const val MAX_RECONNECT_DELAY_MS = 5_000L
private val fencedCodeRegex = Regex("(?s)```(?:[A-Za-z0-9_+-]+)?\\n(.*?)```")
private val tableSeparatorRegex = Regex("^\\s*\\|?(\\s*:?[-]{3,}:?\\s*\\|)+\\s*:?[-]{3,}:?\\s*\\|?\\s*$")
private val horizontalRuleRegex = Regex("^\\s*([-*_])\\1{2,}\\s*$")

private val persistenceGson = Gson()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppNavigation()
        }
    }
}

data class ChatMessage(
    val content: String,
    val fromUser: Boolean,
    val id: String = UUID.randomUUID().toString(),
    val clientMessageId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

private fun sanitizeKeyPart(value: String): String {
    return value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

private fun activeChatPrefsKey(wsUrl: String): String {
    return ACTIVE_CHAT_PREFIX + sanitizeKeyPart(wsUrl)
}

private fun knownChatIdsPrefsKey(wsUrl: String): String {
    return KNOWN_CHAT_IDS_PREFIX + sanitizeKeyPart(wsUrl)
}

private fun chatHistoryPrefsKey(wsUrl: String, chatId: String): String {
    return CHAT_HISTORY_PREFIX + sanitizeKeyPart(wsUrl) + "_" + sanitizeKeyPart(chatId)
}

private fun mergeChatMessages(existing: List<ChatMessage>, incoming: ChatMessage): List<ChatMessage> {
    val matchKeys = buildSet<String> {
        add(incoming.id)
        incoming.clientMessageId?.let { add(it) }
    }
    val index = existing.indexOfFirst { message ->
        message.id in matchKeys || (message.clientMessageId != null && message.clientMessageId in matchKeys)
    }
    return if (index >= 0) {
        existing.toMutableList().apply {
            this[index] = incoming
        }.toList()
    } else {
        existing + incoming
    }
}

private fun mergeChatMessageLists(existing: List<ChatMessage>, incoming: List<ChatMessage>): List<ChatMessage> {
    var merged = existing
    for (message in incoming) {
        merged = mergeChatMessages(merged, message)
    }
    return merged
}

private fun messageFromMap(entry: Map<*, *>): ChatMessage? {
    val content = entry["content"] as? String ?: return null
    val id = (entry["id"] as? String ?: entry["message_id"] as? String).orEmpty()
    val clientMessageId = (entry["client_message_id"] as? String)?.takeIf { it.isNotBlank() }
    val fromValue = entry["from"] as? String
    val fromUser = when (fromValue) {
        "bot" -> false
        else -> true
    }
    return ChatMessage(
        content = content,
        fromUser = fromUser,
        id = if (id.isNotBlank()) id else UUID.randomUUID().toString(),
        clientMessageId = clientMessageId,
        timestamp = System.currentTimeMillis(),
    )
}

private object ChatHistoryStore {
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadActiveChatId(context: Context, wsUrl: String): String {
        return prefs(context).getString(activeChatPrefsKey(wsUrl), DEFAULT_CHAT_ID) ?: DEFAULT_CHAT_ID
    }

    fun saveActiveChatId(context: Context, wsUrl: String, chatId: String) {
        prefs(context).edit().putString(activeChatPrefsKey(wsUrl), chatId).apply()
    }

    fun loadKnownChatIds(context: Context, wsUrl: String): List<String> {
        val raw = prefs(context).getString(knownChatIdsPrefsKey(wsUrl), null) ?: return emptyList()
        return try {
            val values = persistenceGson.fromJson(raw, Array<String>::class.java)?.toList().orEmpty()
            values.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveKnownChatIds(context: Context, wsUrl: String, chatIds: List<String>) {
        val values = chatIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        prefs(context).edit().putString(knownChatIdsPrefsKey(wsUrl), persistenceGson.toJson(values)).apply()
    }

    fun loadMessages(context: Context, wsUrl: String, chatId: String): List<ChatMessage> {
        val raw = prefs(context).getString(chatHistoryPrefsKey(wsUrl, chatId), null) ?: return emptyList()
        return try {
            persistenceGson.fromJson(raw, Array<ChatMessage>::class.java)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveMessages(context: Context, wsUrl: String, chatId: String, messages: List<ChatMessage>) {
        prefs(context).edit().putString(chatHistoryPrefsKey(wsUrl, chatId), persistenceGson.toJson(messages)).apply()
    }

    fun appendMessage(context: Context, wsUrl: String, chatId: String, incoming: ChatMessage) {
        val merged = mergeChatMessages(loadMessages(context, wsUrl, chatId), incoming)
        saveMessages(context, wsUrl, chatId, merged)
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    var wsUrl by rememberSaveable { mutableStateOf("ws://192.168.0.8:8765") }

    NavHost(navController = navController, startDestination = "chat") {
        composable("chat") {
            ChatScreen(wsUrl = wsUrl, onSettingsClick = { navController.navigate("settings") })
        }
        composable("settings") {
            SettingsScreen(wsUrl = wsUrl, onUrlChange = { wsUrl = it }, onBack = { navController.popBackStack() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(wsUrl: String, onSettingsClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }

    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var inputText by remember { mutableStateOf("") }
    var client by remember { mutableStateOf<MyWebSocketClient?>(null) }
    var connectionStatus by remember { mutableStateOf("未连接") }
    var activeChatId by remember { mutableStateOf(DEFAULT_CHAT_ID) }
    var knownChatIds by remember { mutableStateOf(listOf(DEFAULT_CHAT_ID)) }
    var lastProcessedMsgId by remember { mutableStateOf<String?>(null) }
    var reconnectAttempt by remember { mutableIntStateOf(0) }
    var connectionToken by remember { mutableIntStateOf(0) }
    var reconnectEnabled by remember { mutableStateOf(true) }
    var reconnectJob by remember { mutableStateOf<Job?>(null) }
    var historyLoaded by remember { mutableStateOf(false) }
    val activeStreamMessageIds = remember { mutableStateMapOf<String, String>() }
    var connectSocket: () -> Unit = {}

    fun persistKnownChats() {
        ChatHistoryStore.saveKnownChatIds(context, wsUrl, knownChatIds)
    }

    fun persistCurrentMessages() {
        if (historyLoaded) {
            ChatHistoryStore.saveMessages(context, wsUrl, activeChatId, messages)
        }
    }

    fun rememberChat(chatId: String) {
        val normalized = chatId.trim()
        if (normalized.isBlank()) {
            return
        }
        if (normalized !in knownChatIds) {
            knownChatIds = (knownChatIds + normalized).distinct()
        }
    }

    fun loadChat(chatId: String) {
        val normalized = chatId.trim().ifBlank { DEFAULT_CHAT_ID }
        rememberChat(normalized)
        activeChatId = normalized
        lastProcessedMsgId = null
        messages = ChatHistoryStore.loadMessages(context, wsUrl, normalized)
        ChatHistoryStore.saveActiveChatId(context, wsUrl, normalized)
        persistKnownChats()
        if (client?.isOpen == true) {
            scope.launch(Dispatchers.IO) {
                try {
                    client?.send(
                        gson.toJson(
                            mapOf(
                                "type" to "get_messages",
                                "user_id" to DEFAULT_USER_ID,
                                "chat_id" to normalized,
                            ),
                        ),
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun mergeIncomingMessage(chatId: String?, incoming: ChatMessage) {
        val normalizedChatId = chatId?.trim().orEmpty()
        if (normalizedChatId.isNotBlank()) {
            rememberChat(normalizedChatId)
            ChatHistoryStore.appendMessage(context, wsUrl, normalizedChatId, incoming)
            persistKnownChats()
        }
        if (normalizedChatId.isBlank() || normalizedChatId == activeChatId) {
            messages = mergeChatMessages(messages, incoming)
        }
    }

    fun requestHistory(chatId: String = activeChatId) {
        val currentClient = client
        if (currentClient?.isOpen != true) {
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                currentClient.send(
                    gson.toJson(
                        mapOf(
                            "type" to "get_messages",
                            "user_id" to DEFAULT_USER_ID,
                            "chat_id" to chatId,
                        ),
                    ),
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun reconnectDelayMillis(attempt: Int): Long {
        val multiplier = 1L shl minOf(attempt, 3)
        return minOf(MAX_RECONNECT_DELAY_MS, INITIAL_RECONNECT_DELAY_MS * multiplier)
    }

    fun scheduleReconnect() {
        if (!reconnectEnabled || reconnectJob?.isActive == true) {
            return
        }
        val delayMs = reconnectDelayMillis(reconnectAttempt)
        reconnectJob = scope.launch {
            connectionStatus = "连接中断，${delayMs / 1000.0} 秒后重连"
            delay(delayMs)
            reconnectJob = null
            reconnectAttempt += 1
            connectSocket()
        }
    }

    connectSocket = {
        val uri = try {
            URI(wsUrl)
        } catch (_: Exception) {
            connectionStatus = "地址无效"
            null
        }

        if (uri != null) {
            reconnectEnabled = true
            reconnectJob?.cancel()
            reconnectJob = null

            val token = connectionToken + 1
            connectionToken = token
            connectionStatus = if (reconnectAttempt == 0) "连接中..." else "重连中..."

            val newClient = MyWebSocketClient(
                uri = uri,
                onMsgReceived = { messageJson ->
                    if (token == connectionToken) {
                        scope.launch {
                            try {
                                val responseMap =
                                    gson.fromJson(messageJson, Map::class.java) as? Map<*, *>
                                        ?: return@launch
                                when (responseMap["type"] as? String) {
                                    "start_new_conversation", "start_new_conversation_response" -> {
                                        val chatId = responseMap["chat_id"] as? String
                                        if (!chatId.isNullOrBlank()) {
                                            loadChat(chatId)
                                        }
                                    }

                                    "inbound_message" -> {
                                        val chatId = responseMap["chat_id"] as? String
                                        val messageId = responseMap["message_id"] as? String
                                        if (messageId.isNullOrBlank() || messageId == lastProcessedMsgId) {
                                            return@launch
                                        }
                                        val content = responseMap["content"] as? String ?: return@launch
                                        val clientMessageId = responseMap["client_message_id"] as? String
                                        mergeIncomingMessage(
                                            chatId,
                                            ChatMessage(
                                                content = content,
                                                fromUser = true,
                                                id = messageId,
                                                clientMessageId = clientMessageId,
                                            ),
                                        )
                                        lastProcessedMsgId = messageId
                                    }

                                    "bot_message" -> {
                                        val userId = responseMap["user_id"] as? String
                                        if (userId != DEFAULT_USER_ID) {
                                            return@launch
                                        }
                                        val chatId = responseMap["chat_id"] as? String
                                        val messageId = responseMap["message_id"] as? String
                                        if (messageId.isNullOrBlank() || messageId == lastProcessedMsgId) {
                                            return@launch
                                        }
                                        val content = responseMap["content"] as? String ?: return@launch
                                        val clientMessageId = responseMap["client_message_id"] as? String
                                        mergeIncomingMessage(
                                            chatId,
                                            ChatMessage(
                                                content = content,
                                                fromUser = false,
                                                id = messageId,
                                                clientMessageId = clientMessageId,
                                            ),
                                        )
                                        lastProcessedMsgId = messageId
                                    }

                                    "bot_message_stream" -> {
                                        val userId = responseMap["user_id"] as? String
                                        if (userId != DEFAULT_USER_ID) {
                                            return@launch
                                        }

                                        val state = (responseMap["state"] as? String)?.trim()?.lowercase()
                                        val chatId = (responseMap["chat_id"] as? String).orEmpty()
                                        if (chatId.isBlank()) {
                                            return@launch
                                        }

                                        val streamId = (responseMap["stream_id"] as? String)
                                            ?.takeIf { it.isNotBlank() }
                                            ?: return@launch
                                        val streamKey = "$chatId::$streamId"
                                        val messageId = (responseMap["message_id"] as? String)
                                            ?.takeIf { it.isNotBlank() }
                                            ?: streamId
                                        val clientMessageId = responseMap["client_message_id"] as? String

                                        when (state) {
                                            "delta" -> {
                                                val streamMessageId = activeStreamMessageIds[streamKey] ?: messageId
                                                activeStreamMessageIds[streamKey] = streamMessageId
                                                val content = (responseMap["content"] as? String)
                                                    ?: (responseMap["delta"] as? String)
                                                    ?: ""
                                                mergeIncomingMessage(
                                                    chatId,
                                                    ChatMessage(
                                                        content = content,
                                                        fromUser = false,
                                                        id = streamMessageId,
                                                        clientMessageId = clientMessageId,
                                                    ),
                                                )
                                            }

                                            "final" -> {
                                                val finalMessageId = activeStreamMessageIds[streamKey] ?: messageId
                                                val content = (responseMap["content"] as? String) ?: ""
                                                mergeIncomingMessage(
                                                    chatId,
                                                    ChatMessage(
                                                        content = content,
                                                        fromUser = false,
                                                        id = finalMessageId,
                                                        clientMessageId = clientMessageId,
                                                    ),
                                                )
                                                activeStreamMessageIds.remove(streamKey)
                                                lastProcessedMsgId = finalMessageId
                                            }
                                        }
                                    }

                                    "get_messages_response" -> {
                                        val responseChatId = responseMap["chat_id"] as? String
                                        if (responseChatId.isNullOrBlank() || responseChatId != activeChatId) {
                                            return@launch
                                        }
                                        val serverMessages = (responseMap["messages"] as? List<*>)
                                            ?.mapNotNull { item ->
                                                val entry = item as? Map<*, *> ?: return@mapNotNull null
                                                messageFromMap(entry)
                                            }
                                            .orEmpty()
                                        messages = mergeChatMessageLists(messages, serverMessages)
                                        persistCurrentMessages()
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                },
                onStatusChange = { status ->
                    if (token == connectionToken) {
                        scope.launch {
                            connectionStatus = status
                        }
                    }
                },
                onOpenCallback = {
                    if (token == connectionToken) {
                        scope.launch {
                            reconnectAttempt = 0
                            reconnectEnabled = true
                            connectionStatus = "已连接"
                            requestHistory(activeChatId)
                        }
                    }
                },
                onCloseCallback = { code, _, _ ->
                    if (token == connectionToken) {
                        scope.launch {
                            client = null
                            connectionStatus = "已断开(code=$code)"
                            scheduleReconnect()
                        }
                    }
                },
                onErrorCallback = { _ ->
                    if (token == connectionToken) {
                        scope.launch {
                            connectionStatus = "连接错误"
                            scheduleReconnect()
                        }
                    }
                },
            )

            client = newClient
            scope.launch(Dispatchers.IO) {
                try {
                    newClient.connect()
                } catch (e: Exception) {
                    e.printStackTrace()
                    if (token == connectionToken) {
                        scope.launch {
                            connectionStatus = "连接失败，准备重连"
                            scheduleReconnect()
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(wsUrl) {
        reconnectEnabled = true
        reconnectAttempt = 0
        historyLoaded = false
        val savedActiveChatId = ChatHistoryStore.loadActiveChatId(context, wsUrl)
        val savedKnownChatIds = ChatHistoryStore.loadKnownChatIds(context, wsUrl)
        knownChatIds = (listOf(savedActiveChatId) + savedKnownChatIds + listOf(DEFAULT_CHAT_ID))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        activeChatId = savedActiveChatId.ifBlank { DEFAULT_CHAT_ID }
        messages = ChatHistoryStore.loadMessages(context, wsUrl, activeChatId)
        historyLoaded = true
        connectSocket()
    }

    LaunchedEffect(activeChatId, wsUrl, historyLoaded) {
        if (!historyLoaded) {
            return@LaunchedEffect
        }
        rememberChat(activeChatId)
        ChatHistoryStore.saveActiveChatId(context, wsUrl, activeChatId)
        persistKnownChats()
        if (client?.isOpen == true) {
            requestHistory(activeChatId)
        }
    }

    LaunchedEffect(messages, activeChatId, wsUrl, historyLoaded) {
        if (historyLoaded) {
            persistCurrentMessages()
        }
    }

    DisposableEffect(wsUrl) {
        onDispose {
            reconnectEnabled = false
            reconnectJob?.cancel()
            reconnectJob = null
            client?.close()
            client = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenClaw Chat") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Text(
                text = "连接状态: $connectionStatus",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "当前会话: $activeChatId",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            if (knownChatIds.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(knownChatIds) { chatId ->
                        FilterChip(
                            selected = chatId == activeChatId,
                            onClick = {
                                if (chatId != activeChatId) {
                                    loadChat(chatId)
                                }
                            },
                            label = { Text(chatId) },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = {
                        if (client?.isOpen == true) {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    client?.send(
                                        gson.toJson(
                                            mapOf(
                                                "type" to "start_new_conversation",
                                                "user_id" to DEFAULT_USER_ID,
                                            ),
                                        ),
                                    )
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    },
                ) {
                    Text("开启新对话")
                }
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp),
            ) {
                items(messages) { msg ->
                    ChatBubble(msg)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") },
                )
                IconButton(
                    onClick = {
                        val currentClient = client
                        val trimmed = inputText.trim()
                        if (trimmed.isBlank() || currentClient?.isOpen != true) {
                            return@IconButton
                        }
                        val clientMessageId = UUID.randomUUID().toString()
                        inputText = ""
                        val optimisticMessage = ChatMessage(
                            content = trimmed,
                            fromUser = true,
                            id = clientMessageId,
                            clientMessageId = clientMessageId,
                        )
                        messages = mergeChatMessages(messages, optimisticMessage)
                        ChatHistoryStore.appendMessage(context, wsUrl, activeChatId, optimisticMessage)
                        persistCurrentMessages()

                        val outgoing = mapOf(
                            "type" to "simulate_user_message",
                            "user_id" to DEFAULT_USER_ID,
                            "chat_id" to activeChatId,
                            "client_message_id" to clientMessageId,
                            "content" to trimmed,
                        )
                        scope.launch(Dispatchers.IO) {
                            try {
                                currentClient.send(gson.toJson(outgoing))
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    },
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
fun ChatBubble(msg: ChatMessage) {
    val alignment = if (msg.fromUser) Alignment.End else Alignment.Start
    val color = if (msg.fromUser) Color(0xFF95EC69) else Color.White

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = alignment,
    ) {
        Surface(
            color = color,
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 2.dp,
        ) {
            if (msg.fromUser) {
                Text(
                    text = msg.content,
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                MarkdownMessageText(
                    markdown = msg.content,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

private data class MarkdownSegment(val text: String, val isCodeBlock: Boolean)
private data class MarkdownTable(val header: List<String>, val rows: List<List<String>>)

@Composable
private fun MarkdownMessageText(markdown: String, modifier: Modifier = Modifier) {
    val segments = remember(markdown) { splitMarkdownSegments(markdown) }
    val linkColor = MaterialTheme.colorScheme.primary
    val quoteColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier) {
        segments.forEachIndexed { index, segment ->
            if (segment.isCodeBlock) {
                Surface(
                    color = Color(0xFFF3F4F6),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = segment.text.trimEnd(),
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            } else {
                MarkdownRichTextBlock(
                    raw = segment.text,
                    linkColor = linkColor,
                    quoteColor = quoteColor,
                )
            }
            if (index < segments.lastIndex) {
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun MarkdownRichTextBlock(
    raw: String,
    linkColor: Color,
    quoteColor: Color,
) {
    val lines = remember(raw) { raw.split("\n") }
    var index = 0

    while (index < lines.size) {
        val tableResult = parseMarkdownTable(lines, index)
        if (tableResult != null) {
            MarkdownTableView(
                table = tableResult.first,
                linkColor = linkColor,
                quoteColor = quoteColor,
            )
            index = tableResult.second
            if (index < lines.size) {
                Spacer(modifier = Modifier.height(6.dp))
            }
            continue
        }

        val current = lines[index]
        if (horizontalRuleRegex.matches(current.trim())) {
            Divider(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            index += 1
            continue
        }

        val start = index
        while (index < lines.size) {
            if (horizontalRuleRegex.matches(lines[index].trim()) || parseMarkdownTable(lines, index) != null) {
                break
            }
            index += 1
        }

        val textChunk = lines.subList(start, index).joinToString("\n")
        if (textChunk.isNotBlank()) {
            Text(
                text = buildMarkdownAnnotatedString(textChunk, linkColor, quoteColor),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else if (index < lines.size) {
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (index < lines.size) {
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun MarkdownTableView(
    table: MarkdownTable,
    linkColor: Color,
    quoteColor: Color,
) {
    val columnCount = remember(table) {
        (listOf(table.header.size) + table.rows.map { it.size }).maxOrNull()?.coerceAtLeast(1) ?: 1
    }

    Surface(
        color = Color(0xFFF8FAFC),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            MarkdownTableRow(
                cells = table.header,
                columnCount = columnCount,
                isHeader = true,
                linkColor = linkColor,
                quoteColor = quoteColor,
            )
            Divider(color = MaterialTheme.colorScheme.outlineVariant)

            table.rows.forEachIndexed { rowIndex, row ->
                MarkdownTableRow(
                    cells = row,
                    columnCount = columnCount,
                    isHeader = false,
                    linkColor = linkColor,
                    quoteColor = quoteColor,
                )
                if (rowIndex < table.rows.lastIndex) {
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<String>,
    columnCount: Int,
    isHeader: Boolean,
    linkColor: Color,
    quoteColor: Color,
) {
    val displayCells = remember(cells, columnCount) {
        List(columnCount) { idx -> cells.getOrNull(idx).orEmpty() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        displayCells.forEach { cell ->
            Box(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                val cellText = buildMarkdownAnnotatedString(cell, linkColor, quoteColor)
                Text(
                    text = cellText,
                    style = if (isHeader) {
                        MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                )
            }
        }
    }
}

private fun parseMarkdownTable(lines: List<String>, startIndex: Int): Pair<MarkdownTable, Int>? {
    if (startIndex + 1 >= lines.size) {
        return null
    }

    val headerLine = lines[startIndex]
    val separatorLine = lines[startIndex + 1]
    if (!headerLine.contains("|") || !tableSeparatorRegex.matches(separatorLine)) {
        return null
    }

    val headerCells = splitTableRow(headerLine)
    if (headerCells.isEmpty()) {
        return null
    }

    val rows = mutableListOf<List<String>>()
    var cursor = startIndex + 2
    while (cursor < lines.size) {
        val line = lines[cursor]
        if (!line.contains("|") || line.trim().isEmpty()) {
            break
        }
        rows.add(splitTableRow(line))
        cursor += 1
    }

    return MarkdownTable(header = headerCells, rows = rows) to cursor
}

private fun splitTableRow(line: String): List<String> {
    val content = line.trim()
    if (content.isEmpty()) {
        return emptyList()
    }

    val stripped = content
        .removePrefix("|")
        .removeSuffix("|")

    return stripped.split("|").map { it.trim() }
}

private fun splitMarkdownSegments(markdown: String): List<MarkdownSegment> {
    if (markdown.isBlank()) {
        return listOf(MarkdownSegment("", false))
    }

    val segments = mutableListOf<MarkdownSegment>()
    var currentIndex = 0
    for (match in fencedCodeRegex.findAll(markdown)) {
        val range = match.range
        if (range.first > currentIndex) {
            val normal = markdown.substring(currentIndex, range.first)
            if (normal.isNotBlank()) {
                segments.add(MarkdownSegment(normal.trimEnd(), false))
            }
        }
        val codeBody = match.groupValues.getOrNull(1).orEmpty()
        segments.add(MarkdownSegment(codeBody, true))
        currentIndex = range.last + 1
    }

    if (currentIndex < markdown.length) {
        val trailing = markdown.substring(currentIndex)
        if (trailing.isNotBlank()) {
            segments.add(MarkdownSegment(trailing.trimEnd(), false))
        }
    }

    return if (segments.isEmpty()) listOf(MarkdownSegment(markdown, false)) else segments
}

private fun buildMarkdownAnnotatedString(
    raw: String,
    linkColor: Color,
    quoteColor: Color,
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val lines = raw.split("\n")

    lines.forEachIndexed { index, line ->
        when {
            line.startsWith("### ") -> {
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                appendInlineMarkdown(builder, line.removePrefix("### "), linkColor)
                builder.pop()
            }

            line.startsWith("## ") -> {
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                appendInlineMarkdown(builder, line.removePrefix("## "), linkColor)
                builder.pop()
            }

            line.startsWith("# ") -> {
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                appendInlineMarkdown(builder, line.removePrefix("# "), linkColor)
                builder.pop()
            }

            line.startsWith("- ") || line.startsWith("* ") -> {
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                builder.append("• ")
                builder.pop()
                appendInlineMarkdown(builder, line.substring(2), linkColor)
            }

            line.startsWith("> ") -> {
                builder.pushStyle(SpanStyle(color = quoteColor))
                builder.append("▎")
                builder.pop()
                builder.append(" ")
                builder.pushStyle(SpanStyle(color = quoteColor))
                appendInlineMarkdown(builder, line.removePrefix("> "), linkColor)
                builder.pop()
            }

            else -> appendInlineMarkdown(builder, line, linkColor)
        }

        if (index < lines.lastIndex) {
            builder.append("\n")
        }
    }

    return builder.toAnnotatedString()
}

private fun appendInlineMarkdown(
    builder: AnnotatedString.Builder,
    text: String,
    linkColor: Color,
) {
    var i = 0
    while (i < text.length) {
        if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
            val end = text.indexOf("**", startIndex = i + 2)
            if (end > i + 2) {
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                appendInlineMarkdown(builder, text.substring(i + 2, end), linkColor)
                builder.pop()
                i = end + 2
                continue
            }
        }

        if (text[i] == '*' && (i + 1 >= text.length || text[i + 1] != '*')) {
            val end = text.indexOf('*', startIndex = i + 1)
            if (end > i + 1) {
                builder.pushStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                appendInlineMarkdown(builder, text.substring(i + 1, end), linkColor)
                builder.pop()
                i = end + 1
                continue
            }
        }

        if (text[i] == '`') {
            val end = text.indexOf('`', startIndex = i + 1)
            if (end > i + 1) {
                builder.pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0xFFECECEC),
                    ),
                )
                builder.append(text.substring(i + 1, end))
                builder.pop()
                i = end + 1
                continue
            }
        }

        if (text[i] == '[') {
            val textEnd = text.indexOf(']', startIndex = i + 1)
            if (textEnd > i + 1 && textEnd + 1 < text.length && text[textEnd + 1] == '(') {
                val urlEnd = text.indexOf(')', startIndex = textEnd + 2)
                if (urlEnd > textEnd + 2) {
                    val linkText = text.substring(i + 1, textEnd)
                    builder.pushStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    )
                    builder.append(linkText)
                    builder.pop()
                    i = urlEnd + 1
                    continue
                }
            }
        }

        builder.append(text[i])
        i += 1
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(wsUrl: String, onUrlChange: (String) -> Unit, onBack: () -> Unit) {
    var tempUrl by remember { mutableStateOf(wsUrl) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            TextField(
                value = tempUrl,
                onValueChange = { tempUrl = it },
                label = { Text("WebSocket URL") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    onUrlChange(tempUrl)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save & Back")
            }
        }
    }
}

class MyWebSocketClient(
    uri: URI,
    private val onMsgReceived: (String) -> Unit,
    private val onStatusChange: (String) -> Unit,
    private val onOpenCallback: () -> Unit,
    private val onCloseCallback: (Int, String?, Boolean) -> Unit,
    private val onErrorCallback: (Exception?) -> Unit,
) : WebSocketClient(uri) {
    override fun onOpen(handshakedata: ServerHandshake?) {
        onStatusChange("已连接")
        onOpenCallback()
    }

    override fun onMessage(message: String?) {
        message?.let(onMsgReceived)
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        onStatusChange("已断开(code=$code)")
        onCloseCallback(code, reason, remote)
    }

    override fun onError(ex: Exception?) {
        onStatusChange("连接错误")
        onErrorCallback(ex)
        ex?.printStackTrace()
    }
}
