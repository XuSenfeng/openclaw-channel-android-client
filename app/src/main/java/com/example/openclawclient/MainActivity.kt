package com.example.openclawclient

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val DEFAULT_USER_ID = "user001"
private const val DEFAULT_SERVER_ID = "default"
private const val DEFAULT_WS_URL = "ws://192.168.0.8:8765"
private const val DEFAULT_NICKNAME = "手机用户"
private const val DEFAULT_CHAT_ID = "default_chat"
private const val PREFS_NAME = "openclaw_client_state"
private const val ACTIVE_CHAT_PREFIX = "active_chat_"
private const val CHAT_HISTORY_PREFIX = "chat_history_"
private const val KNOWN_CHAT_IDS_PREFIX = "known_chat_ids_"
private const val INITIAL_RECONNECT_DELAY_MS = 500L
private const val MAX_RECONNECT_DELAY_MS = 5_000L
private const val TAG = "OpenClawVoice"
private val fencedCodeRegex = Regex("(?s)```(?:[A-Za-z0-9_+-]+)?\\n(.*?)```")
private val tableSeparatorRegex = Regex("^\\s*\\|?(\\s*:?[-]{3,}:?\\s*\\|)+\\s*:?[-]{3,}:?\\s*\\|?\\s*$")
private val horizontalRuleRegex = Regex("^\\s*([-*_])\\1{2,}\\s*$")

private val persistenceGson = Gson()

data class ClientSettings(
    val wsUrl: String,
    val serverId: String,
    val userId: String,
    val pairToken: String,
    val nickname: String,
    val deviceId: String,
)

private data class PairingResult(
    val success: Boolean,
    val wsUrl: String,
    val serverId: String,
    val accountId: String,
    val pairToken: String,
    val nickname: String,
    val error: String? = null,
)

object ClientSettingsStore {
    private const val KEY_WS_URL = "settings_ws_url"
    private const val KEY_SERVER_ID = "settings_server_id"
    private const val KEY_USER_ID = "settings_user_id"
    private const val KEY_PAIR_TOKEN = "settings_pair_token"
    private const val KEY_NICKNAME = "settings_nickname"
    private const val KEY_DEVICE_ID = "settings_device_id"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): ClientSettings {
        val store = prefs(context)
        val deviceId = store.getString(KEY_DEVICE_ID, null)?.trim().orEmpty().ifBlank {
            UUID.randomUUID().toString()
        }
        if (store.getString(KEY_DEVICE_ID, null).isNullOrBlank()) {
            store.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return ClientSettings(
            wsUrl = store.getString(KEY_WS_URL, DEFAULT_WS_URL)?.trim().orEmpty().ifBlank { DEFAULT_WS_URL },
            serverId = store.getString(KEY_SERVER_ID, DEFAULT_SERVER_ID)?.trim().orEmpty().ifBlank { DEFAULT_SERVER_ID },
            userId = store.getString(KEY_USER_ID, "")?.trim().orEmpty(),
            pairToken = store.getString(KEY_PAIR_TOKEN, "")?.trim().orEmpty(),
            nickname = store.getString(KEY_NICKNAME, DEFAULT_NICKNAME)?.trim().orEmpty().ifBlank {
                DEFAULT_NICKNAME
            },
            deviceId = deviceId,
        )
    }

    fun save(context: Context, settings: ClientSettings) {
        prefs(context).edit()
            .putString(KEY_WS_URL, settings.wsUrl)
            .putString(KEY_SERVER_ID, settings.serverId)
            .putString(KEY_USER_ID, settings.userId)
            .putString(KEY_PAIR_TOKEN, settings.pairToken)
            .putString(KEY_NICKNAME, settings.nickname)
            .putString(KEY_DEVICE_ID, settings.deviceId)
            .apply()
    }

    fun clearPairing(context: Context) {
        val current = load(context)
        save(
            context,
            current.copy(
                userId = "",
                pairToken = "",
            ),
        )
    }
}

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

