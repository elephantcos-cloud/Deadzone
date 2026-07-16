package com.shohan.deadzonemirror.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.overlayDataStore by preferencesDataStore(name = "overlay_prefs")

/**
 * Saved bounds of the floating overlay window, in real screen pixel coordinates.
 */
data class OverlayBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

/**
 * Persists where Shohan last dragged/resized the overlay to, so it reopens
 * in the same spot instead of a guessed default every time.
 */
object OverlayPreferences {

    private val KEY_X = intPreferencesKey("overlay_x")
    private val KEY_Y = intPreferencesKey("overlay_y")
    private val KEY_WIDTH = intPreferencesKey("overlay_width")
    private val KEY_HEIGHT = intPreferencesKey("overlay_height")

    suspend fun save(context: Context, bounds: OverlayBounds) {
        context.overlayDataStore.edit { prefs ->
            prefs[KEY_X] = bounds.x
            prefs[KEY_Y] = bounds.y
            prefs[KEY_WIDTH] = bounds.width
            prefs[KEY_HEIGHT] = bounds.height
        }
    }

    /** Returns null if nothing has been saved yet (first ever launch). */
    suspend fun load(context: Context): OverlayBounds? {
        val prefs = context.overlayDataStore.data.first()
        val x = prefs[KEY_X] ?: return null
        val y = prefs[KEY_Y] ?: return null
        val width = prefs[KEY_WIDTH] ?: return null
        val height = prefs[KEY_HEIGHT] ?: return null
        return OverlayBounds(x, y, width, height)
    }
}
