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

    const val STT_FILENAME = "ggml-tiny.en-q5_1.bin"
    const val STT_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$STT_FILENAME"

    const val DEFAULT_VOICE = "jarvis"

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

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
