package com.equalai.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
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
// 1. DATA MODELS & CENTRAL REPOSITORY
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
    val timeLabel: String
)

object AssistantBridge {
    val notifications = mutableStateListOf<AppNotificationItem>()
    val scheduledTasks = mutableStateListOf<ScheduledActionItem>()
    val commandLogs = mutableStateListOf<String>()

    var readNotificationsAloud by mutableStateOf(true)
    var latestNotification: AppNotificationItem? = null

    var speakAndPromptCallback: ((String, Boolean) -> Unit)? = null

    var pendingLoginUser: String? = null
    var pendingLoginPass: String? = null
    var autoClickSendUntil: Long = 0L

    var notificationService: EqualNotificationService? = null

    fun log(msg: String) {
        val time = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())
        commandLogs.add(0, "[$time] $msg")
    }
}

// ============================================================================
// 2. NOTIFICATION LISTENER SERVICE
// ============================================================================

class EqualNotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        AssistantBridge.notificationService = this
        AssistantBridge.log("Notification Reader connected.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (AssistantBridge.notificationService == this) {
            AssistantBridge.notificationService = null
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.isOngoing) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || pkg == "android" || pkg.contains("systemui")) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return

        val latest = AssistantBridge.latestNotification
        if (latest != null && latest.sender == title && latest.message == text) return

        val appName = try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            pkg.substringAfterLast('.')
        }

        val replyAction = sbn.notification?.actions?.firstOrNull { action ->
            action.remoteInputs?.isNotEmpty() == true
        }

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
                val speech = "Notification from $appName. ${item.sender} says: ${item.message}. What would you like to do next?"
                AssistantBridge.speakAndPromptCallback?.invoke(speech, true)
            }
        }
    }

    fun dismissLatest() {
        val target = AssistantBridge.latestNotification ?: AssistantBridge.notifications.firstOrNull()
        if (target != null) {
            cancelNotification(target.key)
            AssistantBridge.notifications.remove(target)
            AssistantBridge.log("Dismissed notification from ${target.sender}")
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
// 3. ACCESSIBILITY SERVICE (Auto-Login + WhatsApp Auto-Send)
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
                    AssistantBridge.log("Auto-clicked WhatsApp Send button.")
                    break
                }
            }
        }

        val user = AssistantBridge.pendingLoginUser
        val pass = AssistantBridge.pendingLoginPass
        if (!user.isNullOrBlank() || !pass.isNullOrBlank()) {
            performAutoLoginOnNodeTree(root, user ?: "", pass ?: "")
        }
    }

    private fun performAutoLoginOnNodeTree(root: AccessibilityNodeInfo, user: String, pass: String) {
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
        } else if (editNodes.size >= 2 && pass.isNotBlank()) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pass)
            }
            editNodes[1].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            filledAny = true
        }

        if (filledAny) {
            AssistantBridge.pendingLoginUser = null
            AssistantBridge.pendingLoginPass = null
            AssistantBridge.log("Filled login credentials on screen.")

            val loginBtn = clickableNodes.firstOrNull { node ->
                val label = node.ContentDescriptionOrText().lowercase()
                label.contains("log in") || label.contains("login") ||
                    label.contains("sign in") || label.contains("submit") ||
                    label.contains("continue")
            }
            loginBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo?,
        edits: MutableList<AccessibilityNodeInfo>,
        clickables: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return
        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            edits.add(node)
        }
        if (node.isClickable) {
            clickables.add(node)
        }
        for (i in 0 until node.childCount) {
            collectNodes(node.getChild(i), edits, clickables)
        }
    }

    private fun AccessibilityNodeInfo.ContentDescriptionOrText(): String {
        return "${this.text ?: ""} ${this.contentDescription ?: ""}".trim()
    }

    override fun onInterrupt() {}
}

// ============================================================================
// 4. SCHEDULED TASK RECEIVER
// ============================================================================

class ScheduledTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val actionType = intent.getStringExtra("actionType") ?: return
        val target = intent.getStringExtra("target") ?: ""
        val message = intent.getStringExtra("message") ?: ""

        AssistantBridge.log("Executing scheduled task: $actionType -> $target")

        when (actionType) {
            "SMS" -> ActionExecutor.sendSms(context, target, message)
            "WHATSAPP" -> ActionExecutor.sendWhatsApp(context, target, message)
            "CALL_SPEAK" -> {
                ActionExecutor.makePhoneCall(context, target)
                CoroutineScope(Dispatchers.Main).launch {
                    delay(8000)
                    AssistantBridge.speakAndPromptCallback?.invoke(message, false)
                }
            }
            else -> {}
        }
    }
}

