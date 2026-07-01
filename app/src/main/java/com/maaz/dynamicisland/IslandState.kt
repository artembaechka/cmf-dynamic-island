package com.maaz.dynamicisland

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.drawable.Icon

/**
 * What the island is currently showing. The notification service produces these,
 * the overlay service consumes them.
 */
sealed class IslandContent {

    object Idle : IslandContent()

    data class Notification(
        val key: String,
        val packageName: String,
        val appName: CharSequence,
        val title: CharSequence?,
        val text: CharSequence?,
        val smallIcon: Icon?,
        val contentIntent: PendingIntent?,
        val accentColor: Int
    ) : IslandContent()

    data class Media(
        val title: CharSequence?,
        val artist: CharSequence?,
        val art: Bitmap?,
        val isPlaying: Boolean,
        val packageName: String
    ) : IslandContent()
}

interface IslandListener {
    fun onContent(content: IslandContent)
    fun onMediaGone()
}

/**
 * Tiny synchronous bus. Both the NotificationListenerService and the overlay
 * IslandService live in the same process, so a plain singleton with listeners
 * is enough — no IPC, no broadcasts. Callers must post on the main thread.
 */
object IslandBus {
    private val listeners = mutableListOf<IslandListener>()

    @Synchronized
    fun register(l: IslandListener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    @Synchronized
    fun unregister(l: IslandListener) {
        listeners.remove(l)
    }

    fun post(content: IslandContent) {
        synchronized(this) { listeners.toList() }.forEach { it.onContent(content) }
    }

    fun mediaGone() {
        synchronized(this) { listeners.toList() }.forEach { it.onMediaGone() }
    }
}
