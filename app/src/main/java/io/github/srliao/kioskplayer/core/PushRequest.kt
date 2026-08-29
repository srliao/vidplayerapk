package io.github.srliao.kioskplayer.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface PushResult {
    data class Accepted(val name: String?, val url: String) : PushResult
    data class Rejected(val status: Int, val reason: String) : PushResult
}

/**
 * The whole accept/reject decision for a pushed stream, as pure logic - the
 * socket layer only moves bytes. Reuses UrlValidator so there is exactly one
 * copy of the URL rules in the project.
 */
object PushRequest {

    @Serializable
    private data class Body(val url: String? = null, val name: String? = null)

    @Serializable
    private data class Ok(val ok: Boolean, val name: String? = null)

    @Serializable
    private data class Err(val ok: Boolean, val error: String)

    private const val MISSING_URL = "Body must be a JSON object with a url field"

    private val json = Json { ignoreUnknownKeys = true }

    fun evaluate(body: String): PushResult {
        val parsed = runCatching { json.decodeFromString<Body>(body) }.getOrNull()
            ?: return PushResult.Rejected(400, MISSING_URL)
        val url = parsed.url ?: return PushResult.Rejected(400, MISSING_URL)

        return when (val check = UrlValidator.check(url)) {
            is UrlCheck.Invalid -> PushResult.Rejected(400, check.reason)
            is UrlCheck.Valid -> PushResult.Accepted(
                name = parsed.name?.trim()?.ifBlank { null },
                url = check.url,
            )
        }
    }

    /** Built by the serializer, never by hand: [reason] embeds user input. */
    fun okJson(name: String?): String = json.encodeToString(Ok(ok = true, name = name))

    fun errorJson(reason: String): String = json.encodeToString(Err(ok = false, error = reason))
}
