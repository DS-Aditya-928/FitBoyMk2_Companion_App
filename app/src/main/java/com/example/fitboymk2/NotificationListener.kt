package com.example.fitboymk2

import android.annotation.SuppressLint
import android.app.Notification
import android.bluetooth.BluetoothGattCharacteristic
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.util.UUID

class NotificationListener : NotificationListenerService()
{
    private var mediaManager : MediaSessionManager? = null

    private val receiver = object : BroadcastReceiver()
    {
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?)
        {
            if (intent?.action == "com.fitboymk2.DELETENOTIFICATION")
            {
                val code = intent.getStringExtra("CODE")

                this@NotificationListener.cancelNotification(code)
            }
        }
    }


    private val metadataCallback = object : MediaController.Callback()
    {
        val MUSICDEETS_UUID: UUID = UUID.fromString("5df4d2b0-a927-11ee-a506-0242ac120002")
        var activeController: MediaController? = null
        //var nlContext : Context? = null
        var lastSent = ""
        var lastSentTime = System.currentTimeMillis()
        fun sendDeets(mc: MediaController?)
        {
            var album = ""
            var trackName = ""
            var artist = ""
            var trackLength = 0L
            var play = 0
            var cPos = 0L

            if(mc != null)
            {
                val metadata = mc.metadata

                if(metadata != null)
                {
                    album = if(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) == null) " " else metadata.getString(
                        MediaMetadata.METADATA_KEY_ALBUM)
                    artist = if(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) == null) " " else metadata.getString(
                        MediaMetadata.METADATA_KEY_ARTIST)
                    trackName = if(metadata.getString(MediaMetadata.METADATA_KEY_TITLE) == null) " " else metadata.getString(
                        MediaMetadata.METADATA_KEY_TITLE)
                    trackLength = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                    if(trackLength > 0)
                    {
                        trackLength /= 1000//convert to seconds.
                    }
                }

                val pbS = mc.playbackState

                if(pbS != null)
                {
                    cPos = pbS.position
                    if(cPos > 0)
                    {
                        cPos /= 1000
                    }
                    play = -1 * (pbS.state == PlaybackState.STATE_PAUSED).compareTo(true)
                }

                //trackN = if(metadata.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER) == null) " " else (metadata.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER).toString())
                //trackN = if(metadata.containsKey("com.google.android.music.mediasession.METADATA_KEY_QUEUE_POSITION")) metadata.getLong("com.google.android.music.mediasession.METADATA_KEY_QUEUE_POSITION").toString() else "0aa"
                //totalT = if(metadata.getLong(MediaMetadata.METADATA_KEY_NUM_TRACKS) == null) " " else metadata.getLong(MediaMetadata.METADATA_KEY_NUM_TRACKS).toString()
            }

            val toSend = if(trackName.isEmpty()) {
                "KILL"
            } else {
                "<AD>$trackName<1>$artist<2>$album<3>$trackLength<4>$cPos<5>$play"
            }

            //Log.i("MDCB", "Attempted $toSend  $lastSent")
            if((toSend != lastSent) || ((System.currentTimeMillis() - lastSentTime) > 200L))
            {
                Log.i("TS", toSend)

                val intent = Intent("com.fitboymk2.SEND_BLE_COMMAND").apply {
                    putExtra("TOSEND", toSend)
                    putExtra("uuid", MUSICDEETS_UUID.toString())
                }

                this@NotificationListener.sendBroadcast(intent)
                lastSent = toSend
                lastSentTime = System.currentTimeMillis()
            }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?)
        {
            sendDeets(activeController)
            //Log.i("PS", state.toString())
            super.onPlaybackStateChanged(state)
        }

        @SuppressLint("MissingPermission", "WrongConstant")
        override fun onMetadataChanged(metadata: MediaMetadata?)
        {
            sendDeets(activeController)

            super.onMetadataChanged(metadata)
        }

        @SuppressLint("MissingPermission")
        override fun onSessionDestroyed() {
            super.onSessionDestroyed()
            Log.i("Sesh", "sesh destroyed")
            //deregister this callback
            if(this.activeController != null)
            {
                this.activeController!!.unregisterCallback(this)
            }
            //send message to watch to disable media control.
            /*
            if((deetsCharacteristic != null) and connected)
            {
                btGatt?.writeCharacteristic(deetsCharacteristic!!, "KILL".toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            }
            */
            val intent = Intent("com.fitboymk2.SEND_BLE_COMMAND").apply {
                putExtra("TOSEND", "KILL")
                putExtra("uuid", MUSICDEETS_UUID.toString())
            }

            this@NotificationListener.sendBroadcast(intent)
        }
    }

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
            //val packageManager = this@NotificationListener.packageManager
            /*
            val mediaAppName = packageManager?.getApplicationLabel(
                packageManager.getApplicationInfo(
                    p0,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            ) as String
            */
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
        super.onListenerConnected()
        Log.i("Listener Status", "Listener Connected")
        ContextCompat.startForegroundService(this, Intent(this, BTService::class.java))
        mediaManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        mediaManager!!.removeOnMediaKeyEventSessionChangedListener (keyListener)
        mediaManager!!.addOnMediaKeyEventSessionChangedListener(this.mainExecutor, keyListener)

        try
        {
            registerReceiver(receiver, IntentFilter("com.fitboymk2.DELETENOTIFICATION"),
                RECEIVER_EXPORTED
            )
        }

        catch (_:Exception)
        {
            unregisterReceiver(receiver)
            registerReceiver(receiver, IntentFilter("com.fitboymk2.DELETENOTIFICATION"),
                RECEIVER_EXPORTED
            )
        }
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

        sendMsg = sendMsg.filter { it.code <= 127 }
        sendMsg += "<5>$nId"

        Log.i("SEND MSG", sendMsg)
        val NOTBUF_UUID: UUID = UUID.fromString("05590c96-12bb-11ee-be56-0242ac120002")
        val intent = Intent("com.fitboymk2.SEND_BLE_COMMAND").apply {
            putExtra("TOSEND", sendMsg)
            putExtra("uuid", NOTBUF_UUID.toString())
        }

        this@NotificationListener.sendBroadcast(intent)
    }

    @SuppressLint("MissingPermission")
    override fun onNotificationRemoved(sbn: StatusBarNotification?)
    {
        super.onNotificationRemoved(sbn)
        Log.i("SEND MSG DEL", sbn?.key!!)
        val NOTDELBUF_UUID: UUID = UUID.fromString("19e04166-12bb-11ee-be56-0242ac120002")
        val intent = Intent("com.fitboymk2.SEND_BLE_COMMAND").apply {
            putExtra("TOSEND", sbn.key!!)
            putExtra("uuid", NOTDELBUF_UUID.toString())
        }

        this@NotificationListener.sendBroadcast(intent)
    }
}