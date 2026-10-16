package com.equalai.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

// 1. Background Android Call Screening Service
class EqualCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val phoneNumber = callDetails.handle?.schemeSpecificPart ?: "Unknown Number"
        CallRepository.onIncomingSystemCall(phoneNumber)

        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()

        respondToCall(callDetails, response)
    }
}

// 2. Data Models & In-Memory Store
data class TranscriptTurn(val speaker: String, val text: String)

data class ScreenedCall(
    val id: String = UUID.randomUUID().toString(),
    val callerName: String,
    val phoneNumber: String,
    val timestamp: String,
    val transcript: List<TranscriptTurn>,
    val summary: String,
    val actionItems: List<String>,
    val category: String
)

object CallRepository {
    val calls = mutableStateListOf(
        ScreenedCall(
            callerName = "Amazon Courier",
            phoneNumber = "+91 98480 12345",
            timestamp = "Today, 2:15 PM",
            transcript = listOf(
                TranscriptTurn("Equal AI", "Hello! My owner is currently busy. Who is calling and how can I help?"),
                TranscriptTurn("Caller", "I am at the gate with a package. Should I leave it with security?"),
                TranscriptTurn("Equal AI", "Yes, please leave the parcel with the guard at the main gate. Thank you!")
            ),
            summary = "Delivery executive called with a package at the main gate. Advised to leave it with security.",
            actionItems = listOf("Pick up parcel at gate", "Call +91 98480 12345"),
            category = "Delivery"
        )
    )

    fun onIncomingSystemCall(number: String) {
        val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
        calls.add(
            0,
            ScreenedCall(
                callerName = "Incoming Call",
                phoneNumber = number,
                timestamp = "Today, $time",
                transcript = emptyList(),
                summary = "System call detected. Tap 'Screen Call' to answer with Equal AI.",
                actionItems = listOf("Call back $number"),
                category = "Incoming"
            )
        )
    }
}

// 3. Direct REST Client for Gemini API (No deprecated SDK issues)
fun callGeminiRestApi(apiKey: String, modelName: String, promptText: String): String {
    if (apiKey.isBlank()) {
        return "Missing API Key: Open the 'Tell Equal' tab and paste your Google AI Studio API Key."
    }
    val cleanModel = modelName.trim().removePrefix("models/")
    val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=${apiKey.trim()}"
    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        doOutput = true
        connectTimeout = 15000
        readTimeout = 15000
    }

    val payload = JSONObject().apply {
        put(
            "contents",
            JSONArray().put(
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", promptText))
                )
            )
        )
    }

    conn.outputStream.use { os ->
        os.write(payload.toString().toByteArray(Charsets.UTF_8))
    }

    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val body = stream?.bufferedReader()?.use { it.readText() } ?: ""

    if (code !in 200..299) {
        return "API Error ($code): Check model name ($cleanModel) or API key."
    }

    val json = JSONObject(body)
    val candidates = json.optJSONArray("candidates")
    if (candidates != null && candidates.length() > 0) {
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
        if (parts != null && parts.length() > 0) {
            return parts.getJSONObject(0).optString("text", "No text returned.")
        }
    }
    return "Empty response from Gemini."
}

