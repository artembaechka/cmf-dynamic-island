package com.maaz.dynamicisland

import android.content.Context

/**
 * All sizes are stored in dp. They are tuned per device from the settings screen
 * so the collapsed pill hugs the punch-hole exactly.
 *
 * Defaults below are a starting point for the CMF Phone 2 Pro (1080x2392,
 * centered cutout). Fine-tune with the sliders in the app.
 */
object Prefs {

    private const val FILE = "island_prefs"

    const val KEY_Y = "y_offset"
    const val KEY_CW = "collapsed_w"
    const val KEY_CH = "collapsed_h"
    const val KEY_EW = "expanded_w"
    const val KEY_CORNER = "corner"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun yOffset(c: Context) = sp(c).getFloat(KEY_Y, 8f)
    fun collapsedW(c: Context) = sp(c).getFloat(KEY_CW, 112f)
    fun collapsedH(c: Context) = sp(c).getFloat(KEY_CH, 30f)
    fun expandedW(c: Context) = sp(c).getFloat(KEY_EW, 330f)
    fun corner(c: Context) = sp(c).getFloat(KEY_CORNER, 22f)

    fun set(c: Context, key: String, value: Float) {
        sp(c).edit().putFloat(key, value).apply()
    }
}
