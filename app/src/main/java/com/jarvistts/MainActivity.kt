package com.jarvistts

import android.Manifest
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val SAMPLE_RATE = 24000
private const val STT_SAMPLE_RATE = 16000
private const val MAX_RECORD_SECONDS = 15
private const val SILENCE_HANG_MS = 1000
private const val MIN_SPEECH_MS = 300
private const val SILENCE_RMS_THRESHOLD = 400.0
private const val AMPLITUDE_NORMALIZER = 3000.0f
private const val LLM_N_CTX = 2048
private const val LLM_MAX_TOKENS = 200

// Approximate download sizes shown in the not-on-Wi-Fi prompt; display only.
private const val STT_SIZE_MB = 57
private const val LLM_SIZE_MB = 770

// Rest of LLM_N_CTX after reserving room for the generated reply. Also has to
// cover the persona system prompt and the current utterance, not just history,
// since VoicePipeline.buildHistory's token count is an estimate rather than an
// exact tokenization -- this is deliberately conservative, not tight.
private const val LLM_HISTORY_TOKEN_BUDGET = LLM_N_CTX - LLM_MAX_TOKENS

// The platform minimum buffer size leaves almost no slack: any brief stall in
// the native TTS synthesis loop (thermal throttling, a GC pause, CPU
// contention right after LLM generation) drains it before the next chunk
// arrives, which AudioFlinger surfaces as an audible underrun/restart glitch
// (confirmed via logcat: "BUFFER TIMEOUT ... due to underrun"). A few times
// the minimum gives synthesis room to catch up without the listener noticing.
private const val AUDIO_TRACK_BUFFER_MULTIPLIER = 4

class MainActivity : ComponentActivity() {
    private var sttHandle: Long = 0
    private var llmHandle: Long = 0
    private var ttsHandle: Long = 0
    private var isBusy = false

    // Character/system-prompt text per voice, loaded from the bundled
    // personas.json asset (see ModelManager.loadPersonas) so the persona speaking matches the character
    // suggested by the cloned voice without a Kotlin change to tune one.
    // A voice with no entry falls back to the "jarvis" persona.
    private val personas: Map<String, String> by lazy { ModelManager.loadPersonas(this) }

    private fun personaFor(voiceName: String): String = ModelManager.personaFor(personas, voiceName)

    private val uiState = JarvisUiState()

