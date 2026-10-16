package com.equalai.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import kotlinx.coroutines.delay
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
    var latestNotification: AppNotificationItem? = null

    var speakAndPromptCallback: ((String, Boolean) -> Unit)? = null
    var promptInTextBoxCallback: ((String) -> Unit)? = null

    var pendingLoginUser: String? = null
    var pendingLoginPass: String? = null
    var autoClickSendUntil: Long = 0L

    var notificationService: EqualNotificationService? = null
    var foregroundService: EqualForegroundService? = null

    fun log(msg: String) {
        val time = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())
        commandLogs.add(0, "[$time] $msg")
    }
}

// ============================================================================
// 2. CRASH-SAFE FOREGROUND SERVICE
// ============================================================================

class EqualForegroundService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    override fun onCreate() {
        super.onCreate()
        AssistantBridge.foregroundService = this
        try {
            tts = TextToSpeech(this, this)
        } catch (e: Exception) {
            AssistantBridge.log("Service TTS init error: ${e.localizedMessage}")
        }
        createNotificationChannel()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    1001,
                    buildNotification("Equal AI Active in Background"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(1001, buildNotification("Equal AI Active in Background"))
            }
        } catch (e: Exception) {
            AssistantBridge.log("startForeground exception handled: ${e.localizedMessage}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
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
            .setContentTitle("Equal AI Assistant")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("en", "IN")
            isTtsReady = true
        }
    }

    fun speakBackground(text: String) {
        if (isTtsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "BG_TTS")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

// ============================================================================
// 3. NOTIFICATION LISTENER SERVICE
// ============================================================================

class EqualNotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        AssistantBridge.notificationService = this
        AssistantBridge.log("Notification Reader attached.")
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
                val promptText = "Notification from $appName. ${item.sender} says: ${item.message}. What would you like to do next?"
                AssistantBridge.promptInTextBoxCallback?.invoke("From $appName: ${item.message} -> What next?")
                AssistantBridge.speakAndPromptCallback?.invoke(promptText, true)
                AssistantBridge.foregroundService?.speakBackground(promptText)
            }
        }
    }

    fun dismissLatest() {
        val target = AssistantBridge.latestNotification ?: AssistantBridge.notifications.firstOrNull()
        if (target != null) {
            cancelNotification(target.key)
            AssistantBridge.notifications.remove(target)
            AssistantBridge.log("Dismissed: ${target.sender}")
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
// 4. ACCESSIBILITY SERVICE
// ============================================================================

class EqualAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return

        if (System.currentTimeMillis() < AssistantBridge.autoClickSendUntil) {
            val sendNodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
                .ifEmpty { root.findAccessibilityNodeInfosByText("Send") }

            for (node in sendNodes) {
                if (node.isClickable && node.ContentDescriptionOrText().contains("Send", ignoreCase = true)) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    AssistantBridge.autoClickSendUntil = 0L
                    AssistantBridge.log("Auto-clicked WhatsApp Send")
                    break
                }
            }
        }

        val user = AssistantBridge.pendingLoginUser
        val pass = AssistantBridge.pendingLoginPass
        if (!user.isNullOrBlank() || !pass.isNullOrBlank()) {
            performAutoLogin(root, user ?: "", pass ?: "")
        }
    }

    private fun performAutoLogin(root: AccessibilityNodeInfo, user: String, pass: String) {
        val editNodes = mutableListOf<AccessibilityNodeInfo>()
        val clickableNodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, editNodes, clickableNodes)

        if (editNodes.isEmpty()) return
        var filledAny = false
        val passwordNode = editNodes.firstOrNull { it.isPassword }
        val usernameNode = editNodes.firstOrNull { !it.isPassword }

        if (usernameNode != null && user.isNotBlank()) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, user)
            }
            usernameNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            filledAny = true
        }

        if (passwordNode != null && pass.isNotBlank()) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pass)
            }
            passwordNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            filledAny = true
        }

        if (filledAny) {
            AssistantBridge.pendingLoginUser = null
            AssistantBridge.pendingLoginPass = null
            AssistantBridge.log("Auto-filled login fields")
            val loginBtn = clickableNodes.firstOrNull {
                val label = it.ContentDescriptionOrText().lowercase()
                label.contains("login") || label.contains("sign in") || label.contains("submit")
            }
            loginBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private fun collectNodes(node: AccessibilityNodeInfo?, edits: MutableList<AccessibilityNodeInfo>, clickables: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) edits.add(node)
        if (node.isClickable) clickables.add(node)
        for (i in 0 until node.childCount) collectNodes(node.getChild(i), edits, clickables)
    }

    private fun AccessibilityNodeInfo.ContentDescriptionOrText(): String {
        return "${this.text ?: ""} ${this.contentDescription ?: ""}".trim()
    }

    override fun onInterrupt() {}
}

