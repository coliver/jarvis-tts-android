package com.jarvistts

import android.content.Context
import java.io.File

/** Discovery and persistence for user-swappable LLM models. Models the user
 *  drops in (e.g. `adb push mymodel.gguf`) live in the app's external
 *  files dir, which needs no runtime permissions and is reachable by adb or
 *  a file manager even under Android's scoped storage rules. The default
 *  model isn't bundled in the APK (that would balloon it by ~800MB) -- it's
 *  fetched over HTTP into internal storage on first launch instead, from
 *  the same public source used to develop this project.
 */
object ModelManager {
    private const val LLM_SUBDIR = "llm"
    private const val PREFS_NAME = "jarvis_model_prefs"
    private const val KEY_SELECTED_PATH = "selected_model_path"
    private const val KEY_SELECTED_VOICE = "selected_voice"
    private const val PERSONAS_ASSET = "personas.json"
    private val VOICE_EXTENSIONS = setOf("wav", "mp3", "flac", "ogg", "m4a", "aac")

    const val DEFAULT_LLM_FILENAME = "Llama-3.2-1B-Instruct-Q4_K_M.gguf"
    const val DEFAULT_LLM_URL =
        "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/$DEFAULT_LLM_FILENAME"

    // Git LFS sha256 oid for the file above, from the `x-linked-etag` response header on a
    // HEAD request to DEFAULT_LLM_URL -- HuggingFace's LFS-backed content hash, not something
    // computed locally. Update this if DEFAULT_LLM_URL is ever repointed at a different file.
    const val DEFAULT_LLM_SHA256 = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83"

    const val STT_FILENAME = "ggml-base.en-q5_1.bin"
    const val STT_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$STT_FILENAME"

    // Same provenance as DEFAULT_LLM_SHA256 above, for STT_URL.
    const val STT_SHA256 = "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"

    const val DEFAULT_VOICE = "jarvis"

    private const val REPLY_LENGTH_HINT =
        " Keep replies short, spoken-aloud length, one or two sentences unless asked for more."

