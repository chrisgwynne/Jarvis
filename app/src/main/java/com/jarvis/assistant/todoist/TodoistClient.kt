package com.jarvis.assistant.todoist

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jarvis.assistant.llm.NetworkClient
import java.io.IOException
import kotlinx.coroutines.delay

/**
 * TodoistClient — thin REST wrapper around the Todoist v1 API.
 *
 * All HTTP calls flow through [NetworkClient] so the same OkHttp client +
 * timeouts the rest of the app uses applies here too.  We intentionally
 * do NOT depend on Retrofit — the surface is small, JSON is hand-shaped
 * via Gson, and avoiding the extra dep keeps the APK lean.
 *
 * ## Error model
 *
 * Every operation returns [Result] so callers can pattern-match the four
 * canonical failure modes:
 *   - [Result.Ok]               — request succeeded.
 *   - [Result.AuthError]        — 401/403, token bad or revoked.
 *   - [Result.RateLimited]      — 429, with `retryAfterSeconds` if Todoist
 *                                  surfaces one.
 *   - [Result.Offline]          — no network / DNS failure.
 *   - [Result.ServerError]      — 5xx / 4xx that aren't auth/rate-limit.
 *
 * Retries are deliberately conservative: one exponential backoff retry
 * for 5xx and network errors, never for 4xx.  Callers (the runtime tool)
 * decide whether to enqueue offline when [Result.Offline] surfaces.
 */
