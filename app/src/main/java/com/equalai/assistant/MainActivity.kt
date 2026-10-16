package com.equalai.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.*
import android.app.role.RoleManager
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

// ============================================================================
// 1. DATA MODELS & GLOBAL STATE
// ============================================================================

data class AppNotificationItem(
    val key: String,
    val packageName: String,
    val appName: String,
    val sender: String,
    val message: String,
    val timestamp: String,
    val replyAction: Notification.Action? = null
)

data class ScheduledActionItem(
    val id: Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
    val actionType: String,
    val target: String,
    val message: String,
    val timeLabel: String,
    val attachmentUri: String? = null
)

data class CallTranscriptItem(
    val caller: String,
    val summary: String,
    val fullTranscript: String,
    val timestamp: String
)

object AssistantBridge {
    val notifications = mutableStateListOf<AppNotificationItem>()
    val scheduledTasks = mutableStateListOf<ScheduledActionItem>()
    val callLogs = mutableStateListOf<CallTranscriptItem>()
    val commandLogs = mutableStateListOf<String>()

    var readNotificationsAloud by mutableStateOf(true)
    var isContinuousListeningEnabled by mutableStateOf(true)
    var latestNotification: AppNotificationItem? = null

    var promptInTextBoxCallback: ((String) -> Unit)? = null

    var pendingLoginUser: String? = null
    var pendingLoginPass: String? = null
    var autoClickSendUntil: Long = 0L

    var notificationService: EqualNotificationService? = null
    var foregroundService: EqualForegroundService? = null
    var accessibilityService: EqualAccessibilityService? = null

    fun log(msg: String) {
        val time = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())
        commandLogs.add(0, "[$time] $msg")
    }
}

// ============================================================================
// 2. FOREGROUND SERVICE (Continuous Background & TTS-to-Speech Loop)
// ============================================================================

