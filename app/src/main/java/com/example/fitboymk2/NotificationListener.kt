package com.example.fitboymk2

import android.R
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
import kotlin.math.ceil
class NotificationListener : NotificationListenerService()
{
    class StringIdManager
    {
        private val stringToId = mutableMapOf<String, Int>()
        private val idToString = mutableMapOf<Int, String>()
        private val availableIds = ArrayDeque<Int>((1..255).toList())

        fun getId(input: String): Int?
        {
            stringToId[input]?.let { return it }

           //if new we create
            val nextId = availableIds.removeFirstOrNull() ?: return null

            stringToId[input] = nextId
            idToString[nextId] = input
            return nextId
        }

        fun releaseString(input: String): Int?
        {
            val id = stringToId.remove(input)
            if (id != null)
            {
                idToString.remove(id)
                availableIds.add(id)
            }
            return(id)
        }

        fun releaseID(input: Int): String?
        {
            val string = idToString.remove(input)
            if(string != null)
            {
                stringToId.remove(string)
                availableIds.add(input)
            }
            return(string)
        }
    }

    var stringIdManager = StringIdManager();
    private var mediaManager : MediaSessionManager? = null

    private val receiver = object : BroadcastReceiver()
    {
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?)
        {
            if (intent?.action == "com.fitboymk2.DELETENOTIFICATION")
            {
                val codeString: String? = intent.getStringExtra("CODE")
                if(codeString != null)
                {
                    val notificationId = stringIdManager.releaseID(codeString[0].code)
                    this@NotificationListener.cancelNotification(notificationId)
                }
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
                Log.i("metadataCallback, sendDeets", "Data to send: $toSend")
                val MUSIC_SERVICE_UUID_VAL = UUID.fromString("019c9698-ccae-7bd0-9976-3017ee420aba")
                val intent = Intent("com.fitboymk2.SEND_BLE_COMMAND").apply {
                    putExtra("TOSEND", toSend)
                    putExtra("serviceUUID", MUSIC_SERVICE_UUID_VAL.toString())
                    putExtra("characteristicUUID", MUSICDEETS_UUID.toString())
                }

                this@NotificationListener.sendBroadcast(intent)
                lastSent = toSend
                lastSentTime = System.currentTimeMillis()
            }
        }

