package com.example.fitboymk2

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log

class WatchMediaSync(private val context: Context) : MediaController.Callback() {

    var activeController: MediaController? = null
    var lastQueue: List<MediaSession.QueueItem?>? = null
    var lastSongID: Long? = null
    var updateLastVals: Boolean = false

    private var lastSendMD = ""
    private var lastSentTimeMD = System.currentTimeMillis()

    fun sendDeets(mc: MediaController?) {
        val metadata = mc?.metadata
        val pbS = mc?.playbackState

        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: " "
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: " "
        val trackName = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: " "
        val trackLength = (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).let { if (it > 0) it / 1000 else 0L }

        val cPos = (pbS?.position ?: 0L).let { if (it > 0) it / 1000 else 0L }
        val play = if (pbS?.state == PlaybackState.STATE_PAUSED) 0 else 1

        val toSend = if (trackName.isBlank()) "KILL" else "$trackName\u0000$artist\u0000$album\u0000$trackLength\u0000$cPos\u0000$play\u0000"
        //Log.i("sendDeets", toSend)
        if (toSend != lastSendMD || (System.currentTimeMillis() - lastSentTimeMD) > 200L) {
            context.sendBleCommand(Constants.UUIDs.MUSIC_SERVICE, Constants.UUIDs.MUSIC_DEETS_CHAR, toSend)
            lastSendMD = toSend
            lastSentTimeMD = System.currentTimeMillis()
        }

        if (activeController?.playbackState?.activeQueueItemId != lastSongID) {
            activeController?.queue?.let { sendWatchPlaylist(it) }
        }
    }

    private fun sendWatchPlaylist(queue: List<MediaSession.QueueItem?>) {
        val activeId = activeController?.playbackState?.activeQueueItemId
        lastSongID = activeId

        val queueFinal = if (queue.size > 50) {
            val currentIndex = queue.indexOfFirst { it?.queueId == activeId }.takeIf { it != -1 } ?: 0
            val targetBefore = 12
            val afterCountNeeded = 49 - minOf(targetBefore, currentIndex)

            val start = (currentIndex - minOf(currentIndex, targetBefore + (afterCountNeeded - minOf(afterCountNeeded, queue.size - currentIndex - 1)))).coerceAtLeast(0)
            val end = (start + 49).coerceAtMost(queue.size - 1)
            queue.slice(start..end)
        } else queue

        if (queueFinal.isEmpty()) return

        val curIndexInFQ = queueFinal.indexOfFirst { it?.queueId == activeId }.coerceAtLeast(0)
        val posByte = curIndexInFQ.toChar()

        var prefixChar = '\u0040'
        var itemsToAppend = queueFinal

        val lq = lastQueue
        if (!lq.isNullOrEmpty()) {
            // Check for fwd seek
            val seekFwdVal = lq.indexOfFirst {
                it?.description?.title == queueFinal[0]?.description?.title &&
                        it?.description?.subtitle == queueFinal[0]?.description?.subtitle
            }

            if (seekFwdVal > 0) {
                val overlapSize = minOf(lq.size - seekFwdVal, queueFinal.size)
                val isValidFF = (0 until overlapSize).all { i ->
                    lq[seekFwdVal + i]?.description?.title == queueFinal[i]?.description?.title &&
                            lq[seekFwdVal + i]?.description?.subtitle == queueFinal[i]?.description?.subtitle
                }

                if (isValidFF) {
                    prefixChar = (seekFwdVal + 64).toChar()
                    itemsToAppend = queueFinal.drop(overlapSize)
                }
            } else {
                // Check for rewind
                val seekBackVal = queueFinal.indexOfFirst {
                    it?.description?.title == lq[0]?.description?.title &&
                            it?.description?.subtitle == lq[0]?.description?.subtitle
                }

                if (seekBackVal > 0) {
                    val overlapSize = minOf(queueFinal.size - seekBackVal, lq.size)
                    val isValidRW = (0 until overlapSize).all { i ->
                        queueFinal[seekBackVal + i]?.description?.title == lq[i]?.description?.title &&
                                queueFinal[seekBackVal + i]?.description?.subtitle == lq[i]?.description?.subtitle
                    }

                    if (isValidRW) {
                        prefixChar = (64 - seekBackVal).toChar()
                        itemsToAppend = queueFinal.take(seekBackVal)
                    }
                }
            }
        }

        val toSend = buildString {
            append(prefixChar).append(posByte)
            for (item in itemsToAppend) {
                append(item?.description?.title ?: " ").append('\u0000')
                append(item?.description?.subtitle ?: " ").append('\u0000')
            }
        }

        if (updateLastVals) {
            lastQueue = queueFinal
            context.sendBleCommand(Constants.UUIDs.MUSIC_SERVICE, Constants.UUIDs.MUSIC_QUEUE_CHAR, toSend)
        }
    }

    public fun queueSeek(index: Int)
    {
        if(lastQueue?.indices?.contains(index) == true)
        {
            val desiredSong = lastQueue!![index]
            Log.i("queueSeek", "Intent to seek to song: ${desiredSong.toString()}")

            if(desiredSong != null) {
                activeController?.transportControls?.skipToQueueItem(desiredSong.queueId)
            }
        }
    }

    override fun onQueueChanged(queue: List<MediaSession.QueueItem?>?) {
        super.onQueueChanged(queue)
        queue?.let { sendWatchPlaylist(it) }
    }

    override fun onPlaybackStateChanged(state: PlaybackState?) {
        super.onPlaybackStateChanged(state)
        sendDeets(activeController)
    }

    override fun onMetadataChanged(metadata: MediaMetadata?) {
        super.onMetadataChanged(metadata)
        sendDeets(activeController)
    }

    override fun onSessionDestroyed() {
        super.onSessionDestroyed()
        activeController?.unregisterCallback(this)
        context.sendBleCommand(Constants.UUIDs.MUSIC_SERVICE, Constants.UUIDs.MUSIC_DEETS_CHAR, "KILL")
    }
}