    private val requestMicPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (!granted) {
                uiState.errorMessage = "Mic permission denied"
            }
        }

    @Volatile private var sttState = "pending"

    @Volatile private var llmState = "pending"

    @Volatile private var ttsState = "pending"

    @Volatile private var sttProgress = 0f

    @Volatile private var llmProgress = 0f
    private var stageStartedAt: Long = 0L
    private var currentTtsTrack: AudioTrack? = null

    @Volatile private var stopRequested = false

    private class UserStoppedException : Exception()

    private val sessionStore by lazy { SessionStore(File(filesDir, "sessions")) }
    private var currentSessionId: String? = null

    private fun beginStage(caption: String) {
        uiState.caption = caption
        stageStartedAt = System.currentTimeMillis()
        uiState.stageElapsedSeconds = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            JarvisTheme {
                JarvisScreen(
                    state = uiState,
                    micEnabled = uiState.phase == Phase.IDLE || uiState.phase == Phase.ERROR,
                    onMicTap = ::onMicTap,
                    onModelSelect = ::onModelSelected,
                    onVoiceSelect = ::onVoiceSelected,
                    onPauseToggle = ::onPauseToggle,
                    onStopTap = ::onStopTapped,
                    onNewSession = ::onNewSession,
                    onSessionSelect = ::onSessionSelect,
                    onSessionDelete = ::onSessionDelete,
                )
            }
        }

        refreshSessionList()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                warmUp()
                refreshAvailableModels()
                refreshAvailableVoices()
                uiState.phase = Phase.IDLE
                uiState.caption = "Tap to talk"
            } catch (e: Exception) {
                uiState.phase = Phase.ERROR
                uiState.caption = "Tap to try again"
                uiState.errorMessage = "Warm-up failed: ${e.message}"
            }
        }
    }

    private fun onMicTap() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (isBusy || uiState.phase == Phase.WARMING_UP) return
        isBusy = true
        uiState.errorMessage = null
        stopRequested = false
        CoroutineScope(Dispatchers.Main).launch {
            val ticker =
                launch {
                    while (isActive) {
                        uiState.stageElapsedSeconds = ((System.currentTimeMillis() - stageStartedAt) / 1000).toInt()
                        delay(200)
                    }
                }
            try {
                uiState.phase = Phase.LISTENING
                beginStage("Listening")
                val heard = listenAndTranscribe()
                uiState.turns.add(Turn(Speaker.USER, heard.text, heard.durationMs))
                uiState.phase = Phase.THINKING
                beginStage("Thinking")
                val reply = askJarvis(heard.text)
                if (stopRequested) throw UserStoppedException()
                uiState.turns.add(Turn(Speaker.JARVIS, reply.text, reply.durationMs))
                uiState.phase = Phase.SPEAKING
                beginStage("Speaking")
                speak(reply.text)
                uiState.phase = Phase.IDLE
                uiState.caption = "Tap to talk"
            } catch (e: UserStoppedException) {
                uiState.phase = Phase.IDLE
                uiState.caption = "Tap to talk"
            } catch (e: Exception) {
                uiState.phase = Phase.ERROR
                uiState.caption = "Tap to try again"
                uiState.errorMessage = "Error: ${e.message}"
            } finally {
                ticker.cancel()
                persistCurrentSession()
                isBusy = false
            }
        }
    }

    private fun refreshSessionList() {
        uiState.sessions = sessionStore.list()
    }

    /** Upserts the in-progress conversation to disk. Called after every turn
     *  attempt (success, user-stop, or error) so a killed/backgrounded app
     *  never loses more than the single in-flight turn. Runs synchronously
     *  on the main thread: it's a few KB of JSON to local storage, the same
     *  order of cost as the SharedPreferences write ModelManager already
     *  does inline, not worth a dispatcher hop.
     */
    private fun persistCurrentSession() {
        if (uiState.turns.isEmpty()) return
        try {
            val id = currentSessionId
            val saved =
                if (id == null) {
                    sessionStore.create(uiState.turns.toList())
                } else {
                    sessionStore.update(id, uiState.turns.toList())
                }
            currentSessionId = saved.id
            refreshSessionList()
        } catch (e: Exception) {
            android.util.Log.w("JarvisSession", "Failed to save session", e)
        }
    }

    /** Starts a fresh conversation. Saves the current one first if it has
     *  any turns, so switching to "New" never silently drops history.
     */
    private fun onNewSession() {
        if (isBusy || uiState.phase != Phase.IDLE) return
        persistCurrentSession()
        currentSessionId = null
        uiState.turns.clear()
        uiState.errorMessage = null
    }

    private fun onSessionSelect(id: String) {
        if (isBusy || uiState.phase != Phase.IDLE) return
        persistCurrentSession()
        val session = sessionStore.load(id) ?: return
        currentSessionId = session.id
        uiState.turns.clear()
        uiState.turns.addAll(session.turns)
        uiState.errorMessage = null
    }

    private fun onSessionDelete(id: String) {
        sessionStore.delete(id)
        if (id == currentSessionId) {
            currentSessionId = null
            uiState.turns.clear()
        }
        refreshSessionList()
    }

    /** Aborts whatever's in flight and snaps back to idle, rather than pausing
     *  (pause only exists for TTS playback). Each phase needs a different
     *  interruption mechanism since none of listenAndTranscribe/askJarvis/speak
     *  are cooperatively cancellable suspend loops -- they're blocked on either
     *  a blocking AudioRecord.read/AudioTrack.write call or a native function
     *  call, so a plain coroutine job.cancel() wouldn't interrupt them.
     */
    private fun onStopTapped() {
        when (uiState.phase) {
            Phase.LISTENING, Phase.THINKING, Phase.SPEAKING -> {
                stopRequested = true
                if (uiState.phase == Phase.THINKING && llmHandle != 0L) {
                    NativeLLM.nativeCancelGenerate(llmHandle)
                }
                if (uiState.phase == Phase.SPEAKING) {
                    uiState.isPaused = false
                    currentTtsTrack?.stop()
                }
            }
            else -> {}
        }
    }

    /** Best-effort advisory shown once at warm-up: doesn't block or change what gets
     *  loaded, just gives the user a heads-up before a native OOM (caught, see
     *  llm_jni_bridge.cpp's nativeInit, but still a load failure) surprises them.
     */
    private fun checkLowMemory() {
        val am = getSystemService(ActivityManager::class.java) ?: return
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        if (ModelManager.isLowMemoryDevice(info.totalMem)) {
            val totalMb = info.totalMem / (1024 * 1024)
            uiState.lowMemoryWarning =
                "This device has ~${totalMb}MB RAM. The default language model " +
                "(~770MB) may fail to load; if it does, try a smaller .gguf from the model picker."
        }
    }

    /** True if the active network is unmetered (Wi-Fi, ethernet) or its metered
     *  status can't be determined -- fails open, since this is a courtesy heads-up
     *  before a large download, not a hard data-saver guard.
     */
    private fun isOnUnmeteredNetwork(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private val meteredGate = Mutex()
    private var meteredDownloadConfirmed = false

    /** Suspends until the user picks a side of the "not on Wi-Fi" dialog when the
     *  active network is metered; returns immediately (no prompt) on Wi-Fi/ethernet
     *  or once the user has already said yes this session. Serialized so the
     *  parallel STT/LLM loads can't race two dialogs onto one uiState slot, and
     *  also called from the lazy re-load paths so a "Wait for Wi-Fi" answer at
     *  warm-up can't be bypassed by tapping the mic afterwards.
     */
    private suspend fun confirmMeteredDownloadIfNeeded(
        label: String,
        sizeMb: Int,
    ): Boolean =
        meteredGate.withLock {
            if (meteredDownloadConfirmed || isOnUnmeteredNetwork()) return@withLock true
            val decision = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                uiState.meteredDownloadPrompt =
                    MeteredDownloadPrompt(
                        label = label,
                        sizeMb = sizeMb,
                        onProceed = { decision.complete(true) },
                        onCancel = { decision.complete(false) },
                    )
            }
            val proceed = decision.await()
            withContext(Dispatchers.Main) { uiState.meteredDownloadPrompt = null }
            if (proceed) meteredDownloadConfirmed = true
            proceed
        }

    private suspend fun requireDownloadConfirmed(
        label: String,
        sizeMb: Int,
    ) {
        if (!confirmMeteredDownloadIfNeeded(label, sizeMb)) {
            throw IOException("Download cancelled: waiting for Wi-Fi")
        }
    }

    private class PendingDownload(val label: String, val sizeMb: Int)

    /** What warm-up is about to download, if anything, mirroring the same
     *  "file missing" checks ensureSttLoaded/ensureLlmLoaded use. Computed once
     *  up front so a single combined prompt covers both -- those two loads run
     *  in parallel, so gating each separately would race two dialogs onto one
     *  uiState slot and leave the loser's coroutine suspended forever.
     */
    private fun pendingDownload(): PendingDownload? {
        val sttMissing = !File(File(filesDir, "stt"), ModelManager.STT_FILENAME).exists()
        val hasSelectedModel = ModelManager.selectedModelPath(this)?.let { File(it).exists() } == true
        val llmMissing =
            !hasSelectedModel &&
                !File(ModelManager.bundledModelsDir(this), ModelManager.DEFAULT_LLM_FILENAME).exists()
        return when {
            sttMissing && llmMissing -> PendingDownload("speech and language models", STT_SIZE_MB + LLM_SIZE_MB)
            sttMissing -> PendingDownload("speech model", STT_SIZE_MB)
            llmMissing -> PendingDownload("language model", LLM_SIZE_MB)
            else -> null
        }
    }

    private suspend fun warmUp() =
        coroutineScope {
            checkLowMemory()
            pendingDownload()?.let { requireDownloadConfirmed(it.label, it.sizeMb) }
            val startedAt = System.currentTimeMillis()
            // whisper.cpp and omatts/onnxruntime have no load-progress hooks, so those two
            // just get an elapsed-time counter; llama.cpp exposes a real progress callback,
            // wired through in ensureLlmLoaded, so that one gets a genuine percentage.
            val ticker =
                launch(Dispatchers.Main) {
                    while (isActive) {
                        uiState.sttState = sttState
                        uiState.llmState = llmState
                        uiState.ttsState = ttsState
                        uiState.sttProgress = sttProgress
                        uiState.llmProgress = llmProgress
                        uiState.elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                        delay(200)
                    }
                }
            try {
                // Three independent engines, no shared state -- loading them concurrently
                // collapses wall-clock time to the slowest one instead of the sum of all three.
                awaitAll(
                    async(Dispatchers.IO) {
                        sttState = "loading..."
                        ensureSttLoaded()
                        sttState = "ready"
                    },
                    async(Dispatchers.IO) { ensureLlmLoaded() },
                    async(Dispatchers.IO) {
                        ttsState = "loading..."
                        ensureTtsLoaded()
                        ttsState = "ready"
                    },
                )
            } finally {
                ticker.cancel()
                uiState.sttState = sttState
                uiState.llmState = llmState
                uiState.ttsState = ttsState
            }
        }

    override fun onDestroy() {
        super.onDestroy()
        if (sttHandle != 0L) {
            NativeSTT.nativeDestroy(sttHandle)
            sttHandle = 0
        }
        if (llmHandle != 0L) {
            NativeLLM.nativeDestroy(llmHandle)
            llmHandle = 0
        }
        if (ttsHandle != 0L) {
            NativeBridge.destroy(ttsHandle)
            ttsHandle = 0
        }
    }

    private suspend fun ensureSttLoaded() {
        if (sttHandle != 0L) return
        val modelDir = File(filesDir, "stt")
        val modelFile = File(modelDir, ModelManager.STT_FILENAME)
        if (!modelFile.exists()) {
            requireDownloadConfirmed("speech model", STT_SIZE_MB)
            withContext(Dispatchers.Main) { beginStage("Downloading speech model") }
            ModelDownloader.download(ModelManager.STT_URL, modelFile, ModelManager.STT_SHA256) { fraction ->
                sttState = "downloading... ${(fraction * 100).toInt()}%"
                sttProgress = fraction
            }
        }
        sttHandle = NativeSTT.nativeInit(modelFile.absolutePath)
        check(sttHandle != 0L) { "whisper_init failed" }
    }

    private suspend fun ensureLlmLoaded() {
        if (llmHandle != 0L) return

        suspend fun bundledPath(): String {
            val modelDir = ModelManager.bundledModelsDir(this)
            val modelFile = File(modelDir, ModelManager.DEFAULT_LLM_FILENAME)
            if (!modelFile.exists()) {
                requireDownloadConfirmed("language model", LLM_SIZE_MB)
                withContext(Dispatchers.Main) { beginStage("Downloading language model") }
                ModelDownloader.download(ModelManager.DEFAULT_LLM_URL, modelFile, ModelManager.DEFAULT_LLM_SHA256) { fraction ->
                    llmState = "downloading... ${(fraction * 100).toInt()}%"
                    llmProgress = fraction
                }
            }
            return modelFile.absolutePath
        }
        val selected = ModelManager.selectedModelPath(this)?.takeIf { File(it).exists() }
        if (selected == null) {
            loadLlm(bundledPath())
            return
        }
        try {
            loadLlm(selected)
        } catch (e: Exception) {
            // Persisted selection no longer loads (deleted/corrupt file); don't get
            // stuck failing warm-up forever, fall back to the bundled default.
            val fallback = bundledPath()
            ModelManager.setSelectedModelPath(this, fallback)
            loadLlm(fallback)
        }
    }

    private fun loadLlm(modelPath: String) {
        llmState = "loading... 0%"
        llmProgress = 0f
        llmHandle =
            NativeLLM.nativeInit(modelPath, LLM_N_CTX) { fraction ->
                llmState = "loading... ${(fraction * 100).toInt()}%"
                llmProgress = fraction
            }
        check(llmHandle != 0L) { "llama_init failed" }
        llmState = "ready"
        llmProgress = 1f
    }

    private fun refreshAvailableModels() {
        val names = ModelManager.listAvailableModels(this).map { it.name }
        uiState.availableModels = names
        uiState.selectedModelName = ModelManager.resolveSelectedModelName(ModelManager.selectedModelPath(this), names)
    }

    /** Swaps the running LLM for a different model file the user dropped into
     *  ModelManager.externalModelsDir. Refuses while a conversation turn is
     *  in flight so we don't yank the handle out from under nativeGenerate.
     */
    private fun onModelSelected(fileName: String) {
        if (isBusy || uiState.phase != Phase.IDLE) return
        val target = ModelManager.listAvailableModels(this).firstOrNull { it.name == fileName } ?: return
        isBusy = true
        uiState.phase = Phase.SWITCHING_MODEL
        uiState.caption = "Switching model"
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) {
                    if (llmHandle != 0L) {
                        NativeLLM.nativeDestroy(llmHandle)
                        llmHandle = 0
                    }
                    loadLlm(target.absolutePath)
                }
                // Only persist the pick once it's actually loaded, so a bad file
                // doesn't get stuck as the permanent selection across restarts.
                ModelManager.setSelectedModelPath(this@MainActivity, target.absolutePath)
                uiState.selectedModelName = fileName
                uiState.phase = Phase.IDLE
                uiState.caption = "Tap to talk"
            } catch (e: Exception) {
                uiState.phase = Phase.ERROR
                uiState.caption = "Tap to try again"
                uiState.errorMessage = "Model switch failed: ${e.message}"
            } finally {
                isBusy = false
            }
        }
    }

    private fun refreshAvailableVoices() {
        val voicesDir = File(filesDir, "voices")
        val names = ModelManager.listAvailableVoices(voicesDir)
        uiState.availableVoices = names
        uiState.selectedVoiceName = ModelManager.resolveSelectedVoice(ModelManager.selectedVoiceName(this), names)
    }

    /** Switching voices never touches ttsHandle, the name is just passed to
     *  streamStart per utterance, so this is instant. Still idle-gated so a
     *  pick can't land mid-utterance and change the voice out from under an
     *  in-flight speak() call.
     */
    private fun onVoiceSelected(name: String) {
        if (isBusy || uiState.phase != Phase.IDLE) return
        if (name !in uiState.availableVoices) return
        ModelManager.setSelectedVoiceName(this, name)
        uiState.selectedVoiceName = name
    }

    private fun ensureTtsLoaded() {
        if (ttsHandle != 0L) return
        val modelsDir = File(filesDir, "models")
        val voicesDir = File(filesDir, "voices")
        copyAssetsOnce("models", modelsDir)
        copyAssetsOnce("voices", voicesDir)
        val tokenizerPath = File(modelsDir, "tokenizer.model").absolutePath

        ttsHandle =
            NativeBridge.create(
                modelsDir.absolutePath,
                voicesDir.absolutePath,
                tokenizerPath,
                "fp32",
                0.8f,
                10,
                2,
            )
        check(ttsHandle != 0L) { "ptt_create failed" }
        NativeBridge.warmup(ttsHandle)
    }

    private data class TimedResult(val text: String, val durationMs: Long)

    private val toolRunner by lazy { ToolRunner(this) }

    private suspend fun askJarvis(userText: String): TimedResult =
        withContext(Dispatchers.Default) {
            Tools.route(userText)?.let { call ->
                val result =
                    if (call.name == "web_search") {
                        withContext(Dispatchers.Main) { beginStage("Searching") }
                        WebSearch.search(call.args["query"].orEmpty())
                    } else {
                        withContext(Dispatchers.Main) { toolRunner.run(call) }
                    }
                android.util.Log.d("JarvisTools", "${call.name}${call.args} -> $result")
                return@withContext TimedResult(result, 0)
            }
            if (llmHandle == 0L) {
                withContext(Dispatchers.Main) { beginStage("Loading language model") }
                ensureLlmLoaded()
                withContext(Dispatchers.Main) { beginStage("Thinking") }
            }

            // uiState.turns already ends with this same user utterance (added by the
            // caller before askJarvis runs), so drop it here to avoid sending it twice.
            val history = VoicePipeline.buildHistory(uiState.turns.dropLast(1), LLM_HISTORY_TOKEN_BUDGET)
            val persona = personaFor(uiState.selectedVoiceName)
            val prompt =
                NativeLLM.nativeFormatPrompt(
                    llmHandle,
                    persona,
                    history.map { it.first }.toTypedArray(),
                    history.map { it.second }.toTypedArray(),
                    userText,
                )
            val genStart = System.currentTimeMillis()
            val reply = NativeLLM.nativeGenerate(llmHandle, prompt, LLM_MAX_TOKENS).trim()
            val genMs = System.currentTimeMillis() - genStart
            TimedResult(reply, genMs)
        }

    private suspend fun listenAndTranscribe(): TimedResult =
        withContext(Dispatchers.Default) {
            val recordStart = System.currentTimeMillis()
            val pcm = recordPcm { level -> uiState.pushAmplitude(level) }
            if (stopRequested) throw UserStoppedException()
            val recordMs = System.currentTimeMillis() - recordStart
            android.util.Log.d("JarvisTiming", "recorded ${pcm.size / STT_SAMPLE_RATE.toFloat()}s of audio in ${recordMs}ms")
            withContext(Dispatchers.Main) { beginStage("Transcribing") }

            if (sttHandle == 0L) {
                withContext(Dispatchers.Main) { beginStage("Loading speech model") }
                ensureSttLoaded()
                withContext(Dispatchers.Main) { beginStage("Transcribing") }
            }

            val transcribeStart = System.currentTimeMillis()
            val result = VoicePipeline.cleanTranscript(NativeSTT.nativeTranscribe(sttHandle, pcm))
            val transcribeMs = System.currentTimeMillis() - transcribeStart
            android.util.Log.d(
                "JarvisTiming",
                "whisper_full took ${transcribeMs}ms for ${pcm.size / STT_SAMPLE_RATE.toFloat()}s of audio -> \"$result\"",
            )
            if (stopRequested) throw UserStoppedException()
            TimedResult(result, transcribeMs)
        }

    @Suppress("MissingPermission")
    private fun recordPcm(onAmplitude: (Float) -> Unit): FloatArray {
        val minBufSize =
            AudioRecord.getMinBufferSize(
                STT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        val chunkSamples = STT_SAMPLE_RATE / 10 // 100ms chunks for VAD polling
        val bufSize = maxOf(minBufSize, chunkSamples * 4)
        val recorder =
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                STT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )

        val maxSamples = STT_SAMPLE_RATE * MAX_RECORD_SECONDS
        val out = ShortArray(maxSamples)
        val chunk = ShortArray(chunkSamples)
        var total = 0
        val silence = SilenceDetector(SILENCE_RMS_THRESHOLD, MIN_SPEECH_MS, SILENCE_HANG_MS)

        try {
            recorder.startRecording()
            while (total < maxSamples) {
                if (stopRequested) break
                val read = recorder.read(chunk, 0, chunkSamples)
                if (read <= 0) break

                val rms = VoicePipeline.rms(chunk, read)
                onAmplitude((rms / AMPLITUDE_NORMALIZER).toFloat())

                val room = maxSamples - total
                val toCopy = minOf(read, room)
                System.arraycopy(chunk, 0, out, total, toCopy)
                total += toCopy

                val chunkMs = (read * 1000L / STT_SAMPLE_RATE).toInt()
                if (silence.accept(rms, chunkMs)) break
            }
        } finally {
            recorder.stop()
            recorder.release()
        }

        return FloatArray(total) { i -> out[i] / 32768.0f }
    }

    private suspend fun speak(text: String) =
        withContext(Dispatchers.Default) {
            if (ttsHandle == 0L) {
                withContext(Dispatchers.Main) { beginStage("Loading voice model") }
                ensureTtsLoaded()
            }

            val minBufferBytes =
                AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val track =
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(minBufferBytes * AUDIO_TRACK_BUFFER_MULTIPLIER)
                    .build()

            currentTtsTrack = track
            uiState.isPaused = false
            track.play()
            val streamCtx = NativeBridge.streamStart(ttsHandle, Markdown.stripForSpeech(text), uiState.selectedVoiceName)
            check(streamCtx != 0L) { "ptt_stream_start failed" }
            var framesWritten = 0L
            try {
                while (true) {
                    if (stopRequested) break
                    while (uiState.isPaused) {
                        delay(50)
                    }
                    val chunk = NativeBridge.streamRead(streamCtx) ?: break
                    if (chunk.isNotEmpty()) {
                        track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                        framesWritten += chunk.size // mono float PCM: one sample is one frame
                    }
                }
                // write() only guarantees the data is queued, not that it's been rendered
                // yet -- stopping/releasing right after the last write clipped the tail of
                // every sentence. Wait for the track to actually catch up to what was
                // written before tearing it down, bounded so a stuck driver can't hang here.
                if (!stopRequested) {
                    val deadline = System.currentTimeMillis() + 3000
                    while (track.playbackHeadPosition < framesWritten && System.currentTimeMillis() < deadline) {
                        delay(20)
                    }
                }
            } finally {
                NativeBridge.streamEnd(streamCtx)
                track.stop()
                track.release()
                currentTtsTrack = null
            }
        }

    private fun onPauseToggle() {
        uiState.isPaused = !uiState.isPaused
        if (uiState.isPaused) currentTtsTrack?.pause() else currentTtsTrack?.play()
    }

    /** Per-file, not per-directory: an earlier install's copy left files
     *  behind in internal storage, and gating on "destDir is non-empty"
     *  meant a newly added bundled asset (e.g. a second voice clip) was
     *  silently never copied on an app update, only on a fresh install.
     *  Existing files are left alone, so this stays cheap (an exists()
     *  check per asset) on every launch after the first.
     */
    private fun copyAssetsOnce(
        assetDir: String,
        destDir: File,
    ) {
        destDir.mkdirs()
        val files = assets.list(assetDir) ?: return
        for (name in files) {
            val dest = File(destDir, name)
            if (dest.exists()) continue
            assets.open("$assetDir/$name").use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}
