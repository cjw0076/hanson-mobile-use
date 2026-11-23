package com.hanson.mobileuse.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.hanson.mobileuse.BuildConfig
import com.hanson.mobileuse.service.FloatingHudService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

enum class LiveVoiceMode(val labelKo: String, val badge: String) {
    ON_DEVICE_DUPLEX("실시간 양방향 보이스 (Full-Duplex)", "LIVE VOICE"),
    GEMINI_LIVE_V1BETA("Gemini Live 실시간 오디오", "GEMINI LIVE"),
    GEMINI_3_8_LIVE("Gemini 3.8 Live Extended Thinking", "3.8 LIVE")
}

enum class LiveVoicePhase(val labelKo: String) {
    OFF("보이스 대기"),
    CONNECTING("Gemini Live 연결 중..."),
    LISTENING("듣고 있습니다... 편하게 말씀하세요"),
    THINKING("화면을 보며 생각 중..."),
    SPEAKING("대답하는 중 (말씀하시면 즉시 듣습니다)"),
    ERROR("마이크/음성 오류")
}

data class LiveVoiceState(
    val isActive: Boolean = false,
    val mode: LiveVoiceMode = LiveVoiceMode.ON_DEVICE_DUPLEX,
    val phase: LiveVoicePhase = LiveVoicePhase.OFF,
    val partialTranscript: String = "",
    val lastSpokenResponse: String = "",
    val audioLevelRms: Float = 0f,
    val onDeviceRecognizerAvailable: Boolean = false,
    val standardRecognizerAvailable: Boolean = false,
    val hardwareAecAvailable: Boolean = false,
    val hardwareNsAvailable: Boolean = false,
    val detectedEngines: List<String> = emptyList(),
    val errorMessage: String? = null,
    val liveOutputTranscript: String = "",
    val isWebSocketConnected: Boolean = false,
    val activeLiveModel: String = LiveVoiceSessionManager.DEFAULT_GEMINI_LIVE_MODEL
)

/**
 * Full-Duplex Conversational Voice Coordinator (`LiveVoiceSessionManager`).
 *
 * Dual-path real-time voice architecture:
 * 1. **Primary Path — Official Gemini Live API v1beta WebSocket**:
 *    - Connects via OkHttp WebSocket to `BidiGenerateContent` authenticating strictly through
 *      the `x-goog-api-key` header (never URL query parameters).
 *    - Full-duplex 16kHz 16-bit mono PCM capture via `AudioRecord` with hardware
 *      `AcousticEchoCanceler` and `NoiseSuppressor` attached to the audio session.
 *    - Streams PCM chunks via `realtimeInput.mediaChunks` (`audio/pcm;rate=16000`).
 *    - Plays 24kHz PCM responses via `AudioTrack` (`USAGE_VOICE_COMMUNICATION` / `CONTENT_TYPE_SPEECH`)
 *      with immediate sub-200ms barge-in flush on `serverContent.interrupted == true`.
 *    - Streams `inputTranscription` & `outputTranscription` into UI state and emits completed
 *      user transcripts via `userTranscriptFlow` (`SharedFlow<String>`).
 * 2. **Hardened Fallback Path — Android `SpeechRecognizer` + `TextToSpeech`**:
 *    - Enforces `Manifest.permission.RECORD_AUDIO` before audio capture or recognition.
 *    - Stops immediately on fatal recognizer errors (`ERROR_INSUFFICIENT_PERMISSIONS`,
 *      `ERROR_AUDIO`, `ERROR_CLIENT`) with explicit Korean error state (no 450ms infinite loop).
 *    - Tracks and cancels all pending `Handler` retry runnables to prevent `ERROR_RECOGNIZER_BUSY`.
 *    - Enforces an 8-second `THINKING` watchdog timer so the state machine never stalls.
 */
object LiveVoiceSessionManager {

    const val GEMINI_LIVE_WEBSOCKET_ENDPOINT =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    const val DEFAULT_GEMINI_LIVE_MODEL = "models/gemini-2.5-flash-native-audio-preview"

    const val DEFAULT_LIVE_SYSTEM_INSTRUCTION =
        "당신은 친절하고 간결한 한국어 실시간 모바일 AI 동행 어시스턴트 Hanson입니다. " +
            "사용자의 말을 자연스럽게 이해하고 핵심을 짧고 명확한 한국어 구어체로 답하세요."

    const val AUDIO_INPUT_SAMPLE_RATE = 16000
    const val AUDIO_INPUT_BITS_PER_SAMPLE = 16
    const val AUDIO_INPUT_CHANNELS = 1
    const val AUDIO_INPUT_BYTE_RATE =
        AUDIO_INPUT_SAMPLE_RATE * (AUDIO_INPUT_BITS_PER_SAMPLE / 8) * AUDIO_INPUT_CHANNELS // 32000 B/s

    const val AUDIO_OUTPUT_SAMPLE_RATE = 24000
    const val AUDIO_OUTPUT_BITS_PER_SAMPLE = 16
    const val AUDIO_OUTPUT_CHANNELS = 1
    const val AUDIO_OUTPUT_BYTE_RATE =
        AUDIO_OUTPUT_SAMPLE_RATE * (AUDIO_OUTPUT_BITS_PER_SAMPLE / 8) * AUDIO_OUTPUT_CHANNELS // 48000 B/s

    const val MAX_BARGE_IN_LATENCY_MS = 200L
    const val THINKING_WATCHDOG_TIMEOUT_MS = 8_000L

    private const val AUDIO_CAPTURE_CHUNK_BYTES = 1280 // 40ms @ 16kHz 16-bit mono
    private const val MAX_CONSECUTIVE_RECOGNIZER_ERRORS = 5
    private const val MIC_PERMISSION_REQUIRED_KO = "마이크 권한이 필요해요"

    private val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val wsHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    private val audioOutputQueue = Collections.synchronizedList(mutableListOf<ByteArray>())

    @Volatile
    private var playbackWakeChannel = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var lastPongMs = System.currentTimeMillis()

    @Volatile
    private var isMicMuted = false

    @Volatile
    private var reconnectCount = 0

    @Volatile
    private var isWebSocketConnected = false

    private val isSetupComplete = AtomicBoolean(false)
    private val activeSessionGeneration = AtomicLong(0L)

