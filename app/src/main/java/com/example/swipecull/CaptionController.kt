package com.example.swipecull

import android.app.Application
import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Cue(val startMs: Long, val endMs: Long, val text: String)

data class CaptionState(
    val modelReady: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    /** Spoken language code, or "auto". */
    val sourceLang: String = "auto",
    /** Caption language code, or "original" to show what was said. */
    val targetLang: String = "original",
    /** Uri of the video being captioned right now (null = idle). */
    val workingOn: String? = null,
    val status: String? = null,
    /** Uri the cues below belong to. */
    val cuesFor: String? = null,
    val cues: List<Cue> = emptyList(),
    val detectedLang: String? = null,
    val show: Boolean = true,
    val error: String? = null,
)

/**
 * On-demand captions: Whisper (offline, via whisper.cpp) transcribes the video's audio, and
 * ML Kit translates the text when you pick a different caption language. Results are cached.
 */
class CaptionController(app: Application, private val scope: CoroutineScope) {
    private val ctx: Context = app
    private val prefs = app.getSharedPreferences("swipecull_cc", Context.MODE_PRIVATE)
    private val modelFile = File(app.filesDir, "models/$MODEL_NAME")
    private val cacheDir = File(app.filesDir, "captions").apply { mkdirs() }

    private val _state = MutableStateFlow(
        CaptionState(
            modelReady = modelFile.exists() && modelFile.length() > MIN_MODEL_BYTES,
            sourceLang = prefs.getString("source", "auto") ?: "auto",
            targetLang = prefs.getString("target", "original") ?: "original",
        ),
    )
    val state: StateFlow<CaptionState> = _state.asStateFlow()

    private var job: Job? = null
    private var downloadJob: Job? = null

    /** Transcript of the current video (kept so changing the caption language doesn't redo it). */
    private class Transcript(var lang: String, val segs: MutableList<Cue>, val translations: MutableMap<String, List<String>>)

    private var current: Pair<String, Transcript>? = null // uri -> transcript

    fun setSource(code: String) {
        prefs.edit().putString("source", code).apply()
        _state.update { it.copy(sourceLang = code) }
    }

    fun setTarget(code: String) {
        prefs.edit().putString("target", code).apply()
        _state.update { it.copy(targetLang = code) }
        current?.let { (uri, t) -> publish(uri, t) }
    }

    fun setShow(on: Boolean) = _state.update { it.copy(show = on) }

    // ------------------------------------------------------------------ model download

