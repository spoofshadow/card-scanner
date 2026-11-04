package dev.pokescan.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

private const val MAX_ATTEMPTS = 4
private const val RETRY_BASE_MS = 300L
private const val RETRY_MAX_MS = 2_000L

/**
 * Runs the request and returns the response body, or null on 404. Server errors and rate limiting
 * (429) are retried with backoff, since the card databases occasionally have bad moments.
 */
internal suspend fun OkHttpClient.fetchWithRetry(request: Request): String? = withContext(Dispatchers.IO) {
    var lastError = IOException("Card lookup failed")
    for (attempt in 0 until MAX_ATTEMPTS) {
        if (attempt > 0) delay((RETRY_BASE_MS shl (attempt - 1)).coerceAtMost(RETRY_MAX_MS))
        val response = try {
            newCall(request).execute()
        } catch (e: IOException) {
            lastError = e
            continue
        }
        response.use {
            if (it.isSuccessful) return@withContext it.body!!.string()
            if (it.code == 404) return@withContext null
            val retryable = it.code >= 500 || it.code == 429
            lastError = IOException(
                if (retryable) "The card database is having trouble (HTTP ${it.code}). Try again in a moment."
                else "Card lookup failed (HTTP ${it.code})"
            )
            if (!retryable) throw lastError
        }
    }
    throw lastError
}
