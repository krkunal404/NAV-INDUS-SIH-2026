package com.example.myapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.myapp.llm.ActionExecutor
import com.example.myapp.llm.AlarmManager
import com.example.myapp.llm.CallManager
import com.example.myapp.llm.NimoAction
import com.example.myapp.llm.NimoLLM
import com.example.myapp.overlay.ActiveVoiceInput
import com.example.myapp.overlay.OverlayController
import com.example.myapp.overlay.OverlayService
import com.example.myapp.overlay.WakeWordManager
import com.example.myapp.speech.LanguageManager
import com.example.myapp.speech.TTSManager
import com.example.myapp.ui.ChatMessage
import com.example.myapp.ui.theme.MyAppTheme
import com.example.myapp.ui.theme.ThemeManager

class MainActivity : ComponentActivity() {

    private val chatMessages = mutableStateListOf<ChatMessage>()
    private var statusTextState = mutableStateOf("Online")

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] ?: false
        if (micGranted) {
            setupNimo()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Theme, Language & Text-To-Speech
        ThemeManager.init(this)
        LanguageManager.init(this)
        TTSManager.init(this)

        // Initial welcome message from Nimo
        if (chatMessages.isEmpty()) {
            val welcomeMsg = if (LanguageManager.isHindi()) {
                "नमस्ते! मैं निमो हूँ, आपकी क्या सहायता कर सकता हूँ?"
            } else {
                "Hello! I am Nimo, your voice assistant. How can I help you today?"
            }
            chatMessages.add(ChatMessage(text = welcomeMsg, isUser = false))
        }

        // Fired when the mic button inside app or overlay is tapped
        OverlayService.onMicTapped = {
            Log.d("Nimo", "Mic tapped — starting active voice capture")
            TTSManager.stop()
            if (!CallManager.isAwaitingConfirmation && !AlarmManager.isAwaitingTimeInput) {
                updateStatus("Listening... speak now")
            }

            WakeWordManager.stop()

            ActiveVoiceInput.startListening(
                context = this,
                onPartial = { partialText ->
                    runOnUiThread {
                        updateStatus("Listening: \"$partialText\"")
                    }
                },
                onResult = { spokenText ->
                    Log.d("Nimo", "Voice captured: $spokenText")
                    runOnUiThread {
                        submitQuery(spokenText)
                    }
                },
                onError = { err ->
                    Log.e("Nimo", "Voice capture error: $err")
                    runOnUiThread {
                        updateStatus("Voice ended ($err)")
                        CallManager.cancelPending()
                        AlarmManager.cancelPending()
                        restartWakeWord()
                    }
                }
            )
        }

        // Fired when a query is submitted (voice or typed)
        OverlayService.onQuerySubmitted = { text ->
            submitQuery(text)
        }

        setContent {
            var currentLang by remember { mutableStateOf(LanguageManager.currentLanguage) }
            var currentAppTheme by remember { mutableStateOf(ThemeManager.currentTheme) }

            MyAppTheme(appTheme = currentAppTheme) {
                NimoMainChatScreen(
                    messages = chatMessages,
                    statusText = statusTextState.value,
                    currentLanguage = currentLang,
                    currentTheme = currentAppTheme,
                    onMicTapped = {
                        OverlayService.onMicTapped?.invoke()
                    },
                    onSendText = { typedText ->
                        submitQuery(typedText)
                    },
                    onToggleLanguage = {
                        val newLang = if (LanguageManager.isHindi()) {
                            LanguageManager.Language.ENGLISH
                        } else {
                            LanguageManager.Language.HINDI
                        }
                        LanguageManager.setLanguage(this@MainActivity, newLang)
                        currentLang = newLang
                        val confirmText = if (newLang == LanguageManager.Language.HINDI) {
                            "नमस्ते! अब मैं हिंदी में सहायता करूँगा।"
                        } else {
                            "Switched to English mode."
                        }
                        chatMessages.add(ChatMessage(text = confirmText, isUser = false))
                        TTSManager.speak(confirmText)
                    },
                    onToggleTheme = {
                        val newTheme = if (ThemeManager.isLight()) {
                            ThemeManager.AppTheme.DARK
                        } else {
                            ThemeManager.AppTheme.LIGHT
                        }
                        ThemeManager.setTheme(this@MainActivity, newTheme)
                        currentAppTheme = newTheme
                    }
                )
            }
        }
    }

    private fun updateStatus(text: String) {
        statusTextState.value = text
        OverlayService.updateStatus(text)
    }

    private fun submitQuery(text: String) {
        if (text.isBlank()) return
        Log.d("Nimo", "Query submitted: $text")

        // Add user message to Chat List
        runOnUiThread {
            chatMessages.add(ChatMessage(text = text, isUser = true))
        }

        // Check pending confirmations
        if (AlarmManager.isAwaitingTimeInput) {
            val handled = AlarmManager.handleTimeInput(this@MainActivity, text)
            if (handled) {
                restartWakeWord()
            }
            return
        }

        if (CallManager.isAwaitingConfirmation) {
            val handled = CallManager.handleUserConfirmation(this@MainActivity, text)
            if (handled) {
                restartWakeWord()
            }
            return
        }

        updateStatus("Thinking...")

        NimoLLM.ask(this@MainActivity, text, object : NimoLLM.Callback {
            override fun onResult(action: NimoAction) {
                runOnUiThread {
                    chatMessages.add(ChatMessage(text = action.reply, isUser = false))
                    updateStatus("Online")

                    if (action !is NimoAction.CallContact && action !is NimoAction.AskAlarmTime && action !is NimoAction.EmergencyCall) {
                        TTSManager.speak(action.reply)
                    }
                    ActionExecutor.execute(this@MainActivity, action)
                    if (action !is NimoAction.CallContact && action !is NimoAction.AskAlarmTime) {
                        restartWakeWord()
                    }
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    val errMsg = if (LanguageManager.isHindi()) "क्षमा करें, कोई त्रुटि हुई।" else "Sorry, I encountered an error"
                    chatMessages.add(ChatMessage(text = errMsg, isUser = false))
                    updateStatus("Error")
                    TTSManager.speak(errMsg)
                    restartWakeWord()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // When main app is open on screen, hide floating overlay
        OverlayController.hide(this)
        setupNimo()
    }

    override fun onPause() {
        super.onPause()
        // When user switches away from main app, enable floating overlay over other apps
        if (OverlayController.hasPermission(this)) {
            OverlayController.show(this)
        }
    }

    private fun setupNimo() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CALL_PHONE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.SEND_SMS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
            return
        }

        startNimo()
    }

    private fun startNimo() {
        if (!OverlayController.hasPermission(this)) {
            Log.w("Nimo", "Overlay permission missing. Requesting system alert window permission...")
            OverlayController.requestPermission(this)
        }
        restartWakeWord()
    }

    private fun restartWakeWord() {
        WakeWordManager.start(this) { trailingCommand ->
            runOnUiThread {
                Log.d("Nimo", "Wake word detected! trailing=\"$trailingCommand\"")

                if (!trailingCommand.isNullOrBlank()) {
                    // The user said their command in the same breath as the
                    // wake word (e.g. "hey nimo open whatsapp"). Act on it
                    // immediately instead of discarding it and making them
                    // wait through a spoken "Hello, I am Nimo" greeting.
                    TTSManager.stop()
                    updateStatus("Listening: \"$trailingCommand\"")
                    submitQuery(trailingCommand)
                    return@runOnUiThread
                }

                val greeting = if (LanguageManager.isHindi()) {
                    "नमस्ते! मैं निमो हूँ, आपकी क्या सहायता कर सकता हूँ?"
                } else {
                    "Hello Nimo! How can I help you?"
                }
                updateStatus("Listening...")

                TTSManager.speak(greeting) {
                    runOnUiThread {
                        OverlayService.onMicTapped?.invoke()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ActiveVoiceInput.destroy()
        WakeWordManager.stop()
        TTSManager.shutdown()
        CallManager.cancelPending()
        AlarmManager.cancelPending()
    }
}

/**
 * Main Chat Screen with Dual Theme Support:
 * - Light Theme ☀️ (Light Mode - Japanese Cherry Blossom)
 * - Dark Theme 🌙 (Dark Mode - Midnight Slate)
 */
@Composable
fun NimoMainChatScreen(
    messages: List<ChatMessage>,
    statusText: String,
    currentLanguage: LanguageManager.Language,
    currentTheme: ThemeManager.AppTheme,
    onMicTapped: () -> Unit,
    onSendText: (String) -> Unit,
    onToggleLanguage: () -> Unit,
    onToggleTheme: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val isLight = currentTheme == ThemeManager.AppTheme.LIGHT
    val colors = MaterialTheme.colorScheme

    // Dynamic Theme Colors
    val appBgColor = colors.background
    val topBarColor = colors.secondaryContainer
    val titleColor = colors.onSecondaryContainer
    val inputBgColor = colors.surfaceVariant
    val inputTextColor = colors.onSurfaceVariant
    val micBtnColor = if (isLight) colors.primary else colors.tertiary
    val sendBtnColor = colors.secondary
    val sendIconColor = colors.onSecondary

    // Auto scroll to latest message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            Surface(
                color = topBarColor,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Nimo Assistant",
                            color = titleColor,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = statusText,
                            color = when {
                                statusText.contains("Listening") -> if (isLight) colors.secondary else colors.tertiary
                                statusText.contains("Thinking") -> Color(0xFFFF8C42)
                                else -> colors.primary
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Theme Toggle Pill (Light ☀️ / Dark 🌙)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(colors.surfaceVariant)
                            .clickable { onToggleTheme() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isLight) "Light ☀️" else "Dark 🌙",
                            color = colors.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Language Toggle Pill (EN / HI)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isLight) colors.primary else colors.tertiary)
                            .clickable { onToggleLanguage() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (currentLanguage == LanguageManager.Language.HINDI) "HI (हिंदी)" else "EN",
                            color = if (isLight) colors.onPrimary else colors.onTertiary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        },
        containerColor = appBgColor
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Chat Messages List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    ThemedChatBubbleItem(message = msg, isLight = isLight)
                }
            }

            // Bottom Control Bar
            Surface(
                color = topBarColor,
                shadowElevation = 16.dp,
                modifier = Modifier.imePadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left Text Input Box
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(28.dp))
                            .background(inputBgColor)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        BasicTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            textStyle = TextStyle(color = inputTextColor, fontSize = 16.sp),
                            cursorBrush = SolidColor(colors.primary),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { innerTextField ->
                                if (inputText.isEmpty()) {
                                    Text(
                                        text = "Type a message...",
                                        color = colors.onSurfaceVariant,
                                        fontSize = 16.sp
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // CENTERED ROUND MIC BUTTON
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(micBtnColor)
                            .clickable { onMicTapped() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_btn_speak_now),
                            contentDescription = "Mic",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Send Button
                    AnimatedVisibility(visible = inputText.isNotBlank()) {
                        IconButton(
                            onClick = {
                                val text = inputText.trim()
                                if (text.isNotBlank()) {
                                    onSendText(text)
                                    inputText = ""
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(sendBtnColor)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_menu_send),
                                contentDescription = "Send",
                                tint = sendIconColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Themed Chat Bubble Item:
 * - Light Mode ☀️ vs Dark Mode 🌙
 */
@Composable
fun ThemedChatBubbleItem(message: ChatMessage, isLight: Boolean) {
    val isUser = message.isUser
    val colors = MaterialTheme.colorScheme

    val userBubbleColor = colors.primary
    val userTextColor = colors.onPrimary
    val nimoBubbleColor = colors.surfaceVariant
    val nimoTextColor = colors.onSurfaceVariant
    val nimoBorderColor = colors.outlineVariant

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) userBubbleColor else nimoBubbleColor,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp
            ),
            shadowElevation = 3.dp,
            modifier = Modifier
                .widthIn(max = 285.dp)
                .then(
                    if (!isUser) {
                        Modifier.border(
                            width = 1.dp,
                            color = nimoBorderColor,
                            shape = RoundedCornerShape(
                                topStart = 18.dp,
                                topEnd = 18.dp,
                                bottomStart = 4.dp,
                                bottomEnd = 18.dp
                            )
                        )
                    } else Modifier
                )
        ) {
            Text(
                text = message.text,
                color = if (isUser) userTextColor else nimoTextColor,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}