    fun downloadModel() {
        if (_state.value.downloading) return
        _state.update { it.copy(downloading = true, downloadProgress = 0f, error = null) }
        downloadJob = scope.launch(Dispatchers.IO) {
            val part = File(modelFile.parentFile, "$MODEL_NAME.part")
            try {
                modelFile.parentFile?.mkdirs()
                val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                if (conn.responseCode != 200) error("Server answered ${conn.responseCode}")
                val total = conn.contentLengthLong
                var got = 0L
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (!isActive) throw CancellationException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            if (total > 0) _state.update { it.copy(downloadProgress = got.toFloat() / total) }
                        }
                    }
                }
                if (got < MIN_MODEL_BYTES || (total > 0 && got != total)) error("The download was incomplete")
                if (modelFile.exists()) modelFile.delete()
                part.renameTo(modelFile)
                _state.update { it.copy(downloading = false, modelReady = true) }
            } catch (e: CancellationException) {
                part.delete()
                _state.update { it.copy(downloading = false) }
            } catch (e: Exception) {
                part.delete()
                _state.update { it.copy(downloading = false, error = "Download failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun deleteModel() {
        modelFile.delete()
        _state.update { it.copy(modelReady = false) }
    }

    // ------------------------------------------------------------------ per video

    /** Called whenever the video on screen changes: drops old cues, loads any saved captions. */
    fun loadFor(file: MediaFile) {
        val uri = file.uri.toString()
        if (_state.value.workingOn != null && _state.value.workingOn != uri) cancel()
        if (current?.first == uri) return
        current = null
        _state.update { it.copy(cuesFor = null, cues = emptyList(), error = null, detectedLang = null) }
        val t = readCache(file) ?: return
        current = uri to t
        publish(uri, t)
    }

    /** Called when the current item is a photo or has changed to nothing. */
    fun clear() {
        if (_state.value.workingOn != null) cancel()
        current = null
        _state.update { it.copy(cuesFor = null, cues = emptyList(), error = null) }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.update { it.copy(workingOn = null, status = null) }
    }

    fun generate(file: MediaFile) {
        if (_state.value.workingOn != null) return
        if (!_state.value.modelReady) {
            _state.update { it.copy(error = "Download the speech model first") }
            return
        }
        val uri = file.uri.toString()
        val source = _state.value.sourceLang
        val target = _state.value.targetLang
        _state.update { it.copy(workingOn = uri, status = "Starting…", error = null, show = true) }

        job = scope.launch(Dispatchers.Default) {
            try {
                var t = current?.takeIf { it.first == uri }?.second ?: readCache(file)
                if (t == null) {
                    t = transcribe(file, uri, source)
                    current = uri to t
                    writeCache(file, t)
                }
                if (target != "original" && target != t.lang && t.translations[target] == null) {
                    _state.update { it.copy(status = "Translating…") }
                    t.translations[target] = translate(t.segs.map { it.text }, t.lang, target)
                    writeCache(file, t)
                }
                current = uri to t
                publish(uri, t)
                val empty = t.segs.isEmpty()
                _state.update {
                    it.copy(
                        workingOn = null, status = null,
                        error = if (empty) "No speech was found in this video" else null,
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(workingOn = null, status = null) }
                throw e
            } catch (e: Throwable) {
                _state.update {
                    it.copy(workingOn = null, status = null, error = e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private suspend fun transcribe(file: MediaFile, uri: String, source: String): Transcript {
        _state.update { it.copy(status = "Loading speech model…") }
        val handle = withContext(Dispatchers.Default) { WhisperLib.nativeInit(modelFile.absolutePath) }
        if (handle == 0L) error("Couldn't load the speech model (try deleting and downloading it again)")
        val t = Transcript(if (source == "auto") "" else source, ArrayList(), HashMap())
        try {
            var lang = source
            val hasAudio = AudioDecoder(ctx, file.uri).decode(
                CHUNK_SEC, isActive = { job?.isActive != false },
            ) { startMs, pcm ->
                _state.update { it.copy(status = "Transcribing… ${formatClock(startMs)}") }
                val raw = String(WhisperLib.nativeTranscribe(handle, pcm, lang, THREADS), Charsets.UTF_8)
                var any = false
                for (line in raw.lineSequence()) {
                    val parts = line.split('\t', limit = 4)
                    if (parts.size < 2) continue
                    if (parts[0] == "ERR") error("The speech model failed (code ${parts[1]})")
                    if (parts[0] == "lang") {
                        if (lang == "auto" && parts[1].isNotBlank()) {
                            // Lock in the detected language after the first chunk with speech.
                            t.lang = parts[1]
                        }
                        continue
                    }
                    if (parts.size < 4) continue
                    val text = parts[3].trim()
                    val noSpeech = parts[2].toFloatOrNull() ?: 0f
                    if (text.isEmpty() || noSpeech > 0.6f) continue
                    if (text.startsWith("[") && text.endsWith("]") && text.length < 24) continue // [BLANK_AUDIO]
                    t.segs += Cue(startMs + parts[0].toLong() * 10, startMs + parts[1].toLong() * 10, text)
                    any = true
                }
                if (any && lang == "auto" && t.lang.isNotBlank()) lang = t.lang
                if (any) publish(uri, t)
            }
            if (!hasAudio) error("This video has no sound")
        } finally {
            WhisperLib.nativeFree(handle)
        }
        if (t.lang.isBlank()) t.lang = if (source == "auto") "en" else source
        return t
    }

    private suspend fun translate(texts: List<String>, from: String, to: String): List<String> {
        val src = TranslateLanguage.fromLanguageTag(from)
            ?: error("Translating from ${languageName(from)} isn't supported")
        val dst = TranslateLanguage.fromLanguageTag(to)
            ?: error("Translating to ${languageName(to)} isn't supported")
        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build(),
        )
        try {
            _state.update { it.copy(status = "Downloading translation pack (first time only)…") }
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            _state.update { it.copy(status = "Translating…") }
            return texts.map { translator.translate(it).await() }
        } finally {
            translator.close()
        }
    }

    /** Sets the cues shown for [uri] from [t], using the chosen caption language. */
    private fun publish(uri: String, t: Transcript) {
        val target = _state.value.targetLang
        val translated = if (target != "original" && target != t.lang) t.translations[target] else null
        val cues = if (translated != null && translated.size == t.segs.size) {
            t.segs.mapIndexed { i, c -> c.copy(text = translated[i]) }
        } else t.segs.toList()
        _state.update { it.copy(cuesFor = uri, cues = cues, detectedLang = t.lang.ifBlank { null }) }
    }

    // ------------------------------------------------------------------ cache

    private fun cacheFile(file: MediaFile): File {
        val key = "${file.name}|${file.size}|${_state.value.sourceLang}"
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$hash.json")
    }

    private fun readCache(file: MediaFile): Transcript? = runCatching {
        val f = cacheFile(file)
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val segs = ArrayList<Cue>()
        val arr = o.getJSONArray("segs")
        for (i in 0 until arr.length()) {
            val s = arr.getJSONArray(i)
            segs += Cue(s.getLong(0), s.getLong(1), s.getString(2))
        }
        val tr = HashMap<String, List<String>>()
        val trObj = o.optJSONObject("tr")
        trObj?.keys()?.forEach { k ->
            val a = trObj.getJSONArray(k)
            tr[k] = List(a.length()) { i -> a.getString(i) }
        }
        Transcript(o.getString("lang"), segs, tr)
    }.getOrNull()

    private fun writeCache(file: MediaFile, t: Transcript) {
        runCatching {
            val o = JSONObject()
            o.put("lang", t.lang)
            o.put("segs", JSONArray().also { a ->
                t.segs.forEach { c -> a.put(JSONArray().put(c.startMs).put(c.endMs).put(c.text)) }
            })
            o.put("tr", JSONObject().also { tr ->
                t.translations.forEach { (k, v) -> tr.put(k, JSONArray(v)) }
            })
            cacheFile(file).writeText(o.toString())
        }
    }

    private fun formatClock(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        const val MODEL_NAME = "ggml-small-q5_1.bin"
        const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"
        const val MODEL_LABEL = "about 180 MB"
        private const val MIN_MODEL_BYTES = 100_000_000L
        private const val CHUNK_SEC = 120
        private const val THREADS = 4
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
}
