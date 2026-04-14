package com.example.fitboymk2

import android.annotation.SuppressLint
import android.app.Notification
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
import java.util.UUID
import kotlin.let
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

    class LongIdManager
    {
        private val longToId = mutableMapOf<Long, Int>()
        private val idToLong = mutableMapOf<Int, Long>()
        private val availableIds = ArrayDeque<Int>((1..255).toList())

        fun getId(input: Long): Int?
        {
            longToId[input]?.let { return it }

            //if new we create
            val nextId = availableIds.removeFirstOrNull() ?: return null

            longToId[input] = nextId
            idToLong[nextId] = input
            return nextId
        }

        fun releaseLong(input: Long): Int?
        {
            val id = longToId.remove(input)
            if (id != null)
            {
                idToLong.remove(id)
                availableIds.add(id)
            }
            return(id)
        }

        fun releaseID(input: Int): Long?
        {
            val l = idToLong.remove(input)
            if(l != null)
            {
                longToId.remove(l)
                availableIds.add(input)
            }
            return(l)
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
        var lastSendMD = ""
        var lastSentTimeMD = System.currentTimeMillis()
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
                "$trackName\u0000$artist\u0000$album\u0000$trackLength\u0000$cPos\u0000$play\u0000"
            }

            //Log.i("MDCB", "Attempted $toSend  $lastSent")
            if((toSend != lastSendMD) || ((System.currentTimeMillis() - lastSentTimeMD) > 200L))
            {
                Log.i("metadataCallback, sendDeets", "Data to send: $toSend")
                val MUSIC_SERVICE_UUID_VAL = UUID.fromString("019c9698-ccae-7bd0-9976-3017ee420aba")
                val intent = Intent("com.fitboymk2.SEND_BLE_COMMAND").apply {
                    putExtra("TOSEND", toSend)
                    putExtra("serviceUUID", MUSIC_SERVICE_UUID_VAL.toString())
                    putExtra("characteristicUUID", MUSICDEETS_UUID.toString())
                }

                this@NotificationListener.sendBroadcast(intent)
                lastSendMD = toSend
                lastSentTimeMD = System.currentTimeMillis()
            }

            if(activeController?.playbackState?.activeQueueItemId != lastSongID)
            {
                Log.i("sendDeets", "Old: $lastSongID, New: ${activeController?.playbackState?.activeQueueItemId}")
                val currentQueue = activeController?.queue
                if(currentQueue != null)
                    sendWatchPlaylist(currentQueue)
            }
        }

        var lastQueue : List<MediaSession.QueueItem?>? = null
        var lastSongID : Long? = null
        fun sendWatchPlaylist(queue: List<MediaSession.QueueItem?>)
        {
            val activeId = activeController?.playbackState?.activeQueueItemId
            lastSongID = activeId
            var queueFinal: List<MediaSession.QueueItem?>? = null
            queue.size.let {
                if(it > 50) {
                    val currentIndex = queue.indexOfFirst { it?.queueId == activeId }.takeIf { it != -1 } ?: 0

                    val maxTotal = 50
                    val targetBefore = 12
                    val targetAfter = maxTotal - targetBefore - 1

                    val actualAvailableBefore = currentIndex
                    val actualAvailableAfter = queue.size - currentIndex - 1

                    val beforeCount = minOf(targetBefore, actualAvailableBefore)
                    val afterCountNeeded = targetAfter + (targetBefore - beforeCount)
                    val afterCount = minOf(afterCountNeeded, actualAvailableAfter)

                    val finalBeforeCount = minOf(actualAvailableBefore, beforeCount + (afterCountNeeded - afterCount))

                    val start = (currentIndex - finalBeforeCount).coerceAtLeast(0)
                    val end = (currentIndex + afterCount).coerceAtMost(queue.size - 1)

                    queueFinal = queue.slice(start..end)
                } else {
                    queueFinal = queue
                }
            }

            val fixedQueue = queueFinal
            if (fixedQueue != null && fixedQueue.isNotEmpty())
            {
                //look for long subsequences. first do a ff pass, then a backward pass.
                val lastQueueSize = lastQueue?.size ?: 0
                var validFF = false
                var validRW = false
                var seekFwdVal = 0
                var seekBackVal = 0
                var idxFF = 0

                lastQueue?.let {
                    for(i in it.indices) {
                        //go forward until we find the first of this new queue. then verify to see if a ff will cover it.
                        if((lastQueue!![i]?.description?.title == fixedQueue[0]?.description?.title)
                            && (lastQueue!![i]?.description?.subtitle == fixedQueue[0]?.description?.subtitle)) {

                            seekFwdVal = i
                            validFF = true

                            for(j in i until lastQueueSize)
                            {
                                if(fixedQueue[idxFF]?.description == null)
                                {
                                    continue
                                }

                                if((lastQueue!![j]?.description?.title == fixedQueue[idxFF]?.description?.title)
                                    && (lastQueue!![j]?.description?.subtitle == fixedQueue[idxFF]?.description?.subtitle))
                                {
                                    idxFF++
                                }

                                else
                                {
                                    validFF = false
                                    break
                                }
                            }

                            break
                        }
                    }
                }

                //also try for a seekback
                var idxRW = 0
                if(lastQueue != null) {
                    for (i in fixedQueue.indices) {
                        //go forward until we find the first of this new queue. then verify to see if a ff will cover it.
                        if ((lastQueue!![0]?.description?.title == fixedQueue[i]?.description?.title)
                            && (lastQueue!![0]?.description?.subtitle == fixedQueue[i]?.description?.subtitle)
                        ) {

                            seekBackVal = i
                            validRW = true

                            for (j in i until fixedQueue.size) {
                                if (lastQueue!![idxRW]?.description == null) {
                                    continue
                                }

                                if ((lastQueue!![idxRW]?.description?.title == fixedQueue[j]?.description?.title)
                                    && (lastQueue!![idxRW]?.description?.subtitle == fixedQueue[j]?.description?.subtitle)
                                ) {
                                    idxRW++
                                } else {
                                    validRW = false
                                    break
                                }
                            }

                            break
                        }
                    }
                }
                //fixedQueue is the return item
                Log.i("sendWatchPlaylist", "${fixedQueue.size} items in arranged list")
                for (i in fixedQueue)
                {
                    Log.i("sendWatchPlaylist", "Item: ${i.toString()}")
                }

                if(validFF)
                {
                    Log.i("sendWatchPlaylist", "Can optimize by seeking to $seekFwdVal and appending...")

                    for(k in idxFF until fixedQueue.size)
                    {
                        Log.i("sendWatchPlaylist", "APPEND ITEM: ${fixedQueue[k].toString()}")
                    }
                }

                if(validRW)
                {
                    Log.i("sendWatchPlaylist", "Can optimize by seeking back by $seekBackVal and adding to top...")

                    for(k in 0 until seekBackVal)
                    {
                        Log.i("sendWatchPlaylist", "TO TOP: ${fixedQueue[k].toString()}")
                    }
                }

                lastQueue = fixedQueue
            }
        }

        override fun onQueueChanged(queue: List<MediaSession.QueueItem?>?)
        {
            Log.i("metadataCallback, onQueueChange", "callback triggered")
            Log.i("metadataCallback, onQueueChange", "Current song ID: ${activeController?.playbackState?.activeQueueItemId}")

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