// 4. Main Activity & UI
class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF0F172A),
                    secondary = Color(0xFF10B981),
                    background = Color(0xFFF8FAFC)
                )
            ) {
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {}

                LaunchedEffect(Unit) {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.READ_PHONE_STATE
                        )
                    )
                }

                EqualAIApp(
                    onSpeak = { text, lang -> speakText(text, lang) },
                    onStartListening = { lang, onResult -> startListening(lang, onResult) },
                    onStopAudio = {
                        tts?.stop()
                        speechRecognizer?.stopListening()
                    }
                )
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("en", "IN")
        }
    }

    private fun speakText(text: String, language: String) {
        val locale = when (language) {
            "Telugu" -> Locale("te", "IN")
            "Hindi" -> Locale("hi", "IN")
            else -> Locale("en", "IN")
        }
        tts?.language = locale
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "EQUAL_AI_TTS")
    }

    private fun startListening(language: String, onResult: (String) -> Unit) {
        val langTag = when (language) {
            "Telugu" -> "te-IN"
            "Hindi" -> "hi-IN"
            else -> "en-IN"
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    onResult(matches[0])
                }
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    override fun onDestroy() {
        tts?.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualAIApp(
    onSpeak: (String, String) -> Unit,
    onStartListening: (String, (String) -> Unit) -> Unit,
    onStopAudio: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("equal_prefs", Context.MODE_PRIVATE) }

    var selectedTab by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var customInstructions by remember {
        mutableStateOf(
            prefs.getString(
                "instructions",
                "I am in a meeting right now. Politely ask who is calling and why. If it is a courier delivery, tell them to leave the parcel at the door."
            ) ?: ""
        )
    }
    var apiKey by remember {
        mutableStateOf(
            prefs.getString("api_key", BuildConfig.GEMINI_API_KEY)?.ifBlank { BuildConfig.GEMINI_API_KEY } ?: ""
        )
    }
    var modelName by remember {
        mutableStateOf(prefs.getString("model_name", "gemini-2.5-flash") ?: "gemini-2.5-flash")
    }
    var selectedLanguage by remember { mutableStateOf("English") }
    var activeCallScreen by remember { mutableStateOf(false) }
    var selectedCallForDetail by remember { mutableStateOf<ScreenedCall?>(null) }

    if (activeCallScreen) {
        LiveCallScreen(
            apiKey = apiKey,
            modelName = modelName,
            instructions = customInstructions,
            language = selectedLanguage,
            onSpeak = onSpeak,
            onStartListening = onStartListening,
            onEndCall = { newCall ->
                onStopAudio()
                if (newCall != null) {
                    CallRepository.calls.add(0, newCall)
                }
                activeCallScreen = false
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color(0xFF10B981))
                        Spacer(Modifier.width(8.dp))
                        Text("Equal AI", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    Button(
                        onClick = { activeCallScreen = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Screen Call")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("Calls & Summaries") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    label = { Text("Tell Equal") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFF8FAFC))
                .padding(16.dp)
        ) {
            if (selectedTab == 0) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search calls, numbers, or transcripts...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(Modifier.height(16.dp))

                val filteredCalls = CallRepository.calls.filter { call ->
                    call.callerName.contains(searchQuery, ignoreCase = true) ||
                        call.phoneNumber.contains(searchQuery, ignoreCase = true) ||
                        call.summary.contains(searchQuery, ignoreCase = true) ||
                        call.transcript.any { it.text.contains(searchQuery, ignoreCase = true) }
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(filteredCalls) { call ->
                        CallSummaryCard(call = call, onClick = { selectedCallForDetail = call })
                    }
                }
            } else {
                TellEqualScreen(
                    instructions = customInstructions,
                    onInstructionsChange = {
                        customInstructions = it
                        prefs.edit().putString("instructions", it).apply()
                    },
                    selectedLanguage = selectedLanguage,
                    onLanguageChange = { selectedLanguage = it },
                    apiKey = apiKey,
                    onApiKeyChange = {
                        apiKey = it
                        prefs.edit().putString("api_key", it).apply()
                    },
                    modelName = modelName,
                    onModelNameChange = {
                        modelName = it
                        prefs.edit().putString("model_name", it).apply()
                    }
                )
            }
        }
    }

    selectedCallForDetail?.let { call ->
        AlertDialog(
            onDismissRequest = { selectedCallForDetail = null },
            title = { Text("${call.callerName} (${call.phoneNumber})", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Instant Summary:", fontWeight = FontWeight.SemiBold, color = Color(0xFF10B981))
                    Text(call.summary, fontSize = 14.sp)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text("Call Transcript:", fontWeight = FontWeight.SemiBold)
                    call.transcript.forEach { turn ->
                        Text("${turn.speaker}: ${turn.text}", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedCallForDetail = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun CallSummaryCard(call: ScreenedCall, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(call.callerName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${call.phoneNumber} • ${call.timestamp}", color = Color.Gray, fontSize = 12.sp)
                }
                Surface(color = Color(0xFFECFDF5), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        text = call.category,
                        color = Color(0xFF059669),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(call.summary, fontSize = 14.sp, color = Color(0xFF334155))

            if (call.actionItems.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    call.actionItems.take(2).forEach { action ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(action, fontSize = 12.sp) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TellEqualScreen(
    instructions: String,
    onInstructionsChange: (String) -> Unit,
    selectedLanguage: String,
    onLanguageChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    modelName: String,
    onModelNameChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Tell Equal How to Answer", fontWeight = FontWeight.Bold, fontSize = 18.sp)

        OutlinedTextField(
            value = instructions,
            onValueChange = onInstructionsChange,
            label = { Text("Custom Call Instructions") },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            shape = RoundedCornerShape(12.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { onInstructionsChange("I am in a meeting. Ask who is calling and if it is urgent.") },
                label = { Text("In a Meeting") }
            )
            AssistChip(
                onClick = { onInstructionsChange("If this is a delivery call, tell them to leave the package at the door.") },
                label = { Text("Delivery Mode") }
            )
        }

        Text("Spoken Language:", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("English", "Telugu", "Hindi").forEach { lang ->
                FilterChip(
                    selected = selectedLanguage == lang,
                    onClick = { onLanguageChange(lang) },
                    label = { Text(lang) }
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Gemini AI Configuration", fontWeight = FontWeight.Bold, fontSize = 16.sp)

        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = { Text("Google AI Studio API Key") },
            placeholder = { Text("AIzaSy...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = modelName,
            onValueChange = onModelNameChange,
            label = { Text("Gemini Model Name") },
            placeholder = { Text("gemini-2.5-flash") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

@Composable
fun LiveCallScreen(
    apiKey: String,
    modelName: String,
    instructions: String,
    language: String,
    onSpeak: (String, String) -> Unit,
    onStartListening: (String, (String) -> Unit) -> Unit,
    onEndCall: (ScreenedCall?) -> Unit
) {
    val scope = rememberCoroutineScope()
    val transcript = remember { mutableStateListOf<TranscriptTurn>() }
    var callerInput by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    val callerNumber = "+91 90000 54321"

    LaunchedEffect(Unit) {
        val greeting = when (language) {
            "Telugu" -> "నమస్కారం! నేను ఈక్వల్ AI అసిస్టెంట్ మాట్లాడుతున్నాను. ఎవరు మాట్లాడుతున్నారు మరియు ఎందుకు ఫోన్ చేశారో చెప్పగలరా?"
            "Hindi" -> "नमस्ते! मैं इक्वल AI असिस्टेंट बात कर रहा हूँ। कृपया बताएं कौन बोल रहा है और किस काम से कॉल किया है?"
            else -> "Hello! I am the Equal AI call assistant. Who is calling and how may I help you?"
        }
        transcript.add(TranscriptTurn("Equal AI", greeting))
        onSpeak(greeting, language)
    }

    fun sendCallerMessage(text: String) {
        if (text.isBlank()) return
        transcript.add(TranscriptTurn("Caller", text))
        callerInput = ""
        isProcessing = true

        scope.launch {
            val historyText = transcript.joinToString("\n") { "${it.speaker}: ${it.text}" }
            val prompt = """
                You are Equal AI, a live phone receptionist screening a call on behalf of your user.
                User instructions: $instructions
                Reply in $language in 1-2 polite, natural spoken sentences.
                
                Call transcript so far:
                $historyText
                
                Equal AI:
            """.trimIndent()

            val reply = withContext(Dispatchers.IO) {
                try {
                    callGeminiRestApi(apiKey, modelName, prompt).trim()
                } catch (e: Exception) {
                    "Connection error: ${e.localizedMessage}"
                }
            }
            transcript.add(TranscriptTurn("Equal AI", reply))
            onSpeak(reply, language)
            isProcessing = false
        }
    }

    fun finishAndSummarize() {
        isProcessing = true
        scope.launch {
            val historyText = transcript.joinToString("\n") { "${it.speaker}: ${it.text}" }
            val summaryPrompt = """
                Summarize this phone call in 1-2 concise sentences (who called and why).
                Then on a new line starting with ACTIONS:, list 2 short post-call actions separated by a comma.
                
                Transcript:
                $historyText
            """.trimIndent()

            val res = withContext(Dispatchers.IO) {
                try {
                    callGeminiRestApi(apiKey, modelName, summaryPrompt)
                } catch (e: Exception) {
                    "Call screened by Equal AI."
                }
            }

            val parts = res.split("ACTIONS:")
            val summaryText = parts[0].trim()
            val actions = if (parts.size > 1) {
                parts[1].split(",").map { it.trim() }
            } else {
                listOf("Call back $callerNumber")
            }

            val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            onEndCall(
                ScreenedCall(
                    callerName = "Screened Caller",
                    phoneNumber = callerNumber,
                    timestamp = "Today, $time",
                    transcript = transcript.toList(),
                    summary = summaryText,
                    actionItems = actions,
                    category = "AI Screened"
                )
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Equal AI Screening Live Call", color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
        Text(callerNumber, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Language: $language • Model: $modelName", color = Color.LightGray, fontSize = 12.sp)

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E293B))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(transcript) { turn ->
                val isAi = turn.speaker == "Equal AI"
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isAi) Alignment.Start else Alignment.End
                ) {
                    Text(
                        turn.speaker,
                        color = if (isAi) Color(0xFF10B981) else Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        color = if (isAi) Color(0xFF334155) else Color(0xFF0284C7),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            turn.text,
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }

        if (isProcessing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = {
                    onStartListening(language) { spoken ->
                        sendCallerMessage(spoken)
                    }
                },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFF10B981))
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Speak", tint = Color.White)
            }

            OutlinedTextField(
                value = callerInput,
                onValueChange = { callerInput = it },
                placeholder = { Text("Caller says...", color = Color.LightGray) },
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                singleLine = true
            )

            IconButton(onClick = { sendCallerMessage(callerInput) }) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = Color(0xFF10B981))
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { finishAndSummarize() },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.CallEnd, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("End Call & Generate AI Summary")
        }
    }
}