class EqualForegroundService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false

    override fun onCreate() {
        super.onCreate()
        AssistantBridge.foregroundService = this

        try {
            tts = TextToSpeech(this, this)
        } catch (e: Exception) {
            AssistantBridge.log("TTS err: ${e.localizedMessage}")
        }

        createNotificationChannel()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    1001,
                    buildNotification("Equal AI Listening Always"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(1001, buildNotification("Equal AI Listening Always"))
            }
        } catch (e: Exception) {
            AssistantBridge.log("FGS err: ${e.localizedMessage}")
        }

        mainHandler.postDelayed({ initSpeechRecognizer() }, 1500)
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            AssistantBridge.promptInTextBoxCallback?.invoke(matches[0])
                            handleSpokenCommand(matches[0])
                        } else {
                            AssistantBridge.promptInTextBoxCallback?.invoke("")
                        }
                        restartListeningLoop(1000)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            AssistantBridge.promptInTextBoxCallback?.invoke("Hearing: ${matches[0]}...")
                        }
                    }
                    override fun onError(error: Int) {
                        isListening = false
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            AssistantBridge.promptInTextBoxCallback?.invoke("")
                        }
                        restartListeningLoop(1000)
                    }
                    override fun onReadyForSpeech(params: Bundle?) { isListening = true }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { isListening = false }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            startListeningInternal(false)
        } catch (e: Exception) {
            AssistantBridge.log("Recognizer err: ${e.localizedMessage}")
        }
    }

    fun startListeningInternal(playBeep: Boolean) {
        if (!AssistantBridge.isContinuousListeningEnabled || isListening) return

        if (playBeep) {
            try {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                tone.startTone(ToneGenerator.TONE_PROP_PROMPT, 150)
                AssistantBridge.promptInTextBoxCallback?.invoke("🎤 Listening...")
            } catch (e: Exception) {}
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        mainHandler.post {
            try {
                speechRecognizer?.startListening(intent)
                isListening = true
            } catch (e: Exception) {
                isListening = false
                restartListeningLoop(1000)
            }
        }
    }

    private fun restartListeningLoop(delayMs: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (AssistantBridge.isContinuousListeningEnabled) {
                startListeningInternal(false)
            }
        }, delayMs)
    }

    private fun handleSpokenCommand(spokenText: String) {
        AssistantBridge.log("Voice Command: \"$spokenText\"")

        val prefs = getSharedPreferences("equal_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", BuildConfig.GEMINI_API_KEY) ?: ""
        val model = prefs.getString("model_name", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        val user = prefs.getString("saved_user", "") ?: ""
        val pass = prefs.getString("saved_pass", "") ?: ""

        CoroutineScope(Dispatchers.Main).launch {
            parseCommandUnified(this@EqualForegroundService, spokenText, apiKey, model, user, pass)
        }
    }

    fun speakBackground(text: String, listenAfter: Boolean = false) {
        if (isTtsReady) {
            speechRecognizer?.stopListening()
            isListening = false
            val utteranceId = if (listenAfter) "LISTEN_AFTER_BEEP" else "SPEAK_ONLY"
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("en", "IN")
            isTtsReady = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == "LISTEN_AFTER_BEEP") {
                        mainHandler.post { startListeningInternal(true) }
                    } else {
                        restartListeningLoop(1000)
                    }
                }
                override fun onError(id: String?) { restartListeningLoop(1000) }
            })
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "equal_bg_channel",
                "Equal AI Background Agent",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "equal_bg_channel")
            .setContentTitle("Equal AI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}

// ============================================================================
// 3. UNIFIED COMMAND PARSER (OFFLINE + CLOUD + CONTEXT AWARE)
// ============================================================================

suspend fun parseCommandUnified(
    context: Context,
    spoken: String,
    apiKey: String,
    modelName: String,
    savedUser: String,
    savedPass: String
) {
    val clean = spoken.trim().lowercase()

    // Context-Aware: "Open the app" / "Open it" -> Opens the app from the last notification
    if (clean == "open the app" || clean == "open it" || clean == "open app") {
        val targetPkg = AssistantBridge.latestNotification?.packageName
        if (targetPkg != null) {
            AssistantBridge.foregroundService?.speakBackground("Opening ${AssistantBridge.latestNotification?.appName}")
            ActionExecutor.openAppByPackageName(context, targetPkg)
        } else {
            AssistantBridge.foregroundService?.speakBackground("I don't know which app you mean.")
        }
        return
    }

    // Direct Offline Rules
    if (clean.contains("whatsapp")) {
        ActionExecutor.openAppByName(context, "whatsapp")
        AssistantBridge.foregroundService?.speakBackground("Opening WhatsApp")
        return
    }

    if (clean.startsWith("open ") || clean.startsWith("launch ")) {
        val appName = clean.removePrefix("open ").removePrefix("launch ").replace("app", "").trim()
        if (ActionExecutor.openAppByName(context, appName)) {
            AssistantBridge.foregroundService?.speakBackground("Opening $appName")
            return
        }
    }

    if (clean == "close" || clean == "dismiss" || clean == "close it") {
        AssistantBridge.notificationService?.dismissLatest()
        AssistantBridge.foregroundService?.speakBackground("Dismissed")
        return
    }

    if (clean.startsWith("call ") && !clean.contains(" at ") && !clean.contains(" in ")) {
        val target = clean.removePrefix("call ").trim()
        AssistantBridge.foregroundService?.speakBackground("Calling $target")
        ActionExecutor.makePhoneCall(context, target)
        return
    }

    if (clean.startsWith("text ") || clean.startsWith("reply ")) {
        val body = clean.substringAfter("saying", clean.removePrefix("text ").removePrefix("reply ")).trim().ifBlank { "Hello" }
        // If there's an active notification, reply to it first
        if (AssistantBridge.notificationService?.replyToLatest(body) == true) {
            AssistantBridge.foregroundService?.speakBackground("Reply sent.")
            return
        }
    }

    // Online Cloud Gemini Routing
    if (apiKey.isBlank()) {
        AssistantBridge.foregroundService?.speakBackground("Saved command. Please set API key for complex tasks.")
        return
    }

    val notifContext = AssistantBridge.latestNotification?.let { "Latest Notification from ${it.appName}: ${it.message}" } ?: "No active notification"

    val prompt = """
        Context: $notifContext
        User said: "$spoken"
        Return JSON:
        {
          "action": "DISMISS_NOTIFICATION" | "REPLY_NOTIFICATION" | "SEND_SMS" | "MAKE_CALL" | "SEND_WHATSAPP" | "OPEN_APP" | "AUTO_LOGIN" | "SPEAK_ONLY",
          "spoken_confirm": "short reply",
          "target": "target app, number, contact, or username",
          "message": "message text"
        }
    """.trimIndent()

    val raw = withContext(Dispatchers.IO) {
        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/${modelName.trim()}:generateContent?key=${apiKey.trim()}"
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 8000
                readTimeout = 8000
            }
            val payload = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            ""
        }
    }

    if (raw.isNotBlank()) {
        try {
            val json = JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            val s = json.indexOf('{')
            val e = json.lastIndexOf('}')
            val obj = JSONObject(json.substring(s, e + 1))
            val action = obj.optString("action")
            val target = obj.optString("target", "")
            val message = obj.optString("message", "")

            withContext(Dispatchers.Main) {
                AssistantBridge.foregroundService?.speakBackground(obj.optString("spoken_confirm", "Done"))
                when (action) {
                    "OPEN_APP" -> ActionExecutor.openAppByName(context, target)
                    "SEND_WHATSAPP" -> ActionExecutor.sendWhatsApp(context, target, message)
                    "MAKE_CALL" -> ActionExecutor.makePhoneCall(context, target)
                    "SEND_SMS" -> ActionExecutor.sendSms(context, target, message)
                    "DISMISS_NOTIFICATION" -> AssistantBridge.notificationService?.dismissLatest()
                    "REPLY_NOTIFICATION" -> AssistantBridge.notificationService?.replyToLatest(message)
                    else -> {}
                }
                Unit
            }
        } catch (e: Exception) {
            AssistantBridge.log("Gemini parse err: ${e.localizedMessage}")
        }
    }
}

