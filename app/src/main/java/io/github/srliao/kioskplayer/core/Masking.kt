package io.github.srliao.kioskplayer.core

/**
 * Reduces a stream URL to scheme and host. Stream URLs embed credentials or
 * tokens, so this is used anywhere a URL crosses the network or reaches a log.
 */
fun maskUrl(url: String): String {
    val sep = url.indexOf("://")
    if (sep <= 0) return "***"
    val scheme = url.substring(0, sep)
    val rest = url.substring(sep + 3)
    val authority = rest.substringBefore('/').substringBefore('?')
    val host = authority.substringAfterLast('@')
    if (host.isEmpty()) return "***"
    return "$scheme://$host/***"
}