// ============================================================================
// 5. CALL SCREENING SERVICE
// ============================================================================

class EqualCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val phoneNumber = callDetails.handle?.schemeSpecificPart ?: "Unknown Number"
        AssistantBridge.log("Incoming call detected from $phoneNumber")

        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()

        respondToCall(callDetails, response)
    }
}

// ============================================================================
// 6. ACTION EXECUTOR & COMMAND ROUTER
// ============================================================================

object ActionExecutor {

    fun resolveContactNumber(context: Context, nameOrNumber: String): String {
        val cleaned = nameOrNumber.trim()
        if (cleaned.any { it.isDigit() } && cleaned.length >= 6) {
            return cleaned.filter { it.isDigit() || it == '+' }
        }
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$cleaned%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numberIdx >= 0) return it.getString(numberIdx)
                }
            }
            cleaned
        } catch (e: Exception) {
            cleaned
        }
    }

    fun makePhoneCall(context: Context, target: String) {
        val number = resolveContactNumber(context, target)
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AssistantBridge.log("Calling $number")
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
            val smsManager = context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
            smsManager.sendTextMessage(number, null, message, null, null)
            AssistantBridge.log("Sent SMS to $number: $message")
        } catch (e: Exception) {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun sendWhatsApp(context: Context, target: String, message: String) {
        if (target.isBlank() && message.isBlank()) {
            openAppByName(context, "whatsapp")
            return
        }
        if (target.isBlank() && AssistantBridge.notificationService?.replyToLatest(message) == true) {
            return
        }
        var number = resolveContactNumber(context, target).filter { it.isDigit() }
        if (number.length == 10) number = "91$number"

        if (number.isBlank()) {
            // If no phone number resolved, open WhatsApp directly with share text
            try {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage("com.whatsapp")
                    putExtra(Intent.EXTRA_TEXT, message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                AssistantBridge.log("Opened WhatsApp with message: $message")
                return
            } catch (e: Exception) {
                openAppByName(context, "whatsapp")
                return
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
            AssistantBridge.log("Opening WhatsApp chat for $number")
        } catch (e: Exception) {
            openAppByName(context, "whatsapp")
        }
    }

    fun sendEmail(context: Context, toEmail: String, subject: String, body: String) {
        try {
            val uri = Uri.parse(
                "mailto:${toEmail.trim()}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"
            )
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AssistantBridge.log("Opened email to $toEmail")
        } catch (e: Exception) {
            AssistantBridge.log("Email error: ${e.localizedMessage}")
        }
    }

    fun openAppByName(context: Context, spokenAppName: String): Boolean {
        val pm = context.packageManager
        val cleanName = spokenAppName
            .lowercase()
            .replace("open ", "")
            .replace("launch ", "")
            .replace("start ", "")
            .replace("app", "")
            .trim()

        // 1. Direct fast-path for popular apps
        val directPackages = mapOf(
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "youtube" to listOf("com.google.android.youtube"),
            "chrome" to listOf("com.android.chrome"),
            "instagram" to listOf("com.instagram.android"),
            "gmail" to listOf("com.google.android.gm"),
            "maps" to listOf("com.google.android.apps.maps"),
            "telegram" to listOf("org.telegram.messenger"),
            "phone" to listOf("com.google.android.dialer", "com.samsung.android.dialer")
        )

        for ((keyword, pkgList) in directPackages) {
            if (cleanName.contains(keyword)) {
                for (pkg in pkgList) {
                    val launchIntent = pm.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        AssistantBridge.log("Opened $keyword ($pkg)")
                        return true
                    }
                }
            }
        }

        // 2. Query all launchable apps on the phone
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val launchables = pm.queryIntentActivities(mainIntent, 0)

        for (resolveInfo in launchables) {
            val label = resolveInfo.loadLabel(pm).toString().lowercase()
            val pkgName = resolveInfo.activityInfo.packageName.lowercase()
            if (label == cleanName || label.contains(cleanName) || cleanName.contains(label) || pkgName.contains(cleanName)) {
                val launchIntent = pm.getLaunchIntentForPackage(resolveInfo.activityInfo.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    AssistantBridge.log("Opened app: ${resolveInfo.loadLabel(pm)}")
                    return true
                }
            }
        }

        AssistantBridge.log("Could not find installed app matching '$cleanName'")
        return false
    }

    fun scheduleTask(
        context: Context,
        actionType: String,
        target: String,
        message: String,
        delayMinutes: Int,
        hour: Int,
        minute: Int
    ) {
        val cal = Calendar.getInstance()
        if (hour in 0..23 && minute in 0..59) {
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)
            if (cal.timeInMillis <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        } else {
            cal.add(Calendar.MINUTE, delayMinutes.coerceAtLeast(1))
        }

        val item = ScheduledActionItem(
            actionType = actionType,
            target = target,
            message = message,
            timeLabel = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(cal.time)
        )
        AssistantBridge.scheduledTasks.add(0, item)

        val intent = Intent(context, ScheduledTaskReceiver::class.java).apply {
            putExtra("actionType", actionType)
            putExtra("target", target)
            putExtra("message", message)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            item.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } catch (e: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
        AssistantBridge.log("Scheduled $actionType to $target at ${item.timeLabel}")
    }
}

fun callGeminiRaw(apiKey: String, modelName: String, promptText: String): String {
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
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", promptText)))
            )
        )
    }

    conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val body = stream?.bufferedReader()?.use { it.readText() } ?: ""

    if (code !in 200..299) return "{\"action\":\"SPEAK_ONLY\",\"spoken_confirm\":\"API Error $code. Check your Gemini API key.\"}"

    val json = JSONObject(body)
    val candidates = json.optJSONArray("candidates")
    if (candidates != null && candidates.length() > 0) {
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
        if (parts != null && parts.length() > 0) {
            return parts.getJSONObject(0).optString("text", "")
        }
    }
    return ""
}

