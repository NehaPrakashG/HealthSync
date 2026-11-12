package dev.neha.healthsync.domain.model

/**
 * Domain errors. The data layer maps raw platform exceptions (SecurityException,
 * RemoteException, IllegalArgumentException...) into these, so the ViewModel and
 * WorkManager can decide what to do without knowing Health Connect internals.
 */
sealed class HealthException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Permission never granted or revoked by the user. Not retryable. */
    class PermissionMissing(cause: Throwable? = null) :
        HealthException("Health Connect permission is missing or was revoked", cause)

    /** Health Connect is not installed / not supported. Not retryable. */
    class Unavailable(cause: Throwable? = null) :
        HealthException("Health Connect is not available", cause)

    /** IPC failure, rate limit or I/O error. Retry with backoff. */
    class Transient(cause: Throwable) :
        HealthException("Temporary Health Connect error: ${cause.message}", cause)

    /** Record rejected by validation (ours or Health Connect's). Not retryable. */
    class InvalidData(message: String) : HealthException(message)
}

fun Throwable.toUserMessage(): String = when (this) {
    is HealthException.PermissionMissing -> "Access was removed. Reconnect Health Connect."
    is HealthException.Unavailable -> "Health Connect isn't available."
    is HealthException.Transient -> "Health Connect is busy. Try again shortly."
    is HealthException.InvalidData -> message ?: "Invalid data"
    is java.io.IOException -> "Network error. Will retry."
    else -> message ?: "Something went wrong"
}
