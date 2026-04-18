package dev.knyazev.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes
import org.slf4j.LoggerFactory

val ChatRateLimit = RateLimitName("chat")
val MediaRateLimit = RateLimitName("media")

private val rateLimitLogger = LoggerFactory.getLogger("dev.knyazev.plugins.RateLimit")

fun Application.configureRateLimit() {
    install(RateLimit) {
        // /api/chat — 10 requests per minute per composite fingerprint (ADR-18)
        register(ChatRateLimit) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { call -> compositeKey(call.request) }
        }
        // /api/tts + /api/asr — 20 requests per minute per composite fingerprint
        register(MediaRateLimit) {
            rateLimiter(limit = 20, refillPeriod = 1.minutes)
            requestKey { call -> compositeKey(call.request) }
        }
    }
}

/**
 * Composite rate limit key (ADR-18):
 *   SHA-256( ja3 + ":" + clientFingerprint + ":" + ip )
 *
 * - ja3: TLS ClientHello fingerprint set by Nginx (nginx-ssl-fingerprint module)
 * - clientFingerprint: canvas-based browser fingerprint sent as X-Fingerprint header
 * - ip: real client IP forwarded by Nginx as X-Real-IP
 *
 * Degrades gracefully — missing signals fall back to sentinel values so rate
 * limiting still functions even without the full fingerprint chain.
 *
 * Trust model: JA3 and X-Real-IP must be set by the trusted reverse proxy (Nginx).
 * When they arrive missing in production, the service is either running bare or
 * being hit directly — log a warning so misconfiguration is visible.
 */
private fun compositeKey(request: io.ktor.server.request.ApplicationRequest): String {
    val ja3Header = request.headers["X-JA3-Fingerprint"]
    val clientFpHeader = request.headers["X-Fingerprint"]
    val realIp = request.headers["X-Real-IP"]
    val forwardedFor = request.headers["X-Forwarded-For"]?.substringBefore(",")

    val ja3 = ja3Header ?: "no-ja3"
    val clientFp = clientFpHeader ?: "no-fp"
    val ip = realIp ?: forwardedFor ?: "unknown"

    if (ja3Header == null || realIp == null) {
        rateLimitLogger.warn(
            "Rate-limit fallback: ja3={} realIp={} xff={} (reverse proxy headers missing — " +
                "composite key degrades to partial fingerprint)",
            ja3Header ?: "missing",
            realIp ?: "missing",
            forwardedFor ?: "missing",
        )
    }

    return sha256("$ja3:$clientFp:$ip")
}

private fun sha256(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
