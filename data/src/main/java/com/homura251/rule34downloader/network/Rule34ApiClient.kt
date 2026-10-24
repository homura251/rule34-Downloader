package com.homura251.rule34downloader.network

import com.homura251.rule34downloader.domain.*
import com.homura251.rule34downloader.domain.files.FileChecksum
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import kotlin.coroutines.resumeWithException

class ApiRateLimiter(private val intervalMillis: Long = 250) {
    private val lock = Mutex()
    private var nextAt = 0L
    suspend fun acquire() = lock.withLock {
        delay((nextAt - System.currentTimeMillis()).coerceAtLeast(0))
        nextAt = System.currentTimeMillis() + intervalMillis
    }
    suspend fun defer(millis: Long) = lock.withLock { nextAt = maxOf(nextAt, System.currentTimeMillis() + millis) }
}

class Rule34ApiClient(
    private val account: AccountRepository,
    client: OkHttpClient,
    private val limiter: ApiRateLimiter = ApiRateLimiter(),
    private val endpoint: HttpUrl = "https://api.rule34.xxx/index.php".toHttpUrl(),
    override val pageSize: Int = 200,
) : CatalogGateway {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    init { require(pageSize in 1..1000) }
    override suspend fun validate(credentials: ApiCredentials) {
        if (!credentials.userId.matches(Regex("[1-9][0-9]*")) || credentials.apiKey.isBlank())
            throw SourceFailure(FailureKind.AUTH, "请同时填写有效的 User ID 与 API Key。")
        request("post", mapOf("limit" to "1"), credentials)
    }
    override suspend fun getPost(postId: Long): Rule34Post {
        val posts = request("post", mapOf("id" to "$postId", "limit" to "1", "fields" to "tag_info")).map(::parsePost)
        if (posts.isEmpty()) throw SourceFailure(FailureKind.NOT_FOUND, "帖子 #$postId 不存在或已删除。")
        val post = posts.singleOrNull()?.takeIf { it.id == postId } ?: throw SourceFailure(FailureKind.BAD_RESPONSE,
            "原站返回的帖子信息与请求 #$postId 不一致，未替换已保存的信息。")
        post.availabilityError?.let { throw SourceFailure(FailureKind.BAD_RESPONSE, it) }
        return post
    }

    override suspend fun searchPosts(tag: String, afterPostId: Long, beforePostId: Long?): List<Rule34Post> {
        val boundary = beforePostId?.let { "id:<$it" } ?: afterPostId.takeIf { it > 0 }?.let { "id:>$it" }.orEmpty()
        return request("post", mapOf("tags" to listOf(tag, "sort:id:desc", boundary).filter(String::isNotBlank).joinToString(" "),
            "limit" to "$pageSize", "pid" to "0")).map(::parsePost)
    }

    override suspend fun resolveArtistTags(post: Rule34Post): List<Rule34Tag> {
        post.tagInfo?.let { tags ->
            if (post.tags.all { name -> tags.any { it.name == name } }) return tags.filter { it.type == 1 }.sortedBy { it.name }
        }
        val names = post.tags.distinct()
        val resolved = linkedMapOf<String, Rule34Tag>()
        for (batch in names.chunked(100)) {
            try {
                request("tag", mapOf("names" to batch.joinToString(" "), "limit" to "${batch.size}"))
                    .mapNotNull(::parseTag).filter { it.name in batch }.forEach { resolved[it.name] = it }
            } catch (e: SourceFailure) {
                if (e.kind != FailureKind.BAD_RESPONSE) throw e
            }
        }
        for (name in names.filterNot(resolved::containsKey)) {
            request("tag", mapOf("name" to name, "limit" to "1")).mapNotNull(::parseTag)
                .firstOrNull { it.name == name }?.let { resolved[name] = it }
        }
        return resolved.values.filter { it.type == 1 }.sortedWith(compareByDescending<Rule34Tag> { it.count }.thenBy { it.name })
    }

    private suspend fun request(key: String, params: Map<String, String>, draft: ApiCredentials? = null,
        json: Boolean = true): List<JSONObject> {
        val credentials = draft ?: account.credentials()?.takeIf { account.state.value.canUseApi }
            ?: throw SourceFailure(FailureKind.AUTH, "请在设置中填写 User ID 与 API Key。")
        limiter.acquire()
        val url = endpoint.newBuilder().addQueryParameter("page", "dapi").addQueryParameter("s", key)
            .addQueryParameter("q", "index").apply { if (json) addQueryParameter("json", "1") }
            .addQueryParameter("user_id", credentials.userId).addQueryParameter("api_key", credentials.apiKey)
            .apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }.build()
        val call = client.newCall(Request.Builder().url(url).header("Accept", if (json) "application/json" else "application/xml").build())
        val result = coroutineScope {
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
            try {
                val response = suspendCancellableCoroutine<Response> { continuation ->
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                        override fun onResponse(call: Call, response: Response) {
                            continuation.resume(response) { _, value, _ -> value.close() }
                        }
                    })
                }
                withContext(Dispatchers.IO) {
                    response.use {
                        val retry = RetryAfter.millis(it.header("Retry-After"))
                        when {
                            it.code == 403 && (it.header("Content-Type").orEmpty().contains("text/html", true) ||
                                it.peekBody(4096).string().trimStart().let { text -> text.startsWith("<html", true) || text.startsWith("<!doctype html", true) }) ->
                                throw SourceFailure(FailureKind.BLOCKED, "API 返回了验证网页，请稍后重试。")
                            it.code == 401 || it.code == 403 -> throw SourceFailure(FailureKind.AUTH, "API 认证失败，请在设置中更新 User ID 与 API Key。")
                            it.code == 429 -> { limiter.defer(retry ?: 30_000); throw SourceFailure(FailureKind.RATE_LIMIT, "API 请求受到限流，稍后自动重试。", retry) }
                            it.code >= 500 -> throw SourceFailure(FailureKind.SERVER, "API 暂时不可用，稍后自动重试。", retry)
                            !it.isSuccessful -> throw SourceFailure(FailureKind.BAD_RESPONSE, "API 请求失败（HTTP ${it.code}）。")
                        }
                        val body = it.body?.string().orEmpty()
                        // DAPI returns a zero-byte JSON body for a valid empty search.
                        // Confirm it through XML; a truncated response must not advance the checkpoint.
                        if (json && key == "post" && body.isBlank()) return@withContext null
                        val result = parseObjects(body, key)
                        if (!json && result.isEmpty() && !confirmedEmptyXml(body, key)) throw badResponse()
                        ensureActive()
                        result
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: SourceFailure) {
                if (draft == null && e.kind == FailureKind.AUTH) account.invalidate(credentials, e.message)
                throw e
            } catch (_: IOException) {
                ensureActive()
                throw SourceFailure(FailureKind.NETWORK, "网络连接失败，等待重试。")
            } finally { watcher.cancel() }
        }
        return result ?: request(key, params, draft, json = false)
    }

    private fun confirmedEmptyXml(body: String, key: String): Boolean {
        if (!body.trimStart().startsWith('<')) return false
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(StringReader(body)) }
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG)
                return parser.name == "${key}s" && parser.getAttributeValue(null, "count")?.toLongOrNull() == 0L
            parser.next()
        }
        return false
    }

    internal fun parseObjects(body: String, key: String): List<JSONObject> {
        val text = body.trim()
        if (text.contains("missing authentication", true) || text.contains("invalid api key", true))
            throw SourceFailure(FailureKind.AUTH, "API 凭据无效，请更新凭据。")
        if (text.startsWith("<!doctype html", true) || text.startsWith("<html", true))
            throw SourceFailure(FailureKind.BLOCKED, "API 返回了验证网页，请稍后重试。")
        try {
            return when (text.firstOrNull()) {
                '[' -> JSONArray(text).objects()
                '{' -> {
                    val root = JSONObject(text)
                    if (root.has("success") && !root.optBoolean("success"))
                        throw SourceFailure(FailureKind.SERVER, "API 搜索暂时不可用。")
                    when (val value = root.opt(key)) {
                        is JSONArray -> value.objects()
                        is JSONObject -> listOf(value)
                        else -> if (root.has("id")) listOf(root) else throw badResponse()
                    }
                }
                '<' -> parseXml(text, key)
                else -> throw badResponse()
            }
        } catch (e: SourceFailure) { throw e }
        catch (_: Exception) { throw badResponse() }
    }
    private fun parseXml(body: String, key: String): List<JSONObject> {
        if (body.contains("<!DOCTYPE", true)) throw badResponse()
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(StringReader(body)) }
        val result = mutableListOf<JSONObject>()
        var validRoot = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                if (parser.depth == 1) {
                    validRoot = parser.name == key || parser.name == "${key}s"
                    if (!validRoot) throw badResponse()
                    if (parser.getAttributeValue(null, "success") == "false") throw SourceFailure(FailureKind.SERVER, "API 搜索暂时不可用。")
                }
                if (parser.name == key) result += JSONObject().apply {
                    for (index in 0 until parser.attributeCount) put(parser.getAttributeName(index), parser.getAttributeValue(index))
                }
            }
            parser.next()
        }
        if (!validRoot) throw badResponse()
        return result
    }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun parsePost(json: JSONObject): Rule34Post {
        val id = json.optLong("id", -1)
        if (id <= 0) throw badResponse()
        val file = mediaUrl(json.optString("file_url")).orEmpty()
        val availability = if (file.isBlank()) "帖子 #$id 的原文件地址暂不可用。该帖子会单独保留待处理，其他作品可继续下载。" else null
        val tags = json.optString("tags").split(Regex("\\s+")).filter(String::isNotBlank)
        val typed = when (val info = json.opt("tag_info")) {
            is JSONObject -> info.keys().asSequence().mapNotNull { name ->
                val value = info.opt(name)
                val type = tagType(if (value is JSONObject) value.opt("type") else value)
                type.takeIf { it >= 0 }?.let { Rule34Tag(name, it, 0) }
            }.toList()
            is JSONArray -> (0 until info.length()).mapNotNull { info.optJSONObject(it)?.let(::parseTag) }
            else -> null
        }
        val md5 = FileChecksum.normalize(json.optString("md5")) ?: FileChecksum.normalize(json.optString("hash"))
            ?: FileChecksum.expected("", file).orEmpty()
        return Rule34Post(id, file, md5, tags, json.optInt("width").takeIf { it > 0 },
            json.optInt("height").takeIf { it > 0 }, mediaUrl(json.optString("preview_url")) ?: mediaUrl(json.optString("sample_url")), typed, availability)
    }
    private fun parseTag(json: JSONObject): Rule34Tag? = json.optString("name").ifBlank { json.optString("tag") }.takeIf(String::isNotBlank)?.let {
        Rule34Tag(it, tagType(json.opt("type")), json.optLong("count"))
    }
    private fun tagType(value: Any?): Int = value.toString().toIntOrNull() ?: when (value.toString().lowercase()) {
        "tag", "general" -> 0
        "artist" -> 1
        "copyright" -> 3
        "character" -> 4
        "metadata" -> 5
        else -> -1
    }
    private fun mediaUrl(value: String): String? {
        val url = value.takeIf(String::isNotBlank)?.let { raw ->
            runCatching { (if (raw.startsWith("//")) "https:$raw" else raw).toHttpUrl() }.getOrNull()
        } ?: return null
        if (url.isHttps) return url.toString()
        return if (url.host == "rule34.xxx" || url.host.endsWith(".rule34.xxx")) url.newBuilder().scheme("https").build().toString() else null
    }
    private fun badResponse() = SourceFailure(FailureKind.BAD_RESPONSE,
        "暂时无法确认作品列表，API 响应为空或格式异常。已保留扫描进度，可重试扫描或先下载已发现文件。")
}
