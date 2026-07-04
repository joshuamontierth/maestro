package com.maestro.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.maestro.app.face.GestureConfig
import com.maestro.app.face.GestureType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "maestro_settings")

/** Persists [GestureConfig] in a Preferences DataStore and exposes it as a Flow. */
class SettingsStore(private val context: Context) {

    private object Keys {
        val NEXT = stringPreferencesKey("next_gesture")
        val PREV = stringPreferencesKey("prev_gesture")
        val YAW = floatPreferencesKey("yaw_threshold")
        val EYE_CLOSED = floatPreferencesKey("eye_closed_threshold")
        val EYE_OPEN = floatPreferencesKey("eye_open_threshold")
        val SMILE = floatPreferencesKey("smile_threshold")
        val DWELL = longPreferencesKey("dwell_ms")
        val COOLDOWN = longPreferencesKey("cooldown_ms")
        val LONG_BLINK = longPreferencesKey("long_blink_ms")
        val ENABLED = booleanPreferencesKey("enabled_by_default")
    }

    val configFlow: Flow<GestureConfig> = context.dataStore.data.map { prefs ->
        val defaults = GestureConfig()
        GestureConfig(
            nextGesture = prefs[Keys.NEXT]?.let { GestureType.fromName(it) } ?: defaults.nextGesture,
            prevGesture = prefs[Keys.PREV]?.let { GestureType.fromName(it) } ?: defaults.prevGesture,
            yawThresholdDeg = prefs[Keys.YAW] ?: defaults.yawThresholdDeg,
            eyeClosedThreshold = prefs[Keys.EYE_CLOSED] ?: defaults.eyeClosedThreshold,
            eyeOpenThreshold = prefs[Keys.EYE_OPEN] ?: defaults.eyeOpenThreshold,
            smileThreshold = prefs[Keys.SMILE] ?: defaults.smileThreshold,
            dwellMs = prefs[Keys.DWELL] ?: defaults.dwellMs,
            cooldownMs = prefs[Keys.COOLDOWN] ?: defaults.cooldownMs,
            longBlinkMs = prefs[Keys.LONG_BLINK] ?: defaults.longBlinkMs,
            enabledByDefault = prefs[Keys.ENABLED] ?: defaults.enabledByDefault
        )
    }

    suspend fun save(config: GestureConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NEXT] = config.nextGesture.name
            prefs[Keys.PREV] = config.prevGesture.name
            prefs[Keys.YAW] = config.yawThresholdDeg
            prefs[Keys.EYE_CLOSED] = config.eyeClosedThreshold
            prefs[Keys.EYE_OPEN] = config.eyeOpenThreshold
            prefs[Keys.SMILE] = config.smileThreshold
            prefs[Keys.DWELL] = config.dwellMs
            prefs[Keys.COOLDOWN] = config.cooldownMs
            prefs[Keys.LONG_BLINK] = config.longBlinkMs
            prefs[Keys.ENABLED] = config.enabledByDefault
        }
    }
}
