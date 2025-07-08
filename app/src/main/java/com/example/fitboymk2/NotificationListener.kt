package com.example.fitboymk2

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

class NotificationListener : NotificationListenerService()
{
    private var mediaManager : MediaSessionManager? = null
    private val keyListener = @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    object : MediaSessionManager.OnMediaKeyEventSessionChangedListener
    {
        override fun onMediaKeyEventSessionChanged(p0: String, p1: MediaSession.Token?)
        {
            Log.i("MediaKey new listener", "$p0 " + p1.toString())
            if(p0.isEmpty() || (p1 == null))
            {
                return
            }
            val packageManager = this@NotificationListener.packageManager
            val mediaAppName = packageManager?.getApplicationLabel(
                packageManager.getApplicationInfo(
                    p0,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            ) as String

            //wait for 100ms to ensue that the list is populated.
            Thread.sleep(100)

            val mcList = mediaManager?.getActiveSessions(ComponentName("com.example.fitboymk2", ".NotificationListener"))

            if(mcList != null)
            {
                Log.i("MediaKey", "Searching for new listener's session")
                for(i in mcList)
                {
                    Log.i("S", i.packageName)
                    if(i.sessionToken == p1)
                    {
                        Log.i("KC", "Found media controller")
                        if(metadataCallback.activeController != null)
                        {
                            metadataCallback.activeController!!.unregisterCallback(metadataCallback)
                        }

                        i.unregisterCallback(metadataCallback)
                        i.registerCallback(metadataCallback)
                        metadataCallback.activeController = i

                        break
                    }
                }
            }
        }
    }
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onListenerConnected()
    {
        Log.i("Listener Status", "Listener Connected")
        ContextCompat.startForegroundService(this, Intent(this, BTService::class.java))
        mediaManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        mediaManager!!.removeOnMediaKeyEventSessionChangedListener (keyListener)
        mediaManager!!.addOnMediaKeyEventSessionChangedListener(this.mainExecutor, keyListener)
        super.onListenerConnected()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onNotificationPosted(sbn: StatusBarNotification)
    {
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            //Ignore the notification
            return
        }
        val mNotification = sbn.notification
        val extras = mNotification.extras
        //bundle2string(extras)?.let { Log.i("NL", it) }

        val packageManager = applicationContext.packageManager
        val appName = packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(
                sbn.packageName,
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        ) as String

        val nTitle = extras.getString("android.title")
        val nSubText = extras.getString("android.subText")

        var sendMsg = "<0>$appName<1>$nTitle<2>$nSubText<3>"

        if(extras.containsKey("android.messages"))
        {
            sendMsg += "T<4>\n"
            val pArray: Array<Parcelable> =
                extras.getParcelableArray("android.messages") as Array<Parcelable>
            val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(pArray)

            for (messageI in messages) {
                sendMsg += messageI.senderPerson?.name.toString() + ":" + messageI.text.toString() + "\n"
            }
        }

        else
        {
            sendMsg += "D<4>"

            val bigText = extras.getCharSequence("android.bigText").toString()
            val text = extras.getCharSequence("android.text").toString()
            var formattedText = ""

            if(bigText.isNotEmpty())
            {
                formattedText = bigText
            }

            else if(text.isNotEmpty())
            {
                formattedText = text
            }

            if(formattedText.length > 128)
            {
                formattedText = formattedText.subSequence(0, 125).toString()
                formattedText += "..."
            }

            sendMsg += formattedText
        }

        val nId = sbn.key

        sendMsg.filter { it.code <= 127 }
        sendMsg += "<5>$nId"

        Log.i("SEND MSG", sendMsg)
    }
}