package com.dongmyeong.smsautoreply.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sms_auto_reply")

/**
 * Persists [AppState] as a single JSON blob in Preferences DataStore.
 *
 * The whole state is small, so one atomic document is simpler and safer than
 * several keys: receivers and the UI can all call [update]/[transact] without
 * clobbering each other because DataStore serialises writes.
 */
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val state: Flow<AppState> = store.data.map { prefs -> decode(prefs[KEY_STATE]) }

    suspend fun current(): AppState = state.first()

    suspend fun update(transform: (AppState) -> AppState) {
        transact { state -> transform(state) to Unit }
    }

    /** Atomically transform the state and return a side value (e.g. actions to perform afterwards). */
    suspend fun <R> transact(transform: (AppState) -> Pair<AppState, R>): R {
        var result: R? = null
        var produced = false
        store.edit { prefs ->
            val (next, r) = transform(decode(prefs[KEY_STATE]))
            prefs[KEY_STATE] = json.encodeToString(AppState.serializer(), next)
            result = r
            produced = true
        }
        check(produced) { "DataStore edit did not run" }
        @Suppress("UNCHECKED_CAST")
        return result as R
    }

    private fun decode(raw: String?): AppState {
        if (raw.isNullOrBlank()) return AppState()
        return try {
            json.decodeFromString(AppState.serializer(), raw)
        } catch (e: SerializationException) {
            AppState()
        } catch (e: IllegalArgumentException) {
            AppState()
        }
    }

    private companion object {
        val KEY_STATE = stringPreferencesKey("state_json")
    }
}
