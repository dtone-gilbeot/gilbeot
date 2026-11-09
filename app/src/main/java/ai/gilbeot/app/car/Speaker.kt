package ai.gilbeot.app.car

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Pleos TTS를 ON_DEVICE에서 HYBRID 순으로 시도하고 실패하면 자막만 유지한다. */
class Speaker(private val context: Context) {
    enum class State(val label: String) {
        INITIALIZING("준비 중"),
        ON_DEVICE("ON_DEVICE"),
        HYBRID("HYBRID"),
        SUBTITLE_ONLY("음성 꺼짐"),
    }

    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var eventListener: EventListener? = null
    private var attempt = 0
    private var generation = 0
    private var initializing = false
    private var waitingForDone = false
    var onState: ((State) -> Unit)? = null
    var onSubtitle: ((String) -> Unit)? = null
    var onSpeechFinished: (() -> Unit)? = null

    /** ON_DEVICE 초기화와 시험 발화를 시작한다. */
    fun start() {
        if (engine != null || waitingForDone) return
        attempt = 0
        tryMode(MODES[attempt])
    }

    /** 현재 음성 모드로 말하고 자막을 항상 함께 보낸다. */
    fun speak(text: String): Boolean {
        onSubtitle?.invoke(text)
        val current = engine
        if (current == null) {
            log("tts subtitle-only text=$text")
            return false
        }
        try {
            waitingForDone = true
            current.speak(text, false)
            log("tts speak mode=${MODES.getOrNull(attempt)} text=$text")
            armTimeout(generation)
            return true
        } catch (error: Exception) {
            log("tts speak threw=$error")
            failCurrent("speak exception")
            return false
        }
    }

    /** 현재 발화가 끝나기를 기다리는지 알려 준다. */
    fun isSpeaking(): Boolean = initializing || waitingForDone

    /** 지정 모드의 TTS 엔진을 초기화한다. */
    private fun tryMode(mode: Mode) {
        releaseEngine()
        initializing = true
        generation += 1
        val thisGeneration = generation
        onState?.invoke(State.INITIALIZING)
        log("tts initialize mode=$mode")
        try {
            val created = TextToSpeech(context.applicationContext, mode)
            engine = created
            created.initialize(object : OnInitializeListener {
                /** 초기화 성공 뒤 이벤트 리스너와 시험 발화를 연결한다. */
                override fun onSuccess() {
                    if (thisGeneration != generation) return
                    log("tts initialize success mode=$mode")
                    if (!attachEvents(created, mode, thisGeneration)) return
                    initializing = false
                    onState?.invoke(if (mode == Mode.ON_DEVICE) State.ON_DEVICE else State.HYBRID)
                    speak(TEST_UTTERANCE)
                }

                /** 초기화 실패 시 다음 모드로 넘어간다. */
                override fun onFailure(e: ErrorType) {
                    if (thisGeneration != generation) return
                    log("tts initialize failure mode=$mode error=$e")
                    failCurrent("initialize failure $e")
                }
            })
        } catch (error: Exception) {
            log("tts initialize threw mode=$mode error=$error")
            failCurrent("initialize exception")
        }
    }

    /** 발화 이벤트를 현재 엔진에 등록한다. */
    private fun attachEvents(tts: TextToSpeech, mode: Mode, thisGeneration: Int): Boolean {
        val listener = object : EventListener {
            /** 발화 시작을 기록한다. */
            override fun onStart() { log("tts onStart mode=$mode") }

            /** 엔진 준비 완료를 기록한다. */
            override fun onReady() { log("tts onReady mode=$mode") }

            /** 정상 종료로 timeout을 취소한다. */
            override fun onDone() {
                if (thisGeneration != generation) return
                waitingForDone = false
                handler.removeCallbacksAndMessages(TIMEOUT_TOKEN)
                log("tts onDone mode=$mode")
                onSpeechFinished?.invoke()
            }

            /** 엔진 오류 시 다음 모드로 넘어간다. */
            override fun onError(e: ErrorType) {
                if (thisGeneration != generation) return
                log("tts onError mode=$mode error=$e")
                failCurrent("event error $e")
            }

            /** 발화 중단을 기록한다. */
            override fun onStop() { log("tts onStop mode=$mode") }

            /** 음량 갱신은 M0에서 사용하지 않는다. */
            override fun onUpdatedRms(r: Double) = Unit
        }
        eventListener = listener
        return try {
            tts.addEventListener(listener)
            true
        } catch (error: Exception) {
            log("tts addEventListener failed=$error")
            failCurrent("listener exception")
            false
        }
    }

    /** 8초 안에 onDone이 없으면 현재 모드를 실패 처리한다. */
    private fun armTimeout(thisGeneration: Int) {
        handler.removeCallbacksAndMessages(TIMEOUT_TOKEN)
        handler.postAtTime({
            if (waitingForDone && thisGeneration == generation) {
                log("tts timeout mode=${MODES.getOrNull(attempt)} after=${TIMEOUT_MS}ms")
                failCurrent("timeout")
            }
        }, TIMEOUT_TOKEN, android.os.SystemClock.uptimeMillis() + TIMEOUT_MS)
    }

    /** 현재 모드를 정리하고 HYBRID 또는 자막 전용으로 전환한다. */
    private fun failCurrent(reason: String) {
        val interruptedSpeech = waitingForDone
        initializing = false
        waitingForDone = false
        handler.removeCallbacksAndMessages(TIMEOUT_TOKEN)
        releaseEngine()
        attempt += 1
        if (attempt < MODES.size) {
            log("tts fallback reason=$reason next=${MODES[attempt]}")
            tryMode(MODES[attempt])
        } else {
            generation += 1
            log("tts fallback reason=$reason next=SUBTITLE_ONLY")
            onState?.invoke(State.SUBTITLE_ONLY)
        }
        if (interruptedSpeech) onSpeechFinished?.invoke()
    }

    /** 현재 TTS 리스너와 엔진을 안전하게 해제한다. */
    private fun releaseEngine() {
        val current = engine ?: return
        try {
            eventListener?.let(current::removeEventListener)
            current.release()
        } catch (error: Exception) {
            log("tts release failed=$error")
        } finally {
            eventListener = null
            engine = null
        }
    }

    /** timeout과 TTS 엔진을 정리한다. */
    fun stop() {
        generation += 1
        initializing = false
        waitingForDone = false
        handler.removeCallbacksAndMessages(null)
        releaseEngine()
        log("tts stopped")
    }

    /** GILBEOT 태그로 정보 로그를 남긴다. */
    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "GILBEOT"
        const val TEST_UTTERANCE = "길벗이 준비됐습니다."
        const val TIMEOUT_MS = 8_000L
        val TIMEOUT_TOKEN = Any()
        val MODES = listOf(Mode.ON_DEVICE, Mode.HYBRID)
    }
}