// ============================================================================
// 5. CALL SCREENING SERVICE
// ============================================================================

class EqualCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val phoneNumber = callDetails.handle?.schemeSpecificPart ?: "Unknown Caller"
        val callerName = ActionExecutor.resolveCallerName(this, phoneNumber)

        AssistantBridge.log("Incoming call: $callerName ($phoneNumber)")

        val announceSpeech = "You have an incoming call from $callerName."
        AssistantBridge.speakAndPromptCallback?.invoke(announceSpeech, false)
        AssistantBridge.foregroundService?.speakBackground(announceSpeech)

        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            autoAnswerAndRunAiReceptionist(this, phoneNumber, callerName)
        }, 10000)

        val response = CallResponse.Builder().build()
        respondToCall(callDetails, response)
    }

    private fun autoAnswerAndRunAiReceptionist(context: Context, number: String, name: String) {
        try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    tm?.acceptRingingCall()
                    AssistantBridge.log("Auto-answered call from $name")

                    val greeting = "Hello, my owner is currently unavailable. This is Equal AI. Please leave your message after the tone."
                    AssistantBridge.speakAndPromptCallback?.invoke(greeting, false)
                    AssistantBridge.foregroundService?.speakBackground(greeting)

                    val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                    val transcript = CallTranscriptItem(
                        caller = "$name ($number)",
                        summary = "Unanswered call answered by Equal AI. Informed caller that owner is unavailable.",
                        fullTranscript = "Equal AI: $greeting\nCaller: [Audio logged]",
                        timestamp = time
                    )
                    AssistantBridge.callLogs.add(0, transcript)
                    AssistantBridge.promptInTextBoxCallback?.invoke("Call from $name ($number) answered by AI. Summary generated.")
                }
            }
        } catch (e: Exception) {
            AssistantBridge.log("Auto-answer error: ${e.localizedMessage}")
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
            AssistantBridge.log("Sent SMS to $number")
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
                AssistantBridge.log("Sent WhatsApp media to $target")
                return
            } catch (e: Exception) {
                AssistantBridge.log("WhatsApp attachment err: ${e.localizedMessage}")
            }
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
            AssistantBridge.log("Opened WhatsApp for $number")
        } catch (e: Exception) {
            openAppByName(context, "whatsapp")
        }
    }

    fun openAppByName(context: Context, spokenAppName: String): Boolean {
        val pm = context.packageManager
        val clean = spokenAppName.lowercase().replace("open ", "").replace("launch ", "").trim()

        val fast = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "instagram" to "com.instagram.android",
            "gmail" to "com.google.android.gm"
        )
        fast[clean]?.let { pkg ->
            pm.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
                return true
            }
        }

        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        for (info in pm.queryIntentActivities(mainIntent, 0)) {
            val label = info.loadLabel(pm).toString().lowercase()
            if (label.contains(clean) || clean.contains(label)) {
                pm.getLaunchIntentForPackage(info.activityInfo.packageName)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                    return true
                }
            }
        }
        return false
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

    fun scheduleTask(
        context: Context,
        actionType: String,
        target: String,
        message: String,
        triggerTimeMillis: Long,
        attachmentUri: String? = null
    ) {
        val item = ScheduledActionItem(
            actionType = actionType,
            target = target,
            message = message,
            timeLabel = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(triggerTimeMillis)),
            attachmentUri = attachmentUri
        )
        AssistantBridge.scheduledTasks.add(0, item)

        val intent = Intent(context, ScheduledTaskReceiver::class.java).apply {
            putExtra("actionType", actionType)
            putExtra("target", target)
            putExtra("message", message)
            putExtra("attachmentUri", attachmentUri)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            item.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMillis, pi)
        } catch (e: Exception) {
            am.set(AlarmManager.RTC_WAKEUP, triggerTimeMillis, pi)
        }
        AssistantBridge.log("Task scheduled for ${item.timeLabel}")
    }
}

class ScheduledTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra("actionType") ?: return
        val target = intent.getStringExtra("target") ?: ""
        val message = intent.getStringExtra("message") ?: ""
        val attachment = intent.getStringExtra("attachmentUri")

        when (type) {
            "SMS" -> ActionExecutor.sendSms(context, target, message)
            "WHATSAPP" -> ActionExecutor.sendWhatsApp(context, target, message, attachment)
            "CALL_SPEAK" -> {
                ActionExecutor.makePhoneCall(context, target)
                Handler(Looper.getMainLooper()).postDelayed({
                    AssistantBridge.speakAndPromptCallback?.invoke(message, false)
                    AssistantBridge.foregroundService?.speakBackground(message)
                }, 8000)
            }
            else -> {}
        }
    }
}

// ============================================================================
// 7. GEMINI AI PARSER
// ============================================================================

suspend fun parseWithGemini(
    context: Context,
    spoken: String,
    apiKey: String,
    modelName: String,
    savedUser: String,
    savedPass: String
) {
    val clean = spoken.trim().lowercase()

    if (clean.startsWith("open ") || clean.startsWith("launch ")) {
        val app = clean.removePrefix("open ").removePrefix("launch ")
        ActionExecutor.openAppByName(context, app)
        AssistantBridge.speakAndPromptCallback?.invoke("Opening $app", false)
        return
    }
    if (clean == "close" || clean == "dismiss") {
        AssistantBridge.notificationService?.dismissLatest()
        AssistantBridge.speakAndPromptCallback?.invoke("Dismissed.", false)
        return
    }

    if (apiKey.isBlank()) {
        AssistantBridge.speakAndPromptCallback?.invoke("Please save your Gemini API Key in Setup.", false)
        return
    }

    val prompt = """
        Parse command into JSON:
        User said: "$spoken"
        Return JSON:
        {
          "action": "DISMISS_NOTIFICATION" | "REPLY_NOTIFICATION" | "SEND_SMS" | "MAKE_CALL" | "SEND_WHATSAPP" | "OPEN_APP" | "AUTO_LOGIN" | "SPEAK_ONLY",
          "spoken_confirm": "short reply",
          "target": "target app, number, contact, or username",
          "secondary_target": "password or subject",
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
                connectTimeout = 10000
                readTimeout = 10000
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

    try {
        val json = JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        val s = json.indexOf('{')
        val e = json.lastIndexOf('}')
        val obj = JSONObject(json.substring(s, e + 1))

        val action = obj.optString("action")
        val confirm = obj.optString("spoken_confirm", "Done.")
        val target = obj.optString("target", "")
        val message = obj.optString("message", "")

        AssistantBridge.speakAndPromptCallback?.invoke(confirm, false)
        withContext(Dispatchers.Main) {
            when (action) {
                "DISMISS_NOTIFICATION" -> AssistantBridge.notificationService?.dismissLatest()
                "REPLY_NOTIFICATION" -> AssistantBridge.notificationService?.replyToLatest(message)
                "SEND_SMS" -> ActionExecutor.sendSms(context, target, message)
                "MAKE_CALL" -> ActionExecutor.makePhoneCall(context, target)
                "SEND_WHATSAPP" -> ActionExecutor.sendWhatsApp(context, target, message)
                "OPEN_APP" -> ActionExecutor.openAppByName(context, target)
                "AUTO_LOGIN" -> {
                    AssistantBridge.pendingLoginUser = target.ifBlank { savedUser }
                    AssistantBridge.pendingLoginPass = obj.optString("secondary_target").ifBlank { savedPass }
                }
                else -> {}
            }
            Unit
        }
    } catch (e: Exception) {
        AssistantBridge.log("Gemini parse err: ${e.localizedMessage}")
    }
}

// ============================================================================
// 8. MAIN ACTIVITY
// ============================================================================

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            tts = TextToSpeech(this, this)
        } catch (e: Exception) {
            AssistantBridge.log("TTS init exception: ${e.localizedMessage}")
        }

        try {
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            }
        } catch (e: Exception) {
            AssistantBridge.log("SpeechRecognizer unavailable: ${e.localizedMessage}")
        }

        // Start Foreground Service safely
        try {
            val serviceIntent = Intent(this, EqualForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            AssistantBridge.log("Service start deferred: ${e.localizedMessage}")
        }

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
                ) {}

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

                EqualAssistantMainScreen(
                    onStartStt = { isOffline, onRecognized ->
                        startSttSession(isOffline, onRecognized)
                    }
                )
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("en", "IN")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == "LISTEN_AFTER") {
                        mainHandler.post {
                            startSttSession(false) { text ->
                                val prefs = getSharedPreferences("equal_prefs", Context.MODE_PRIVATE)
                                val key = prefs.getString("api_key", BuildConfig.GEMINI_API_KEY) ?: ""
                                val model = prefs.getString("model_name", "gemini-2.5-flash") ?: "gemini-2.5-flash"
                                val user = prefs.getString("saved_user", "") ?: ""
                                val pass = prefs.getString("saved_pass", "") ?: ""
                                CoroutineScope(Dispatchers.Main).launch {
                                    parseWithGemini(this@MainActivity, text, key, model, user, pass)
                                }
                            }
                        }
                    }
                }
                override fun onError(id: String?) {}
            })
        }
    }

    fun speakText(text: String, listenAfter: Boolean) {
        val id = if (listenAfter) "LISTEN_AFTER" else "SPEAK_ONLY"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun startSttSession(isOffline: Boolean, onResult: (String) -> Unit) {
        if (speechRecognizer == null) {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            } catch (e: Exception) {
                AssistantBridge.log("Cannot create recognizer: ${e.localizedMessage}")
                return
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            if (isOffline) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val matches = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) onResult(matches[0])
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(r: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    override fun onDestroy() {
        tts?.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}

// ============================================================================
// 9. COMPOSE UI
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualAssistantMainScreen(
    onStartStt: (Boolean, (String) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("equal_prefs", Context.MODE_PRIVATE) }

    var selectedTab by remember { mutableStateOf(0) }
    var universalInput by remember { mutableStateOf("") }
    var isOfflineStt by remember { mutableStateOf(false) }

    var showPreviewDialog by remember { mutableStateOf(false) }
    var previewVoiceText by remember { mutableStateOf("") }

    var apiKey by remember { mutableStateOf(prefs.getString("api_key", BuildConfig.GEMINI_API_KEY) ?: "") }
    var modelName by remember { mutableStateOf(prefs.getString("model_name", "gemini-2.5-flash") ?: "gemini-2.5-flash") }
    var savedUser by remember { mutableStateOf(prefs.getString("saved_user", "") ?: "") }
    var savedPass by remember { mutableStateOf(prefs.getString("saved_pass", "") ?: "") }

    LaunchedEffect(Unit) {
        AssistantBridge.speakAndPromptCallback = { text, listenAfter ->
            (context as? MainActivity)?.speakText(text, listenAfter)
        }
        AssistantBridge.promptInTextBoxCallback = { text ->
            universalInput = text
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
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 6.dp)) {
                        Text(if (isOfflineStt) "Offline" else "Online", fontSize = 11.sp, color = Color.Gray)
                        Switch(
                            checked = isOfflineStt,
                            onCheckedChange = { isOfflineStt = it },
                            modifier = Modifier.scale(0.8f)
                        )
                    }
                    Button(
                        onClick = {
                            onStartStt(isOfflineStt) { recognized ->
                                previewVoiceText = recognized
                                showPreviewDialog = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Speak")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                    label = { Text("Notifications") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Schedule, contentDescription = null) },
                    label = { Text("Scheduler") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.PhoneCallback, contentDescription = null) },
                    label = { Text("Calls") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Setup") }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = universalInput,
                    onValueChange = { universalInput = it },
                    placeholder = { Text("What to do next? (e.g. 'Reply...', 'Call...')") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                )
                IconButton(
                    onClick = {
                        if (universalInput.isNotBlank()) {
                            val cmd = universalInput
                            universalInput = ""
                            scope.launch {
                                parseWithGemini(context, cmd, apiKey, modelName, savedUser, savedPass)
                            }
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
                1 -> SchedulerTab(context = context)
                2 -> CallsAndSummariesTab()
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

    if (showPreviewDialog) {
        AlertDialog(
            onDismissRequest = { showPreviewDialog = false },
            title = { Text("Voice Input Preview (${if (isOfflineStt) "Offline" else "Online"})") },
            text = {
                Column {
                    Text("Check or edit what Equal AI heard before executing:", fontSize = 13.sp, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = previewVoiceText,
                        onValueChange = { previewVoiceText = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPreviewDialog = false
                        val cmd = previewVoiceText
                        scope.launch {
                            parseWithGemini(context, cmd, apiKey, modelName, savedUser, savedPass)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                ) {
                    Text("Execute")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPreviewDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// ============================================================================
// 10. TAB SCREENS
// ============================================================================

@Composable
fun NotificationsTab() {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Speak All Notifications Aloud", fontWeight = FontWeight.Bold)
                    Text("Reads incoming message & asks what to do next", fontSize = 12.sp, color = Color.Gray)
                }
                Switch(
                    checked = AssistantBridge.readNotificationsAloud,
                    onCheckedChange = { AssistantBridge.readNotificationsAloud = it }
                )
            }
        }

        Text("Live Notifications (${AssistantBridge.notifications.size})", fontWeight = FontWeight.Bold)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AssistantBridge.notifications) { item ->
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
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

        Text("Logs", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E293B)).padding(8.dp)
        ) {
            items(AssistantBridge.commandLogs) { log -> Text(log, color = Color(0xFF10B981), fontSize = 11.sp) }
        }
    }
}

@Composable
fun SchedulerTab(context: Context) {
    var target by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var actionType by remember { mutableStateOf("WHATSAPP") }
    var selectedCalendar by remember { mutableStateOf(Calendar.getInstance()) }
    var attachmentUriString by remember { mutableStateOf<String?>(null) }

    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        uri?.let {
            val cursor = context.contentResolver.query(it, null, null, null, null)
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val pCursor = context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        null,
                        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(id),
                        null
                    )
                    pCursor?.use { pc ->
                        if (pc.moveToFirst()) {
                            target = pc.getString(pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                        }
                    }
                }
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        attachmentUriString = uri?.toString()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Schedule Action", fontWeight = FontWeight.Bold, fontSize = 17.sp)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("WHATSAPP" to "WhatsApp", "CALL_SPEAK" to "Call & Speak", "SMS" to "SMS").forEach { (k, v) ->
                FilterChip(selected = actionType == k, onClick = { actionType = k }, label = { Text(v) })
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = target,
                onValueChange = { target = it },
                label = { Text("Contact Number or Name") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            IconButton(onClick = { contactPicker.launch(null) }) {
                Icon(Icons.Default.Contacts, contentDescription = "Import Contact", tint = Color(0xFF10B981))
            }
        }

        Column {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Message text or text to speak") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { filePicker.launch("*/*") }) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Attach File")
                }
                IconButton(onClick = {
                    val recordIntent = Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
                    context.startActivity(Intent.createChooser(recordIntent, "Record Audio"))
                }) {
                    Icon(Icons.Default.Mic, contentDescription = "Record Audio")
                }
                IconButton(onClick = {
                    val videoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
                    context.startActivity(Intent.createChooser(videoIntent, "Record Video"))
                }) {
                    Icon(Icons.Default.Videocam, contentDescription = "Record Video")
                }
                attachmentUriString?.let {
                    Text("Attachment ready", color = Color(0xFF10B981), fontSize = 11.sp)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val now = Calendar.getInstance()
                    DatePickerDialog(context, { _, y, m, d ->
                        selectedCalendar.set(Calendar.YEAR, y)
                        selectedCalendar.set(Calendar.MONTH, m)
                        selectedCalendar.set(Calendar.DAY_OF_MONTH, d)
                    }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
            ) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Pick Date")
            }

            Button(
                onClick = {
                    val now = Calendar.getInstance()
                    TimePickerDialog(context, { _, h, min ->
                        selectedCalendar.set(Calendar.HOUR_OF_DAY, h)
                        selectedCalendar.set(Calendar.MINUTE, min)
                        selectedCalendar.set(Calendar.SECOND, 0)
                    }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
            ) {
                Icon(Icons.Default.AccessTime, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Pick Time")
            }
        }

        Text(
            "Selected: ${SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(selectedCalendar.time)}",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF10B981)
        )

        Button(
            onClick = {
                ActionExecutor.scheduleTask(context, actionType, target, message, selectedCalendar.timeInMillis, attachmentUriString)
                target = ""
                message = ""
                attachmentUriString = null
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
        ) {
            Text("Schedule Task")
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Scheduled Queue", fontWeight = FontWeight.Bold)
        AssistantBridge.scheduledTasks.forEach { task ->
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("${task.actionType} to ${task.target} at ${task.timeLabel}", fontWeight = FontWeight.Bold)
                    Text(task.message, fontSize = 12.sp, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun CallsAndSummariesTab() {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("AI Receptionist Call Summaries", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("Unanswered calls will be automatically received by Equal AI and logged here:", fontSize = 12.sp, color = Color.Gray)

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (AssistantBridge.callLogs.isEmpty()) {
                item {
                    Text("No screened calls yet.", fontSize = 13.sp, color = Color.Gray)
                }
            }
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
                        Text("Full Transcript:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text(call.fullTranscript, fontSize = 12.sp, color = Color.DarkGray)
                    }
                }
            }
        }
    }
}

@Composable
fun SetupTab(
    context: Context,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    modelName: String,
    onModelNameChange: (String) -> Unit,
    savedUser: String,
    onUserChange: (String) -> Unit,
    savedPass: String,
    onPassChange: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("System Permissions", fontWeight = FontWeight.Bold)
        Button(
            onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
        ) {
            Text("Enable Notification Listener")
        }

        Button(
            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
        ) {
            Text("Enable Accessibility / Auto-Login")
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Auto-Login Vault (Optional)", fontWeight = FontWeight.Bold)
        OutlinedTextField(value = savedUser, onValueChange = onUserChange, label = { Text("Default Username") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = savedPass, onValueChange = onPassChange, label = { Text("Default Password") }, modifier = Modifier.fillMaxWidth())

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Gemini API Setup", fontWeight = FontWeight.Bold)
        OutlinedTextField(value = apiKey, onValueChange = onApiKeyChange, label = { Text("AI Studio API Key") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = modelName, onValueChange = onModelNameChange, label = { Text("Model Name (gemini-2.5-flash)") }, modifier = Modifier.fillMaxWidth())
    }
}

fun Modifier.scale(scale: Float): Modifier = this
