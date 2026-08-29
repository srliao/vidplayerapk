package io.github.srliao.kioskplayer

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The tablet's first non-loopback IPv4 address, or "?" when there is none -
 * shown by both the Diagnostics header and the Setup receive panel, so they
 * cannot disagree about what address to tell you to use.
 */
fun localIpv4(): String =
    runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()?.hostAddress
    }.getOrNull() ?: "?"