// ============================================================================
// 4. NOTIFICATION LISTENER SERVICE
// ============================================================================

class EqualNotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        AssistantBridge.notificationService = this
        AssistantBridge.log("Notification Reader active")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.isOngoing) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || pkg == "android" || pkg.contains("systemui")) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return

        val appName = try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            pkg.substringAfterLast('.')
        }

        val replyAction = sbn.notification?.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
        val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())

        val item = AppNotificationItem(
            key = sbn.key,
            packageName = pkg,
            appName = appName,
            sender = title.ifBlank { appName },
            message = text,
            timestamp = time,
            replyAction = replyAction
        )

        Handler(Looper.getMainLooper()).post {
            AssistantBridge.latestNotification = item
            AssistantBridge.notifications.add(0, item)

            if (AssistantBridge.readNotificationsAloud) {
                // Read the notification aloud, and then trigger the microphone beep immediately
                val promptText = "Notification from $appName. ${item.sender} says: ${item.message}. What would you like to do next?"
                AssistantBridge.promptInTextBoxCallback?.invoke("From $appName: ${item.message} -> What next?")
                AssistantBridge.foregroundService?.speakBackground(promptText, listenAfter = true)
            }
        }
    }

    fun dismissLatest() {
        val target = AssistantBridge.latestNotification ?: AssistantBridge.notifications.firstOrNull()
        if (target != null) {
            cancelNotification(target.key)
            AssistantBridge.notifications.remove(target)
            AssistantBridge.log("Dismissed notification: ${target.sender}")
        }
    }

    fun replyToLatest(replyText: String): Boolean {
        val target = AssistantBridge.latestNotification ?: AssistantBridge.notifications.firstOrNull()
        val action = target?.replyAction ?: return false
        val remoteInputs = action.remoteInputs ?: return false

        val intent = Intent()
        val bundle = Bundle()
        for (input in remoteInputs) {
            bundle.putCharSequence(input.resultKey, replyText)
        }
        RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
        return try {
            action.actionIntent.send(this, 0, intent)
            AssistantBridge.log("Replied to ${target.sender}: $replyText")
            true
        } catch (e: Exception) {
            false
        }
    }
}

// ============================================================================
// 5. CALL SCREENING & AUTO-ANSWER RECEPTIONIST
// ============================================================================

class EqualCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val phoneNumber = callDetails.handle?.schemeSpecificPart ?: "Unknown"
        val callerName = ActionExecutor.resolveCallerName(this, phoneNumber)

        AssistantBridge.log("Call from: $callerName ($phoneNumber)")

        val speech = "You have an incoming call from $callerName."
        AssistantBridge.foregroundService?.speakBackground(speech, listenAfter = false)

        Handler(Looper.getMainLooper()).postDelayed({
            autoAnswerCall(this, phoneNumber, callerName)
        }, 10000)

        respondToCall(callDetails, CallResponse.Builder().build())
    }

    private fun autoAnswerCall(context: Context, number: String, name: String) {
        try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    tm?.acceptRingingCall()
                    AssistantBridge.log("Auto-answered call from $name")

                    val greeting = "Hello, my owner is currently unavailable. This is Equal AI. Please leave your message after the tone."
                    AssistantBridge.foregroundService?.speakBackground(greeting, listenAfter = false)

                    val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                    val transcript = CallTranscriptItem(
                        caller = "$name ($number)",
                        summary = "Unanswered call answered by Equal AI receptionist.",
                        fullTranscript = "Equal AI: $greeting",
                        timestamp = time
                    )
                    AssistantBridge.callLogs.add(0, transcript)
                }
            }
        } catch (e: Exception) {
            AssistantBridge.log("Call auto-answer err: ${e.localizedMessage}")
        }
    }
}

