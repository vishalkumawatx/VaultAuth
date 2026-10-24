package com.example.authanticator.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences as StoredPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.preferences by preferencesDataStore("preferences")

data class Preferences(
    val onboarded: Boolean = false,
    val lightTheme: Boolean = false,
    val hideCodes: Boolean = false,
    val clipboard: Boolean = true,
    val lockInBackground: Boolean = true,
    val timeoutSeconds: Int = 60,
    val reduceMotion: Boolean = false
)

class PreferenceStore(context: Context, private val store: DataStore<StoredPreferences> = context.preferences) {
    private val onboarded = booleanPreferencesKey("onboarded")
    private val light = booleanPreferencesKey("light")
    private val privacy = booleanPreferencesKey("privacy")
    private val clipboard = booleanPreferencesKey("clipboard")
    private val background = booleanPreferencesKey("background")
    private val timeout = intPreferencesKey("timeout")
    private val reduceMotion = booleanPreferencesKey("reduce_motion")
    val flow = store.data.map {
        Preferences(it[onboarded] ?: false, it[light] ?: false, it[privacy] ?: false,
            it[clipboard] ?: true, it[background] ?: true, it[timeout] ?: 60, it[reduceMotion] ?: false)
    }

    suspend fun update(value: Preferences) {
        require(value.timeoutSeconds in listOf(30, 60, 300))
        store.edit {
            it[onboarded] = value.onboarded
            it[light] = value.lightTheme
            it[privacy] = value.hideCodes
            it[clipboard] = value.clipboard
            it[background] = value.lockInBackground
            it[timeout] = value.timeoutSeconds
            it[reduceMotion] = value.reduceMotion
        }
    }
}