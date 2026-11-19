package com.mycalender.notion

import android.os.SystemClock
import com.mycalender.tasks.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class Connection(val dataSourceId: String, val token: String) {
    override fun toString() = "Connection(dataSourceId=$dataSourceId)"
}

fun notionId(input: String): String {
    val value = input.trim().replace("-", "")
    require(value.matches(Regex("[a-fA-F0-9]{32}"))) { "데이터 소스 ID 32자리를 입력해 주세요." }
    return UUID.fromString(value.replace(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
        .toString()
}

class NotionApi(private val config: Connection) : TaskGateway {
    fun validateConnection() {
        val properties = request("GET", "/data_sources/${config.dataSourceId}").getJSONObject("properties")
        val expected = mapOf("이름" to "title", "날짜" to "date", "상태" to "status",
            "중요" to "checkbox", "기록 ID" to "rich_text")
        for ((name, type) in expected) {
            require(properties.optJSONObject(name)?.optString("type") == type) {
                "노션의 ‘$name’ 속성을 확인해 주세요. 필요한 유형은 $type 입니다."
            }
        }
        val options = properties.getJSONObject("상태").getJSONObject("status").getJSONArray("options")
        val names = (0 until options.length()).map { options.getJSONObject(it).getString("name") }
        require("할 일" in names && "완료" in names) { "노션의 ‘상태’에 ‘할 일’과 ‘완료’ 옵션이 필요해요." }
    }

    override fun listOpen(): List<Task> =
        query(fieldFilter("상태", "status", "does_not_equal", "완료"))

    override fun findCreated(recordId: String): Task? =
        query(fieldFilter("기록 ID", "rich_text", "equals", recordId)).firstOrNull()

    override fun create(draft: PendingDraft): Task {
        val fields = draft.fields
        val properties = patchProperties(Changes(
            title = fields.title, setDue = true, due = fields.due, important = fields.important, completed = false,
        )).put("기록 ID", richText("rich_text", draft.id))
        val parent = JSONObject()
            .put("type", "data_source_id")
            .put("data_source_id", config.dataSourceId)
        return decodeTask(request("POST", "/pages", JSONObject().put("parent", parent).put("properties", properties)))
    }

    override fun update(pageId: String, changes: Changes): Task = decodeTask(request(
        "PATCH", "/pages/${notionId(pageId)}", JSONObject().put("properties", patchProperties(changes)),
    ))

    private fun query(filter: JSONObject): List<Task> {
        val result = mutableListOf<Task>()
        val payload = JSONObject().put("filter", filter).put("page_size", 100)
        val seenCursors = mutableSetOf<String>()
        do {
            val response = request("POST", "/data_sources/${config.dataSourceId}/query", payload)
            check(response.optJSONObject("request_status")?.optString("type") != "incomplete") {
                "노션 조회 한도에 도달했어요. 기기의 마지막 목록을 유지합니다."
            }
            val pages = response.getJSONArray("results")
            for (index in 0 until pages.length()) {
                val page = pages.getJSONObject(index)
                if (page.getString("object") == "page" && !page.optBoolean("in_trash") &&
                    !page.optBoolean("archived")) {
                    result.add(decodeTask(page))
                }
            }
            val more = response.getBoolean("has_more")
            if (more) {
                val cursor = response.getString("next_cursor")
                if (!seenCursors.add(cursor)) throw IOException("Notion returned a repeated cursor")
                payload.put("start_cursor", cursor)
            }
        } while (more)
        return result
    }

    private fun decodeTask(page: JSONObject): Task {
        val properties = page.getJSONObject("properties")
        val pieces = properties.getJSONObject("이름").getJSONArray("title")
        val title = (0 until pieces.length()).joinToString("") {
            pieces.getJSONObject(it).getString("plain_text")
        }
        val date = properties.getJSONObject("날짜").optJSONObject("date")
        val zone = date?.optString("time_zone")?.takeUnless { it.isBlank() || it == "null" }
        val end = date?.optString("end")?.takeUnless { it.isBlank() || it == "null" }
        return Task(
            id = page.getString("id"),
            title = title,
            due = date?.let { DueDate.parse(it.getString("start"), zone) },
            important = properties.getJSONObject("중요").getBoolean("checkbox"),
            completed = properties.getJSONObject("상태").optJSONObject("status")?.optString("name") == "완료",
            dueEnd = end?.let { DueDate.parse(it, zone) },
        )
    }

    private fun patchProperties(changes: Changes): JSONObject {
        val properties = JSONObject()
        changes.title?.let { properties.put("이름", richText("title", it)) }
        if (changes.setDue) {
            val date = changes.due?.let { JSONObject().put("start", it.forNotion()) } ?: JSONObject.NULL
            properties.put("날짜", JSONObject().put("date", date))
        }
        changes.important?.let { properties.put("중요", JSONObject().put("checkbox", it)) }
        changes.completed?.let {
            properties.put("상태", JSONObject().put("status", JSONObject().put("name", if (it) "완료" else "할 일")))
        }
        return properties
    }

    private fun richText(propertyType: String, content: String): JSONObject {
        val text = JSONObject().put("type", "text").put("text", JSONObject().put("content", content))
        return JSONObject().put(propertyType, JSONArray().put(text))
    }

    private fun fieldFilter(name: String, type: String, comparison: String, value: String) =
        JSONObject().put("property", name).put(type, JSONObject().put(comparison, value))

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        repeat(3) { attempt ->
            val waitMillis = retryAt.get() - SystemClock.elapsedRealtime()
            if (waitMillis > 0) throw NotionRejected(429, rejected(429, (waitMillis + 999) / 1_000))
            val connection = URL("https://api.notion.com/v1$path").openConnection() as HttpURLConnection
            val retryAfter: Long?
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = method
                connection.connectTimeout = 8_000
                connection.readTimeout = 12_000
                connection.setRequestProperty("Authorization", "Bearer ${config.token}")
                connection.setRequestProperty("Notion-Version", "2026-03-11")
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    val bytes = body.toString().toByteArray(Charsets.UTF_8)
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                if (status in 200..299) {
                    return connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
                }
                retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull()
                if (status == 429) {
                    val delaySeconds = retryAfter?.coerceIn(0, 3_600) ?: 1
                    val end = SystemClock.elapsedRealtime() + delaySeconds * 1_000
                    retryAt.accumulateAndGet(end, ::maxOf)
                }
                val canWait = status == 429 && attempt < 2 && retryAfter != null && retryAfter in 0..5
                if (!canWait) {
                    if (status in 400..499 && status != 408) throw NotionRejected(status, rejected(status, retryAfter))
                    throw IOException("Notion 응답을 확인하지 못했어요. HTTP $status")
                }
            } finally {
                connection.disconnect()
            }
            Thread.sleep(retryAfter * 1_000)
        }
        error("Notion 요청을 완료하지 못했어요.")
    }

    private fun rejected(status: Int, retryAfter: Long?) = when (status) {
        400 -> "노션에서 요청을 거절했어요. 데이터 소스 ID와 속성·입력값을 확인해 주세요."
        401 -> "노션 토큰이 올바르지 않거나 만료됐어요."
        403 -> "이 연결에 읽기·추가·수정 권한을 부여해 주세요."
        404 -> "데이터 소스를 찾을 수 없어요. ID와 노션 DB에 연결이 추가됐는지 확인해 주세요."
        429 -> if (retryAfter == null) "노션 요청이 많아요. 잠시 뒤 다시 시도해 주세요."
            else "노션 요청이 많아요. $retryAfter 초 뒤 다시 시도해 주세요."
        else -> "노션에서 요청을 처리하지 못했어요. HTTP $status"
    }

    private companion object {
        val retryAt = AtomicLong()
    }
}