// ============================================================================
// 6. ACTION EXECUTOR
// ============================================================================

object ActionExecutor {

    fun resolveCallerName(context: Context, number: String): String {
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val cursor = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) it.getString(0) else number
            } ?: number
        } catch (e: Exception) {
            number
        }
    }

    fun openAppByPackageName(context: Context, pkgName: String) {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(pkgName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            try {
                context.startActivity(launchIntent)
            } catch (e: Exception) {}
        }
    }

    fun openAppByName(context: Context, appName: String): Boolean {
        val clean = appName.lowercase().trim()

        val directMap = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "instagram" to "com.instagram.android",
            "gmail" to "com.google.android.gm"
        )

        val targetPkg = directMap[clean] ?: run {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            pm.queryIntentActivities(mainIntent, 0).firstOrNull {
                val label = it.loadLabel(pm).toString().lowercase()
                label.contains(clean) || clean.contains(label)
            }?.activityInfo?.packageName
        }

        if (targetPkg != null) {
            val launchedViaA11y = AssistantBridge.accessibilityService?.launchAppFromAccessibility(targetPkg) ?: false
            if (launchedViaA11y) {
                return true
            }

            openAppByPackageName(context, targetPkg)
            return true
        }
        return false
    }

    fun makePhoneCall(context: Context, target: String) {
        val number = resolveContactNumber(context, target)
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
        }
    }

    fun sendSms(context: Context, target: String, message: String) {
        val number = resolveContactNumber(context, target)
        try {
            val sms = context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
            sms.sendTextMessage(number, null, message, null, null)
        } catch (e: Exception) {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun sendWhatsApp(context: Context, target: String, message: String, attachmentUri: String? = null) {
        var number = resolveContactNumber(context, target).filter { it.isDigit() }
        if (number.length == 10) number = "91$number"

        if (attachmentUri != null) {
            try {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "*/*"
                    setPackage("com.whatsapp")
                    putExtra(Intent.EXTRA_STREAM, Uri.parse(attachmentUri))
                    putExtra(Intent.EXTRA_TEXT, message)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            } catch (e: Exception) {}
        }

        try {
            AssistantBridge.autoClickSendUntil = System.currentTimeMillis() + 7000L
            val encoded = URLEncoder.encode(message, "UTF-8")
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$number&text=$encoded")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            openAppByName(context, "whatsapp")
        }
    }

    fun resolveContactNumber(context: Context, query: String): String {
        val clean = query.trim()
        if (clean.any { it.isDigit() } && clean.length >= 6) return clean
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$clean%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) it.getString(0) else clean
            } ?: clean
        } catch (e: Exception) {
            clean
        }
    }
}

// ============================================================================
// 7. ACCESSIBILITY & SCHEDULER
// ============================================================================

class EqualAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        AssistantBridge.accessibilityService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    fun launchAppFromAccessibility(packageName: String): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(launchIntent)
            return true
        }
        return false
    }

    override fun onInterrupt() {}
}

class ScheduledTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
}