private class AndroidTtsSpeaker(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    @Volatile
    private var engine: TextToSpeech? = TextToSpeech(appContext, this)
    @Volatile
    private var ready = false
    @Volatile
    private var pendingText: String? = null
    @Volatile
    private var pendingUtteranceId: String? = null

    override fun onInit(status: Int) {
        val tts = engine ?: return
        ready = status == TextToSpeech.SUCCESS
        if (!ready) {
            pendingText = null
            pendingUtteranceId = null
            return
        }

        val locale = Locale.getDefault()
        val localeState = tts.setLanguage(locale)
        if (localeState == TextToSpeech.LANG_MISSING_DATA || localeState == TextToSpeech.LANG_NOT_SUPPORTED) {
            val fallbackState = tts.setLanguage(Locale.US)
            ready = fallbackState != TextToSpeech.LANG_MISSING_DATA && fallbackState != TextToSpeech.LANG_NOT_SUPPORTED
        }

        val text = pendingText
        val utteranceId = pendingUtteranceId
        pendingText = null
        pendingUtteranceId = null
        if (ready && !text.isNullOrBlank() && !utteranceId.isNullOrBlank()) {
            speakInternal(text, utteranceId)
        }
    }

    fun speak(text: String) {
        val cleaned = cleanForSpeech(text)
        if (cleaned.isBlank()) {
            return
        }

        val utteranceId = UUID.randomUUID().toString()
        if (!ready) {
            pendingText = cleaned
            pendingUtteranceId = utteranceId
            return
        }

        speakInternal(cleaned, utteranceId)
    }

    private fun speakInternal(text: String, utteranceId: String) {
        val tts = engine ?: return
        tts.stop()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun shutdown() {
        pendingText = null
        pendingUtteranceId = null
        ready = false
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}

private class AndroidSpeechInput(
    private val context: Context,
    private val onPartialText: (String) -> Unit,
    private val onFinalText: (String) -> Unit,
    private val onError: (String) -> Unit,
) : RecognitionListener {
    private val recognizer: SpeechRecognizer? = if (SpeechRecognizer.isRecognitionAvailable(context)) {
        SpeechRecognizer.createSpeechRecognizer(context)
    } else {
        Log.e(TAG, "SpeechRecognizer unavailable: isRecognitionAvailable=false")
        null
    }

    init {
        recognizer?.setRecognitionListener(this)
    }

    fun isAvailable(): Boolean {
        return recognizer != null
    }

    fun startListening() {
        val activeRecognizer = recognizer ?: run {
            Log.e(TAG, "startListening aborted: recognizer is null")
            onError("语音输入不可用：请安装语音识别服务")
            return
        }
        Log.d(TAG, "startListening: launching SpeechRecognizer")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            activeRecognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            onError("语音输入不可用：语音识别服务未响应")
        }
    }

    fun stop() {
        recognizer?.destroy()
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() = Unit

    override fun onError(error: Int) {
        val message = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "语音输入失败：音频录制错误"
            SpeechRecognizer.ERROR_CLIENT -> "语音输入失败：客户端错误，请重试"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "语音输入失败：缺少麦克风权限"
            SpeechRecognizer.ERROR_NETWORK -> "语音输入失败：网络不可用"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音输入失败：网络超时"
            SpeechRecognizer.ERROR_NO_MATCH -> "语音输入失败：没有识别到内容"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音输入失败：识别器忙"
            SpeechRecognizer.ERROR_SERVER -> "语音输入失败：识别服务异常"
            else -> "语音输入失败：错误码 $error"
        }
        Log.e(TAG, "SpeechRecognizer onError=$error message=$message")
        onError(message)
    }

    override fun onResults(results: Bundle?) {
        val transcript = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (transcript.isNotBlank()) {
            Log.d(TAG, "onResults transcript=$transcript")
            onFinalText(transcript)
        } else {
            Log.w(TAG, "onResults returned empty transcript")
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val transcript = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (transcript.isNotBlank()) {
            Log.d(TAG, "onPartialResults transcript=$transcript")
            onPartialText(transcript)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}

private fun cleanForSpeech(text: String): String {
    return text
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("`[^`]+`"), " ")
        .replace(Regex("!\\[.*?\\]\\(.*?\\)"), " ")
        .replace(Regex("\\[([^\\]]+)\\]\\(.*?\\)"), "$1")
        .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("\\*{1,3}(.*?)\\*{1,3}"), "$1")
        .replace(Regex("_{1,3}(.*?)_{1,3}"), "$1")
        .replace(Regex("^>\\s?", RegexOption.MULTILINE), "")
        .replace(Regex("^[-*_]{3,}\\s*$", RegexOption.MULTILINE), "")
        .replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("^\\s*\\d+\\.\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun createSpeechRecognitionIntent(context: Context): Intent {
    return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
    }
}

private fun isSpeechRecognitionIntentAvailable(context: Context): Boolean {
    return createSpeechRecognitionIntent(context).resolveActivity(context.packageManager) != null
}

private fun listSpeechRecognitionServices(context: Context): List<String> {
    return try {
        val queryIntent = Intent("android.speech.RecognitionService")
        context.packageManager
            .queryIntentServices(queryIntent, PackageManager.MATCH_ALL)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
            .sorted()
    } catch (_: Exception) {
        emptyList()
    }
}

private fun openVoiceInputSettings(context: Context): Boolean {
    val settingsIntent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        if (settingsIntent.resolveActivity(context.packageManager) == null) {
            false
        } else {
            context.startActivity(settingsIntent)
            true
        }
    } catch (_: Exception) {
        false
    }
}

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
    val byIdIndex = existing.indexOfFirst { message ->
        message.id == incoming.id
    }
    if (byIdIndex >= 0) {
        return existing.toMutableList().apply {
            this[byIdIndex] = incoming
        }.toList()
    }

    val incomingClientMessageId = incoming.clientMessageId
    val byClientMessageIdIndex = if (incomingClientMessageId.isNullOrBlank()) {
        -1
    } else {
        existing.indexOfFirst { message ->
            message.fromUser == incoming.fromUser &&
                (message.clientMessageId == incomingClientMessageId || message.id == incomingClientMessageId)
        }
    }

    val index = byClientMessageIdIndex
    return if (index >= 0) {
        existing.toMutableList().apply {
            this[index] = incoming
        }.toList()
    } else {
        existing + incoming
    }
}

