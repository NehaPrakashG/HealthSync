package dev.neha.healthsync.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists sync checkpoints. Changes tokens are scoped to a set of record types,
 * so tokens are stored per "scope key" (e.g. "HEART_RATE,STEPS"). If the user
 * grants or revokes a type, the scope changes and a fresh full import runs.
 */
interface SyncStateStore {
    val lastSyncAt: Flow<Instant?>
    val lastError: Flow<String?>

    suspend fun changesToken(scopeKey: String): String?
    suspend fun saveToken(scopeKey: String, token: String)
    suspend fun clearToken(scopeKey: String)
    suspend fun lastSyncAt(): Instant?
    suspend fun markSucceeded(at: Instant)
    suspend fun markFailed(message: String)
}

private val Context.syncDataStore by preferencesDataStore(name = "health_sync_state")

@Singleton
class DataStoreSyncStateStore @Inject constructor(
    @ApplicationContext context: Context,
) : SyncStateStore {

    private val store = context.syncDataStore

    override val lastSyncAt: Flow<Instant?> =
        store.data.map { prefs -> prefs[LAST_SYNC]?.let(Instant::ofEpochMilli) }

    override val lastError: Flow<String?> = store.data.map { prefs -> prefs[LAST_ERROR] }

    override suspend fun changesToken(scopeKey: String): String? = store.data.first()[tokenKey(scopeKey)]

    override suspend fun saveToken(scopeKey: String, token: String) {
        store.edit { it[tokenKey(scopeKey)] = token }
    }

    override suspend fun clearToken(scopeKey: String) {
        store.edit { it.remove(tokenKey(scopeKey)) }
    }

    override suspend fun lastSyncAt(): Instant? = lastSyncAt.first()

    override suspend fun markSucceeded(at: Instant) {
        store.edit {
            it[LAST_SYNC] = at.toEpochMilli()
            it.remove(LAST_ERROR)
        }
    }

    override suspend fun markFailed(message: String) {
        store.edit { it[LAST_ERROR] = message }
    }

    private fun tokenKey(scopeKey: String) = stringPreferencesKey("changes_token_$scopeKey")

    private companion object {
        val LAST_SYNC = longPreferencesKey("last_sync_ms")
        val LAST_ERROR = stringPreferencesKey("last_error")
    }
}