suspend fun processVoiceCommandWithGemini(
    context: Context,
    spokenCommand: String,
    apiKey: String,
    modelName: String,
    savedUser: String,
    savedPass: String
) {
    val cmdTrimmed = spokenCommand.trim()
    val lower = cmdTrimmed.lowercase()
    AssistantBridge.log("Heard: \"$cmdTrimmed\"")

    // =========================================================================
    // STEP 1: INSTANT ON-DEVICE ROUTER (Works with zero lag & without API key)
    // =========================================================================
    if (lower.startsWith("open ") || lower.startsWith("launch ")) {
        val appTarget = lower.removePrefix("open ").removePrefix("launch ").trim()
        val opened = ActionExecutor.openAppByName(context, appTarget)
        if (opened) {
            AssistantBridge.speakAndPromptCallback?.invoke("Opening $appTarget", false)
            return
        }
    }

    if (lower == "whatsapp" || lower == "open whatsapp") {
        ActionExecutor.openAppByName(context, "whatsapp")
        AssistantBridge.speakAndPromptCallback?.invoke("Opening WhatsApp", false)
        return
    }

    if (lower == "close" || lower == "close it" || lower == "dismiss" || lower == "ignore") {
        AssistantBridge.notificationService?.dismissLatest()
        AssistantBridge.speakAndPromptCallback?.invoke("Notification dismissed.", false)
        return
    }

    if (lower.startsWith("call ") && !lower.contains(" at ") && !lower.contains(" in ")) {
        val target = cmdTrimmed.substring(5).trim()
        AssistantBridge.speakAndPromptCallback?.invoke("Calling $target", false)
        ActionExecutor.makePhoneCall(context, target)
        return
    }

    // =========================================================================
    // STEP 2: AI PARSER FOR COMPLEX, CONTEXTUAL, OR SCHEDULED COMMANDS
    // =========================================================================
    if (apiKey.isBlank()) {
        AssistantBridge.speakAndPromptCallback?.invoke(
            "Please paste your Google AI Studio API Key in the Setup tab for complex AI commands.",
            false
        )
        return
    }

    val latestNotif = AssistantBridge.latestNotification
    val notifContext = if (latestNotif != null) {
        "Latest Notification -> App: ${latestNotif.appName}, Sender: ${latestNotif.sender}, Message: ${latestNotif.message}"
    } else {
        "No active notification"
    }

    val prompt = """
        You are an Android system voice assistant. Parse the user's voice command into a single JSON object.
        Current Context: $notifContext
        Saved default login username: "$savedUser", password: "$savedPass"
        User said: "$cmdTrimmed"

        Return ONLY raw valid JSON (no markdown backticks) with these exact keys:
        {
          "action": "DISMISS_NOTIFICATION" | "REPLY_NOTIFICATION" | "SEND_SMS" | "MAKE_CALL" | "SEND_WHATSAPP" | "OPEN_APP" | "AUTO_LOGIN" | "SEND_EMAIL" | "SCHEDULE_TASK" | "SPEAK_ONLY",
          "spoken_confirm": "Short sentence confirming what you are doing",
          "target": "phone number, contact name, email address, app name to open (e.g. WhatsApp), or username for login",
          "secondary_target": "password for login, email subject, or scheduled sub-action (CALL_SPEAK, WHATSAPP, SMS)",
          "message": "message text to send or speak",
          "delay_minutes": 0,
          "scheduled_hour": -1,
          "scheduled_minute": -1
        }
    """.trimIndent()

    val rawResponse = withContext(Dispatchers.IO) {
        try {
            callGeminiRaw(apiKey, modelName, prompt)
        } catch (e: Exception) {
            "{\"action\":\"SPEAK_ONLY\",\"spoken_confirm\":\"Network error: ${e.localizedMessage}\"}"
        }
    }

    // Extract JSON block safely even if Gemini adds extra text around it
    val startIdx = rawResponse.indexOf('{')
    val endIdx = rawResponse.lastIndexOf('}')
    val cleanedJson = if (startIdx >= 0 && endIdx > startIdx) {
        rawResponse.substring(startIdx, endIdx + 1)
    } else {
        rawResponse.replace("```json", "").replace("```", "").trim()
    }

    try {
        val obj = JSONObject(cleanedJson)
        val action = obj.optString("action", "SPEAK_ONLY")
        val confirm = obj.optString("spoken_confirm", "Done.")
        val target = obj.optString("target", "")
        val secondary = obj.optString("secondary_target", "")
        val message = obj.optString("message", "")
        val delayMin = obj.optInt("delay_minutes", 0)
        val hour = obj.optInt("scheduled_hour", -1)
        val minute = obj.optInt("scheduled_minute", -1)

        AssistantBridge.speakAndPromptCallback?.invoke(confirm, false)

        withContext(Dispatchers.Main) {
            when (action) {
                "DISMISS_NOTIFICATION" -> {
                    AssistantBridge.notificationService?.dismissLatest()
                }
                "REPLY_NOTIFICATION" -> {
                    val ok = AssistantBridge.notificationService?.replyToLatest(message) ?: false
                    if (!ok && latestNotif != null) {
                        ActionExecutor.sendSms(context, latestNotif.sender, message)
                    }
                }
                "SEND_SMS" -> {
                    ActionExecutor.sendSms(context, target.ifBlank { latestNotif?.sender ?: "" }, message)
                }
                "MAKE_CALL" -> {
                    ActionExecutor.makePhoneCall(context, target.ifBlank { latestNotif?.sender ?: "" })
                }
                "SEND_WHATSAPP" -> {
                    ActionExecutor.sendWhatsApp(context, target, message)
                }
                "OPEN_APP" -> {
                    ActionExecutor.openAppByName(context, target.ifBlank { cmdTrimmed })
                }
                "AUTO_LOGIN" -> {
                    AssistantBridge.pendingLoginUser = target.ifBlank { savedUser }
                    AssistantBridge.pendingLoginPass = secondary.ifBlank { savedPass }
                    AssistantBridge.log("Armed Auto-Login for user: ${AssistantBridge.pendingLoginUser}")
                }
                "SEND_EMAIL" -> {
                    ActionExecutor.sendEmail(context, target, secondary.ifBlank { "Message from Equal AI" }, message)
                }
                "SCHEDULE_TASK" -> {
                    val subType = if (secondary in listOf("CALL_SPEAK", "WHATSAPP", "SMS")) secondary else "CALL_SPEAK"
                    ActionExecutor.scheduleTask(context, subType, target, message, delayMin, hour, minute)
                }
                else -> {}
            }
            Unit
        }
    } catch (e: Exception) {
        AssistantBridge.log("Parse error: ${e.localizedMessage}")
        AssistantBridge.speakAndPromptCallback?.invoke(rawResponse.take(160), false)
    }
}

