package com.example.fitboymk2

import android.content.*
import android.media.session.MediaSessionManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

class NotificationListener : NotificationListenerService() {

    private val idManager = IdManager<String>()
    private var mediaManager: MediaSessionManager? = null
    private lateinit var watchMediaSync: WatchMediaSync

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Constants.Actions.DELETE_NOTIFICATION -> {
                    intent.getStringExtra("CODE")?.firstOrNull()?.code?.let {
                        val internalKey = idManager.releaseId(it)
                        cancelNotification(internalKey)
                    }
                }
                Constants.Actions.WATCH_DISCONNECTED -> {
                    watchMediaSync.lastQueue = null
                    watchMediaSync.updateLastVals = false
                }
                Constants.Actions.WATCH_CONNECTED -> {
                    watchMediaSync.lastQueue = null
                    watchMediaSync.updateLastVals = true
                }

                Constants.Actions.MUSIC_CONTROL -> {
                    intent.getIntExtra("Index", -1).let {
                        watchMediaSync.queueSeek(it)
                    }
                }
            }
        }
    }

    private val keyListener = MediaSessionManager.OnMediaKeyEventSessionChangedListener { p0, p1 ->
        if (p0.isEmpty() || p1 == null) return@OnMediaKeyEventSessionChangedListener
        Thread.sleep(50)

        val mcList = mediaManager?.getActiveSessions(ComponentName(applicationContext, NotificationListener::class.java))
        mcList?.firstOrNull { it.sessionToken == p1 }?.let { newController ->
            watchMediaSync.activeController?.unregisterCallback(watchMediaSync)
            newController.registerCallback(watchMediaSync)
            watchMediaSync.activeController = newController
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        watchMediaSync = WatchMediaSync(this)

        ContextCompat.startForegroundService(this, Intent(this, BTService::class.java))
        mediaManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        mediaManager?.removeOnMediaKeyEventSessionChangedListener(keyListener)
        mediaManager?.addOnMediaKeyEventSessionChangedListener(mainExecutor, keyListener)

        val filter = IntentFilter().apply {
            addAction(Constants.Actions.DELETE_NOTIFICATION)
            addAction(Constants.Actions.WATCH_CONNECTED)
            addAction(Constants.Actions.WATCH_DISCONNECTED)
            addAction(Constants.Actions.MUSIC_CONTROL)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val nId = idManager.getId(sbn.key) ?: return
        val msg = NotificationParser.parse(applicationContext, sbn, nId) ?: return

        sendBleCommand(Constants.UUIDs.NOTIFICATION_SERVICE, Constants.UUIDs.NOTIFICATION_CHAR, msg)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let { key ->
            val internalId = idManager.releaseItem(key) ?: return
            sendBleCommand(Constants.UUIDs.NOTIFICATION_SERVICE, Constants.UUIDs.NOTIFICATION_DEL_BUF_CHAR, internalId.toChar().toString())
        }
    }
}