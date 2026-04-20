package dev.knyazev.config

import dev.mooner.dotenv.dotenv

/**
 * Multiplatform environment variable reader.
 *
 * Uses `dev.mooner:dotenv-kmp` on all targets (JVM + Native). A missing `.env`
 * is silently ignored — values then come from the real process environment.
 * `overrideSystemEnv = false` keeps container/secret-manager envs authoritative
 * in production; `.env` only fills gaps for local development.
 */
object EnvReader {
    private val env = dotenv {
        path = ".env"
        ignoreIfMissing = true
        overrideSystemEnv = false
    }

    fun get(key: String): String? = env[key]
}