// ============================================================================
// 7. MAIN ACTIVITY & JETPACK COMPOSE UI
// ============================================================================

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        AssistantBridge.speakAndPromptCallback = { text, listenAfter ->
            speakText(text, listenAfter)
        }

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
                            Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.CALL_PHONE,
                            Manifest.permission.SEND_SMS,
                            Manifest.permission.READ_CONTACTS
                        )
                    )
                }

                EqualAssistantMainScreen(
                    onTriggerVoiceCommand = { apiKey, model, savedUser, savedPass ->
                        startListeningForCommand(apiKey, model, savedUser, savedPass)
                    },
                    onTestSpeakAndListen = {
                        speakText("What would you like me to do next?", true)
                    }
                )
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("en", "IN")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "PROMPT_LISTEN_AFTER") {
                        val prefs = getSharedPreferences("equal_prefs", Context.MODE_PRIVATE)
                        val apiKey = prefs.getString("api_key", BuildConfig.GEMINI_API_KEY) ?: ""
                        val model = prefs.getString("model_name", "gemini-2.5-flash") ?: "gemini-2.5-flash"
                        val user = prefs.getString("saved_user", "") ?: ""
                        val pass = prefs.getString("saved_pass", "") ?: ""
                        mainHandler.post {
                            startListeningForCommand(apiKey, model, user, pass)
                        }
                    }
                }
                override fun onError(utteranceId: String?) {}
            })
        }
    }

    private fun speakText(text: String, listenAfter: Boolean) {
        val utteranceId = if (listenAfter) "PROMPT_LISTEN_AFTER" else "SPEAK_ONLY_ID"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun startListeningForCommand(
        apiKey: String,
        modelName: String,
        savedUser: String,
        savedPass: String
    ) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val spoken = matches[0]
                    CoroutineScope(Dispatchers.Main).launch {
                        processVoiceCommandWithGemini(
                            this@MainActivity,
                            spoken,
                            apiKey,
                            modelName,
                            savedUser,
                            savedPass
                        )
                    }
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
fun EqualAssistantMainScreen(
    onTriggerVoiceCommand: (String, String, String, String) -> Unit,
    onTestSpeakAndListen: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("equal_prefs", Context.MODE_PRIVATE) }

    var selectedTab by remember { mutableStateOf(0) }
    var typedCommand by remember { mutableStateOf("") }

    var apiKey by remember {
        mutableStateOf(
            prefs.getString("api_key", BuildConfig.GEMINI_API_KEY)?.ifBlank { BuildConfig.GEMINI_API_KEY } ?: ""
        )
    }
    var modelName by remember {
        mutableStateOf(prefs.getString("model_name", "gemini-2.5-flash") ?: "gemini-2.5-flash")
    }
    var savedUser by remember { mutableStateOf(prefs.getString("saved_user", "") ?: "") }
    var savedPass by remember { mutableStateOf(prefs.getString("saved_pass", "") ?: "") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = Color(0xFF10B981))
                        Spacer(Modifier.width(8.dp))
                        Text("Equal AI Voice Agent", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                },
                actions = {
                    Button(
                        onClick = { onTriggerVoiceCommand(apiKey, modelName, savedUser, savedPass) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
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
                    icon = { Icon(Icons.Default.NotificationsActive, contentDescription = null) },
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
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Setup & Keys") }
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
                    value = typedCommand,
                    onValueChange = { typedCommand = it },
                    placeholder = { Text("Say or type: 'Open WhatsApp', 'Call...', 'At 6pm...'") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                IconButton(
                    onClick = {
                        if (typedCommand.isNotBlank()) {
                            val cmd = typedCommand
                            typedCommand = ""
                            scope.launch {
                                processVoiceCommandWithGemini(
                                    context,
                                    cmd,
                                    apiKey,
                                    modelName,
                                    savedUser,
                                    savedPass
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xFF0F172A))
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Run", tint = Color.White)
                }
            }

            Spacer(Modifier.height(12.dp))

            when (selectedTab) {
                0 -> NotificationsAndLogsTab(onTestSpeakAndListen = onTestSpeakAndListen)
                1 -> SchedulerTab(context = context)
                else -> SetupAndVaultTab(
                    context = context,
                    apiKey = apiKey,
                    onApiKeyChange = {
                        apiKey = it
                        prefs.edit().putString("api_key", it).apply()
                    },
                    modelName = modelName,
                    onModelNameChange = {
                        modelName = it
                        prefs.edit().putString("model_name", it).apply()
                    },
                    savedUser = savedUser,
                    onUserChange = {
                        savedUser = it
                        prefs.edit().putString("saved_user", it).apply()
                    },
                    savedPass = savedPass,
                    onPassChange = {
                        savedPass = it
                        prefs.edit().putString("saved_pass", it).apply()
                    }
                )
            }
        }
    }
}

@Composable
fun NotificationsAndLogsTab(onTestSpeakAndListen: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Read All Notifications Aloud", fontWeight = FontWeight.Bold)
                    Text(
                        "Automatically speaks incoming alerts & asks what to do next",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
                Switch(
                    checked = AssistantBridge.readNotificationsAloud,
                    onCheckedChange = { AssistantBridge.readNotificationsAloud = it }
                )
            }
        }

        Text("Live Notifications (${AssistantBridge.notifications.size})", fontWeight = FontWeight.Bold)

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (AssistantBridge.notifications.isEmpty()) {
                item {
                    Text(
                        "No notifications yet. Enable Notification Access in the Setup tab.",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                }
            }
            items(AssistantBridge.notifications) { item ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("${item.appName} • ${item.sender}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(item.timestamp, color = Color.Gray, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(item.message, fontSize = 13.sp)
                    }
                }
            }
        }

        Text("Assistant Action Log", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1E293B))
                .padding(8.dp)
        ) {
            items(AssistantBridge.commandLogs) { log ->
                Text(log, color = Color(0xFF10B981), fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun SchedulerTab(context: Context) {
    var target by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var delayMin by remember { mutableStateOf("5") }
    var actionType by remember { mutableStateOf("CALL_SPEAK") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Schedule Voice Call, WhatsApp, or SMS", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(
            "Schedule by voice (e.g. 'At 6:30 PM WhatsApp Rahul saying I am ready') or add manually below:",
            fontSize = 13.sp,
            color = Color.Gray
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("CALL_SPEAK" to "Call & Speak", "WHATSAPP" to "WhatsApp", "SMS" to "SMS").forEach { (key, label) ->
                FilterChip(
                    selected = actionType == key,
                    onClick = { actionType = key },
                    label = { Text(label) }
                )
            }
        }

        OutlinedTextField(
            value = target,
            onValueChange = { target = it },
            label = { Text("Contact Name or Phone Number") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("Text to Speak or Message to Send") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = delayMin,
            onValueChange = { delayMin = it },
            label = { Text("Run in how many minutes?") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Button(
            onClick = {
                val mins = delayMin.toIntOrNull() ?: 1
                ActionExecutor.scheduleTask(context, actionType, target, message, mins, -1, -1)
                target = ""
                message = ""
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
        ) {
            Icon(Icons.Default.AlarmAdd, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Schedule Task")
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("Upcoming Scheduled Tasks", fontWeight = FontWeight.Bold)

        AssistantBridge.scheduledTasks.forEach { item ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("${item.actionType} -> ${item.target} at ${item.timeLabel}", fontWeight = FontWeight.Bold)
                    Text(item.message, fontSize = 13.sp, color = Color.DarkGray)
                }
            }
        }
    }
}

@Composable
fun SetupAndVaultTab(
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
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("1. One-Time System Permissions", fontWeight = FontWeight.Bold, fontSize = 16.sp)

        Button(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
        ) {
            Icon(Icons.Default.Notifications, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Enable Notification Reader Access")
        }

        Button(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
        ) {
            Icon(Icons.Default.AccessibilityNew, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Enable Auto-Login & UI Accessibility")
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("2. Default Login Credentials (Optional)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "Say 'Login with my credentials' or 'Login with username X and password Y' on any login screen.",
            fontSize = 12.sp,
            color = Color.Gray
        )

        OutlinedTextField(
            value = savedUser,
            onValueChange = onUserChange,
            label = { Text("Default Username / Email") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = savedPass,
            onValueChange = onPassChange,
            label = { Text("Default Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("3. Gemini AI Key & Model", fontWeight = FontWeight.Bold, fontSize = 16.sp)

        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = { Text("Google AI Studio API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = modelName,
            onValueChange = onModelNameChange,
            label = { Text("Model Name (e.g. gemini-2.5-flash)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}