    fun externalModelsDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, LLM_SUBDIR).apply { mkdirs() }

    /** Internal storage location the default (downloaded, not bundled) model
     *  lands in -- not synced to external storage, so it survives even if
     *  the user clears their drop-in folder, and isn't visible/removable
     *  via a file manager. Name kept as "bundled" for now since MainActivity
     *  still references it; the model itself is fetched over HTTP, not from
     *  APK assets, once MainActivity's loaders are updated -- see message to
     *  the UI-owning session.
     */
    fun bundledModelsDir(context: Context): File = File(context.filesDir, LLM_SUBDIR).apply { mkdirs() }

    /** All .gguf files available to load, external drop-ins first, sorted by name. */
    fun listAvailableModels(context: Context): List<File> {
        fun ggufsIn(dir: File) = dir.listFiles { f -> f.isFile && f.extension == "gguf" }.orEmpty().toList()
        val external = ggufsIn(externalModelsDir(context))
        val downloaded = ggufsIn(bundledModelsDir(context))
        return (external + downloaded).distinctBy { it.name }.sortedBy { it.name }
    }

    fun selectedModelPath(context: Context): String? = prefs(context).getString(KEY_SELECTED_PATH, null)

    fun setSelectedModelPath(
        context: Context,
        path: String,
    ) {
        prefs(context).edit().putString(KEY_SELECTED_PATH, path).apply()
    }

    /** Voice reference clips available for cloning, by name (no extension),
     *  sorted. `voicesDir` is the app-internal copy of assets/voices/, not a
     *  drop-in dir like externalModelsDir -- adding a voice today means
     *  bundling a new clip in assets and rebuilding, there's no adb-push path
     *  for this yet.
     */
    fun listAvailableVoices(voicesDir: File): List<String> =
        voicesDir.listFiles { f -> f.isFile && f.extension.lowercase() in VOICE_EXTENSIONS }
            .orEmpty()
            .map { it.nameWithoutExtension }
            .distinct()
            .sorted()

    fun selectedVoiceName(context: Context): String = prefs(context).getString(KEY_SELECTED_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE

    fun setSelectedVoiceName(
        context: Context,
        name: String,
    ) {
        prefs(context).edit().putString(KEY_SELECTED_VOICE, name).apply()
    }

    /** Character/system-prompt text per voice, keyed the same way voices
     *  are (the .wav's filename without extension). Loaded from a bundled
     *  JSON asset instead of hardcoded in MainActivity, so tuning a
     *  persona's wording or adding one for a new voice is a data edit, not
     *  a Kotlin change.
     */
    fun loadPersonas(context: Context): Map<String, String> =
        parsePersonas(context.assets.open(PERSONAS_ASSET).bufferedReader().use { it.readText() })

    /** Split out from [loadPersonas] so the parsing itself is testable under
     *  plain JUnit without an Android Context/AssetManager.
     */
    fun parsePersonas(json: String): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        return MiniJson.parse(json) as Map<String, String>
    }

    /** Persona text (with the spoken-reply-length hint appended) for a given
     *  voice. A voice with no entry in [personas] -- e.g. a voice clip added
     *  without a matching personas.json entry -- falls back to [DEFAULT_VOICE]'s
     *  persona. Split out from MainActivity so the fallback is testable
     *  under plain JUnit.
     */
    fun personaFor(
        personas: Map<String, String>,
        voiceName: String,
    ): String {
        val base = personas[voiceName] ?: personas.getValue(DEFAULT_VOICE)
        return base + REPLY_LENGTH_HINT
    }

    /** Which voice is actually selected on app load: the persisted choice if
     *  its clip is still present, else the first available voice, else
     *  [DEFAULT_VOICE] if no voice clips are bundled at all. Split out from
     *  MainActivity.refreshAvailableVoices so this fallback chain -- and the
     *  persona it ends up loading via [personaFor] -- is testable under
     *  plain JUnit.
     */
    fun resolveSelectedVoice(
        persistedVoice: String,
        availableVoices: List<String>,
    ): String = persistedVoice.takeIf { it in availableVoices } ?: availableVoices.firstOrNull() ?: DEFAULT_VOICE

    /** Which model name is shown as selected on app load: the persisted
     *  model's file name if it's still among [availableModelNames], else the
     *  first available model, else [DEFAULT_LLM_FILENAME] if no models are
     *  present at all. Mirrors [resolveSelectedVoice]'s fallback chain, so a
     *  deleted/renamed model file doesn't leave the UI showing a selection
     *  that isn't actually loadable. Split out from
     *  MainActivity.refreshAvailableModels for the same reason. Actual
     *  load-time recovery for a persisted path that no longer exists is
     *  handled separately by MainActivity.ensureLlmLoaded.
     */
    fun resolveSelectedModelName(
        persistedPath: String?,
        availableModelNames: List<String>,
    ): String {
        val persistedName = persistedPath?.let { File(it).name }
        return persistedName?.takeIf { it in availableModelNames }
            ?: availableModelNames.firstOrNull()
            ?: DEFAULT_LLM_FILENAME
    }

    // Below this, a ~770MB gguf load plus whisper.cpp/TTS's own working set risks a
    // native allocation failure (see llm_jni_bridge.cpp's try/catch around llama_init,
    // which turns that into a recoverable "llama_init failed" instead of a process
    // crash -- but a heads-up before the attempt is friendlier than just letting the
    // load fail). A rough floor, not a measured cliff for a specific device.
    private const val LOW_MEMORY_THRESHOLD_BYTES = 3L * 1024 * 1024 * 1024

    /** [totalRamBytes] <= 0 means the caller couldn't read it (e.g. ActivityManager
     *  unavailable); treated as "don't know", not "low", so this never warns from an
     *  unrelated failure to query memory.
     */
    fun isLowMemoryDevice(totalRamBytes: Long): Boolean = totalRamBytes in 1..LOW_MEMORY_THRESHOLD_BYTES

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
