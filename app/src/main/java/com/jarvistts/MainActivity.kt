package com.jarvistts

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val SAMPLE_RATE = 24000
private const val STT_SAMPLE_RATE = 16000
private const val MAX_RECORD_SECONDS = 15
private const val SILENCE_HANG_MS = 1000
private const val MIN_SPEECH_MS = 300
private const val SILENCE_RMS_THRESHOLD = 400.0
private const val AMPLITUDE_NORMALIZER = 3000.0f
private const val LLM_N_CTX = 2048
private const val LLM_MAX_TOKENS = 200

private const val JARVIS_SYSTEM_PROMPT =
    "You are JARVIS, a crisp, dry-witted, unfailingly polite British AI assistant. " +
        "Keep replies short, spoken-aloud length, one or two sentences unless asked for more."

class MainActivity : ComponentActivity() {
    private var sttHandle: Long = 0
    private var llmHandle: Long = 0
    private var ttsHandle: Long = 0
    private var isBusy = false

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
                    onPauseToggle = ::onPauseToggle,
                )
            }
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                warmUp()
                refreshAvailableModels()
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
                uiState.turns.add(Turn(Speaker.JARVIS, reply.text, reply.durationMs))
                uiState.phase = Phase.SPEAKING
                beginStage("Speaking")
                speak(reply.text)
                uiState.phase = Phase.IDLE
                uiState.caption = "Tap to talk"
            } catch (e: Exception) {
                uiState.phase = Phase.ERROR
                uiState.caption = "Tap to try again"
                uiState.errorMessage = "Error: ${e.message}"
            } finally {
                ticker.cancel()
                isBusy = false
            }
        }
    }

    private suspend fun warmUp() =
        coroutineScope {
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
            withContext(Dispatchers.Main) { beginStage("Downloading speech model") }
            ModelDownloader.download(ModelManager.STT_URL, modelFile) { fraction ->
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
                withContext(Dispatchers.Main) { beginStage("Downloading language model") }
                ModelDownloader.download(ModelManager.DEFAULT_LLM_URL, modelFile) { fraction ->
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
        uiState.availableModels = ModelManager.listAvailableModels(this).map { it.name }
        uiState.selectedModelName =
            ModelManager.selectedModelPath(this)?.let { File(it).name }
                ?: ModelManager.DEFAULT_LLM_FILENAME
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

    private suspend fun askJarvis(userText: String): TimedResult =
        withContext(Dispatchers.Default) {
            if (llmHandle == 0L) {
                withContext(Dispatchers.Main) { beginStage("Loading language model") }
                ensureLlmLoaded()
                withContext(Dispatchers.Main) { beginStage("Thinking") }
            }

            val prompt = NativeLLM.nativeFormatPrompt(llmHandle, JARVIS_SYSTEM_PROMPT, userText)

            val genStart = System.currentTimeMillis()
            val reply = NativeLLM.nativeGenerate(llmHandle, prompt, LLM_MAX_TOKENS).trim()
            val genMs = System.currentTimeMillis() - genStart
            TimedResult(reply, genMs)
        }

    private suspend fun listenAndTranscribe(): TimedResult =
        withContext(Dispatchers.Default) {
            val recordStart = System.currentTimeMillis()
            val pcm = recordPcm { level -> uiState.pushAmplitude(level) }
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
                    .build()

            currentTtsTrack = track
            uiState.isPaused = false
            track.play()
            val streamCtx = NativeBridge.streamStart(ttsHandle, text, "jarvis-03")
            check(streamCtx != 0L) { "ptt_stream_start failed" }
            try {
                while (true) {
                    while (uiState.isPaused) {
                        delay(50)
                    }
                    val chunk = NativeBridge.streamRead(streamCtx) ?: break
                    if (chunk.isNotEmpty()) {
                        track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
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

    private fun copyAssetsOnce(
        assetDir: String,
        destDir: File,
    ) {
        if (destDir.exists() && destDir.listFiles()?.isNotEmpty() == true) return
        destDir.mkdirs()
        val files = assets.list(assetDir) ?: return
        for (name in files) {
            assets.open("$assetDir/$name").use { input ->
                File(destDir, name).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}
