package com.example.aiassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import org.json.JSONArray

class FloatingService : Service() {

    private lateinit var wm: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var bubble: View? = null
    private var panel: View? = null
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var lastAnswer = ""

    // История диалога: пары (роль, текст)
    private val history = mutableListOf<Pair<String, String>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        loadHistory()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.setLanguage(Locale.getDefault())
        }
        showBubble()
    }

    private fun prefs() = getSharedPreferences("cfg", MODE_PRIVATE)

    private fun loadHistory() {
        history.clear()
        val raw = prefs().getString("chat_history", "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                history.add(o.getString("role") to o.getString("text"))
            }
        } catch (_: Exception) {}
    }

    private fun saveHistory() {
        val arr = JSONArray()
        history.takeLast(30).forEach { (role, text) ->
            arr.put(org.json.JSONObject().put("role", role).put("text", text))
        }
        prefs().edit().putString("chat_history", arr.toString()).apply()
    }

    private fun buildNotification(): Notification {
        val ch = NotificationChannel("svc", "AI Assistant", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "svc")
            .setContentTitle("AI Assistant активен")
            .setContentText("Нажми на пузырь, чтобы задать вопрос")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .build()
    }

    private fun layoutParams(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun showBubble() {
        if (bubble != null) return
        val b = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(160, 160)
            setBackgroundColor(Color.parseColor("#6750A4"))
            elevation = 10f
            addView(TextView(context).apply {
                text = "AI"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
        val lp = layoutParams(160, 160).apply { x = 40; y = 400 }
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0
        b.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX - lp.x; dy = e.rawY - lp.y; sx = e.rawX.toInt(); sy = e.rawY.toInt() }
                MotionEvent.ACTION_MOVE -> { lp.x = (e.rawX - dx).toInt(); lp.y = (e.rawY - dy).toInt(); wm.updateViewLayout(v, lp) }
                MotionEvent.ACTION_UP ->
                    if (Math.abs(e.rawX - sx) < 12 && Math.abs(e.rawY - sy) < 12) openPanel()
            }
            true
        }
        wm.addView(b, lp)
        bubble = b
    }

    private fun openPanel() {
        if (panel != null) return
        bubble?.let { wm.removeView(it); bubble = null }

        val panelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F7"))
            setPadding(24, 24, 24, 24)
        }

        val chat = TextView(this).apply {
            setTextColor(Color.BLACK); textSize = 14f
            movementMethod = ScrollingMovementMethod()
        }
        history.forEach { (role, text) ->
            chat.append(if (role == "user") "Вы: $text\n" else "AI: $text\n")
        }

        val input = EditText(this).apply { hint = "Напиши или скажи команду…" }

        fun executeAction(action: String): String {
            val m = Regex("\\[(CLICK|SWIPE|TYPE|SCROLL):\\s*(.+?)\\]").find(action) ?: return ""
            val (cmd, arg) = m.destructured
            return when (cmd) {
                "CLICK" -> if (AssistantAccessibilityService.clickByText(arg)) "✅ нажал: $arg" else "❌ не нашёл: $arg"
                "SWIPE", "SCROLL" -> if (AssistantAccessibilityService.swipe(arg)) "✅ свайп: $arg" else "❌ свайп не выполнен"
                "TYPE" -> if (AssistantAccessibilityService.typeText(arg)) "✅ ввёл текст" else "❌ нет поля ввода"
                else -> ""
            }
        }

        fun send() {
            val q = input.text.toString().trim()
            if (q.isEmpty()) return
            chat.append("\nВы: $q\n")
            history.add("user" to q)
            saveHistory()
            input.setText("")
            chat.append("AI: думаю...\n")
            val key = prefs().getString("api_key", "") ?: ""
            if (key.isBlank()) { chat.append("Сначала введи API-ключ в приложении!\n"); return }
            GeminiApi.ask(key, history.toList(), AssistantAccessibilityService.screenText()) { answer ->
                mainHandler.post {
                    val actionResult = executeAction(answer)
                    val clean = answer.replace(Regex("\\[(CLICK|SWIPE|TYPE|SCROLL):.+?\\]"), "").trim()
                    lastAnswer = clean
                    chat.append("AI: $clean\n")
                    if (actionResult.isNotEmpty()) chat.append("$actionResult\n")
                    history.add("model" to (clean + if (actionResult.isNotEmpty()) " ($actionResult)" else ""))
                    saveHistory()
                    scrollToBottom(chat)
                }
            }
        }

        fun startVoice() {
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                recognizer?.destroy()
                recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onResults(results: Bundle) {
                            val text = results.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            if (!text.isNullOrBlank()) { input.setText(text); send() }
                            else input.setText("")
                        }
                        override fun onError(error: Int) { input.setText(""); input.hint = "Ошибка распознавания, повтори" }
                        override fun onReadyForSpeech(params: Bundle?) { input.hint = "Слушаю…" }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() { input.hint = "Напиши или скажи команду…" }
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    startListening(RecognizerIntent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    })
                }
            } else input.hint = "Распознавание недоступно на устройстве"
        }

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun smallBtn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { onClick() }
        }
        btnRow.addView(smallBtn("Отправить") { send() }, LinearLayout.LayoutParams(0, -2, 1.4f))
        btnRow.addView(smallBtn("🎤") { startVoice() }, LinearLayout.LayoutParams(0, -2, 0.8f))
        btnRow.addView(smallBtn("🔊") { if (lastAnswer.isNotBlank()) tts?.speak(lastAnswer, TextToSpeech.QUEUE_FLUSH, null, "a") }, LinearLayout.LayoutParams(0, -2, 0.8f))
        btnRow.addView(smallBtn("Очистить") {
            history.clear(); saveHistory(); chat.text = ""
        }, LinearLayout.LayoutParams(0, -2, 1.2f))
        btnRow.addView(smallBtn("✖") { closePanel() }, LinearLayout.LayoutParams(0, -2, 0.8f))

        panelView.addView(chat, LinearLayout.LayoutParams(-1, 0, 1f))
        panelView.addView(input, LinearLayout.LayoutParams(-1, -2))
        panelView.addView(btnRow, LinearLayout.LayoutParams(-1, -2))

        wm.addView(panelView, layoutParams(-1, 1200).apply { x = 0; y = 200 })
        panel = panelView
        scrollToBottom(chat)
    }

    private fun scrollToBottom(tv: TextView) = mainHandler.post { tv.scrollTo(0, 0) }

    private fun closePanel() {
        panel?.let { wm.removeView(it); panel = null }
        showBubble()
    }

    override fun onDestroy() {
        bubble?.let { runCatching { wm.removeView(it) } }
        panel?.let { runCatching { wm.removeView(it) } }
        recognizer?.destroy()
        tts?.stop(); tts?.shutdown()
        super.onDestroy()
    }
}