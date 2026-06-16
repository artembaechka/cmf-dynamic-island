package com.maaz.dynamicisland

import android.app.Notification
import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * The data tap. Once "notification access" is granted in system settings this
 * service is bound by Android and starts receiving every posted notification.
 * It also uses MediaSessionManager to track whatever is currently playing so
 * the island can show now-playing + transport controls.
 */
class NotificationService : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var mediaCallback: MediaController.Callback? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            bindToTopSession(controllers)
        }

    companion object {
        @Volatile
        var instance: NotificationService? = null
            private set
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, NotificationService::class.java)
        try {
            sessionManager?.addOnActiveSessionsChangedListener(sessionsListener, component)
            bindToTopSession(sessionManager?.getActiveSessions(component))
        } catch (_: SecurityException) {
            // Will retry once access is fully granted.
        }
    }

    override fun onListenerDisconnected() {
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        detachMedia()
        instance = null
        super.onListenerDisconnected()
    }

    // ---------- Media ----------

    private fun bindToTopSession(controllers: List<MediaController>?) {
        val top = controllers?.firstOrNull()
        if (top?.sessionToken == activeController?.sessionToken) {
            top?.let { pushMedia(it) }
            return
        }
        mediaCallback?.let { cb -> activeController?.unregisterCallback(cb) }
        activeController = top
        if (top == null) {
            main.post { IslandBus.mediaGone() }
            return
        }
        val cb = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = pushMedia(top)
            override fun onMetadataChanged(metadata: MediaMetadata?) = pushMedia(top)
            override fun onSessionDestroyed() {
                main.post { IslandBus.mediaGone() }
            }
        }
        mediaCallback = cb
        top.registerCallback(cb)
        pushMedia(top)
    }

    private fun pushMedia(c: MediaController) {
        val md = c.metadata
        val state = c.playbackState
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val title = md?.getText(MediaMetadata.METADATA_KEY_TITLE)
        val artist = md?.getText(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val art: Bitmap? = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val pkg = c.packageName
        main.post {
            IslandBus.post(IslandContent.Media(title, artist, art, playing, pkg))
        }
    }

    private fun detachMedia() {
        mediaCallback?.let { cb -> activeController?.unregisterCallback(cb) }
        mediaCallback = null
        activeController = null
    }

    fun mediaPlayPause() {
        val c = activeController ?: return
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing) c.transportControls.pause() else c.transportControls.play()
    }

    fun mediaNext() = activeController?.transportControls?.skipToNext() ?: Unit
    fun mediaPrev() = activeController?.transportControls?.skipToPrevious() ?: Unit

    // ---------- Notifications ----------

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return

        val n = sbn.notification ?: return
        val extras = n.extras

        // Media notifications are handled by the media-session path above.
        val isMedia = n.category == Notification.CATEGORY_TRANSPORT ||
            extras.containsKey("android.mediaSession")
        if (isMedia) return

        // Skip group summaries (the children carry the real content).
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        val appName = try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0))
        } catch (_: Exception) {
            sbn.packageName
        }

        val content = IslandContent.Notification(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            smallIcon = n.smallIcon,
            contentIntent = n.contentIntent,
            accentColor = n.color
        )
        main.post { IslandBus.post(content) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op: the island auto-collapses on a timer. Hook here if you want
        // dismissals to immediately collapse the matching notification.
    }
}
