package com.example.fitboymk2

import android.annotation.SuppressLint
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.util.Log

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
            album = if(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) == null) " " else metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
            artist = if(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) == null) " " else metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            trackName = if(metadata.getString(MediaMetadata.METADATA_KEY_TITLE) == null) " " else metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            trackLength = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        }

        val pbS = mc.playbackState

        if(pbS != null)
        {
            cPos = pbS.position
            play = -1 * (pbS.state == PlaybackState.STATE_PAUSED).compareTo(true)
        }

        //trackN = if(metadata.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER) == null) " " else (metadata.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER).toString())
        //trackN = if(metadata.containsKey("com.google.android.music.mediasession.METADATA_KEY_QUEUE_POSITION")) metadata.getLong("com.google.android.music.mediasession.METADATA_KEY_QUEUE_POSITION").toString() else "0aa"
        //totalT = if(metadata.getLong(MediaMetadata.METADATA_KEY_NUM_TRACKS) == null) " " else metadata.getLong(MediaMetadata.METADATA_KEY_NUM_TRACKS).toString()
    }

    val toSend = "<AD>$trackName<1>$artist<2>$album<3>$trackLength<4>$cPos<5>$play"

    Log.i("TS", toSend)
}

class DeetsCallback: MediaController.Callback()
{
    var activeController: MediaController? = null
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

        super.onSessionDestroyed()
    }
}

val metadataCallback = DeetsCallback()