// ============================================================================
// 8. COMPOSE UI & ACTIVITY
// ============================================================================

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF0F172A),
                    secondary = Color(0xFF10B981),
                    background = Color(0xFFF8FAFC)
                )
            ) {
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    startAssistantService()
                }

                LaunchedEffect(Unit) {
                    val perms = mutableListOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.CALL_PHONE,
                        Manifest.permission.ANSWER_PHONE_CALLS,
                        Manifest.permission.SEND_SMS,
                        Manifest.permission.READ_CONTACTS
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        perms.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    launcher.launch(perms.toTypedArray())
                }

                EqualAssistantMainScreen()
            }
        }
    }

    private fun startAssistantService() {
        try {
            val serviceIntent = Intent(this, EqualForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualAssistantMainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("equal_prefs", Context.MODE_PRIVATE) }

    var selectedTab by remember { mutableStateOf(0) }
    var universalInput by remember { mutableStateOf("") }

    var apiKey by remember { mutableStateOf(prefs.getString("api_key", BuildConfig.GEMINI_API_KEY) ?: "") }
    var modelName by remember { mutableStateOf(prefs.getString("model_name", "gemini-2.5-flash") ?: "gemini-2.5-flash") }
    var savedUser by remember { mutableStateOf(prefs.getString("saved_user", "") ?: "") }
    var savedPass by remember { mutableStateOf(prefs.getString("saved_pass", "") ?: "") }

    LaunchedEffect(Unit) {
        AssistantBridge.promptInTextBoxCallback = { text ->
            // Don't overwrite the user's manual typing with empty string resets
            if (text.isNotBlank() || universalInput.startsWith("🎤")) {
                universalInput = text
            }
        }
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
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                        Text(if (AssistantBridge.isContinuousListeningEnabled) "Listening" else "Paused", fontSize = 11.sp, color = Color.Gray)
                        Spacer(Modifier.width(4.dp))
                        Switch(
                            checked = AssistantBridge.isContinuousListeningEnabled,
                            onCheckedChange = {
                                AssistantBridge.isContinuousListeningEnabled = it
                                if (it) AssistantBridge.foregroundService?.startListeningInternal(false)
                            }
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.Notifications, null) }, label = { Text("Alerts") })
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.PhoneCallback, null) }, label = { Text("Calls") })
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Setup") })
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).background(Color(0xFFF8FAFC)).padding(16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = universalInput,
                    onValueChange = { universalInput = it },
                    placeholder = { Text("Spoken text appears here...") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                )
                IconButton(
                    onClick = {
                        if (universalInput.isNotBlank()) {
                            val cmd = universalInput.removePrefix("🎤 Listening...").removePrefix("Hearing: ")
                            universalInput = ""
                            scope.launch { parseCommandUnified(context, cmd, apiKey, modelName, savedUser, savedPass) }
                        }
                    },
                    modifier = Modifier.clip(CircleShape).background(Color(0xFF0F172A))
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Run", tint = Color.White)
                }
            }

            Spacer(Modifier.height(12.dp))

            when (selectedTab) {
                0 -> NotificationsTab()
                1 -> CallsAndSummariesTab()
                else -> SetupTab(
                    context = context,
                    apiKey = apiKey,
                    onApiKeyChange = { apiKey = it; prefs.edit().putString("api_key", it).apply() },
                    modelName = modelName,
                    onModelNameChange = { modelName = it; prefs.edit().putString("model_name", it).apply() },
                    savedUser = savedUser,
                    onUserChange = { savedUser = it; prefs.edit().putString("saved_user", it).apply() },
                    savedPass = savedPass,
                    onPassChange = { savedPass = it; prefs.edit().putString("saved_pass", it).apply() }
                )
            }
        }
    }
}

@Composable
fun NotificationsTab() {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Read Notifications Aloud", fontWeight = FontWeight.Bold)
                    Text("Reads alert aloud & prompts for next action", fontSize = 12.sp, color = Color.Gray)
                }
                Switch(checked = AssistantBridge.readNotificationsAloud, onCheckedChange = { AssistantBridge.readNotificationsAloud = it })
            }
        }

        Text("Live Notifications", fontWeight = FontWeight.Bold)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AssistantBridge.notifications) { item ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${item.appName} • ${item.sender}", fontWeight = FontWeight.Bold)
                            Text(item.timestamp, color = Color.Gray, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(item.message, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun CallsAndSummariesTab() {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("AI Receptionist Call Summaries", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("Calls unanswered for 10s are automatically answered and logged here.", fontSize = 12.sp, color = Color.Gray)

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AssistantBridge.callLogs) { call ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(call.caller, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                            Text(call.timestamp, fontSize = 11.sp, color = Color.Gray)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("AI Summary:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF10B981))
                        Text(call.summary, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(call.fullTranscript, fontSize = 12.sp, color = Color.DarkGray)
                    }
                }
            }
        }
    }
}

@Composable
fun SetupTab(
    context: Context, apiKey: String, onApiKeyChange: (String) -> Unit,
    modelName: String, onModelNameChange: (String) -> Unit,
    savedUser: String, onUserChange: (String) -> Unit,
    savedPass: String, onPassChange: (String) -> Unit
) {
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("1. Required System Permissions", fontWeight = FontWeight.Bold)

        // NEW: Assign Caller ID & Spam Role (Required for CallScreeningService)
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val roleManager = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
                    if (roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                        roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
        ) {
            Text("Enable Caller ID & Spam App Role (Required for Calls)")
        }

        Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))) {
            Text("Enable Accessibility (Opens Apps from Background)")
        }

        Button(onClick = { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))) {
            Text("Enable Display Over Other Apps")
        }

        Button(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))) {
            Text("Enable Notification Listener")
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("2. Cloud AI Configuration", fontWeight = FontWeight.Bold)
        OutlinedTextField(value = apiKey, onValueChange = onApiKeyChange, label = { Text("AI Studio API Key") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = modelName, onValueChange = onModelNameChange, label = { Text("Model Name") }, modifier = Modifier.fillMaxWidth())
    }
}
