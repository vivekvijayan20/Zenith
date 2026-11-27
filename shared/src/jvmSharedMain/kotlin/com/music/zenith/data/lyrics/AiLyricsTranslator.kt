package com.music.zenith.data.lyrics

import com.music.zenith.data.Http
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** The AI services lyrics can be translated with, in their default order. */
enum class AiProvider(val id: String, val label: String, val defaultModel: String) {
    GEMINI("gemini", "Gemini", "gemini-flash-latest"),
    GROQ("groq", "Groq", "llama-3.3-70b-versatile"),
    OPENROUTER("openrouter", "OpenRouter", "openrouter/free"),
    ;

    companion object {
        fun fromId(id: String): AiProvider? = entries.firstOrNull { it.id == id }
    }
}

/** One provider's switch, model and API keys. Keys are tried in order. */
data class AiProviderConfig(
    val provider: AiProvider,
    val enabled: Boolean = true,
    val model: String = "",
    val keys: List<String> = emptyList(),
) {
    val effectiveModel: String get() = model.trim().ifEmpty { provider.defaultModel }
    val usable: Boolean get() = enabled && keys.isNotEmpty()
}

/** [providers] is in priority order: the first one is tried first. */
data class AiTranslateConfig(
    val enabled: Boolean = false,
    val providers: List<AiProviderConfig> = AiProvider.entries.map { AiProviderConfig(it) },
) {
    /** The providers a translation will actually try, in order. */
    val chain: List<AiProviderConfig> get() = if (enabled) providers.filter { it.usable } else emptyList()

    fun of(provider: AiProvider): AiProviderConfig =
        providers.firstOrNull { it.provider == provider } ?: AiProviderConfig(provider)
}