    @Volatile
    private var activeWebSocket: WebSocket? = null

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var acousticEchoCanceler: AcousticEchoCanceler? = null

    @Volatile
    private var noiseSuppressor: NoiseSuppressor? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    private val audioCaptureLifecycleLock = Any()
    private val audioTrackLifecycleLock = Any()
    private val transcriptionLock = Any()
    private val handlerTaskLock = Any()

    private var liveAudioCaptureJob: Job? = null
    private var liveAudioPlaybackJob: Job? = null

    private val pendingInputTranscript = StringBuilder()
    private val pendingOutputTranscript = StringBuilder()

    @Volatile
    private var configuredGeminiApiKey: String? = null

    @Volatile
    private var configuredLiveModel: String = DEFAULT_GEMINI_LIVE_MODEL

    private val mainHandler: Handler? by lazy {
        runCatching {
            val looper = Looper.getMainLooper()
            if (looper != null) Handler(looper) else null
        }.getOrNull()
    }

    private var pendingListeningRunnable: Runnable? = null
    private var pendingReconnectRunnable: Runnable? = null
    private var thinkingWatchdogRunnable: Runnable? = null
    private var consecutiveRecognizerErrors = 0

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var appContext: Context? = null

    // Scoped transcript delivery: SharedFlow + non-clobbering callbacks
    private val _userTranscriptFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val userTranscriptFlow: SharedFlow<String> = _userTranscriptFlow.asSharedFlow()

    private val transcriptListeners = ConcurrentHashMap<String, (String) -> Unit>()

    @Volatile
    private var defaultTranscriptCallback: ((String) -> Unit)? = null

    @Volatile
    private var activeSessionCallback: ((String) -> Unit)? = null

    private val _state = MutableStateFlow(LiveVoiceState())
    val state: StateFlow<LiveVoiceState> = _state.asStateFlow()

    // =========================================================================
    // Public Protocol Helpers & Testable Primitives
    // =========================================================================

