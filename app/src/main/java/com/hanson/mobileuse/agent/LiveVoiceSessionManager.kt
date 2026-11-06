package com.hanson.mobileuse.agent

import android.content.Context
import android.content.Intent
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
import com.hanson.mobileuse.service.FloatingHudService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

enum class LiveVoiceMode(val labelKo: String, val badge: String) {
    ON_DEVICE_DUPLEX("실시간 양방향 보이스 (Full-Duplex)", "LIVE VOICE"),
    GEMINI_3_8_LIVE("Gemini 3.8 Live Extended Thinking", "3.8 LIVE")
}

enum class LiveVoicePhase(val labelKo: String) {
    OFF("보이스 대기"),
    LISTENING("듣고 있습니다... 편하게 말씀하세요"),
    THINKING("화면을 보며 생각 중..."),
    SPEAKING("대답하는 중 (말씀하시면 즉시 듣습니다)")
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
    val detectedEngines: List<String> = emptyList()
)

/**
 * Full-Duplex Conversational Voice Coordinator (`LiveVoiceSessionManager`).
 *
 * Designed for natural back-and-forth human dialogue while controlling the phone:
 * - Zero canned heuristic parrot phrases ("네, '...' 바로 처리할게요" 제거).
 * - Unified TTS + SpeechRecognizer state machine: pauses mic cleanly while speaking to avoid self-echo,
 *   supports instant Barge-In interruption, and automatically re-opens the microphone 180ms after
 *   every agent utterance so the conversation flows continuously across apps.
 */
object LiveVoiceSessionManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var onFinalTranscriptCallback: ((String) -> Unit)? = null
    private var appContext: Context? = null

    private val _state = MutableStateFlow(LiveVoiceState())
    val state: StateFlow<LiveVoiceState> = _state.asStateFlow()

    fun init(context: Context, onUserSpokeCommand: (String) -> Unit) {
        appContext = context.applicationContext
        onFinalTranscriptCallback = onUserSpokeCommand
        probeBuiltInModules(context.applicationContext)
        initTts(context.applicationContext)
    }

    fun ensureInitialized(context: Context, onUserSpokeCommand: ((String) -> Unit)? = null) {
        if (appContext == null) {
            appContext = context.applicationContext
            probeBuiltInModules(context.applicationContext)
            initTts(context.applicationContext)
        }
        if (onUserSpokeCommand != null) {
            onFinalTranscriptCallback = onUserSpokeCommand
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

        val pm = context.packageManager
        val knownPackages = listOf(
            "com.google.android.tts" to "Google Neural TTS & Speech",
            "com.google.android.aicore" to "Google AICore (Gemma/LiteRT NPU)",
            "com.samsung.android.bixby.ondevice.kokr" to "Samsung On-Device 한국어 NPU",
            "com.samsung.android.intellivoiceservice" to "Galaxy AI IntelliVoice"
        )
        val installed = knownPackages.mapNotNull { (pkg, name) ->
            val exists = runCatching { pm.getPackageInfo(pkg, 0) != null }.getOrDefault(false)
            if (exists) name else null
        }

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
                            _state.update { it.copy(phase = LiveVoicePhase.SPEAKING) }
                        }

                        override fun onDone(utteranceId: String?) {
                            if (_state.value.isActive) {
                                mainHandler.postDelayed({
                                    if (_state.value.isActive) {
                                        startListeningCycle()
                                    }
                                }, 180L)
                            } else {
                                _state.update { it.copy(phase = LiveVoicePhase.OFF) }
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (_state.value.isActive) {
                                mainHandler.postDelayed({ startListeningCycle() }, 300L)
                            }
                        }
                    })
                    ttsReady = true
                }
            }
        } catch (_: Exception) {
        }
    }

    fun toggleLiveVoiceSession(context: Context, onUserSpokeCommand: (String) -> Unit) {
        init(context, onUserSpokeCommand)
        if (_state.value.isActive) {
            stopLiveVoiceSession()
        } else {
            startLiveVoiceSession()
        }
    }

    fun startLiveVoiceSession() {
        if (appContext == null) return
        _state.update {
            it.copy(
                isActive = true,
                phase = LiveVoicePhase.LISTENING,
                partialTranscript = "듣고 있습니다..."
            )
        }
        FloatingHudService.updateVoiceBadge(true, "보이스 대화 중")
        speakConversationalFiller("네, 듣고 있어요. 무엇을 도와드릴까요?")
    }

    fun stopLiveVoiceSession() {
        mainHandler.post {
            runCatching { speechRecognizer?.stopListening() }
            runCatching { speechRecognizer?.cancel() }
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
            runCatching { tts?.stop() }
        }
        _state.update {
            it.copy(
                isActive = false,
                phase = LiveVoicePhase.OFF,
                partialTranscript = "",
                audioLevelRms = 0f
            )
        }
        FloatingHudService.updateVoiceBadge(false, "보이스 꺼짐")
    }

    /**
     * Interrupts current TTS output immediately (Barge-In) and starts listening to the user.
     */
    fun bargeInAndListen() {
        mainHandler.post {
            runCatching { tts?.stop() }
            if (_state.value.isActive) {
                startListeningCycle()
            }
        }
    }

    /**
     * Speaks a natural conversational response from the agent.
     * If `forceSpeak` is false, speaks only when the Live Voice session is active or TTS is enabled.
     * Automatically resumes continuous listening after speaking completes.
     */
    fun speakConversationalFiller(text: String, queueAdd: Boolean = false) {
        val clean = text
            .replace(Regex("\\[[^\\]]*\\]"), " ") // Strip any bracketed technical tags
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("[•▪▶]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(220)
        if (clean.isBlank()) {
            if (_state.value.isActive) {
                mainHandler.postDelayed({ startListeningCycle() }, 200L)
            }
            return
        }

        _state.update {
            it.copy(
                phase = LiveVoicePhase.SPEAKING,
                lastSpokenResponse = clean
            )
        }

        if (!ttsReady) {
            if (_state.value.isActive) {
                mainHandler.postDelayed({ startListeningCycle() }, 400L)
            }
            return
        }

        mainHandler.post {
            runCatching {
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

    private fun startListeningCycle() {
        val ctx = appContext ?: return
        if (!_state.value.isActive) return

        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                        setRecognitionListener(createRecognitionListener())
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
                        partialTranscript = "듣고 있습니다..."
                    )
                }
                speechRecognizer?.startListening(intent)
            } catch (_: Exception) {
                if (_state.value.isActive) {
                    mainHandler.postDelayed({ startListeningCycle() }, 700L)
                }
            }
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.update {
                    it.copy(
                        phase = LiveVoicePhase.LISTENING,
                        partialTranscript = "말씀하세요..."
                    )
                }
            }

            override fun onBeginningOfSpeech() {
                runCatching { tts?.stop() }
                _state.update { it.copy(phase = LiveVoicePhase.LISTENING) }
            }

            override fun onRmsChanged(rmsdB: Float) {
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1.0f)
                _state.update { it.copy(audioLevelRms = normalized) }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                _state.update {
                    it.copy(
                        phase = LiveVoicePhase.THINKING,
                        audioLevelRms = 0.1f
                    )
                }
            }

            override fun onError(error: Int) {
                // Keep continuous live loop alive unless user explicitly stopped it
                if (_state.value.isActive) {
                    mainHandler.postDelayed({
                        if (_state.value.isActive && _state.value.phase != LiveVoicePhase.SPEAKING) {
                            startListeningCycle()
                        }
                    }, 450L)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = matches?.firstOrNull()?.trim().orEmpty()
                if (spoken.isNotBlank()) {
                    _state.update {
                        it.copy(
                            phase = LiveVoicePhase.THINKING,
                            partialTranscript = spoken
                        )
                    }
                    // Directly forward to the conversational agent engine without any canned parrot filler!
                    onFinalTranscriptCallback?.invoke(spoken)
                } else if (_state.value.isActive) {
                    mainHandler.postDelayed({ startListeningCycle() }, 300L)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
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
}