private fun mergeChatMessagesByAppendingContent(
    existing: List<ChatMessage>,
    incoming: ChatMessage,
): List<ChatMessage> {
    val index = existing.indexOfFirst { message ->
        message.id == incoming.id
    }
    return if (index >= 0) {
        existing.toMutableList().apply {
            val current = this[index]
            this[index] = current.copy(
                content = current.content + incoming.content,
                clientMessageId = incoming.clientMessageId ?: current.clientMessageId,
                timestamp = incoming.timestamp,
            )
        }.toList()
    } else {
        mergeChatMessages(existing, incoming)
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

private fun requestPairing(
    wsUrl: String,
    pairCode: String,
    nickname: String,
    deviceId: String,
): PairingResult {
    val trimmedUrl = wsUrl.trim()
    val trimmedCode = pairCode.trim()
    if (trimmedUrl.isBlank() || trimmedCode.isBlank()) {
        return PairingResult(
            success = false,
            wsUrl = trimmedUrl,
            serverId = "",
            accountId = "",
            pairToken = "",
            nickname = nickname,
            error = "请输入配对码",
        )
    }

    val gson = Gson()
    val resultSignal = CountDownLatch(1)
    var result = PairingResult(
        success = false,
        wsUrl = trimmedUrl,
        serverId = "",
        accountId = "",
        pairToken = "",
        nickname = nickname,
        error = "配对失败",
    )

    val client = object : WebSocketClient(URI(trimmedUrl)) {
        override fun onOpen(handshakedata: ServerHandshake?) {
            try {
                send(
                    gson.toJson(
                        mapOf(
                            "type" to "pair_with_code",
                            "pair_code" to trimmedCode,
                            "nickname" to nickname.trim().ifBlank { DEFAULT_NICKNAME },
                            "device_id" to deviceId,
                        ),
                    ),
                )
            } catch (e: Exception) {
                result = result.copy(error = e.message ?: "发送配对请求失败")
                resultSignal.countDown()
            }
        }

        override fun onMessage(message: String?) {
            if (message.isNullOrBlank()) {
                return
            }
            try {
                @Suppress("UNCHECKED_CAST")
                val payload = gson.fromJson(message, Map::class.java) as? Map<String, Any?> ?: return
                when (payload["type"] as? String) {
                    "pair_with_code_response" -> {
                        val status = payload["status"] as? String
                        if (status == "success") {
                            val nextWsUrl = (payload["ws_url"] as? String)?.trim().orEmpty().ifBlank {
                                trimmedUrl
                            }
                            val nextServerId = (payload["server_id"] as? String)?.trim().orEmpty()
                            val accountId = ((payload["account_id"] as? String)
                                ?: (payload["user_id"] as? String)).orEmpty().trim()
                            val pairToken = (payload["pair_token"] as? String)?.trim().orEmpty()
                            val pairedNickname = (payload["nickname"] as? String)?.trim().orEmpty()
                                .ifBlank { nickname.trim().ifBlank { DEFAULT_NICKNAME } }
                            result = PairingResult(
                                success = nextServerId.isNotBlank() && accountId.isNotBlank() && pairToken.isNotBlank(),
                                wsUrl = nextWsUrl,
                                serverId = nextServerId,
                                accountId = accountId,
                                pairToken = pairToken,
                                nickname = pairedNickname,
                                error = if (
                                    nextServerId.isNotBlank() && accountId.isNotBlank() && pairToken.isNotBlank()
                                ) {
                                    null
                                } else {
                                    "服务端返回的配对信息不完整"
                                },
                            )
                        } else {
                            result = result.copy(error = payload["error"] as? String ?: "配对码无效或已过期")
                        }
                        resultSignal.countDown()
                    }

                    "error" -> {
                        result = result.copy(error = payload["error"] as? String ?: "配对失败")
                        resultSignal.countDown()
                    }
                }
            } catch (_: Exception) {
                result = result.copy(error = "配对响应解析失败")
                resultSignal.countDown()
            }
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            if (resultSignal.count > 0) {
                result = result.copy(error = reason ?: "连接已关闭")
                resultSignal.countDown()
            }
        }

        override fun onError(ex: Exception?) {
            result = result.copy(error = ex?.message ?: "连接失败")
            resultSignal.countDown()
        }
    }

    return try {
        if (!client.connectBlocking(8, TimeUnit.SECONDS)) {
            return result.copy(error = "无法连接到配对服务")
        }
        resultSignal.await(8, TimeUnit.SECONDS)
        result
    } catch (e: Exception) {
        result.copy(error = e.message ?: "配对失败")
    } finally {
        try {
            client.closeBlocking()
        } catch (_: Exception) {
        }
    }
}

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val initialSettings = remember(context) { ClientSettingsStore.load(context) }
    var wsUrl by rememberSaveable { mutableStateOf(initialSettings.wsUrl) }
    var serverId by rememberSaveable { mutableStateOf(initialSettings.serverId) }
    var userId by rememberSaveable { mutableStateOf(initialSettings.userId) }
    var pairToken by rememberSaveable { mutableStateOf(initialSettings.pairToken) }
    var nickname by rememberSaveable { mutableStateOf(initialSettings.nickname) }
    var deviceId by rememberSaveable { mutableStateOf(initialSettings.deviceId) }

    NavHost(navController = navController, startDestination = "chat") {
        composable("chat") {
            ChatScreen(
                wsUrl = wsUrl,
                serverId = serverId,
                userId = userId,
                pairToken = pairToken,
                nickname = nickname,
                deviceId = deviceId,
                onPaired = { next ->
                    wsUrl = next.wsUrl
                    serverId = next.serverId
                    userId = next.userId
                    pairToken = next.pairToken
                    nickname = next.nickname
                    deviceId = next.deviceId
                    ClientSettingsStore.save(context, next)
                },
                onSettingsClick = { navController.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(
                wsUrl = wsUrl,
                serverId = serverId,
                userId = userId,
                pairToken = pairToken,
                nickname = nickname,
                deviceId = deviceId,
                onSave = { next ->
                    wsUrl = next.wsUrl
                    serverId = next.serverId
                    userId = next.userId
                    pairToken = next.pairToken
                    nickname = next.nickname
                    deviceId = next.deviceId
                    ClientSettingsStore.save(context, next)
                },
                onResetPairing = {
                    ClientSettingsStore.clearPairing(context)
                    val refreshed = ClientSettingsStore.load(context)
                    wsUrl = refreshed.wsUrl
                    serverId = refreshed.serverId
                    userId = refreshed.userId
                    pairToken = refreshed.pairToken
                    nickname = refreshed.nickname
                    deviceId = refreshed.deviceId
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    wsUrl: String,
    serverId: String,
    userId: String,
    pairToken: String,
    nickname: String,
    deviceId: String,
    onPaired: (ClientSettings) -> Unit,
    onSettingsClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }
    val speechSpeaker = remember(context) { AndroidTtsSpeaker(context) }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var inputText by remember { mutableStateOf("") }
    var pendingVoiceSendText by remember { mutableStateOf<String?>(null) }
    val isBound = pairToken.isNotBlank() && userId.isNotBlank() && serverId.isNotBlank()
    var connectionStatus by remember { mutableStateOf(if (isBound) "未连接" else "未配对") }
    var pairingExpanded by remember { mutableStateOf(false) }
    var pairingCode by remember { mutableStateOf("") }
    var pairingNickname by remember { mutableStateOf(nickname.ifBlank { DEFAULT_NICKNAME }) }
    var pairingWsUrl by remember { mutableStateOf(wsUrl) }
    var pairingInProgress by remember { mutableStateOf(false) }
    LaunchedEffect(nickname) {
        pairingNickname = nickname.ifBlank { DEFAULT_NICKNAME }
    }
    LaunchedEffect(wsUrl) {
        pairingWsUrl = wsUrl
    }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = RequestPermission(),
    ) { granted ->
        hasMicPermission = granted
        if (!granted) {
            Log.e(TAG, "Microphone permission denied")
            connectionStatus = "语音输入不可用：未授予麦克风权限"
        }
    }
    val voiceInputLauncher = rememberLauncherForActivityResult(
        contract = StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            Log.e(TAG, "System speech activity cancelled resultCode=${result.resultCode}")
            return@rememberLauncherForActivityResult
        }
        val transcript = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (transcript.isNotBlank()) {
            Log.d(TAG, "System speech activity transcript=$transcript")
            inputText = transcript
            pendingVoiceSendText = transcript
        } else {
            Log.w(TAG, "System speech activity returned empty transcript")
        }
    }
    var speechInput by remember {
        mutableStateOf<AndroidSpeechInput?>(null)
    }
    LaunchedEffect(hasMicPermission) {
        if (hasMicPermission && speechInput == null) {
            speechInput = AndroidSpeechInput(
                context = context,
                onPartialText = { transcript ->
                    inputText = transcript
                },
                onFinalText = { transcript ->
                    inputText = transcript
                    pendingVoiceSendText = transcript
                },
                onError = { message ->
                    connectionStatus = message
                },
            )
            Log.d(
                TAG,
                "voice init: recognizerAvailable=${speechInput?.isAvailable() == true}, intentAvailable=${isSpeechRecognitionIntentAvailable(context)}, services=${listSpeechRecognitionServices(context)}",
            )
        }
    }
    val voiceInputAvailable = remember(hasMicPermission, speechInput) {
        hasMicPermission
    }

    var client by remember { mutableStateOf<MyWebSocketClient?>(null) }
    var activeChatId by remember { mutableStateOf(DEFAULT_CHAT_ID) }
    var knownChatIds by remember { mutableStateOf(listOf(DEFAULT_CHAT_ID)) }
    var lastProcessedMsgId by remember { mutableStateOf<String?>(null) }
    var reconnectAttempt by remember { mutableIntStateOf(0) }
    var connectionToken by remember { mutableIntStateOf(0) }
    var reconnectEnabled by remember { mutableStateOf(true) }
    var reconnectJob by remember { mutableStateOf<Job?>(null) }
    var paired by remember { mutableStateOf(false) }
    var historyLoaded by remember { mutableStateOf(false) }
    var speechEnabled by rememberSaveable { mutableStateOf(true) }
    val activeStreamMessageIds = remember { mutableStateMapOf<String, String>() }
    var connectSocket: () -> Unit = {}

    DisposableEffect(speechSpeaker) {
        onDispose {
            speechSpeaker.shutdown()
        }
    }

    DisposableEffect(speechInput) {
        onDispose {
            speechInput?.stop()
            speechInput = null
        }
    }

    fun startVoiceInput() {
        if (!hasMicPermission) {
            Log.e(TAG, "startVoiceInput: requesting RECORD_AUDIO permission")
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val activeSpeechInput = speechInput
        if (activeSpeechInput?.isAvailable() == true) {
            try {
                Log.d(TAG, "startVoiceInput: using SpeechRecognizer")
                activeSpeechInput.startListening()
                return
            } catch (_: Exception) {
                Log.e(TAG, "startVoiceInput: SpeechRecognizer threw exception")
                if (isSpeechRecognitionIntentAvailable(context)) {
                    try {
                        voiceInputLauncher.launch(createSpeechRecognitionIntent(context))
                        connectionStatus = "语音识别器异常，已切换系统语音识别"
                        return
                    } catch (_: Exception) {
                        connectionStatus = "语音输入不可用：无法打开系统语音识别界面"
                        return
                    }
                }
                connectionStatus = "语音输入不可用：语音识别服务未响应"
                return
            }
        }
        if (isSpeechRecognitionIntentAvailable(context)) {
            try {
                Log.d(TAG, "startVoiceInput: falling back to system speech activity")
                voiceInputLauncher.launch(createSpeechRecognitionIntent(context))
                return
            } catch (_: Exception) {
                Log.e(TAG, "startVoiceInput: failed to launch system speech activity")
                connectionStatus = "语音输入不可用：无法打开系统语音识别界面"
                return
            }
        }
        Log.e(
            TAG,
            "startVoiceInput: no speech recognition provider available, recognizerAvailable=${activeSpeechInput?.isAvailable() == true}, intentAvailable=${isSpeechRecognitionIntentAvailable(context)}, services=${listSpeechRecognitionServices(context)}",
        )
        val openedSettings = openVoiceInputSettings(context)
        connectionStatus = if (openedSettings) {
            "语音输入不可用：未检测到识别服务，已为你打开语音输入设置"
        } else {
            "语音输入不可用：未检测到识别服务，请安装或启用系统语音识别"
        }
    }

    fun persistKnownChats() {
        ChatHistoryStore.saveKnownChatIds(context, wsUrl, knownChatIds)
    }

    fun persistCurrentMessages() {
        if (historyLoaded) {
            ChatHistoryStore.saveMessages(context, wsUrl, activeChatId, messages)
        }
    }

    fun sendUserMessage(text: String): Boolean {
        val currentClient = client
        val trimmed = text.trim()
        if (!paired) {
            connectionStatus = "尚未与 OpenClaw 配对成功"
            return false
        }
        if (trimmed.isBlank() || currentClient?.isOpen != true) {
            return false
        }
        val clientMessageId = UUID.randomUUID().toString()
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
            "server_id" to serverId,
            "user_id" to userId,
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
        return true
    }

    LaunchedEffect(pendingVoiceSendText, client, activeChatId) {
        val transcript = pendingVoiceSendText?.trim().orEmpty()
        if (transcript.isBlank()) {
            return@LaunchedEffect
        }
        if (sendUserMessage(transcript)) {
            pendingVoiceSendText = null
            inputText = ""
            return@LaunchedEffect
        }
        connectionStatus = "语音识别已完成，但当前未连接，未能自动发送"
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
                                "user_id" to userId,
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

    fun mergeIncomingMessage(
        chatId: String?,
        incoming: ChatMessage,
        appendToExistingContent: Boolean = false,
    ) {
        val normalizedChatId = chatId?.trim().orEmpty()
        if (normalizedChatId.isNotBlank()) {
            rememberChat(normalizedChatId)
            val persistedMessages = ChatHistoryStore.loadMessages(context, wsUrl, normalizedChatId)
            val mergedPersisted = if (appendToExistingContent) {
                mergeChatMessagesByAppendingContent(persistedMessages, incoming)
            } else {
                mergeChatMessages(persistedMessages, incoming)
            }
            ChatHistoryStore.saveMessages(context, wsUrl, normalizedChatId, mergedPersisted)
            persistKnownChats()
        }
        if (normalizedChatId.isBlank() || normalizedChatId == activeChatId) {
            messages = if (appendToExistingContent) {
                mergeChatMessagesByAppendingContent(messages, incoming)
            } else {
                mergeChatMessages(messages, incoming)
            }
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
                            "user_id" to userId,
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
        if (!isBound) {
            connectionStatus = "未配对"
        } else {
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
                                    "register_response" -> {
                                        val ok = (responseMap["status"] as? String) == "success"
                                        val isPaired = responseMap["paired"] as? Boolean ?: false
                                        if (ok) {
                                            paired = isPaired
                                            connectionStatus = if (isPaired) {
                                                "已连接(配对成功)"
                                            } else {
                                                "已连接(等待配对)"
                                            }
                                            if (isPaired) {
                                                requestHistory(activeChatId)
                                            }
                                        }
                                    }

                                    "error" -> {
                                        val errMsg = responseMap["error"] as? String
                                        if (!errMsg.isNullOrBlank()) {
                                            connectionStatus = "错误: $errMsg"
                                        }
                                    }

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
                                        val remoteUserId = responseMap["user_id"] as? String
                                        if (remoteUserId != userId) {
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
                                        if (speechEnabled && content.isNotBlank()) {
                                            scope.launch {
                                                speechSpeaker.speak(content)
                                            }
                                        }
                                        lastProcessedMsgId = messageId
                                    }

                                    "bot_message_stream" -> {
                                        val remoteUserId = responseMap["user_id"] as? String
                                        if (remoteUserId != userId) {
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
                                                    appendToExistingContent = true,
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
                                                if (speechEnabled && content.isNotBlank()) {
                                                    scope.launch {
                                                        speechSpeaker.speak(content)
                                                    }
                                                }
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
                            paired = false
                            connectionStatus = "已连接，注册中..."
                            val registerPayload = gson.toJson(
                                mapOf(
                                    "type" to "register",
                                    "role" to "client",
                                    "server_id" to serverId,
                                    "user_id" to userId,
                                    "account_id" to userId,
                                    "pair_token" to pairToken,
                                    "device_id" to deviceId,
                                    "user_name" to pairingNickname.trim().ifBlank { DEFAULT_NICKNAME },
                                ),
                            )
                            scope.launch(Dispatchers.IO) {
                                try {
                                    client?.send(registerPayload)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }
                },
                onCloseCallback = { code, _, _ ->
                    if (token == connectionToken) {
                        scope.launch {
                            client = null
                            paired = false
                            connectionStatus = "已断开(code=$code)"
                            scheduleReconnect()
                        }
                    }
                },
                onErrorCallback = { _ ->
                    if (token == connectionToken) {
                        scope.launch {
                            paired = false
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
    }

    LaunchedEffect(wsUrl, serverId, userId, pairToken) {
        if (!isBound) {
            reconnectEnabled = false
            reconnectAttempt = 0
            paired = false
            historyLoaded = false
            reconnectJob?.cancel()
            reconnectJob = null
            client?.close()
            client = null
            connectionStatus = "未配对"
            return@LaunchedEffect
        }
        reconnectEnabled = true
        reconnectAttempt = 0
        paired = false
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
        if (!isBound) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("首次使用", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(10.dp))
                Text("手机首次打开只需要一键配对", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = { pairingExpanded = true },
                    enabled = !pairingInProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (pairingInProgress) "配对中..." else "一键配对")
                }
                if (pairingExpanded) {
                    Spacer(modifier = Modifier.height(16.dp))
                    TextField(
                        value = pairingWsUrl,
                        onValueChange = { pairingWsUrl = it },
                        label = { Text("服务地址 WebSocket URL") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it.filter { ch -> ch.isDigit() }.take(6) },
                        label = { Text("输入 6 位配对码") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = pairingNickname,
                        onValueChange = { pairingNickname = it },
                        label = { Text("昵称（仅展示）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val code = pairingCode.trim()
                            if (code.length != 6) {
                                connectionStatus = "请输入 6 位配对码"
                                return@Button
                            }
                            pairingInProgress = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    requestPairing(
                                        wsUrl = pairingWsUrl,
                                        pairCode = code,
                                        nickname = pairingNickname,
                                        deviceId = deviceId,
                                    )
                                }
                                pairingInProgress = false
                                if (!result.success) {
                                    connectionStatus = result.error ?: "配对失败"
                                    return@launch
                                }
                                pairingCode = ""
                                pairingExpanded = false
                                connectionStatus = "配对成功，连接中..."
                                onPaired(
                                    ClientSettings(
                                        wsUrl = result.wsUrl,
                                        serverId = result.serverId,
                                        userId = result.accountId,
                                        pairToken = result.pairToken,
                                        nickname = result.nickname,
                                        deviceId = deviceId,
                                    ),
                                )
                            }
                        },
                        enabled = !pairingInProgress,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("确认配对")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "状态: $connectionStatus",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            return@Scaffold
        }

        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Text(
                text = "连接状态: $connectionStatus",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "目标: $serverId / $userId (${pairingNickname.ifBlank { DEFAULT_NICKNAME }})",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall,
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
                    onClick = { speechEnabled = !speechEnabled },
                ) {
                    Text(if (speechEnabled) "语音播报: 开" else "语音播报: 关")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        if (!paired) {
                            connectionStatus = "尚未与 OpenClaw 配对成功"
                            return@OutlinedButton
                        }
                        if (client?.isOpen == true) {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    client?.send(
                                        gson.toJson(
                                            mapOf(
                                                "type" to "start_new_conversation",
                                                "server_id" to serverId,
                                                "user_id" to userId,
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
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { startVoiceInput() },
                    enabled = voiceInputAvailable,
                ) {
                    Text(if (voiceInputAvailable) "语音输入" else "语音输入不可用")
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (sendUserMessage(inputText)) {
                            inputText = ""
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
fun SettingsScreen(
    wsUrl: String,
    serverId: String,
    userId: String,
    pairToken: String,
    nickname: String,
    deviceId: String,
    onSave: (ClientSettings) -> Unit,
    onResetPairing: () -> Unit,
    onBack: () -> Unit,
) {
    var tempUrl by remember { mutableStateOf(wsUrl) }
    var tempNickname by remember { mutableStateOf(nickname.ifBlank { DEFAULT_NICKNAME }) }

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
            TextField(
                value = tempNickname,
                onValueChange = { tempNickname = it },
                label = { Text("Nickname") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("Server ID: $serverId", style = MaterialTheme.typography.bodySmall)
            Text("Account ID: ${if (userId.isBlank()) "未配对" else userId}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Pair Token: ${if (pairToken.isBlank()) "未配对" else "已绑定"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = {
                    onSave(
                        ClientSettings(
                            wsUrl = tempUrl.trim().ifBlank { DEFAULT_WS_URL },
                            serverId = serverId,
                            userId = userId,
                            pairToken = pairToken,
                            nickname = tempNickname.trim().ifBlank { DEFAULT_NICKNAME },
                            deviceId = deviceId,
                        ),
                    )
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save & Back")
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    onResetPairing()
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("重置配对")
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
