package com.example.openclawclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import kotlinx.coroutines.launch
import java.net.URI

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppNavigation()
        }
    }
}

data class ChatMessage(val content: String, val fromUser: Boolean, val id: String = java.util.UUID.randomUUID().toString())

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    var wsUrl by remember { mutableStateOf("ws://192.168.0.8:8765") }
    
    NavHost(navController = navController, startDestination = "chat") {
        composable("chat") {
            ChatScreen(wsUrl, onSettingsClick = { navController.navigate("settings") })
        }
        composable("settings") {
            SettingsScreen(wsUrl, onUrlChange = { wsUrl = it }, onBack = { navController.popBackStack() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(wsUrl: String, onSettingsClick: () -> Unit) {
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var inputText by remember { mutableStateOf("") }
    var client by remember { mutableStateOf<MyWebSocketClient?>(null) }
    var lastProcessedMsgId by remember { mutableStateOf<String?>(null) }
    var connectionStatus by remember { mutableStateOf("未连接") }
    var activeChatId by remember { mutableStateOf("default_chat") }
    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }

    // Connect WebSocket
    DisposableEffect(wsUrl) {
        val uri = try { URI(wsUrl) } catch (e: Exception) { null }
        connectionStatus = if (uri == null) "地址无效" else "连接中..."
        if (uri != null) {
            val newClient = MyWebSocketClient(
                uri,
                onMsgReceived = { messageJson ->
                    scope.launch {
                        try {
                            val response = gson.fromJson(messageJson, Map::class.java)
                            val type = response["type"] as? String

                            if (type == "start_new_conversation" || type == "start_new_conversation_response") {
                                val chatId = response["chat_id"] as? String
                                if (!chatId.isNullOrBlank()) {
                                    activeChatId = chatId
                                    lastProcessedMsgId = null
                                    messages = messages + ChatMessage("[系统] 已切换到新对话: $chatId", false)
                                }
                                return@launch
                            }

                            if (type == "bot_message") {
                                val toUserId = response["user_id"] as? String
                                if (toUserId == "user001") {
                                    val content = response["content"] as? String ?: ""
                                    val id = response["message_id"] as? String ?: ""
                                    val chatId = response["chat_id"] as? String ?: ""
                                    if (id.isNotEmpty() && id != lastProcessedMsgId) {
                                        val displayContent =
                                            if (chatId.isNotBlank() && chatId != activeChatId) {
                                                "[$chatId] $content"
                                            } else {
                                                content
                                            }
                                        messages = messages + ChatMessage(displayContent, false, id)
                                        lastProcessedMsgId = id
                                    }
                                }
                                return@launch
                            }

                            if (response["type"] == "get_messages_response") {
                                val messagesList = response["messages"] as? List<*>
                                val lastMsg = messagesList?.lastOrNull() as? Map<*, *>
                                if (lastMsg != null && lastMsg["from"] == "bot") {
                                    val content = lastMsg["content"] as? String ?: ""
                                    val id = lastMsg["id"] as? String ?: ""
                                    if (id.isNotEmpty() && id != lastProcessedMsgId) {
                                        messages = messages + ChatMessage(content, false, id)
                                        lastProcessedMsgId = id
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                },
                onStatusChange = { status ->
                    scope.launch { connectionStatus = status }
                }
            )
            newClient.connect()
            client = newClient
        }
        onDispose {
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
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Text(
                text = "连接状态: $connectionStatus",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "当前会话: $activeChatId",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = {
                        if (client?.isOpen == true) {
                            val req = mapOf(
                                "type" to "start_new_conversation",
                                "user_id" to "user001"
                            )
                            scope.launch(Dispatchers.IO) {
                                try {
                                    client?.send(gson.toJson(req))
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }
                ) {
                    Text("开启新对话")
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
                reverseLayout = false
            ) {
                items(messages) { msg ->
                    ChatBubble(msg)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") }
                )
                IconButton(onClick = {
                    if (inputText.isNotBlank() && client?.isOpen == true) {
                        val contentToSend = inputText
                        inputText = ""
                        messages = messages + ChatMessage(contentToSend, true)

                        val msg = mapOf(
                            "type" to "simulate_user_message",
                            "user_id" to "user001",
                            "chat_id" to activeChatId,
                            "content" to contentToSend
                        )
                        scope.launch(Dispatchers.IO) {
                            try {
                                client?.send(gson.toJson(msg))
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }) {
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
    
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = alignment) {
        Surface(
            color = color,
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 2.dp
        ) {
            Text(
                text = msg.content,
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(wsUrl: String, onUrlChange: (String) -> Unit, onBack: () -> Unit) {
    var tempUrl by remember { mutableStateOf(wsUrl) }
    
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            TextField(
                value = tempUrl,
                onValueChange = { tempUrl = it },
                label = { Text("WebSocket URL") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                onUrlChange(tempUrl)
                onBack()
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Save & Back")
            }
        }
    }
}

class MyWebSocketClient(
    uri: URI,
    private val onMsgReceived: (String) -> Unit,
    private val onStatusChange: (String) -> Unit
) : WebSocketClient(uri) {
    override fun onOpen(handshakedata: ServerHandshake?) {
        onStatusChange("已连接")
        println("WS Open")
    }
    override fun onMessage(message: String?) { 
        message?.let { onMsgReceived(it) } 
    }
    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        onStatusChange("已断开(code=$code)")
        println("WS Closed")
    }
    override fun onError(ex: Exception?) {
        onStatusChange("连接错误")
        ex?.printStackTrace()
    }
}
