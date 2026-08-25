package com.example.fitboymk2

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.*
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.math.min
import kotlin.reflect.typeOf

class BTService : Service() {
    private var btGatt: BluetoothGatt? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var setupJob: Job? = null
    private var txJob: Job? = null

    private var gattEventChannel = Channel<GattEvent>(Channel.UNLIMITED)
    private var commandChannel = Channel<BleWriteCommand>(Channel.BUFFERED)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.Actions.SEND_BLE_COMMAND) {
                val data = intent.getStringExtra("TOSEND") ?: ""
                val serviceUUID = UUID.fromString(intent.getStringExtra("serviceUUID"))
                val charUUID = UUID.fromString(intent.getStringExtra("characteristicUUID"))
                commandChannel.trySend(BleWriteCommand(serviceUUID, charUUID, data))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun executeWrite(command: BleWriteCommand) {
        val gatt = btGatt ?: return
        val writeChar = gatt.getService(command.serviceUUID)?.getCharacteristic(command.characteristicUUID) ?: return

        val stringBytes = command.data.toByteArray(StandardCharsets.UTF_8)
        val swapped = ((stringBytes.size and 0xFF) shl 8) or ((stringBytes.size and 0xFF00) ushr 8)
        val buffer = ByteBuffer.allocate(2 + stringBytes.size).apply {
            putShort(swapped.toShort())
            put(stringBytes)
        }.array()

        for (i in buffer.indices step 60) {
            val chunk = buffer.copyOfRange(i, min(i + 60, buffer.size))
            gatt.writeCharacteristic(writeChar, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            waitForEvent<GattEvent.CharacteristicWritten>()
        }
    }

    private fun startCommandWorker() {
        txJob = serviceScope.launch {
            for (command in commandChannel) {
                if (btGatt != null) {
                    try { executeWrite(command) } catch (e: Exception) { Log.e("BLE", "Tx failed") }
                }
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        private var delStr = ByteArrayOutputStream()
        private var conStr = ByteArrayOutputStream()

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gattEventChannel = Channel(Channel.UNLIMITED)
                commandChannel = Channel(Channel.BUFFERED)
                startSetup(gatt)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                setupJob?.cancel()
                txJob?.cancel()
                gattEventChannel.trySend(GattEvent.Disconnected(status))
                btGatt = null
                sendBroadcast(Intent(Constants.Actions.WATCH_DISCONNECTED))
            }
        }

        // Routing standard overrides to Gatt Events
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) { gattEventChannel.trySend(GattEvent.MtuChanged) }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) { gattEventChannel.trySend(GattEvent.ServicesDiscovered) }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) { gattEventChannel.trySend(GattEvent.CharacteristicWritten) }
        override fun onDescriptorWrite(gatt: BluetoothGatt, desc: BluetoothGattDescriptor, status: Int) { gattEventChannel.trySend(GattEvent.DescriptorWritten) }
        override fun onPhyUpdate(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) { gattEventChannel.trySend(GattEvent.PhyUpdated) }

        @SuppressLint("MissingPermission")
        private fun startSetup(gatt: BluetoothGatt) {
            btGatt = gatt
            setupJob?.cancel()
            setupJob = serviceScope.launch {
                try {
                    waitForEvent<GattEvent.PhyUpdated>()
                    gatt.requestMtu(64)
                    waitForEvent<GattEvent.MtuChanged>()
                    gatt.discoverServices()
                    waitForEvent<GattEvent.ServicesDiscovered>()

                    // Set Time
                    val timeChar = gatt.getService(Constants.UUIDs.TIME_SERVICE)?.getCharacteristic(Constants.UUIDs.TIME_CHAR)
                    if (timeChar != null) {
                        val unixTime = (System.currentTimeMillis() + TimeZone.getDefault().getOffset(System.currentTimeMillis())) / 1000
                        val buf = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(unixTime).array().reversedArray()
                        gatt.writeCharacteristic(timeChar, buf, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        waitForEvent<GattEvent.CharacteristicWritten>()
                    }

                    // Enable Notifications
                    enableCharNotification(gatt, Constants.UUIDs.MUSIC_SERVICE, Constants.UUIDs.MUSIC_CONTROL_CHAR)
                    enableCharNotification(gatt, Constants.UUIDs.NOTIFICATION_SERVICE, Constants.UUIDs.FBDEL_CHAR)

                    startCommandWorker()
                    sendBroadcast(Intent(Constants.Actions.WATCH_CONNECTED))
                } catch (e: Exception) { Log.e("BLE", "Setup aborted") }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray) {
            if (value.isEmpty()) return

            if (char.uuid == Constants.UUIDs.MUSIC_CONTROL_CHAR) {
                conStr.write(value)
                if (value.last() == 0.toByte()) {
                    val decoded = conStr.toByteArray()
                    conStr.reset()
                    if (decoded.isNotEmpty()) handleMediaKey(decoded)
                }
            } else if (char.uuid == Constants.UUIDs.FBDEL_CHAR) {
                delStr.write(value)
                if (value.last() == 0.toByte()) {
                    val idStr = String(delStr.toByteArray().dropLast(1).toByteArray())
                    delStr.reset()
                    sendBroadcast(Intent(Constants.Actions.DELETE_NOTIFICATION).apply { putExtra("CODE", idStr) })
                }
            }
        }
    }

    private fun handleMediaKey(byteArray: ByteArray) {
        val aM = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val keyCode = when (byteArray[0].toInt().toChar()) {
            '1' -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            '2' -> KeyEvent.KEYCODE_MEDIA_NEXT
            '3' -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            '4' -> "Q_SEEK"
            else -> return
        }

        if (keyCode == "Q_SEEK") {
            Log.i("handleMediaQueue", "Index: ${byteArray[1].toInt()}")
            sendBroadcast(Intent(Constants.Actions.MUSIC_CONTROL).apply { putExtra("Index", byteArray[1].toInt()) })
            return
        }

        if (keyCode is Int)
        {
            val eventTime = android.os.SystemClock.uptimeMillis()
            aM.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
            aM.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableCharNotification(gatt: BluetoothGatt, srvUUID: UUID, charUUID: UUID) {
        val char = gatt.getService(srvUUID)?.getCharacteristic(charUUID) ?: return
        gatt.setCharacteristicNotification(char, true)
        val desc = char.getDescriptor(Constants.UUIDs.DESCRIPTOR_CONFIG)
        //desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(char.getDescriptor(Constants.UUIDs.DESCRIPTOR_CONFIG), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        waitForEvent<GattEvent.DescriptorWritten>()
    }

    private suspend inline fun <reified T : GattEvent> waitForEvent(timeout: Long = 5000) {
        withTimeout(timeout) {
            for (event in gattEventChannel) {
                if (event is T) return@withTimeout
                if (event is GattEvent.Disconnected) throw Exception("Disconnected")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val chan = NotificationChannel("channelId", "channelName", NotificationManager.IMPORTANCE_NONE)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
        startForeground(1, Notification.Builder(this, "channelId").setContentTitle("FBMk2").setContentText("Service Active").build())

        ContextCompat.registerReceiver(this, receiver, IntentFilter(Constants.Actions.SEND_BLE_COMMAND), ContextCompat.RECEIVER_EXPORTED)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (btGatt == null) {
            val bd = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.getRemoteDevice(Constants.MAC_ADDRESS)
            btGatt = bd.connectGatt(applicationContext, true, callback)
        }
        return START_STICKY
    }
}