open class TodoistClient(
    private val tokenProvider: () -> String,
    private val baseUrl: String = "https://api.todoist.com/api/v1",
) {

    companion object { private const val TAG = "TodoistClient" }

    /** Sealed result with explicit failure modes. */
    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class AuthError(val message: String) : Result<Nothing>()
        data class RateLimited(val retryAfterSeconds: Int?) : Result<Nothing>()
        object Offline : Result<Nothing>()
        data class ServerError(val code: Int, val message: String) : Result<Nothing>()
        data class Malformed(val message: String) : Result<Nothing>()
    }

    /** Parameters for createTask — most fields optional; server fills defaults. */
    data class CreateTaskRequest(
        val content: String,
        val description: String? = null,
        val projectId: String? = null,
        val sectionId: String? = null,
        val labels: List<String>? = null,
        val priority: Int? = null,
        /** Natural-language string Todoist parses server-side. */
        val dueString: String? = null,
        /** Explicit "YYYY-MM-DD" date — alternative to [dueString]. */
        val dueDate: String? = null,
        /** Explicit RFC-3339 datetime — alternative to [dueString]. */
        val dueDatetime: String? = null,
        val dueLang: String = "en",
    )

    // ── Public API ────────────────────────────────────────────────────────

    /** GET /tasks — active tasks for the current user. */
    suspend fun getActiveTasks(): Result<List<TodoistTask>> =
        get("/tasks", typeListTask)

    /**
     * Todoist v1 filter endpoints.
     *
     * The `/api/v1/tasks` endpoint DOES NOT honour `?filter=today` —
     * that was a `/rest/v2` convention.  v1 requires the dedicated
     * `/tasks/filter` route with a `query` parameter (URL-encoded).
     * Calling `/tasks?filter=today` silently returns the entire active
     * list, which is exactly the "50 tasks today" bug the user hit.
     *
     * Filter query language:
     *   today         — tasks due today
     *   overdue       — past-due tasks
     *   "1 day | 2 days | ..."  — N-day rolling window
     */
    suspend fun getTodayTasks(): Result<List<TodoistTask>> =
        get("/tasks/filter?query=${urlEncode("today")}", typeListTask)

    suspend fun getOverdueTasks(): Result<List<TodoistTask>> =
        get("/tasks/filter?query=${urlEncode("overdue")}", typeListTask)

    suspend fun getUpcomingTasks(days: Int = 7): Result<List<TodoistTask>> {
        // "1 day | 2 days | ... | N days" — Todoist's filter language
        // uses pipes for unions.  Limit to 7 by default to keep the
        // response shape predictable.
        val terms = (1..days).joinToString(" | ") { "$it days" }
        return get("/tasks/filter?query=${urlEncode(terms)}", typeListTask)
    }

    suspend fun getProjects(): Result<List<TodoistProject>> =
        get("/projects", typeListProject)

    suspend fun getLabels(): Result<List<TodoistLabel>> =
        get("/labels", typeListLabel)

    suspend fun getSections(projectId: String): Result<List<TodoistSection>> =
        get("/sections?project_id=$projectId", typeListSection)

    suspend fun searchTasks(query: String): Result<List<TodoistTask>> =
        get("/tasks?filter=${urlEncode("search: $query")}", typeListTask)

    /** Quick reachability test — calls GET /projects with the token. */
    suspend fun testConnection(): Result<Boolean> {
        return when (val r = getProjects()) {
            is Result.Ok           -> Result.Ok(true)
            is Result.AuthError    -> r
            is Result.RateLimited  -> r
            is Result.Offline      -> Result.Offline
            is Result.ServerError  -> r
            is Result.Malformed    -> r
        }
    }

    open suspend fun createTask(req: CreateTaskRequest): Result<TodoistTask> {
        val body = gson.toJson(
            buildMap<String, Any> {
                put("content", req.content)
                req.description?.let { put("description", it) }
                req.projectId?.let   { put("project_id", it) }
                req.sectionId?.let   { put("section_id", it) }
                req.labels?.let      { put("labels", it) }
                req.priority?.let    { put("priority", it) }
                req.dueString?.let   { put("due_string", it); put("due_lang", req.dueLang) }
                req.dueDate?.let     { put("due_date", it) }
                req.dueDatetime?.let { put("due_datetime", it) }
            }
        )
        return post("/tasks", body, TodoistTask::class.java)
    }

    suspend fun completeTask(id: String): Result<Boolean> =
        postEmpty("/tasks/$id/close")

    suspend fun reopenTask(id: String): Result<Boolean> =
        postEmpty("/tasks/$id/reopen")

    suspend fun deleteTask(id: String): Result<Boolean> =
        delete("/tasks/$id")

    suspend fun updateTask(id: String, patch: Map<String, Any?>): Result<TodoistTask> {
        val cleaned = patch.filterValues { it != null }
        return post("/tasks/$id", gson.toJson(cleaned), TodoistTask::class.java)
    }

    suspend fun moveTask(id: String, projectId: String?, sectionId: String?): Result<TodoistTask> {
        val patch = buildMap<String, Any?> {
            projectId?.let  { put("project_id", it) }
            sectionId?.let  { put("section_id", it) }
        }
        return updateTask(id, patch)
    }

    suspend fun addLabel(id: String, label: String): Result<TodoistTask> {
        // Todoist treats labels as a set on the task — read-modify-write.
        return when (val current = get<TodoistTask>("/tasks/$id", TodoistTask::class.java)) {
            is Result.Ok -> {
                val labels = (current.value.labels + label).distinct()
                updateTask(id, mapOf("labels" to labels))
            }
            else -> @Suppress("UNCHECKED_CAST") (current as Result<TodoistTask>)
        }
    }

    suspend fun removeLabel(id: String, label: String): Result<TodoistTask> {
        return when (val current = get<TodoistTask>("/tasks/$id", TodoistTask::class.java)) {
            is Result.Ok -> {
                val labels = current.value.labels - label
                updateTask(id, mapOf("labels" to labels))
            }
            else -> @Suppress("UNCHECKED_CAST") (current as Result<TodoistTask>)
        }
    }

    // ── HTTP plumbing ─────────────────────────────────────────────────────

    private val gson = Gson()

    /** Generic typed GET that uses the (modern) Class<T> form for shapes. */
    private suspend fun <T> get(path: String, clazz: Class<T>): Result<T> =
        request("GET", path, body = null) { gson.fromJson(it, clazz) }

    /**
     * Typed GET for parameterised types (List<T>).
     *
     * **Response-shape tolerance.**  Todoist /api/v1 introduced a
     * paginated envelope shape `{"results": [...], "next_cursor": null}`
     * around list endpoints.  The legacy /rest/v2 shape was a bare JSON
     * array.  This wrapper parses BOTH so the client survives the
     * cutover and any future toggle between the two.  If the server
     * eventually drops the bare-array shape entirely, the array
     * fallback simply never matches.
     */
    private suspend fun <T> get(path: String, type: java.lang.reflect.Type): Result<T> =
        request("GET", path, body = null) { raw ->
            val trimmed = raw.trimStart()
            when {
                // Bare JSON array (legacy /rest/v2 shape).
                trimmed.startsWith("[") -> gson.fromJson<T>(raw, type)
                // Paginated envelope — pluck the `results` array out and
                // hand THAT to the list deserialiser.
                trimmed.startsWith("{") -> {
                    val root = com.google.gson.JsonParser.parseString(raw).asJsonObject
                    val arr  = root.getAsJsonArray("results")
                        ?: error("Server returned an object without 'results'.")
                    gson.fromJson<T>(arr, type)
                }
                else -> error("Unexpected response shape.")
            }
        }

    private suspend fun <T> post(path: String, body: String, clazz: Class<T>): Result<T> =
        request("POST", path, body) { gson.fromJson(it, clazz) }

    private suspend fun postEmpty(path: String): Result<Boolean> =
        request("POST", path, body = "{}") { true }

    private suspend fun delete(path: String): Result<Boolean> =
        request("DELETE", path, body = null) { true }

    /** One request with one retry on 5xx / network failure. */
    private suspend fun <T> request(
        method: String,
        path: String,
        body: String?,
        parse: (String) -> T,
    ): Result<T> {
        val token = tokenProvider().trim()
        if (token.isBlank()) return Result.AuthError("No Todoist API token configured.")

        val url = baseUrl.trimEnd('/') + path
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Content-Type"  to "application/json",
        )

        var attempt = 0
        while (true) {
            attempt++
            try {
                val raw = when (method) {
                    "GET"    -> NetworkClient.get(url, headers)
                    "POST"   -> NetworkClient.post(url, headers, body ?: "{}")
                    "DELETE" -> NetworkClient.delete(url, headers)
                    else     -> error("Unsupported method: $method")
                }
                val parsed = runCatching { parse(raw) }
                    .getOrElse { thrown ->
                        // Strip Java exception class names + stack hints
                        // out of the user-visible reason.  The full detail
                        // goes to logcat for diagnosis.
                        val cleaned = friendlyParseError(thrown, raw)
                        Log.w(TAG, "[TODOIST_PARSE_ERROR] " +
                            "endpoint=$path err=${thrown.javaClass.simpleName}: " +
                            "${thrown.message} bodyPreview=${raw.take(120)}")
                        return Result.Malformed(cleaned)
                    }
                return Result.Ok(parsed)
            } catch (e: com.jarvis.assistant.llm.LlmException) {
                val msg = e.message.orEmpty()
                when {
                    msg.contains("HTTP 401") || msg.contains("HTTP 403") -> {
                        Log.w(TAG, "[TODOIST_AUTH_ERROR] $msg")
                        return Result.AuthError(msg)
                    }
                    msg.contains("HTTP 429") -> {
                        Log.w(TAG, "[TODOIST_RATE_LIMITED] $msg")
                        return Result.RateLimited(parseRetryAfter(msg))
                    }
                    msg.contains("HTTP 5") -> {
                        Log.w(TAG, "[TODOIST_SERVER_ERROR] $msg (attempt $attempt)")
                        if (attempt < 2) { delay(backoffMs(attempt)); continue }
                        return Result.ServerError(extractCode(msg), msg)
                    }
                    else -> {
                        Log.w(TAG, "[TODOIST_HTTP_ERROR] $msg")
                        return Result.ServerError(extractCode(msg), msg)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "[TODOIST_OFFLINE] ${e.javaClass.simpleName}: ${e.message}")
                if (attempt < 2) { delay(backoffMs(attempt)); continue }
                return Result.Offline
            } catch (e: Exception) {
                Log.w(TAG, "[TODOIST_UNEXPECTED] ${e.javaClass.simpleName}: ${e.message}")
                return Result.ServerError(-1, e.message ?: "")
            }
        }
    }

    private fun backoffMs(attempt: Int): Long = (250L shl (attempt - 1)).coerceAtMost(2_000L)

    private fun extractCode(msg: String): Int =
        Regex("""HTTP\s+(\d{3})""").find(msg)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    private fun parseRetryAfter(msg: String): Int? =
        Regex("""retry[-_\s]after[":\s]*(\d+)""", RegexOption.IGNORE_CASE)
            .find(msg)?.groupValues?.get(1)?.toIntOrNull()

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    /**
     * Map a Gson/parse exception to a short UI-safe reason.  Never
     * includes the Java exception class name, never includes file/line
     * refs.  Full detail is logged via `[TODOIST_PARSE_ERROR]` for
     * diagnosis.
     */
    private fun friendlyParseError(thrown: Throwable, raw: String): String {
        val msg = thrown.message.orEmpty()
        return when {
            msg.contains("begin_array", ignoreCase = true) ||
                msg.contains("begin_object", ignoreCase = true) ->
                "Server returned an unexpected response shape."
            msg.contains("end of input", ignoreCase = true) ||
                msg.contains("EOF", ignoreCase = true) ->
                "Server returned an incomplete response."
            raw.isBlank()         -> "Server returned an empty response."
            raw.length < 4        -> "Server returned an empty response."
            else                  -> "Server returned an unexpected response."
        }
    }

    // Reified Gson types reused across list endpoints.
    private val typeListTask    = object : TypeToken<List<TodoistTask>>()    {}.type
    private val typeListProject = object : TypeToken<List<TodoistProject>>() {}.type
    private val typeListLabel   = object : TypeToken<List<TodoistLabel>>()   {}.type
    private val typeListSection = object : TypeToken<List<TodoistSection>>() {}.type
}
