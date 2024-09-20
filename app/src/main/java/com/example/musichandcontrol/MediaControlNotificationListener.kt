package com.example.musichandcontrol

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MediaControlNotificationListener : NotificationListenerService() {

    companion object {
        var mediaController: MediaController? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        updateMediaController()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        updateMediaController()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        updateMediaController()
    }

    private fun updateMediaController() {
        val mediaSessionManager =
            getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
        val controllers = mediaSessionManager.getActiveSessions(
            ComponentName(this, MediaControlNotificationListener::class.java)
        )

        // Choose the first available media controller
        if (controllers.isNotEmpty()) {
            mediaController = controllers[0]
        }
    }
}