    @JvmOverloads
    fun buildLiveSetupHandshake(
        model: String = DEFAULT_GEMINI_LIVE_MODEL,
        systemInstruction: String = DEFAULT_LIVE_SYSTEM_INSTRUCTION
    ): JSONObject {
        val trimmed = model.trim().ifBlank { DEFAULT_GEMINI_LIVE_MODEL }
        val normalizedModel = if (trimmed.startsWith("models/")) trimmed else "models/$trimmed"
        return JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", normalizedModel)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray(listOf("AUDIO")))
                })
                if (systemInstruction.isNotBlank()) {
                    put("systemInstruction", JSONObject().apply {
                        put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", systemInstruction))
                        )
                    })
                }
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
            })
        }
    }

    fun buildRealtimeAudioChunkPayload(pcmBytes: ByteArray): JSONObject {
        val encoded = Base64.getEncoder().encodeToString(pcmBytes)
        return JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put(
                    "mediaChunks",
                    JSONArray().put(
                        JSONObject().apply {
                            put("mimeType", "audio/pcm;rate=$AUDIO_INPUT_SAMPLE_RATE")
                            put("data", encoded)
                        }
                    )
                )
            })
        }
    }

    fun buildLiveWebSocketRequest(apiKey: String): Request {
        return Request.Builder()
            .url(GEMINI_LIVE_WEBSOCKET_ENDPOINT)
            .addHeader("x-goog-api-key", apiKey.trim())
            .build()
    }

    fun shouldSendAudioChunk(bytes: ByteArray): Boolean = bytes.isNotEmpty()

    fun flushAudioQueueOnBargeIn() {
        synchronized(audioOutputQueue) {
            audioOutputQueue.clear()
        }
        flushAudioTrackImmediate()
        synchronized(transcriptionLock) {
            pendingOutputTranscript.clear()
        }
        postToMain { runCatching { tts?.stop() } }
    }

    fun toggleMicMute(): Boolean {
        isMicMuted = !isMicMuted
        return isMicMuted
    }

    fun isMicMuted(): Boolean = isMicMuted

    fun recordPongReceived() {
        lastPongMs = System.currentTimeMillis()
    }

    fun isConnectionAlive(): Boolean = (System.currentTimeMillis() - lastPongMs) < 30_000L

    fun handleSocketDisconnect(onReconnect: (() -> Unit)? = null) {
        isWebSocketConnected = false
        isSetupComplete.set(false)
        reconnectCount++
        _state.update { it.copy(isWebSocketConnected = false) }
        if (_state.value.isActive) {
            val sessionGen = activeSessionGeneration.get()
            scheduleTrackedReconnect(500L) {
                if (sessionGen != activeSessionGeneration.get() || !_state.value.isActive) return@scheduleTrackedReconnect
                if (onReconnect != null) {
                    isWebSocketConnected = true
                    onReconnect.invoke()
                } else {
                    fallbackToHardenedOnDeviceVoice(sessionGen, "실시간 음성 연결이 끊겨 온디바이스 모드로 전환했어요.")
                }
            }
        }
    }

    fun getReconnectCount(): Int = reconnectCount

    fun configureGeminiLiveCredentials(
        apiKey: String?,
        liveModel: String = DEFAULT_GEMINI_LIVE_MODEL
    ) {
        configuredGeminiApiKey = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        if (liveModel.isNotBlank()) {
            configuredLiveModel = if (liveModel.startsWith("models/")) liveModel.trim() else "models/${liveModel.trim()}"
        }
    }

    fun registerTranscriptListener(ownerId: String, listener: (String) -> Unit) {
        if (ownerId.isNotBlank()) {
            transcriptListeners[ownerId] = listener
        }
    }

    fun unregisterTranscriptListener(ownerId: String) {
        transcriptListeners.remove(ownerId)
    }

    fun hasRecordAudioPermission(context: Context): Boolean {
        return runCatching {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun isFatalRecognizerError(errorCode: Int): Boolean {
        return errorCode == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
            errorCode == SpeechRecognizer.ERROR_AUDIO ||
            errorCode == SpeechRecognizer.ERROR_CLIENT
    }

    // =========================================================================
    // Initialization & Hardware Capability Probing
    // =========================================================================

    fun init(context: Context, onUserSpokeCommand: (String) -> Unit) {
        appContext = context.applicationContext ?: context
        activeSessionCallback = onUserSpokeCommand
        if (defaultTranscriptCallback == null) {
            defaultTranscriptCallback = onUserSpokeCommand
        }
        probeBuiltInModules(appContext!!)
        initTts(appContext!!)
    }

    fun ensureInitialized(context: Context, onUserSpokeCommand: ((String) -> Unit)? = null) {
        val resolvedCtx = context.applicationContext ?: context
        if (appContext == null) {
            appContext = resolvedCtx
            probeBuiltInModules(resolvedCtx)
            initTts(resolvedCtx)
        }
        // Only register as fallback default callback — never clobber an active session's callback!
        if (onUserSpokeCommand != null && defaultTranscriptCallback == null) {
            defaultTranscriptCallback = onUserSpokeCommand
        }
    }

    fun probeBuiltInModules(context: Context) {
        val onDeviceAvail = runCatching {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        }.getOrDefault(false)
        val stdAvail = runCatching {
            SpeechRecognizer.isRecognitionAvailable(context)
        }.getOrDefault(false)
        val aecAvail = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)
        val nsAvail = runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)

        val installed = runCatching {
            val pm = context.packageManager ?: return@runCatching emptyList()
            val knownPackages = listOf(
                "com.google.android.tts" to "Google Neural TTS & Speech",
                "com.google.android.aicore" to "Google AICore (Gemma/LiteRT NPU)",
                "com.samsung.android.bixby.ondevice.kokr" to "Samsung On-Device 한국어 NPU",
                "com.samsung.android.intellivoiceservice" to "Galaxy AI IntelliVoice"
            )
            knownPackages.mapNotNull { (pkg, name) ->
                val exists = runCatching { pm.getPackageInfo(pkg, 0) != null }.getOrDefault(false)
                if (exists) name else null
            }
        }.getOrDefault(emptyList())

        _state.update {
            it.copy(
                onDeviceRecognizerAvailable = onDeviceAvail,
                standardRecognizerAvailable = stdAvail,
                hardwareAecAvailable = aecAvail,
                hardwareNsAvailable = nsAvail,
                detectedEngines = installed
            )
        }
    }

    private fun initTts(context: Context) {
        if (tts != null) return
        try {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.KOREAN
                    tts?.setSpeechRate(1.06f)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            cancelThinkingWatchdog()
                            _state.update { it.copy(phase = LiveVoicePhase.SPEAKING) }
                        }

                        override fun onDone(utteranceId: String?) {
                            val sessionGen = activeSessionGeneration.get()
                            if (_state.value.isActive && !isWebSocketConnected) {
                                scheduleTrackedListening(180L, sessionGen)
                            } else if (!_state.value.isActive) {
                                _state.update { it.copy(phase = LiveVoicePhase.OFF) }
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            val sessionGen = activeSessionGeneration.get()
                            if (_state.value.isActive && !isWebSocketConnected) {
                                scheduleTrackedListening(350L, sessionGen)
                            } else if (!_state.value.isActive) {
                                _state.update { it.copy(phase = LiveVoicePhase.OFF) }
                            }
                        }
                    })
                    ttsReady = true
                }
            }
        } catch (_: Exception) {
        }
    }

    // =========================================================================
    // Session Lifecycle (Backward-Compatible + Gemini Live v1beta)
    // =========================================================================

    fun toggleLiveVoiceSession(context: Context, onUserSpokeCommand: (String) -> Unit) {
        toggleLiveVoiceSession(
            context = context,
            apiKey = null,
            liveModel = configuredLiveModel,
            onUserSpokeCommand = onUserSpokeCommand
        )
    }

    fun toggleLiveVoiceSession(
        context: Context,
        apiKey: String?,
        liveModel: String = configuredLiveModel,
        onUserSpokeCommand: (String) -> Unit
    ) {
        init(context, onUserSpokeCommand)
        if (_state.value.isActive) {
            stopLiveVoiceSession()
        } else {
            startLiveSession(
                context = context,
                apiKey = apiKey,
                liveModel = liveModel,
                onUserSpokeCommand = onUserSpokeCommand
            )
        }
    }

    fun startLiveVoiceSession() {
        startLiveSession()
    }

    fun startLiveSession(
        context: Context? = null,
        apiKey: String? = null,
        liveModel: String = configuredLiveModel,
        onUserSpokeCommand: ((String) -> Unit)? = null
    ) {
        if (context != null) {
            appContext = context.applicationContext ?: context
            probeBuiltInModules(appContext!!)
            initTts(appContext!!)
        }
        if (onUserSpokeCommand != null) {
            activeSessionCallback = onUserSpokeCommand
            if (defaultTranscriptCallback == null) {
                defaultTranscriptCallback = onUserSpokeCommand
            }
        }
        val ctx = appContext ?: return

        // Mandatory P1-4 permission guard BEFORE touching AudioRecord or SpeechRecognizer!
        if (!hasRecordAudioPermission(ctx)) {
            stopWithError(MIC_PERMISSION_REQUIRED_KO)
            return
        }

        // Clean any prior session state & pending timers before starting fresh
        teardownActiveTransportOnly()
        cancelAllPendingHandlerCallbacks()
        consecutiveRecognizerErrors = 0
        isMicMuted = false
        val sessionGen = activeSessionGeneration.incrementAndGet()

        val resolvedModel = if (liveModel.isNotBlank()) {
            if (liveModel.startsWith("models/")) liveModel.trim() else "models/${liveModel.trim()}"
        } else {
            DEFAULT_GEMINI_LIVE_MODEL
        }
        configuredLiveModel = resolvedModel

        val resolvedGeminiKey = resolveEffectiveGeminiApiKey(ctx, apiKey)
        if (resolvedGeminiKey != null) {
            _state.update {
                it.copy(
                    isActive = true,
                    mode = LiveVoiceMode.GEMINI_LIVE_V1BETA,
                    phase = LiveVoicePhase.CONNECTING,
                    partialTranscript = "Gemini Live에 연결 중...",
                    errorMessage = null,
                    liveOutputTranscript = "",
                    isWebSocketConnected = false,
                    activeLiveModel = resolvedModel
                )
            }
            FloatingHudService.updateVoiceBadge(true, "Gemini Live 연결 중")
            connectGeminiLiveWebSocket(ctx, resolvedGeminiKey, resolvedModel, sessionGen)
        } else {
            startHardenedOnDeviceSession(ctx, sessionGen, greetUser = true)
        }
    }

    fun stopLiveSession() {
        stopLiveVoiceSession()
    }

    fun stopLiveVoiceSession() {
        activeSessionGeneration.incrementAndGet()
        cancelAllPendingHandlerCallbacks()
        teardownActiveTransportOnly()
        postToMain {
            destroyRecognizerOnMain()
            runCatching { tts?.stop() }
        }
        _state.update {
            it.copy(
                isActive = false,
                phase = LiveVoicePhase.OFF,
                partialTranscript = "",
                audioLevelRms = 0f,
                errorMessage = null,
                isWebSocketConnected = false
            )
        }
        FloatingHudService.updateVoiceBadge(false, "보이스 꺼짐")
    }

    /**
     * Interrupts current audio/TTS output immediately (Barge-In) and resumes listening.
     */
    fun bargeInAndListen() {
        cancelThinkingWatchdog()
        cancelPendingListeningRunnable()
        flushAudioQueueOnBargeIn()
        isMicMuted = false
        val sessionGen = activeSessionGeneration.get()
        if (!_state.value.isActive) return

        if (isWebSocketConnected) {
            _state.update {
                it.copy(
                    phase = LiveVoicePhase.LISTENING,
                    partialTranscript = "듣고 있습니다..."
                )
            }
            return
        }

        val ctx = appContext ?: return
        if (!hasRecordAudioPermission(ctx)) {
            stopWithError(MIC_PERMISSION_REQUIRED_KO)
            return
        }
        postToMain {
            runCatching { tts?.stop() }
            if (_state.value.isActive && sessionGen == activeSessionGeneration.get()) {
                startListeningCycle(sessionGen)
            }
        }
    }

    /**
     * Speaks a natural conversational response from the agent.
     * In active Gemini Live mode, forwards text context to the live session or plays via TTS
     * when running in hardened on-device fallback mode.
     */
    fun speakAgentResponse(text: String, queueAdd: Boolean = false) {
        val clean = sanitizeSpokenText(text)
        cancelThinkingWatchdog()

        if (clean.isBlank()) {
            val sessionGen = activeSessionGeneration.get()
            if (_state.value.isActive && !isWebSocketConnected) {
                scheduleTrackedListening(200L, sessionGen)
            }
            return
        }

        // If Gemini Live WebSocket is actively streaming native audio, relay agent action result cleanly
        val ws = activeWebSocket
        if (_state.value.isActive && isWebSocketConnected && isSetupComplete.get() && ws != null) {
            _state.update {
                it.copy(
                    lastSpokenResponse = clean,
                    liveOutputTranscript = clean
                )
            }
            val clientContent = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put(
                        "turns",
                        JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put(
                                    "parts",
                                    JSONArray().put(
                                        JSONObject().put("text", "[시스템 실행 결과 안내 — 사용자에게 자연스럽게 한 문장으로 말해주세요]: $clean")
                                    )
                                )
                            }
                        )
                    )
                    put("turnComplete", true)
                })
            }
            runCatching { ws.send(clientContent.toString()) }
            return
        }

        _state.update {
            it.copy(
                phase = LiveVoicePhase.SPEAKING,
                lastSpokenResponse = clean
            )
        }

        if (!ttsReady) {
            val sessionGen = activeSessionGeneration.get()
            if (_state.value.isActive) {
                scheduleTrackedListening(400L, sessionGen)
            }
            return
        }

        postToMain {
            runCatching {
                cancelPendingListeningRunnable()
                speechRecognizer?.cancel()
                val queueMode = if (queueAdd) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
                tts?.speak(
                    clean,
                    queueMode,
                    null,
                    "live_voice_${System.currentTimeMillis()}"
                )
            }
        }
    }

    /**
     * Backward-compatible alias for [speakAgentResponse].
     */
    fun speakConversationalFiller(text: String, queueAdd: Boolean = false) {
        speakAgentResponse(text, queueAdd)
    }

    // =========================================================================
    // Gemini Live API v1beta WebSocket + Full-Duplex AudioRecord / AudioTrack
    // =========================================================================

    private fun extractValidGeminiKey(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val parsed = GeminiMultimodalClient.Companion.MultiProviderApiKeySet.parse(raw).geminiKey?.trim()
        return parsed?.takeIf { GeminiMultimodalClient.isValidGeminiKey(it) }
    }

    private fun resolveEffectiveGeminiApiKey(context: Context, explicitApiKey: String?): String? {
        extractValidGeminiKey(explicitApiKey)?.let { return it }
        extractValidGeminiKey(configuredGeminiApiKey)?.let { return it }
        val engineKey = runCatching {
            HansonRuntimeHolder.getAgentEngine(context).config.value.apiKey
        }.getOrNull()
        extractValidGeminiKey(engineKey)?.let { return it }
        val buildKey = runCatching { BuildConfig.GEMINI_API_KEY }.getOrNull()
        return extractValidGeminiKey(buildKey)
    }

    private fun connectGeminiLiveWebSocket(
        context: Context,
        apiKey: String,
        liveModel: String,
        sessionGen: Long
    ) {
        val request = buildLiveWebSocketRequest(apiKey)
        val setupPayload = buildLiveSetupHandshake(model = liveModel).toString()

        activeWebSocket = wsHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (sessionGen != activeSessionGeneration.get() || !_state.value.isActive) {
                    webSocket.close(1000, "Session superseded")
                    return
                }
                recordPongReceived()
                isWebSocketConnected = true
                isSetupComplete.set(false)
                webSocket.send(setupPayload)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (sessionGen != activeSessionGeneration.get()) return
                handleLiveServerJson(text, context, sessionGen)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (sessionGen != activeSessionGeneration.get()) return
                handleLiveServerJson(bytes.utf8(), context, sessionGen)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (sessionGen != activeSessionGeneration.get()) return
                val wasActive = _state.value.isActive
                teardownActiveTransportOnly()
                if (wasActive && code != 1000) {
                    reconnectCount++
                    fallbackToHardenedOnDeviceVoice(sessionGen, "실시간 음성 세션이 종료되어 온디바이스 음성으로 전환했어요.")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (sessionGen != activeSessionGeneration.get()) return
                val wasActive = _state.value.isActive
                teardownActiveTransportOnly()
                if (wasActive) {
                    reconnectCount++
                    fallbackToHardenedOnDeviceVoice(
                        sessionGen,
                        "Gemini Live 연결에 실패해 온디바이스 음성으로 자동 전환했어요."
                    )
                }
            }
        })
    }

    internal fun handleLiveServerJson(
        rawJson: String,
        context: Context? = appContext,
        sessionGen: Long = activeSessionGeneration.get()
    ) {
        val json = runCatching { JSONObject(rawJson) }.getOrNull() ?: return
        recordPongReceived()

        if (json.has("setupComplete")) {
            isSetupComplete.set(true)
            isWebSocketConnected = true
            _state.update {
                it.copy(
                    mode = LiveVoiceMode.GEMINI_LIVE_V1BETA,
                    phase = LiveVoicePhase.LISTENING,
                    isWebSocketConnected = true,
                    partialTranscript = "듣고 있습니다...",
                    errorMessage = null
                )
            }
            FloatingHudService.updateVoiceBadge(true, "Gemini Live 대화 중")
            if (context != null) {
                startFullDuplexAudioIo(context, sessionGen)
            }
            return
        }

        if (json.has("goAway")) {
            if (_state.value.isActive && sessionGen == activeSessionGeneration.get()) {
                teardownActiveTransportOnly()
                fallbackToHardenedOnDeviceVoice(sessionGen, "서버 세션 만료로 온디바이스 음성으로 전환했어요.")
            }
            return
        }

        val serverContent = json.optJSONObject("serverContent") ?: return

        // 1. Immediate Barge-In interruption handling
        if (serverContent.optBoolean("interrupted", false)) {
            cancelThinkingWatchdog()
            flushAudioQueueOnBargeIn()
            if (_state.value.isActive) {
                _state.update {
                    it.copy(
                        phase = LiveVoicePhase.LISTENING,
                        partialTranscript = "듣고 있습니다..."
                    )
                }
            }
            return
        }

        // 2. Input audio transcription (user speech)
        val inputTx = serverContent.optJSONObject("inputTranscription")
            ?.optString("text", "")
            .orEmpty()
        if (inputTx.isNotEmpty()) {
            val combinedInput = synchronized(transcriptionLock) {
                appendIncrementalTranscript(pendingInputTranscript, inputTx)
            }
            if (combinedInput.isNotBlank()) {
                _state.update { it.copy(partialTranscript = combinedInput) }
            }
        }

        // 3. Output audio transcription (model speech)
        val outputTx = serverContent.optJSONObject("outputTranscription")
            ?.optString("text", "")
            .orEmpty()
        if (outputTx.isNotEmpty()) {
            finalizePendingUserTranscriptIfAny()
            val combinedOutput = synchronized(transcriptionLock) {
                appendIncrementalTranscript(pendingOutputTranscript, outputTx)
            }
            if (combinedOutput.isNotBlank()) {
                cancelThinkingWatchdog()
                _state.update {
                    it.copy(
                        phase = LiveVoicePhase.SPEAKING,
                        lastSpokenResponse = combinedOutput,
                        liveOutputTranscript = combinedOutput
                    )
                }
            }
        }

        // 4. Model turn audio/text parts — process ALL parts
        val modelTurn = serverContent.optJSONObject("modelTurn")
        val parts = modelTurn?.optJSONArray("parts")
        if (parts != null && parts.length() > 0) {
            finalizePendingUserTranscriptIfAny()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                if (inlineData != null) {
                    val mime = inlineData.optString("mimeType", inlineData.optString("mime_type", ""))
                    val b64Data = inlineData.optString("data", "")
                    if (b64Data.isNotBlank() && (mime.isBlank() || mime.startsWith("audio/", ignoreCase = true))) {
                        val pcmBytes = runCatching { Base64.getDecoder().decode(b64Data) }.getOrNull()
                        if (pcmBytes != null && pcmBytes.isNotEmpty()) {
                            cancelThinkingWatchdog()
                            enqueueOutputPcmChunk(pcmBytes)
                            _state.update { it.copy(phase = LiveVoicePhase.SPEAKING) }
                        }
                    }
                }
                val partText = part.optString("text", "").trim()
                if (partText.isNotEmpty()) {
                    val combinedOutput = synchronized(transcriptionLock) {
                        appendIncrementalTranscript(pendingOutputTranscript, partText)
                    }
                    _state.update {
                        it.copy(
                            lastSpokenResponse = combinedOutput,
                            liveOutputTranscript = combinedOutput
                        )
                    }
                }
            }
        }

        // 5. Turn completion
        if (serverContent.optBoolean("turnComplete", false) ||
            serverContent.optBoolean("generationComplete", false)
        ) {
            finalizePendingUserTranscriptIfAny()
            synchronized(transcriptionLock) {
                pendingOutputTranscript.clear()
            }
            cancelThinkingWatchdog()
            if (_state.value.isActive) {
                _state.update {
                    it.copy(
                        phase = LiveVoicePhase.LISTENING,
                        partialTranscript = "듣고 있습니다..."
                    )
                }
            }
        }
    }

    private fun appendIncrementalTranscript(buf: StringBuilder, delta: String): String {
        if (buf.isNotEmpty() && !buf.endsWith(" ") && !delta.startsWith(" ") &&
            !delta.startsWith(".") && !delta.startsWith(",") && !delta.startsWith("?") && !delta.startsWith("!")
        ) {
            // Preserve natural Korean spacing when server streams space-delimited tokens
            val lastChar = buf[buf.length - 1]
            val firstChar = delta[0]
            if (lastChar.isLetterOrDigit() && firstChar.isLetterOrDigit() && delta.length > 3) {
                buf.append(' ')
            }
        }
        buf.append(delta)
        return buf.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun finalizePendingUserTranscriptIfAny() {
        val finalized = synchronized(transcriptionLock) {
            val text = pendingInputTranscript.toString().replace(Regex("\\s+"), " ").trim()
            pendingInputTranscript.clear()
            text
        }
        if (finalized.isNotBlank()) {
            _state.update { it.copy(partialTranscript = finalized) }
            emitCompletedUserTranscript(finalized)
        }
    }

    private fun emitCompletedUserTranscript(spoken: String) {
        val clean = spoken.trim()
        if (clean.isEmpty()) return
        _userTranscriptFlow.tryEmit(clean)
        for (listener in transcriptListeners.values) {
            runCatching { listener.invoke(clean) }
        }
        val primary = activeSessionCallback ?: defaultTranscriptCallback
        if (primary != null) {
            runCatching { primary.invoke(clean) }
        }
    }

    private fun enqueueOutputPcmChunk(pcmBytes: ByteArray) {
        synchronized(audioOutputQueue) {
            audioOutputQueue.add(pcmBytes)
        }
        playbackWakeChannel.trySend(Unit)
    }

    @SuppressLint("MissingPermission")
    private fun startFullDuplexAudioIo(context: Context, sessionGen: Long) {
        if (!hasRecordAudioPermission(context)) {
            stopWithError(MIC_PERMISSION_REQUIRED_KO)
            return
        }

        // Start 24kHz PCM AudioTrack playback coroutine
        liveAudioPlaybackJob?.cancel()
        playbackWakeChannel = Channel(Channel.CONFLATED)
        liveAudioPlaybackJob = voiceScope.launch {
            val track = createConfiguredAudioTrack()
            if (track == null) return@launch
            try {
                track.play()
                while (isActive && sessionGen == activeSessionGeneration.get() && isWebSocketConnected) {
                    val chunk = synchronized(audioOutputQueue) {
                        if (audioOutputQueue.isNotEmpty()) audioOutputQueue.removeAt(0) else null
                    }
                    if (chunk != null) {
                        runCatching {
                            track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                        }
                    } else {
                        playbackWakeChannel.receiveCatching()
                    }
                }
            } finally {
                releaseAudioTrack()
            }
        }

        // Start 16kHz 16-bit mono PCM AudioRecord capture coroutine with hardware AEC + NS
        liveAudioCaptureJob?.cancel()
        liveAudioCaptureJob = voiceScope.launch {
            val minBuf = AudioRecord.getMinBufferSize(
                AUDIO_INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                fallbackToHardenedOnDeviceVoice(sessionGen, "마이크 버퍼 초기화에 실패해 온디바이스 모드로 전환했어요.")
                return@launch
            }
            val bufferSize = maxOf(minBuf * 2, AUDIO_CAPTURE_CHUNK_BYTES * 4)
            val record = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    AUDIO_INPUT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }.getOrNull()

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record?.release() }
                fallbackToHardenedOnDeviceVoice(sessionGen, "실시간 마이크 초기화에 실패해 온디바이스 모드로 전환했어요.")
                return@launch
            }

            synchronized(audioCaptureLifecycleLock) {
                audioRecord = record
                attachHardwareAudioEffects(record.audioSessionId)
            }

            val readBuf = ByteArray(AUDIO_CAPTURE_CHUNK_BYTES)
            try {
                record.startRecording()
                while (isActive && sessionGen == activeSessionGeneration.get() && isWebSocketConnected) {
                    val bytesRead = record.read(readBuf, 0, readBuf.size)
                    if (bytesRead > 0) {
                        val rms = computePcm16RmsNormalized(readBuf, bytesRead)
                        _state.update { it.copy(audioLevelRms = if (isMicMuted) 0f else rms) }
                        if (!isMicMuted && isSetupComplete.get()) {
                            val chunk = if (bytesRead == readBuf.size) readBuf.copyOf() else readBuf.copyOf(bytesRead)
                            if (shouldSendAudioChunk(chunk)) {
                                val payload = buildRealtimeAudioChunkPayload(chunk).toString()
                                activeWebSocket?.send(payload)
                            }
                        }
                    } else if (bytesRead < 0) {
                        break
                    }
                }
            } catch (_: Exception) {
                // Clean teardown below
            } finally {
                releaseAudioRecordAndEffects()
            }
        }
    }

    private fun attachHardwareAudioEffects(audioSessionId: Int) {
        if (audioSessionId == 0) return
        if (runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)) {
            acousticEchoCanceler = runCatching {
                AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        if (runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)) {
            noiseSuppressor = runCatching {
                NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
            }.getOrNull()
        }
    }

    private fun createConfiguredAudioTrack(): AudioTrack? {
        val minBuf = AudioTrack.getMinBufferSize(
            AUDIO_OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null
        val bufSize = maxOf(minBuf * 2, 4800)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AUDIO_OUTPUT_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return null

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return null
        }
        synchronized(audioTrackLifecycleLock) {
            audioTrack = track
        }
        return track
    }

    private fun flushAudioTrackImmediate() {
        val track = audioTrack ?: return
        runCatching {
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.pause()
                track.flush()
                if (isWebSocketConnected && _state.value.isActive) {
                    track.play()
                }
            }
        }
    }

    private fun releaseAudioTrack() {
        synchronized(audioTrackLifecycleLock) {
            val track = audioTrack
            audioTrack = null
            if (track != null) {
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }
    }

    private fun releaseAudioRecordAndEffects() {
        synchronized(audioCaptureLifecycleLock) {
            runCatching { acousticEchoCanceler?.release() }
            acousticEchoCanceler = null
            runCatching { noiseSuppressor?.release() }
            noiseSuppressor = null
            val rec = audioRecord
            audioRecord = null
            if (rec != null) {
                runCatching { rec.stop() }
                runCatching { rec.release() }
            }
        }
    }

    private fun teardownActiveTransportOnly() {
        isWebSocketConnected = false
        isSetupComplete.set(false)
        val ws = activeWebSocket
        activeWebSocket = null
        runCatching { ws?.close(1000, "Session stopped") }
        liveAudioCaptureJob?.cancel()
        liveAudioCaptureJob = null
        liveAudioPlaybackJob?.cancel()
        liveAudioPlaybackJob = null
        runCatching { playbackWakeChannel.close() }
        synchronized(audioOutputQueue) {
            audioOutputQueue.clear()
        }
        synchronized(transcriptionLock) {
            pendingInputTranscript.clear()
            pendingOutputTranscript.clear()
        }
        releaseAudioRecordAndEffects()
        releaseAudioTrack()
    }

    internal fun computePcm16RmsNormalized(pcmBytes: ByteArray, length: Int = pcmBytes.size): Float {
        if (length < 2) return 0f
        var sumSq = 0.0
        var count = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((pcmBytes[i + 1].toInt() shl 8) or (pcmBytes[i].toInt() and 0xFF)).toShort().toDouble()
            sumSq += sample * sample
            count++
            i += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sumSq / count)
        return (rms / 6000.0).toFloat().coerceIn(0.05f, 1.0f)
    }

    // =========================================================================
    // Hardened On-Device Fallback (SpeechRecognizer + TextToSpeech)
    // =========================================================================

    private fun fallbackToHardenedOnDeviceVoice(sessionGen: Long, noticeKo: String) {
        val ctx = appContext ?: return
        if (sessionGen != activeSessionGeneration.get() || !_state.value.isActive) return
        if (!hasRecordAudioPermission(ctx)) {
            stopWithError(MIC_PERMISSION_REQUIRED_KO)
            return
        }
        startHardenedOnDeviceSession(ctx, sessionGen, greetUser = false, noticeKo = noticeKo)
    }

    private fun startHardenedOnDeviceSession(
        context: Context,
        sessionGen: Long,
        greetUser: Boolean,
        noticeKo: String? = null
    ) {
        if (!hasRecordAudioPermission(context)) {
            stopWithError(MIC_PERMISSION_REQUIRED_KO)
            return
        }
        _state.update {
            it.copy(
                isActive = true,
                mode = LiveVoiceMode.ON_DEVICE_DUPLEX,
                phase = LiveVoicePhase.LISTENING,
                partialTranscript = noticeKo ?: "듣고 있습니다...",
                errorMessage = null,
                isWebSocketConnected = false
            )
        }
        FloatingHudService.updateVoiceBadge(true, "보이스 대화 중")
        if (greetUser) {
            speakAgentResponse("네, 듣고 있어요. 무엇을 도와드릴까요?")
        } else {
            scheduleTrackedListening(220L, sessionGen)
        }
    }

    private fun startListeningCycle(sessionGen: Long = activeSessionGeneration.get()) {
        val ctx = appContext ?: return
        if (!_state.value.isActive || sessionGen != activeSessionGeneration.get() || isWebSocketConnected) return

        // Mandatory runtime permission verification on EVERY listening cycle
        if (!hasRecordAudioPermission(ctx)) {
            stopWithError(MIC_PERMISSION_REQUIRED_KO)
            return
        }

        cancelPendingListeningRunnable()
        cancelThinkingWatchdog()

        postToMain {
            if (!_state.value.isActive || sessionGen != activeSessionGeneration.get() || isWebSocketConnected) {
                return@postToMain
            }
            if (!hasRecordAudioPermission(ctx)) {
                stopWithError(MIC_PERMISSION_REQUIRED_KO)
                return@postToMain
            }
            try {
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                        setRecognitionListener(createRecognitionListener(sessionGen))
                    }
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                }
                _state.update {
                    it.copy(
                        phase = LiveVoicePhase.LISTENING,
                        partialTranscript = "듣고 있습니다...",
                        errorMessage = null
                    )
                }
                speechRecognizer?.startListening(intent)
            } catch (_: SecurityException) {
                stopWithError(MIC_PERMISSION_REQUIRED_KO)
            } catch (_: Exception) {
                consecutiveRecognizerErrors++
                if (consecutiveRecognizerErrors >= MAX_CONSECUTIVE_RECOGNIZER_ERRORS) {
                    stopWithError("음성 인식을 시작할 수 없어요")
                } else if (_state.value.isActive && sessionGen == activeSessionGeneration.get()) {
                    destroyRecognizerOnMain()
                    scheduleTrackedListening(900L, sessionGen)
                }
            }
        }
    }

    private fun createRecognitionListener(sessionGen: Long): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (sessionGen != activeSessionGeneration.get()) return
                consecutiveRecognizerErrors = 0
                cancelThinkingWatchdog()
                _state.update {
                    it.copy(
                        phase = LiveVoicePhase.LISTENING,
                        partialTranscript = "말씀하세요...",
                        errorMessage = null
                    )
                }
            }

            override fun onBeginningOfSpeech() {
                if (sessionGen != activeSessionGeneration.get()) return
                cancelThinkingWatchdog()
                runCatching { tts?.stop() }
                _state.update { it.copy(phase = LiveVoicePhase.LISTENING) }
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (sessionGen != activeSessionGeneration.get()) return
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1.0f)
                _state.update { it.copy(audioLevelRms = normalized) }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                if (sessionGen != activeSessionGeneration.get()) return
                _state.update {
                    it.copy(
                        phase = LiveVoicePhase.THINKING,
                        audioLevelRms = 0.1f
                    )
                }
                armThinkingWatchdog(sessionGen)
            }

            override fun onError(error: Int) {
                if (sessionGen != activeSessionGeneration.get() || !_state.value.isActive) return
                cancelThinkingWatchdog()

                // Fatal errors: STOP immediately & surface clear Korean error (ZERO 450ms infinite loop!)
                if (isFatalRecognizerError(error)) {
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> MIC_PERMISSION_REQUIRED_KO
                        SpeechRecognizer.ERROR_AUDIO -> "마이크 오디오 오류가 발생했어요"
                        SpeechRecognizer.ERROR_CLIENT -> "음성 인식 클라이언트 오류가 발생했어요"
                        else -> "음성 인식 오류가 발생했어요"
                    }
                    stopWithError(errorMsg)
                    return
                }

                val ctx = appContext
                if (ctx == null || !hasRecordAudioPermission(ctx)) {
                    stopWithError(MIC_PERMISSION_REQUIRED_KO)
                    return
                }

                if (_state.value.phase == LiveVoicePhase.SPEAKING) return

                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        consecutiveRecognizerErrors = 0
                        scheduleTrackedListening(500L, sessionGen)
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        consecutiveRecognizerErrors++
                        if (consecutiveRecognizerErrors >= MAX_CONSECUTIVE_RECOGNIZER_ERRORS) {
                            stopWithError("음성 인식기가 바빠 잠시 중단했어요")
                        } else {
                            destroyRecognizerOnMain()
                            scheduleTrackedListening(900L, sessionGen)
                        }
                    }
                    else -> {
                        consecutiveRecognizerErrors++
                        if (consecutiveRecognizerErrors >= MAX_CONSECUTIVE_RECOGNIZER_ERRORS) {
                            stopWithError("음성 인식 연결이 불안정해 종료했어요")
                        } else {
                            val backoffMs = (700L * consecutiveRecognizerErrors).coerceAtMost(3_000L)
                            scheduleTrackedListening(backoffMs, sessionGen)
                        }
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                if (sessionGen != activeSessionGeneration.get()) return
                consecutiveRecognizerErrors = 0
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = matches?.firstOrNull()?.trim().orEmpty()
                if (spoken.isNotBlank()) {
                    _state.update {
                        it.copy(
                            phase = LiveVoicePhase.THINKING,
                            partialTranscript = spoken,
                            errorMessage = null
                        )
                    }
                    // Arm 8-second watchdog so THINKING never freezes if TTS never fires!
                    armThinkingWatchdog(sessionGen)
                    emitCompletedUserTranscript(spoken)
                } else if (_state.value.isActive) {
                    cancelThinkingWatchdog()
                    scheduleTrackedListening(350L, sessionGen)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (sessionGen != activeSessionGeneration.get()) return
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (partial.isNotBlank()) {
                    _state.update { it.copy(partialTranscript = partial) }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun stopWithError(messageKo: String) {
        activeSessionGeneration.incrementAndGet()
        cancelAllPendingHandlerCallbacks()
        teardownActiveTransportOnly()
        postToMain {
            destroyRecognizerOnMain()
            runCatching { tts?.stop() }
        }
        _state.update {
            it.copy(
                isActive = false,
                phase = LiveVoicePhase.ERROR,
                partialTranscript = messageKo,
                errorMessage = messageKo,
                audioLevelRms = 0f,
                isWebSocketConnected = false
            )
        }
        FloatingHudService.updateVoiceBadge(false, messageKo.take(16))
    }

    private fun destroyRecognizerOnMain() {
        runCatching { speechRecognizer?.stopListening() }
        runCatching { speechRecognizer?.cancel() }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
    }

    // =========================================================================
    // Tracked Handler Runnables & 8-Second THINKING Watchdog
    // =========================================================================

    private fun scheduleTrackedListening(delayMs: Long, sessionGen: Long) {
        synchronized(handlerTaskLock) {
            pendingListeningRunnable?.let { removeFromMain(it) }
            val task = Runnable {
                synchronized(handlerTaskLock) {
                    pendingListeningRunnable = null
                }
                if (sessionGen == activeSessionGeneration.get() &&
                    _state.value.isActive &&
                    _state.value.phase != LiveVoicePhase.SPEAKING &&
                    !isWebSocketConnected
                ) {
                    startListeningCycle(sessionGen)
                }
            }
            pendingListeningRunnable = task
            postDelayedToMain(task, delayMs)
        }
    }

    private fun cancelPendingListeningRunnable() {
        synchronized(handlerTaskLock) {
            pendingListeningRunnable?.let { removeFromMain(it) }
            pendingListeningRunnable = null
        }
    }

    private fun scheduleTrackedReconnect(delayMs: Long, action: () -> Unit) {
        synchronized(handlerTaskLock) {
            pendingReconnectRunnable?.let { removeFromMain(it) }
            val task = Runnable {
                synchronized(handlerTaskLock) {
                    pendingReconnectRunnable = null
                }
                action()
            }
            pendingReconnectRunnable = task
            postDelayedToMain(task, delayMs)
        }
    }

    private fun armThinkingWatchdog(sessionGen: Long) {
        synchronized(handlerTaskLock) {
            thinkingWatchdogRunnable?.let { removeFromMain(it) }
            val watchdog = Runnable {
                synchronized(handlerTaskLock) {
                    thinkingWatchdogRunnable = null
                }
                if (sessionGen != activeSessionGeneration.get()) return@Runnable
                if (_state.value.phase != LiveVoicePhase.THINKING) return@Runnable

                if (_state.value.isActive) {
                    if (isWebSocketConnected) {
                        _state.update {
                            it.copy(
                                phase = LiveVoicePhase.LISTENING,
                                partialTranscript = "듣고 있습니다..."
                            )
                        }
                    } else {
                        val ctx = appContext
                        if (ctx != null && hasRecordAudioPermission(ctx)) {
                            startListeningCycle(sessionGen)
                        } else {
                            stopWithError(MIC_PERMISSION_REQUIRED_KO)
                        }
                    }
                } else {
                    _state.update { it.copy(phase = LiveVoicePhase.OFF, partialTranscript = "") }
                }
            }
            thinkingWatchdogRunnable = watchdog
            postDelayedToMain(watchdog, THINKING_WATCHDOG_TIMEOUT_MS)
        }
    }

    private fun cancelThinkingWatchdog() {
        synchronized(handlerTaskLock) {
            thinkingWatchdogRunnable?.let { removeFromMain(it) }
            thinkingWatchdogRunnable = null
        }
    }

    private fun cancelAllPendingHandlerCallbacks() {
        synchronized(handlerTaskLock) {
            pendingListeningRunnable?.let { removeFromMain(it) }
            pendingListeningRunnable = null
            pendingReconnectRunnable?.let { removeFromMain(it) }
            pendingReconnectRunnable = null
            thinkingWatchdogRunnable?.let { removeFromMain(it) }
            thinkingWatchdogRunnable = null
        }
    }

    private fun postToMain(block: () -> Unit) {
        val h = mainHandler
        if (h != null) {
            runCatching { h.post(block) }
        }
    }

    private fun postDelayedToMain(runnable: Runnable, delayMs: Long) {
        val h = mainHandler
        if (h != null) {
            runCatching { h.postDelayed(runnable, delayMs) }
        }
    }

    private fun removeFromMain(runnable: Runnable) {
        val h = mainHandler
        if (h != null) {
            runCatching { h.removeCallbacks(runnable) }
        }
    }

    private fun sanitizeSpokenText(text: String): String {
        return RichAnswerBoardFormatter.formatForNaturalSpeech(text, maxLength = 240)
    }
}
