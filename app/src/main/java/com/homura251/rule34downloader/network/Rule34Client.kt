package com.homura251.rule34downloader.network

import com.homura251.rule34downloader.data.ApiCredentials
import com.homura251.rule34downloader.data.Rule34Post
import com.homura251.rule34downloader.data.Rule34Tag
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.io.StringReader
import javax.net.ssl.HttpsURLConnection

class Rule34Client(
    private val credentials: ApiCredentials,
    private val checkActive: () -> Unit = {},
    private val registerConnection: (HttpURLConnection) -> Closeable = { Closeable {} },
) {
    fun getPost(postId: Long): Rule34Post {
        val posts = requestObjects(
            key = "post",
            params = mapOf(
                "page" to "dapi",
                "s" to "post",
                "q" to "index",
                "id" to postId.toString(),
                "limit" to "1",
                "json" to "1",
            ),
        ).mapNotNull(::parsePost)

        return posts.firstOrNull()
            ?: throw ApiException("未找到帖子 #$postId，或该帖子已被删除。")
    }

    fun resolveArtistTags(postTags: List<String>): List<Rule34Tag> {
        val requested = postTags
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        if (requested.isEmpty()) return emptyList()

        val resolved = linkedMapOf<String, Rule34Tag>()

        // Gelbooru-compatible deployments accept `names` for batch lookup. Rule34 has
        // historically exposed the same DAPI shape, but we do not rely on it blindly:
        // any names not returned by the batch call are resolved one-by-one below.
        requested.chunked(TAG_BATCH_SIZE).forEach { batch ->
            val objects = try {
                requestObjects(
                    key = "tag",
                    params = mapOf(
                        "page" to "dapi",
                        "s" to "tag",
                        "q" to "index",
                        "names" to batch.joinToString(" "),
                        "limit" to batch.size.toString(),
                        "json" to "1",
                    ),
                )
            } catch (e: AuthException) {
                throw e
            } catch (e: RetryableApiException) {
                throw e
            } catch (_: ApiException) {
                emptyList()
            }
            objects
                .mapNotNull(::parseTag)
                .filter { it.name in batch }
                .forEach { resolved[it.name] = it }
        }

        val missing = requested.filterNot(resolved::containsKey)
        missing.forEachIndexed { index, name ->
            val tag = requestObjects(
                key = "tag",
                params = mapOf(
                    "page" to "dapi",
                    "s" to "tag",
                    "q" to "index",
                    "name" to name,
                    "limit" to "1",
                    "json" to "1",
                ),
            ).mapNotNull(::parseTag)
                .firstOrNull { it.name == name }
            if (tag != null) resolved[tag.name] = tag
            if (index != missing.lastIndex) Thread.sleep(TAG_LOOKUP_DELAY_MS)
        }

        return resolved.values
            .filter { it.type == ARTIST_TAG_TYPE }
            .distinctBy { it.name }
            .sortedWith(compareByDescending<Rule34Tag> { it.count }.thenBy { it.name })
    }

    fun searchPosts(
        artistTag: String,
        afterPostId: Long,
        page: Int,
        limit: Int = MAX_POSTS_PER_PAGE,
        beforePostId: Long? = null,
    ): List<Rule34Post> {
        val boundary = when {
            beforePostId != null -> "id:<$beforePostId"
            afterPostId > 0L -> "id:>$afterPostId"
            else -> ""
        }
        val tagQuery = listOf(artistTag, "sort:id:desc", boundary).filter(String::isNotBlank).joinToString(" ")
        return requestObjects(
            key = "post",
            params = mapOf(
                "page" to "dapi",
                "s" to "post",
                "q" to "index",
                "tags" to tagQuery,
                "pid" to page.toString(),
                "limit" to limit.coerceIn(1, MAX_POSTS_PER_PAGE).toString(),
                "json" to "1",
            ),
        ).map { parsePost(it) ?: throw RetryableApiException("API 帖子数据不完整，本次扫描未标记完成。") }
    }

    private fun requestObjects(key: String, params: Map<String, String>): List<JSONObject> {
        val allParams = LinkedHashMap(params).apply {
            put("api_key", credentials.apiKey)
            put("user_id", credentials.userId)
        }
        val query = allParams.entries.joinToString("&") { (name, value) ->
            "${encode(name)}=${encode(value)}"
        }
        val url = "$API_ENDPOINT?$query"
        checkActive()
        val connection = (URI(url).toURL().openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }

        val registration = registerConnection(connection)
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            checkActive()
            when {
                status == HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    throw AuthException("API 认证失败，请检查 User ID 与 API Key。")
                }
                status == 429 || status >= 500 -> {
                    throw RetryableApiException("Rule34 API 暂时不可用（HTTP $status）。")
                }
                status !in 200..299 -> {
                    throw ApiException("Rule34 API 请求失败（HTTP $status）。")
                }
            }
            return parseObjectArray(body, key)
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            checkActive()
            throw RetryableApiException("网络连接失败：${e.message ?: "I/O error"}", e)
        } finally {
            registration.close()
            connection.disconnect()
        }
    }

    private fun parseObjectArray(body: String, key: String): List<JSONObject> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            when (trimmed.first()) {
                '[' -> JSONArray(trimmed).toObjectList()
                '{' -> {
                    val root = JSONObject(trimmed)
                    when (val value = root.opt(key)) {
                        is JSONArray -> value.toObjectList()
                        is JSONObject -> listOf(value)
                        else -> if (root.has("id")) listOf(root) else emptyList()
                    }
                }
                '<' -> parseXmlObjects(trimmed, key)
                else -> throw ApiException("Rule34 API 返回了无法识别的数据格式。")
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("解析 Rule34 API 响应失败。", e)
        }
    }

    private fun parseXmlObjects(body: String, key: String): List<JSONObject> {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(StringReader(body))
        }
        val result = mutableListOf<JSONObject>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == key) {
                val json = JSONObject()
                for (index in 0 until parser.attributeCount) {
                    json.put(parser.getAttributeName(index), parser.getAttributeValue(index))
                }
                result += json
            }
            event = parser.next()
        }
        return result
    }

    private fun JSONArray.toObjectList(): List<JSONObject> = buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(::add)
        }
    }

    private fun parsePost(json: JSONObject): Rule34Post? {
        val id = json.optLong("id", -1L)
        val fileUrl = normalizeFileUrl(json.optString("file_url")) ?: return null
        if (id <= 0L) return null
        return Rule34Post(
            id = id,
            fileUrl = fileUrl,
            md5 = json.optString("md5"),
            tags = json.optString("tags")
                .split(Regex("\\s+"))
                .map(String::trim)
                .filter(String::isNotEmpty),
            width = json.optInt("width").takeIf { it > 0 },
            height = json.optInt("height").takeIf { it > 0 },
            previewUrl = normalizeFileUrl(json.optString("preview_url"))
                ?: normalizeFileUrl(json.optString("sample_url")),
        )
    }

    private fun parseTag(json: JSONObject): Rule34Tag? {
        val name = json.optString("name").trim()
        if (name.isEmpty()) return null
        return Rule34Tag(
            name = name,
            type = json.optInt("type", 0),
            count = json.optLong("count", 0L),
        )
    }

    private fun normalizeFileUrl(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val uri = URI(trimmed)
            when (uri.scheme?.lowercase()) {
                "https" -> trimmed
                "http" -> {
                    val host = uri.host?.lowercase().orEmpty()
                    if (host == "rule34.xxx" || host.endsWith(".rule34.xxx")) {
                        URI(
                            "https",
                            uri.userInfo,
                            uri.host,
                            uri.port,
                            uri.path,
                            uri.query,
                            uri.fragment,
                        ).toString()
                    } else {
                        null
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        const val MAX_POSTS_PER_PAGE = 1000
        const val ARTIST_TAG_TYPE = 1

        private const val TAG_BATCH_SIZE = 100
        private const val TAG_LOOKUP_DELAY_MS = 250L
        private const val API_ENDPOINT = "https://api.rule34.xxx/index.php"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val USER_AGENT =
            "rule34-Downloader/1.0 (+https://github.com/homura251/rule34-Downloader)"
    }
}

open class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
class AuthException(message: String) : ApiException(message)
class RetryableApiException(message: String, cause: Throwable? = null) : ApiException(message, cause)
