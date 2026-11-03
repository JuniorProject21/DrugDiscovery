package com.inhibitai.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.sessionDataStore by preferencesDataStore(name = "session")

/** Keeps the user signed in between app launches (token + basic profile, in app-private storage). */
class SessionStore(private val context: Context) {

    data class Session(val token: String, val user: User)

    suspend fun load(): Session? {
        val prefs = context.sessionDataStore.data.first()
        val token = prefs[TOKEN] ?: return null
        val user = User(prefs[USER_ID] ?: return null, prefs[EMAIL].orEmpty(), prefs[NAME].orEmpty())
        return Session(token, user)
    }

    suspend fun save(token: String, user: User) {
        context.sessionDataStore.edit {
            it[TOKEN] = token
            it[USER_ID] = user.id
            it[EMAIL] = user.email
            it[NAME] = user.fullName
        }
    }

    suspend fun updateUser(user: User) {
        context.sessionDataStore.edit {
            it[USER_ID] = user.id
            it[EMAIL] = user.email
            it[NAME] = user.fullName
        }
    }

    suspend fun clear() {
        context.sessionDataStore.edit { it.clear() }
    }

    private companion object {
        val TOKEN = stringPreferencesKey("token")
        val USER_ID = longPreferencesKey("user_id")
        val EMAIL = stringPreferencesKey("email")
        val NAME = stringPreferencesKey("full_name")
    }
}