/** Keys pasted as "a, b" or one per line, without blanks or repeats. */
fun parseAiKeys(raw: String): List<String> =
    raw.split('\n', '\r', ',', ';', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/**
 * Translates lyric lines with the AI providers the user switched on.
 *
 * Providers are tried in the configured order and, inside one provider, its
 * keys in order. A key that is rate limited or rejected is parked for a while
 * so the next song does not wait on it again. When nothing answers this
 * returns null and [LyricsTranslation] carries on with the built-in translation.
 */
object AiLyricsTranslator {
    /** Set by the app; read on every translation so setting changes apply at once. */
    @Volatile
    var configProvider: () -> AiTranslateConfig = { AiTranslateConfig() }

    /** "Groq · llama-3.3-70b-versatile" for the last provider that answered. */
    val lastUsed = MutableStateFlow("")

    class Answer(val lines: List<String>, val source: String)
    class Attempt(val keyNumber: Int, val error: String?)
    class ProviderRun(val provider: AiProvider, val answer: Answer?, val attempts: List<Attempt>)
    class ProviderTest(val provider: AiProvider, val ok: Boolean, val detail: String)

    private sealed interface Outcome {
        class Ok(val answer: Answer) : Outcome
        class Fail(
            val reason: String,
            val blockMs: Long = 0L,
            val wholeProvider: Boolean = false,
            val retrySame: Boolean = false,
        ) : Outcome
    }

    private class HttpResult(val code: Int, val body: String, val retryAfterMs: Long?)

    private const val MAX_CHUNK_CHARS = 6_000
    private const val MAX_CHUNK_LINES = 90
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val GEMINI_SAFETY = listOf(
        "HARM_CATEGORY_HARASSMENT",
        "HARM_CATEGORY_HATE_SPEECH",
        "HARM_CATEGORY_SEXUALLY_EXPLICIT",
        "HARM_CATEGORY_DANGEROUS_CONTENT",
    )
    private val thinkRegex = Regex("(?s)<think>.*?</think>")
    private val json = Json { ignoreUnknownKeys = true }
    private val keyBlocked = ConcurrentHashMap<String, Long>()
    private val providerBlocked = ConcurrentHashMap<String, Long>()
    private val client by lazy {
        Http.client.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** "Indonesian (id)": a name the model understands, with the exact tag. */
    fun targetName(tag: String): String {
        val locale = Locale.forLanguageTag(tag.replace('_', '-'))
        val name = locale.getDisplayName(Locale.ENGLISH).ifBlank { tag }
        return "$name ($tag)"
    }

    /**
     * Every text translated, or null when no provider could do it. The first
     * provider that answers a chunk wins; later chunks start from the top again.
     */
    suspend fun translateAll(
        texts: List<String>,
        targetTag: String,
        config: AiTranslateConfig = configProvider(),
    ): Answer? {
        val chain = config.chain
        if (chain.isEmpty() || texts.isEmpty()) return null
        val target = targetName(targetTag)
        val lines = ArrayList<String>(texts.size)
        val votes = HashMap<String, Int>()
        for (chunk in chunk(texts)) {
            var answer: Answer? = null
            for (cfg in chain) {
                val run = runProvider(cfg, chunk, target, ignoreCooldown = false)
                if (run.answer != null) {
                    answer = run.answer
                    lastUsed.value = "${cfg.provider.label} · ${usedModel[cfg.provider] ?: cfg.effectiveModel}"
                    break
                }
            }
            if (answer == null) return null
            lines += answer.lines
            votes.merge(answer.source, chunk.sumOf { it.length }, Int::plus)
        }
        val source = votes.maxByOrNull { it.value }?.key.orEmpty()
        return Answer(lines, source)
    }

    /** Sends one short sentence to every active provider, ignoring any cooldown. */
    suspend fun testAll(config: AiTranslateConfig = configProvider()): List<ProviderTest> =
        config.providers.filter { it.usable }.map { cfg ->
            val run = runProvider(cfg, listOf("Hello, how are you?"), "Indonesian (id)", ignoreCooldown = true)
            val ok = run.answer != null
            val detail = if (ok) {
                "OK · key #${run.attempts.last().keyNumber}"
            } else {
                run.attempts.joinToString("; ") { "#${it.keyNumber} ${it.error}" }
            }
            ProviderTest(cfg.provider, ok, detail)
        }

    class ModelList(val models: List<String>, val error: String?)
    class KeyTest(val keyNumber: Int, val ok: Boolean, val detail: String)

    private val autoModels = ConcurrentHashMap<String, Pair<String, Long>>()
    private val usedModel = ConcurrentHashMap<AiProvider, String>()
    private val NOT_FOR_TEXT = listOf(
        "embed", "whisper", "tts", "guard", "orpheus", "imagen", "veo", "image",
        "audio", "live", "aqa", "robotics", "moderation", "rerank", "transcribe",
    )

    /** The models this key can use, straight from the provider, best choice first. */
    suspend fun listModels(provider: AiProvider, key: String): ModelList {
        val request = Request.Builder().header("Accept", "application/json").apply {
            when (provider) {
                AiProvider.GEMINI ->
                    url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
                        .header("x-goog-api-key", key)
                AiProvider.GROQ ->
                    url("https://api.groq.com/openai/v1/models").header("Authorization", "Bearer $key")
                AiProvider.OPENROUTER ->
                    url("https://openrouter.ai/api/v1/models").header("Authorization", "Bearer $key")
            }
        }.get().build()
        val http = try {
            client.newCall(request).awaitResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            return ModelList(emptyList(), "network ${error.javaClass.simpleName}")
        }
        if (http.code !in 200..299) return ModelList(emptyList(), httpReason(http))
        val ids = runCatching { parseModels(provider, http.body) }.getOrNull().orEmpty()
        return if (ids.isEmpty()) ModelList(emptyList(), "no models returned") else ModelList(ids, null)
    }

    /** Checks every key of one provider: valid, and does the chosen model answer. */
    suspend fun testKeys(cfg: AiProviderConfig): List<KeyTest> = cfg.keys.mapIndexed { index, key ->
        val number = index + 1
        val list = listModels(cfg.provider, key)
        val listError = list.error
        if (listError != null) return@mapIndexed KeyTest(number, false, listError)
        val model = cfg.model.trim().ifEmpty { list.models.first() }
        when (val outcome = requestOnce(cfg.provider, model, key, listOf("Hello, how are you?"), "Indonesian (id)")) {
            is Outcome.Ok -> KeyTest(number, true, "OK · ${list.models.size} models · $model")
            is Outcome.Fail -> KeyTest(number, false, "${outcome.reason} · $model")
        }
    }

    private fun httpReason(http: HttpResult): String = when {
        http.code == 401 || http.code == 403 ||
            (http.code == 400 && http.body.contains("API key", true)) -> "HTTP ${http.code} key rejected"
        http.code == 429 -> "HTTP 429 rate limited"
        http.code == 402 -> "HTTP 402 no credit"
        else -> "HTTP ${http.code}"
    }

    private fun parseModels(provider: AiProvider, body: String): List<String> {
        val root = json.parseToJsonElement(body).jsonObject
        val ids = when (provider) {
            AiProvider.GEMINI -> root["models"]!!.jsonArray
                .filter { model ->
                    model.jsonObject["supportedGenerationMethods"]?.jsonArray
                        ?.any { it.jsonPrimitive.contentOrNull == "generateContent" } == true
                }
                .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/") }
            else -> root["data"]!!.jsonArray
                .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
        }
        return ids
            .filterNot { id -> NOT_FOR_TEXT.any { id.contains(it, ignoreCase = true) } }
            .distinct()
            .sortedWith(compareBy<String> { rank(provider, it) }.thenByDescending { it })
    }

    /** Lower sorts first: the model a translation should reach for before the rest. */
    private fun rank(provider: AiProvider, id: String): Int = when (provider) {
        AiProvider.GEMINI -> when {
            id == "gemini-flash-latest" -> 0
            id.contains("flash") && !id.contains("lite") -> 1
            id.contains("flash") -> 2
            else -> 3
        }
        AiProvider.GROQ -> when {
            id == "llama-3.3-70b-versatile" -> 0
            id.contains("llama") -> 1
            else -> 2
        }
        AiProvider.OPENROUTER -> when {
            id == "openrouter/free" -> 0
            id.endsWith(":free") -> 1
            else -> 2
        }
    }

    /** The model to use when none is chosen: the best one this key can actually see. */
    private suspend fun autoModel(provider: AiProvider, key: String): String {
        val id = "${provider.id}:${key.hashCode()}"
        val cached = autoModels[id]
        if (cached != null && cached.second > System.currentTimeMillis()) return cached.first
        val picked = listModels(provider, key).models.firstOrNull() ?: return provider.defaultModel
        autoModels[id] = picked to (System.currentTimeMillis() + 6 * HOUR)
        return picked
    }

    private suspend fun runProvider(
        cfg: AiProviderConfig,
        texts: List<String>,
        target: String,
        ignoreCooldown: Boolean,
    ): ProviderRun {
        val providerId = "${cfg.provider.id}:${cfg.model.trim().ifEmpty { "auto" }}"
        if (!ignoreCooldown && (providerBlocked[providerId] ?: 0L) > System.currentTimeMillis()) {
            return ProviderRun(cfg.provider, null, emptyList())
        }
        val attempts = mutableListOf<Attempt>()
        for ((index, key) in cfg.keys.withIndex()) {
            val keyId = "${cfg.provider.id}:${key.hashCode()}"
            if (!ignoreCooldown && (keyBlocked[keyId] ?: 0L) > System.currentTimeMillis()) {
                attempts += Attempt(index + 1, "cooldown")
                continue
            }
            val model = cfg.model.trim().ifEmpty { autoModel(cfg.provider, key) }
            when (val outcome = request(cfg.provider, model, key, texts, target)) {
                is Outcome.Ok -> {
                    attempts += Attempt(index + 1, null)
                    usedModel[cfg.provider] = model
                    return ProviderRun(cfg.provider, outcome.answer, attempts)
                }
                is Outcome.Fail -> {
                    attempts += Attempt(index + 1, outcome.reason)
                    if (outcome.blockMs > 0L) {
                        val until = System.currentTimeMillis() + outcome.blockMs
                        if (outcome.wholeProvider) providerBlocked[providerId] = until else keyBlocked[keyId] = until
                    }
                    if (outcome.wholeProvider) break
                }
            }
        }
        return ProviderRun(cfg.provider, null, attempts)
    }

    private suspend fun request(
        provider: AiProvider,
        model: String,
        key: String,
        texts: List<String>,
        target: String,
    ): Outcome {
        var failure: Outcome.Fail = Outcome.Fail("no answer")
        for (attempt in 0..1) {
            when (val outcome = requestOnce(provider, model, key, texts, target)) {
                is Outcome.Ok -> return outcome
                is Outcome.Fail -> {
                    failure = outcome
                    if (!outcome.retrySame) return outcome
                }
            }
        }
        return failure
    }

    private suspend fun requestOnce(
        provider: AiProvider,
        model: String,
        key: String,
        texts: List<String>,
        target: String,
    ): Outcome {
        val userJson = buildJsonObject { putJsonArray("lines") { texts.forEach { add(it) } } }.toString()
        val http = try {
            client.newCall(buildRequest(provider, model, key, userJson, target)).awaitResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            return Outcome.Fail("network ${error.javaClass.simpleName}", wholeProvider = true)
        }
        val keyRejected = http.code == 400 && http.body.contains("API key", ignoreCase = true)
        return when {
            http.code in 200..299 -> parse(provider, http.body, texts)
            http.code == 401 || http.code == 403 || keyRejected ->
                Outcome.Fail("HTTP ${http.code} key rejected", blockMs = 6 * HOUR)
            http.code == 402 -> Outcome.Fail("HTTP 402 no credit", blockMs = HOUR)
            http.code == 429 ->
                Outcome.Fail("HTTP 429 rate limited", blockMs = http.retryAfterMs ?: (2 * MINUTE))
            http.code == 404 ->
                Outcome.Fail("HTTP 404 model not found", blockMs = 10 * MINUTE, wholeProvider = true)
            http.code >= 500 -> Outcome.Fail("HTTP ${http.code}", blockMs = 30_000L, wholeProvider = true)
            else -> Outcome.Fail("HTTP ${http.code}", wholeProvider = true)
        }
    }

    private fun buildRequest(
        provider: AiProvider,
        model: String,
        key: String,
        userJson: String,
        target: String,
    ): Request {
        val system = systemPrompt(target)
        val builder = Request.Builder().header("Accept", "application/json")
        val body: JsonObject = when (provider) {
            AiProvider.GEMINI -> {
                builder
                    .url("https://generativelanguage.googleapis.com/v1beta/models/${model.removePrefix("models/")}:generateContent")
                    .header("x-goog-api-key", key)
                buildJsonObject {
                    putJsonObject("systemInstruction") {
                        putJsonArray("parts") { addJsonObject { put("text", system) } }
                    }
                    putJsonArray("contents") {
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("parts") { addJsonObject { put("text", userJson) } }
                        }
                    }
                    putJsonObject("generationConfig") {
                        put("temperature", 0.2)
                        put("responseMimeType", "application/json")
                    }
                    putJsonArray("safetySettings") {
                        GEMINI_SAFETY.forEach { category ->
                            addJsonObject {
                                put("category", category)
                                put("threshold", "BLOCK_NONE")
                            }
                        }
                    }
                }
            }
            else -> {
                builder
                    .url(if (provider == AiProvider.GROQ) GROQ_URL else OPENROUTER_URL)
                    .header("Authorization", "Bearer $key")
                if (provider == AiProvider.OPENROUTER) builder.header("X-Title", "Zenith")
                buildJsonObject {
                    put("model", model)
                    put("temperature", 0.2)
                    if (provider == AiProvider.GROQ) {
                        putJsonObject("response_format") { put("type", "json_object") }
                    }
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "system")
                            put("content", system)
                        }
                        addJsonObject {
                            put("role", "user")
                            put("content", userJson)
                        }
                    }
                }
            }
        }
        return builder.post(body.toString().toRequestBody(JSON_MEDIA)).build()
    }

    private fun parse(provider: AiProvider, body: String, texts: List<String>): Outcome {
        val text = runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            when (provider) {
                AiProvider.GEMINI -> root["candidates"]!!.jsonArray[0].jsonObject["content"]!!
                    .jsonObject["parts"]!!.jsonArray
                    .filter { it.jsonObject["thought"]?.jsonPrimitive?.booleanOrNull != true }
                    .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
                else -> root["choices"]!!.jsonArray[0].jsonObject["message"]!!
                    .jsonObject["content"]!!.jsonPrimitive.content
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return Outcome.Fail("empty answer", retrySame = true)

        val cleaned = text.replace(thinkRegex, "")
        val open = cleaned.indexOf('{')
        val close = cleaned.lastIndexOf('}')
        val obj = if (open in 0 until close) {
            runCatching { json.parseToJsonElement(cleaned.substring(open, close + 1)).jsonObject }.getOrNull()
        } else {
            null
        }
        val array: JsonArray?
        val source: String
        if (obj != null) {
            array = obj["lines"] as? JsonArray
            source = obj["source"]?.jsonPrimitive?.contentOrNull.orEmpty()
        } else {
            val s = cleaned.indexOf('[')
            val e = cleaned.lastIndexOf(']')
            array = if (s in 0 until e) {
                runCatching { json.parseToJsonElement(cleaned.substring(s, e + 1)).jsonArray }.getOrNull()
            } else {
                null
            }
            source = ""
        }
        if (array == null) return Outcome.Fail("not JSON", retrySame = true)
        val lines = array.map { ((it as? JsonPrimitive)?.contentOrNull.orEmpty()).replace(Regex("\\s*\\n\\s*"), " ").trim() }
        if (lines.size != texts.size) return Outcome.Fail("line count ${lines.size}/${texts.size}", retrySame = true)
        return Outcome.Ok(Answer(lines.mapIndexed { i, line -> line.ifBlank { texts[i] } }, source.trim()))
    }

    private fun chunk(texts: List<String>): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var length = 0
        for (text in texts) {
            if (current.isNotEmpty() && (length + text.length > MAX_CHUNK_CHARS || current.size >= MAX_CHUNK_LINES)) {
                result += current
                current = mutableListOf()
                length = 0
            }
            current += text
            length += text.length
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private fun systemPrompt(target: String): String = """
        You are a professional song lyric translator.
        Translate every lyric line into $target. Keep the meaning, mood and poetic flow, sound natural rather than literal. Do not censor, explain or add notes.
        Rules:
        - The user sends JSON like {"lines": [...]}. Return exactly one translated line for every input line, in the same order and the same count. Never merge, split, skip or reorder lines.
        - A line that is already in $target, a name, or only ad-libs and sounds (ooh, la la, yeah) may stay as it is.
        - Detect the language the lyrics are written in and give its ISO 639-1 code (for example "ja", "ko", "es").
        - Reply with ONLY a JSON object, no markdown fences: {"source":"<code>","lines":["...","..."]}
    """.trimIndent()

    private suspend fun Call.awaitResult(): HttpResult = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = runCatching { it.body?.string().orEmpty() }.getOrDefault("")
                    val retry = it.header("Retry-After")?.toLongOrNull()?.times(1000L)
                    if (continuation.isActive) continuation.resume(HttpResult(it.code, text, retry))
                }
            }
        })
    }
}