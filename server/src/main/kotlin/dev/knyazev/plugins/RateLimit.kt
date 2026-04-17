package dev.knyazev.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes

val ChatRateLimit = RateLimitName("chat")
val MediaRateLimit = RateLimitName("media")

fun Application.configureRateLimit() {
    install(RateLimit) {
        // /api/chat — 10 requests per minute per composite fingerprint (ADR-22)
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
 * Composite rate limit key (ADR-22):
 *   SHA-256( ja3 + ":" + clientFingerprint + ":" + ip )
 *
 * - ja3: TLS ClientHello fingerprint set by Nginx (nginx-ssl-fingerprint module)
 * - clientFingerprint: canvas-based browser fingerprint sent as X-Fingerprint header
 * - ip: real client IP forwarded by Nginx as X-Real-IP
 *
 * Degrades gracefully — missing signals fall back to sentinel values so rate
 * limiting still functions even without the full fingerprint chain.
 */
private fun compositeKey(request: io.ktor.server.request.ApplicationRequest): String {
    val ja3 = request.headers["X-JA3-Fingerprint"] ?: "no-ja3"
    val clientFp = request.headers["X-Fingerprint"] ?: "no-fp"
    val ip = request.headers["X-Real-IP"] ?: request.headers["X-Forwarded-For"]?.substringBefore(",") ?: "unknown"
    return sha256("$ja3:$clientFp:$ip")
}

private fun sha256(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
