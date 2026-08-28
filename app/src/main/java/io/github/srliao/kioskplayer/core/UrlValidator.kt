package io.github.srliao.kioskplayer.core

sealed interface UrlCheck {
    data class Valid(val url: String) : UrlCheck
    data class Invalid(val reason: String) : UrlCheck
}

object UrlValidator {

    private val ALLOWED_SCHEMES = setOf("rtsp", "rtsps")

    fun check(raw: String): UrlCheck {
        val url = raw.trim()
        if (url.isEmpty()) return UrlCheck.Invalid("Enter a stream URL")

        val sep = url.indexOf("://")
        if (sep <= 0) return UrlCheck.Invalid("URL must start with rtsp:// or rtsps://")

        val scheme = url.substring(0, sep).lowercase()
        if (scheme !in ALLOWED_SCHEMES) {
            return UrlCheck.Invalid("Scheme must be rtsp:// or rtsps://, not $scheme://")
        }

        val authority = url.substring(sep + 3).substringBefore('/').substringBefore('?')
        val host = authority.substringAfterLast('@').substringBefore(':')
        if (host.isEmpty()) return UrlCheck.Invalid("URL is missing a host")

        return UrlCheck.Valid(url)
    }
}