        override fun onQueueChanged(queue: List<MediaSession.QueueItem?>?)
        {
            Log.i("metadataCallback, onQueueChange", "callback triggered")
            if (queue != null)
            {
                Log.i("metadataCallback, onQueueChange", "${queue.size} items")
                for (i in queue)
                {
                    Log.i("metadataCallback, onQueueChange", "Item: ${i.toString()}")
                    //Log.i("Title", (i?.description?.title ?: "") as String)
                    //Log.i("subtitle", (i?.description?.subtitle ?: "") as String)
                }
            }
            super.onQueueChanged(queue)
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
            Log.i("metadataCallback, onSessionDestroyed", "Active session ${this.activeController} destroyed")
            //deregister this callback
            if(this.activeController != null)
            {
                this.activeController!!.unregisterCallback(this)
            }

            //send message to watch to disable media control.
            val MUSIC_SERVICE_UUID_VAL = UUID.fromString("019c9698-ccae-7bd0-9976-3017ee420aba")
            val intent = Intent("com.fitboymk2.SEND_BLE_COMMAND").apply {
                putExtra("TOSEND", "KILL")
                putExtra("serviceUUID", MUSIC_SERVICE_UUID_VAL.toString())
                putExtra("characteristicUUID", MUSICDEETS_UUID.toString())
            }

            this@NotificationListener.sendBroadcast(intent)
        }
    }

    private val keyListener = @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    object : MediaSessionManager.OnMediaKeyEventSessionChangedListener
    {
        override fun onMediaKeyEventSessionChanged(p0: String, p1: MediaSession.Token?)
        {
            Log.i("keyListener, onMediaKeyEventSessionChanged", "New listener: $p0 $p1")
            if(p0.isEmpty() || (p1 == null)) {
                return
            }
            //wait for 50ms to ensue that the list is populated.
            Thread.sleep(50)

            val mcList = mediaManager?.getActiveSessions(ComponentName("com.example.fitboymk2", ".NotificationListener"))

            if(mcList != null)
            {
                Log.i("keyListener, onMediaKeyEventSessionChanged", "Searching for $p0 session. token: $p1")
                for(i in mcList)
                {
                    Log.i("keyListener, onMediaKeyEventSessionChanged", "Possible match: ${i.packageName}")
                    if(i.sessionToken == p1)
                    {
                        Log.i("keyListener, onMediaKeyEventSessionChanged", "Found media controller ${i.packageName} with token ${i.sessionToken}")
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
        Log.i("Notification Listener, onListenerConnected", "Listener Connected")
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
        var appName = packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(
                sbn.packageName,
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        ) as String

        appName = appName.replace("\u0000", " ")
        val nTitle = extras.getString("android.title")?.replace("\u0000", " ")
        val nSubText = extras.getString("android.subText")?.replace("\u0000", " ")

        var sendMsg = "$appName\u0000$nTitle\u0000$nSubText\u0000"

        if(extras.containsKey("android.messages"))
        {
            sendMsg += "T\u0000"
            val pArray: Array<Parcelable> =
                extras.getParcelableArray("android.messages") as Array<Parcelable>
            val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(pArray)

            var totalLines = 0
            val maxLines = 6
            for (messageI in messages) {
                val msg = messageI.senderPerson?.name.toString() + ":" + messageI.text.toString()
                val numLines = ceil(msg.length/21.0).toInt()

                if((numLines + 1 <= maxLines) || (totalLines == 0))
                {
                    totalLines += numLines
                    sendMsg += (msg.replace("\u0000", " ") + "\n")
                }
            }
        }

        else
        {
            sendMsg += "D\u0000"

            var bigText = extras.getCharSequence("android.bigText").toString()
            val text = extras.getCharSequence("android.text").toString()
            var formattedText = ""

            if(bigText.isNotEmpty())
            {
                if(bigText.length >= 255)
                {
                    bigText = bigText.substring(0, 255)
                }

                formattedText = bigText
            }

            else if(text.isNotEmpty())
            {
                if(text.length >= 255)
                {
                    text.substring(0, 255)
                }

                formattedText = text
            }

            sendMsg += formattedText.replace("\u0000", " ")
        }

        val nId = stringIdManager.getId(sbn.key) //will be 1 to 255 inclusive.

        if(nId == null)
        {
            Log.i("NotificationListener, onNotificationPosted", "No more space. Returning early.")
            return
        }

        val idByte : Char = nId.toChar()
        sendMsg = sendMsg.filter { it.code <= 255 }
        sendMsg += "\u0000$idByte\u0000"

        Log.i("NotificationListener, onNotificationPosted", "Final data packet: $sendMsg; ID is $nId")
        val NOTIFICATION_SERVICE_UUID: UUID = UUID.fromString("d2fa52f9-4c5d-4a05-a010-c26a1b99f5e6")
        val NOTCHAR_UUID: UUID = UUID.fromString("05590c96-12bb-11ee-be56-0242ac120002")

        val intent = Intent("com.fitboymk2.SEND_BLE_COMMAND").apply {
            putExtra("TOSEND", sendMsg)
            putExtra("serviceUUID", NOTIFICATION_SERVICE_UUID.toString())
            putExtra("characteristicUUID", NOTCHAR_UUID.toString())
        }

        this@NotificationListener.sendBroadcast(intent)
    }

    @SuppressLint("MissingPermission")
    override fun onNotificationRemoved(sbn: StatusBarNotification?)
    {
        super.onNotificationRemoved(sbn)
        if(sbn?.key == null)
        {
            return
        }

        val internalId = stringIdManager.releaseString(sbn.key!!)
        Log.i("NotificationListener, onNotificationRemoved", "Deleted notification ID: ${sbn?.key!!}, internal ID: $internalId")
        val NOTIFICATION_SERVICE_UUID: UUID = UUID.fromString("d2fa52f9-4c5d-4a05-a010-c26a1b99f5e6")
        val NOTDELBUF_UUID: UUID = UUID.fromString("19e04166-12bb-11ee-be56-0242ac120002")

        if(internalId == null)
        {
            return
        }
        val intent = Intent("com.fitboymk2.SEND_BLE_COMMAND").apply {
            putExtra("TOSEND", internalId.toChar().toString())
            putExtra("serviceUUID", NOTIFICATION_SERVICE_UUID.toString())
            putExtra("characteristicUUID", NOTDELBUF_UUID.toString())
        }

        this@NotificationListener.sendBroadcast(intent)
    }
}