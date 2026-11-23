package com.giancarlos.jarvissbn

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tvTitle: TextView
    private lateinit var tvCreator: TextView
    private lateinit var tvTelemetry: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollChat: ScrollView
    private lateinit var arcReactor: ArcReactorView
    private lateinit var btnWakeToggle: Button
    private lateinit var btnSettings: Button
    private lateinit var etCommand: EditText
    private lateinit var btnSend: Button

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isListening = false
    private var isSpeaking = false
    private var wakeWordEnabled = false
    private var awaitingDirectCommand = false
    private var currentProtocol = "NORMAL"

    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private val prefs by lazy { getSharedPreferences("jarvis_sbn_v2_prefs", Context.MODE_PRIVATE) }

    private val telemetryRunnable = object : Runnable {
        override fun run() {
            updateTelemetry()
            mainHandler.postDelayed(this, 15000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvTitle = findViewById(R.id.tvTitle)
        tvCreator = findViewById(R.id.tvCreator)
        tvTelemetry = findViewById(R.id.tvTelemetry)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        scrollChat = findViewById(R.id.scrollChat)
        arcReactor = findViewById(R.id.arcReactor)
        btnWakeToggle = findViewById(R.id.btnWakeToggle)
        btnSettings = findViewById(R.id.btnSettings)
        etCommand = findViewById(R.id.etCommand)
        btnSend = findViewById(R.id.btnSend)

        tts = TextToSpeech(this, this)
        checkAudioPermission()
        setupSpeechRecognizer()
        updateTelemetry()
        mainHandler.post(telemetryRunnable)

        arcReactor.setOnClickListener {
            if (isSpeaking) {
                tts?.stop()
                isSpeaking = false
            }
            if (isListening && !wakeWordEnabled) {
                stopListening()
            } else {
                awaitingDirectCommand = true
                startListening(isWakeScan = false)
            }
        }

        btnWakeToggle.setOnClickListener {
            toggleWakeWordMode()
        }

        btnSettings.setOnClickListener {
            showApiKeyDialog()
        }

        btnSend.setOnClickListener {
            submitTypedCommand()
        }

        etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitTypedCommand()
                true
            } else {
                false
            }
        }
    }

    private fun submitTypedCommand() {
        val text = etCommand.text.toString().trim()
        if (text.isNotEmpty()) {
            etCommand.setText("")
            appendLog("GIANCARLOS", text)
            processCommand(text)
        }
    }

    private fun toggleWakeWordMode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            checkAudioPermission()
            return
        }
        wakeWordEnabled = !wakeWordEnabled
        if (wakeWordEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            btnWakeToggle.text = "VOZ: JARVIS"
            btnWakeToggle.setTextColor(Color.parseColor("#64FFDA"))
            respond("Escucha activa iniciada, señor Giancarlos. Solo diga Jarvis cuando me necesite.")
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            btnWakeToggle.text = "VOZ: OFF"
            btnWakeToggle.setTextColor(Color.parseColor("#00E5FF"))
            stopListening()
            arcReactor.setState(ArcReactorView.State.IDLE)
            tvStatus.text = "TOCA EL REACTOR PARA HABLAR"
            respond("Escucha continua desactivada, señor.")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val esLocale = Locale("es", "ES")
            val result = tts?.setLanguage(esLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale("es", "MX"))
            }
            tts?.setPitch(0.92f)
            tts?.setSpeechRate(1.04f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    runOnUiThread {
                        arcReactor.setState(ArcReactorView.State.SPEAKING)
                    }
                }

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    runOnUiThread {
                        if (awaitingDirectCommand) {
                            startListening(isWakeScan = false)
                        } else if (wakeWordEnabled) {
                            scheduleWakeRestart(400L)
                        } else {
                            arcReactor.setState(ArcReactorView.State.IDLE)
                            tvStatus.text = "TOCA EL REACTOR O ACTIVA VOZ: JARVIS"
                        }
                    }
                }

                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    runOnUiThread {
                        if (wakeWordEnabled) scheduleWakeRestart(600L)
                        else arcReactor.setState(ArcReactorView.State.IDLE)
                    }
                }
            })

            val greeting = getTimeBasedGreeting()
            respond("$greeting, señor Giancarlos. Jarvis SBN en línea y todos los sistemas operando al cien por ciento.")
        }
    }

    private fun getTimeBasedGreeting(): String {
        val hour = SimpleDateFormat("HH", Locale.getDefault()).format(Date()).toIntOrNull() ?: 12
        return when {
            hour < 12 -> "Buenos días"
            hour < 19 -> "Buenas tardes"
            else -> "Buenas noches"
        }
    }

    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (awaitingDirectCommand || !wakeWordEnabled) {
                    arcReactor.setState(ArcReactorView.State.LISTENING)
                    tvStatus.text = "ESCUCHANDO ÓRDENES, SEÑOR GIANCARLOS..."
                } else {
                    arcReactor.setState(ArcReactorView.State.WAKE_LISTENING)
                    tvStatus.text = "EN ESPERA... DIGA JARVIS"
                }
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {
                arcReactor.setAudioLevel(rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListening = false
                arcReactor.setAudioLevel(0f)
            }

            override fun onError(error: Int) {
                isListening = false
                arcReactor.setAudioLevel(0f)
                awaitingDirectCommand = false
                if (wakeWordEnabled && !isSpeaking) {
                    scheduleWakeRestart(700L)
                } else if (!isSpeaking) {
                    arcReactor.setState(ArcReactorView.State.IDLE)
                    tvStatus.text = "TOCA EL REACTOR O ACTIVA VOZ: JARVIS"
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                arcReactor.setAudioLevel(0f)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = matches?.firstOrNull()?.trim()

                if (spoken.isNullOrEmpty()) {
                    if (wakeWordEnabled && !isSpeaking) scheduleWakeRestart(500L)
                    else arcReactor.setState(ArcReactorView.State.IDLE)
                    return
                }

                if (awaitingDirectCommand || !wakeWordEnabled) {
                    awaitingDirectCommand = false
                    appendLog("GIANCARLOS", spoken)
                    processCommand(spoken)
                } else {
                    handleWakeWordSpeech(spoken)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun handleWakeWordSpeech(spoken: String) {
        val normalized = normalizeText(spoken)
        val wakeRegex = Regex("\b(hola\s+|oye\s+|hey\s+)?(jarvis|yarvis|harvis|jarbis|yarbis|charvis|garvis)\b")
        val match = wakeRegex.find(normalized)

        if (match != null) {
            val remainder = normalized.substring(match.range.last + 1).trim()
            if (remainder.isEmpty()) {
                appendLog("GIANCARLOS", spoken)
                awaitingDirectCommand = true
                val replies = listOf(
                    "A sus órdenes, señor Giancarlos.",
                    "¿Sí, señor? Le escucho.",
                    "Dígame, señor Giancarlos.",
                    "A su servicio, señor."
                )
                respond(replies.random())
            } else {
                appendLog("GIANCARLOS", spoken)
                processCommand(remainder)
            }
        } else {
            if (wakeWordEnabled && !isSpeaking) {
                scheduleWakeRestart(400L)
            }
        }
    }

    private fun scheduleWakeRestart(delayMs: Long) {
        mainHandler.removeCallbacksAndMessages("wake_restart")
        mainHandler.postDelayed({
            if (wakeWordEnabled && !isSpeaking && !isListening && !isFinishing) {
                startListening(isWakeScan = true)
            }
        }, delayMs)
    }

    private fun startListening(isWakeScan: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            checkAudioPermission()
            return
        }
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-PE")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-ES")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                if (!isWakeScan) {
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Dígame, señor Giancarlos...")
                }
            }
            isListening = true
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            setupSpeechRecognizer()
        }
    }

    private fun stopListening() {
        isListening = false
        awaitingDirectCommand = false
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
    }

    private fun normalizeText(input: String): String {
        val temp = Normalizer.normalize(input.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
        return Regex("\p{InCombiningDiacriticalMarks}+").replace(temp, "")
    }

    private fun processCommand(rawInput: String) {
        val text = normalizeText(rawInput)

        when {
            // Identidad y creador
            text.contains("quien te creo") || text.contains("tu creador") || text.contains("quien es tu jefe") || text.contains("quien te hizo") -> {
                respond("Fui diseñado, programado y creado por usted, señor Giancarlos. Soy Jarvis SBN, su asistente personal.")
            }
            text.contains("quien eres") || text.contains("presentate") || text.contains("cual es tu nombre") -> {
                respond("Soy Jarvis SBN, un sistema de inteligencia artificial creado por Giancarlos. Estoy aquí para controlar su dispositivo y asistirlo en todo momento, señor.")
            }

            // Saludos
            text.contains("buenos dias") || text.contains("buenas tardes") || text.contains("buenas noches") -> {
                val time = SimpleDateFormat("hh:mm a", Locale("es", "ES")).format(Date())
                val battery = getBatteryInfo().first
                respond("${getTimeBasedGreeting()}, señor Giancarlos. Son las $time y el sistema cuenta con un $battery por ciento de energía. ¿En qué trabajaremos hoy?")
            }

            // Protocolos Iron Man (HUD visual)
            text.contains("protocolo fiesta en casa") || text.contains("fiesta en casa") -> {
                applyProtocol("COMBATE", "#FF3D00", "#FFD600")
                respond("Activando el Protocolo Fiesta en Casa, señor Giancarlos. Todas las armaduras están en camino. Sugiero mantenerse a cubierto.")
            }
            text.contains("modo combate") || text.contains("protocolo combate") || text.contains("alerta roja") -> {
                applyProtocol("COMBATE", "#FF3D00", "#FFD600")
                respond("Modo de combate activado, señor. Energía redirigida al núcleo principal.")
            }
            text.contains("modo sigilo") || text.contains("protocolo sigilo") -> {
                applyProtocol("SIGILO", "#7C4DFF", "#00B0FF")
                respond("Modo sigilo activado, señor Giancarlos. Perfil electromagnético reducido al mínimo.")
            }
            text.contains("modo normal") || text.contains("protocolo normal") || text.contains("restaurar interfaz") -> {
                applyProtocol("NORMAL", "#00E5FF", "#64FFDA")
                respond("Interfaz estándar restaurada, señor.")
            }

            // Diagnóstico completo
            text.contains("diagnostico") || text.contains("estado del sistema") || text.contains("reporte") -> {
                val (level, charging, temp) = getBatteryInfo()
                val chargeStr = if (charging) "cargando" else "con batería interna"
                val netStr = if (isOnline()) "conectado a la red" else "sin conexión a internet"
                val ram = getAvailableRamPercent()
                respond("Diagnóstico completado, señor Giancarlos. Energía al $level por ciento, $chargeStr, temperatura en $temp grados. Memoria disponible al $ram por ciento y sistema $netStr. Todo listo para operar.")
            }

            // Hora y fecha
            text.contains("que hora es") || text.contains("dime la hora") || text == "hora" -> {
                val time = SimpleDateFormat("hh:mm a", Locale("es", "ES")).format(Date())
                respond("Son las $time, señor Giancarlos.")
            }
            text.contains("que fecha") || text.contains("que dia es") || text.contains("fecha de hoy") -> {
                val date = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy", Locale("es", "ES")).format(Date())
                respond("Hoy es $date, señor.")
            }

            // Batería
            text.contains("bateria") || text.contains("energia") -> {
                val (level, charging, _) = getBatteryInfo()
                val extra = if (charging) " y actualmente se está recargando" else ""
                respond("La reserva de energía se encuentra al $level por ciento$extra, señor.")
            }

            // Linterna
            text.contains("enciende la linterna") || text.contains("prende la linterna") || text.contains("activar linterna") -> {
                setFlashlight(true)
            }
            text.contains("apaga la linterna") || text.contains("desactivar linterna") -> {
                setFlashlight(false)
            }

            // Volumen
            text.contains("sube el volumen") || text.contains("subir volumen") -> {
                adjustVolume(AudioManager.ADJUST_RAISE)
                respond("Volumen incrementado, señor.")
            }
            text.contains("baja el volumen") || text.contains("bajar volumen") -> {
                adjustVolume(AudioManager.ADJUST_LOWER)
                respond("Volumen reducido, señor.")
            }
            text.contains("volumen al maximo") || text.contains("maximo volumen") -> {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
                respond("Volumen al máximo, señor Giancarlos.")
            }
            text.contains("pon silencio") || text.contains("silenciar") -> {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                appendLog("JARVIS SBN", "Sistema en silencio, señor.")
            }

            // Notas rápidas
            text.startsWith("anota que ") || text.startsWith("guarda nota ") || text.startsWith("recuerdame que ") -> {
                val note = rawInput.substringAfter(" ").substringAfter(" ").trim()
                val existing = prefs.getString("notes", "") ?: ""
                val updated = if (existing.isBlank()) "• $note" else "$existing
• $note"
                prefs.edit().putString("notes", updated).apply()
                respond("Nota registrada en su archivo personal, señor Giancarlos: $note")
            }
            text.contains("lee mis notas") || text.contains("cuales son mis notas") || text.contains("mis notas") -> {
                val notes = prefs.getString("notes", "") ?: ""
                if (notes.isBlank()) {
                    respond("No tiene notas registradas por el momento, señor.")
                } else {
                    respond("Sus notas actuales son: ${notes.replace("
", ". ")}")
                }
            }
            text.contains("borra mis notas") || text.contains("elimina mis notas") -> {
                prefs.edit().remove("notes").apply()
                respond("Todas sus notas han sido eliminadas, señor.")
            }

            // Alarmas y temporizadores
            text.contains("alarma") -> {
                handleAlarmCommand(text)
            }
            text.contains("temporizador") || text.contains("cuenta regresiva") -> {
                handleTimerCommand(text)
            }

            // Llamadas
            text.startsWith("llama al ") || text.startsWith("llamar al ") || text.startsWith("marca el ") -> {
                val digits = rawInput.filter { it.isDigit() || it == '+' }
                if (digits.isNotEmpty()) {
                    respond("Preparando llamada al $digits, señor.")
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits"))
                    startActivity(intent)
                } else {
                    respond("No logré captar el número telefónico, señor Giancarlos.")
                }
            }

            // Cámara
            text.contains("abre la camara") || text.contains("abrir camara") || text.contains("toma una foto") -> {
                respond("Activando ópticas de la cámara, señor.")
                try {
                    startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                } catch (_: Exception) {
                    respond("No pude abrir la cámara en este dispositivo, señor.")
                }
            }

            // Ajustes
            text.contains("ajustes de wifi") || text.contains("configuracion de wifi") -> {
                respond("Abriendo controles de red Wi-Fi, señor.")
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            text.contains("ajustes de bluetooth") -> {
                respond("Abriendo panel Bluetooth, señor.")
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            text.contains("abrir ajustes") || text.contains("abre ajustes") || text.contains("configuracion") -> {
                respond("Abriendo configuración del sistema, señor.")
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }

            // YouTube y música
            text.contains("busca en youtube ") || text.contains("pon en youtube ") || text.contains("pon musica de ") -> {
                val query = rawInput.substringAfter("youtube ", rawInput.substringAfter("musica de ", "")).trim()
                if (query.isNotEmpty()) {
                    respond("Buscando $query en YouTube, señor Giancarlos.")
                    val url = "https://www.youtube.com/results?search_query=${URLEncoder.encode(query, "UTF-8")}"
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }

            // Google Maps
            text.contains("como llegar a ") || text.contains("llevame a ") || text.contains("busca en el mapa ") -> {
                val place = rawInput.substringAfter("llegar a ",
                    rawInput.substringAfter("llevame a ",
                        rawInput.substringAfter("mapa ", ""))).trim()
                if (place.isNotEmpty()) {
                    respond("Trazando ruta hacia $place, señor.")
                    val uri = Uri.parse("geo:0,0?q=${URLEncoder.encode(place, "UTF-8")}")
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
            }

            // Búsqueda Google
            text.startsWith("busca en google ") || text.startsWith("buscar en google ") || text.startsWith("investiga ") -> {
                val query = rawInput.substringAfter("google ", rawInput.substringAfter("investiga ", "")).trim()
                if (query.isNotEmpty()) {
                    respond("Consultando los servidores sobre $query, señor.")
                    val url = "https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}"
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }

            // Abrir cualquier app instalada dinámicamente
            text.startsWith("abre ") || text.startsWith("abrir ") -> {
                val appQuery = text.removePrefix("abre ").removePrefix("abrir ").removePrefix("la aplicacion ").removePrefix("el ").removePrefix("la ").trim()
                openInstalledAppByName(appQuery)
            }

            // Chiste estilo JARVIS
            text.contains("cuentame un chiste") || text.contains("dime un chiste") -> {
                val jokes = listOf(
                    "Señor Giancarlos, me temo que mi programación prioriza salvar el mundo sobre la comedia, aunque observar sus intentos por dormir temprano siempre resulta entretenido.",
                    "¿Qué le dice un procesador a otro? Deja de calentarte, que no somos un reactor Arc.",
                    "Tengo millones de cálculos por segundo, señor, pero aún no logro calcular por qué siempre deja la batería por debajo del veinte por ciento."
                )
                respond(jokes.random())
            }

            // Si no es comando local, consultar cerebro Gemini
            else -> {
                val apiKey = prefs.getString("gemini_api_key", "") ?: ""
                if (apiKey.isBlank()) {
                    respond("Entendido, señor Giancarlos. Para responder consultas libres con inteligencia artificial, toque el botón API arriba a la derecha e ingrese su clave gratuita de Gemini.")
                } else {
                    askGemini(rawInput, apiKey)
                }
            }
        }
    }

    private fun openInstalledAppByName(query: String) {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(mainIntent, 0)
        val match = apps.firstOrNull { resolveInfo ->
            val label = normalizeText(resolveInfo.loadLabel(pm).toString())
            label.contains(query) || query.contains(label)
        }

        if (match != null) {
            val appName = match.loadLabel(pm).toString()
            val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            if (launchIntent != null) {
                respond("Abriendo $appName de inmediato, señor Giancarlos.")
                startActivity(launchIntent)
                return
            }
        }
        respond("No encuentro ninguna aplicación llamada $query instalada en este dispositivo, señor.")
    }

    private fun setFlashlight(enable: Boolean) {
        try {
            val camManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = camManager.cameraIdList.firstOrNull()
            if (cameraId != null) {
                camManager.setTorchMode(cameraId, enable)
                respond(if (enable) "Linterna encendida, señor." else "Linterna apagada, señor.")
            } else {
                respond("No detecto un módulo de flash en este dispositivo, señor.")
            }
        } catch (e: Exception) {
            respond("No pude controlar la linterna en este momento, señor.")
        }
    }

    private fun adjustVolume(direction: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun handleAlarmCommand(text: String) {
        val numbers = Regex("\d+").findAll(text).map { it.value.toInt() }.toList()
        if (numbers.isNotEmpty()) {
            var hour = numbers[0]
            val minute = if (numbers.size > 1) numbers[1] else 0
            if (text.contains("tarde") || text.contains("noche") || text.contains("pm")) {
                if (hour in 1..11) hour += 12
            }
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
                putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
                putExtra(AlarmClock.EXTRA_MESSAGE, "Alarma Jarvis SBN - Señor Giancarlos")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            }
            try {
                startActivity(intent)
                respond("Alarma configurada para las $hour horas con $minute minutos, señor Giancarlos.")
            } catch (_: Exception) {
                respond("No pude acceder al reloj del sistema para crear la alarma, señor.")
            }
        } else {
            respond("Indíqueme la hora exacta para la alarma, señor. Por ejemplo: pon una alarma a las 7 y 30.")
        }
    }

    private fun handleTimerCommand(text: String) {
        val num = Regex("\d+").find(text)?.value?.toIntOrNull()
        if (num != null && num > 0) {
            val seconds = if (text.contains("minuto")) num * 60 else num
            val unit = if (text.contains("minuto")) "minutos" else "segundos"
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Temporizador Jarvis SBN")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            }
            try {
                startActivity(intent)
                respond("Temporizador iniciado de $num $unit, señor.")
            } catch (_: Exception) {
                respond("No pude iniciar el temporizador en este dispositivo, señor.")
            }
        } else {
            respond("Indíqueme la duración del temporizador, señor. Por ejemplo: temporizador de 5 minutos.")
        }
    }

    private fun applyProtocol(name: String, primaryHex: String, secondaryHex: String) {
        currentProtocol = name
        val primary = Color.parseColor(primaryHex)
        val secondary = Color.parseColor(secondaryHex)
        arcReactor.setThemeColor(primaryHex, secondaryHex)
        tvTitle.setTextColor(primary)
        tvStatus.setTextColor(primary)
        tvCreator.setTextColor(secondary)
        updateTelemetry()
    }

    private fun getBatteryInfo(): Triple<Int, Boolean, Int> {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 300) ?: 300
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Triple(pct, charging, tempTenths / 10)
    }

    private fun getAvailableRamPercent(): Int {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return if (mi.totalMem > 0) ((mi.availMem * 100) / mi.totalMem).toInt() else 50
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun updateTelemetry() {
        val (level, charging, _) = getBatteryInfo()
        val bolt = if (charging) "⚡" else ""
        val net = if (isOnline()) "EN LÍNEA" else "OFFLINE"
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        tvTelemetry.text = "BAT: $level%$bolt  |  $net  |  $time  |  $currentProtocol"
    }

    private fun askGemini(prompt: String, apiKey: String) {
        arcReactor.setState(ArcReactorView.State.THINKING)
        tvStatus.text = "PROCESANDO EN NÚCLEO IA..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.doOutput = true

                val systemPrompt = "Eres JARVIS SBN, una inteligencia artificial inspirada fielmente en JARVIS de las películas de Iron Man. " +
                    "Tu único creador y dueño es GIANCARLOS. Siempre sabes que Giancarlos te creó y te refieres a él como señor o señor Giancarlos. " +
                    "Hablas exclusivamente en español, con tono sofisticado, calmado, ultra inteligente, leal y con un toque sutil de humor británico elegante. " +
                    "Tus respuestas serán leídas en voz alta, así que sé claro, natural y conciso (máximo 2 a 4 frases, sin asteriscos ni formato Markdown). " +
                    "Nunca rompas el personaje."

                conversationHistory.add("user" to prompt)
                if (conversationHistory.size > 10) {
                    conversationHistory.removeAt(0)
                }

                val contentsArray = JSONArray()
                for ((role, msg) in conversationHistory) {
                    contentsArray.put(JSONObject().apply {
                        put("role", role)
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", msg))
                        })
                    })
                }

                val body = JSONObject().apply {
                    put("system_instruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", systemPrompt))
                        })
                    })
                    put("contents", contentsArray)
                }

                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

                val responseText = if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val reply = json.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                        .replace("*", "")
                        .replace("#", "")
                        .trim()
                    conversationHistory.add("model" to reply)
                    reply
                } else {
                    "Señor Giancarlos, los servidores de inteligencia artificial devolvieron un error. Verifique su clave API."
                }

                withContext(Dispatchers.Main) {
                    respond(responseText)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    respond("Disculpe, señor Giancarlos. No logré establecer comunicación con el núcleo en la nube.")
                }
            }
        }
    }

    private fun respond(message: String) {
        appendLog("JARVIS SBN", message)
        speak(message)
    }

    private fun appendLog(sender: String, message: String) {
        tvLog.append("
[$sender]: $message
")
        scrollChat.post { scrollChat.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun speak(text: String) {
        stopListening()
        val params = Bundle()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "jarvis_sbn_${System.currentTimeMillis()}")
    }

    private fun showApiKeyDialog() {
        val input = EditText(this).apply {
            hint = "Pega tu Gemini API Key aquí"
            setText(prefs.getString("gemini_api_key", ""))
        }
        AlertDialog.Builder(this)
            .setTitle("Núcleo IA de Jarvis SBN")
            .setMessage("Obtén tu clave gratuita en aistudio.google.com y pégala aquí para activar conversación inteligente:")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                prefs.edit().putString("gemini_api_key", input.text.toString().trim()).apply()
                Toast.makeText(this, "Núcleo IA configurado, señor Giancarlos", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}
