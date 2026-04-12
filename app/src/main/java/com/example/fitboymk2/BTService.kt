package com.example.fitboymk2

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import kotlin.math.min

//var timeCharacteristic : BluetoothGattCharacteristic? = null

class BTService : Service()
{
    private var btGatt: BluetoothGatt? = null

    // Use a single SupervisorJob for the whole service life
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var setupJob: Job? = null
    private var txJob: Job? = null
    private var gattEventChannel = Channel<GattEvent>(Channel.UNLIMITED)
    private var commandChannel = Channel<BleWriteCommand>(Channel.BUFFERED)

    sealed class GattEvent
    {
        object MtuChanged : GattEvent()
        object ServicesDiscovered : GattEvent()
        object CharacteristicWritten : GattEvent()
        object DescriptorWritten : GattEvent()
        object PhyUpdated : GattEvent()
        data class Disconnected(val status: Int) : GattEvent()
    }

    private suspend inline fun <reified T : GattEvent> waitForEvent(timeout: Long = 5000)
    {
        withTimeout(timeout) {
            for (event in gattEventChannel)
            {
                if (event is T) return@withTimeout
                if (event is GattEvent.Disconnected) {
                    throw Exception("Disconnected")
                }
            }
        }
    }

    data class BleWriteCommand(
        val serviceUUID: UUID,
        val characteristicUUID: UUID,
        val data: String
    )
    private val receiver = object : BroadcastReceiver()
    {
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?)
        {
            if (intent?.action == "com.fitboymk2.SEND_BLE_COMMAND")
            {
                Log.i("onReceive", "intent received")
                val data = intent.getStringExtra("TOSEND") ?:""
                val serviceUUID = UUID.fromString(intent.getStringExtra("serviceUUID"))
                val characteristicUUID = UUID.fromString(intent.getStringExtra("characteristicUUID"))

                commandChannel.trySend(BleWriteCommand(serviceUUID, characteristicUUID, data))
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @SuppressLint("MissingPermission")
    private suspend fun executeWrite(command: BleWriteCommand)
    {
        val gatt = btGatt ?: return
        Log.i("executeWrite", "Gatt OK")
        val characteristic = gatt.getService(command.serviceUUID)?.getCharacteristic(command.characteristicUUID) ?: return

        val stringBytes = command.data.toByteArray(StandardCharsets.UTF_8)
        val charCount = stringBytes.size
        val swapped = ((charCount and 0xFF) shl 8) or ((charCount and 0xFF00) ushr 8)
        val buffer = ByteBuffer.allocate(2 + stringBytes.size)

        buffer.putShort(swapped.toShort())
        buffer.put(stringBytes)

        val writeChar = btGatt!!.getService(command.serviceUUID)?.getCharacteristic(command.characteristicUUID)
        //find the characteristic
        if ((writeChar != null) && (buffer != null))
        {
            for (i in 0 until buffer.array().size step 60) {
                val end = min(i + 60, buffer.array().size)
                val chunk = buffer.array().copyOfRange(i, end)

                Log.i("BTService, Tx Intent", "Sending packet at $i. Content: ${chunk.contentToString()}")
                btGatt?.writeCharacteristic(
                    writeChar,
                    chunk,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )

                waitForEvent<GattEvent.CharacteristicWritten>()
                Log.i("BLE", "Chunk at $i sent successfully")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun startCommandWorker() {
        txJob = serviceScope.launch {
            for (command in commandChannel) {
                Log.i("commandChannel", "New Command")
                val gatt = btGatt

                if (gatt == null) {
                    Log.e("BTService", "Device not connected. Ignoring command: ${command.data}")
                    continue
                }

                try {
                    executeWrite(command)
                } catch (e: Exception) {
                    Log.e("BTService", "Execution failed: ${e.message}")
                }
            }
        }
    }

    private val callback = object : BluetoothGattCallback()
    {
        val MUSICCONTROL_UUID : UUID = UUID.fromString("6ddb28be-a927-11ee-a506-0242ac120002")
        val FBDEL_UUID: UUID = UUID.fromString("c533a7ba-272e-11ee-be56-0242ac120002")

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int)
        {
            if (newState == BluetoothProfile.STATE_CONNECTED)
            {
                gattEventChannel = Channel<GattEvent>(Channel.UNLIMITED)
                commandChannel = Channel<BleWriteCommand>(Channel.BUFFERED)
                startSetup(gatt)
            }

            else if (newState == BluetoothProfile.STATE_DISCONNECTED)
            {
                Log.e("onConnectionStateChange", "Disconnected with status $status")
                setupJob?.cancel() //kill setup on connection fail.
                txJob?.cancel()
                gattEventChannel.trySend(GattEvent.Disconnected(status))
                btGatt = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int)
        {
            gattEventChannel.trySend(GattEvent.MtuChanged)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int)
        {
            gattEventChannel.trySend(GattEvent.ServicesDiscovered)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int)
        {
            gattEventChannel.trySend(GattEvent.CharacteristicWritten)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, desc: BluetoothGattDescriptor, status: Int)
        {
            gattEventChannel.trySend(GattEvent.DescriptorWritten)
        }

        override fun onPhyUpdate(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int)
        {
            gattEventChannel.trySend(GattEvent.PhyUpdated)
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        @SuppressLint("MissingPermission")
        private fun startSetup(gatt: BluetoothGatt)
        {
            btGatt = gatt
            if(btGatt == null) return;
            setupJob?.cancel()
            setupJob = serviceScope.launch{
                try
                {
                    waitForEvent<GattEvent.PhyUpdated>()

                    Log.i("setupJob", "Requesting MTU...")
                    btGatt!!.requestMtu(64)
                    waitForEvent<GattEvent.MtuChanged>()

                    Log.i("setupJob", "Discovering Services...")
                    btGatt!!.discoverServices()
                    waitForEvent<GattEvent.ServicesDiscovered>()

                    val timeService : UUID = UUID.fromString("1f55d926-12bb-11ee-be56-0242ac120007")
                    val timeCharacteristic = btGatt!!.getService(timeService)?.getCharacteristic(TIME_UUID)
                    val notificationServiceUUID : UUID = UUID.fromString("d2fa52f9-4c5d-4a05-a010-c26a1b99f5e6")
                    val notificationService = btGatt!!.getService(notificationServiceUUID)
                    val musicServiceUUID : UUID = UUID.fromString("019c9698-ccae-7bd0-9976-3017ee420aba")
                    val musicControlCharacteristic = btGatt!!.getService(musicServiceUUID)?.getCharacteristic(MUSICCONTROL_UUID)
                    //sleep to ensure population
                    Thread.sleep(100)
                    if (timeCharacteristic != null)
                    {
                        Log.i("startSetup", "Found time service, writing time")
                        var unixTime = (Calendar.getInstance().timeInMillis/1000)
                        val tz = TimeZone.getDefault() as TimeZone
                        unixTime += (tz.getOffset(Calendar.getInstance().timeInMillis)/1000)

                        val utString = unixTime.toString()
                        val x: ByteBuffer = ByteBuffer.allocate(Long.SIZE_BYTES)
                        x.putLong(unixTime)

                        btGatt!!.writeCharacteristic(timeCharacteristic,  x.array().reversedArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        waitForEvent<GattEvent.CharacteristicWritten>()
                        Log.i("startSetup", "Time set $utString " + unixTime + " " + tz.getOffset(unixTime)/1000)
                    }

                    //also set up music controls and notDels callback notify flags
                    if(musicControlCharacteristic != null)
                    {
                        btGatt?.setCharacteristicNotification(musicControlCharacteristic, true)
                        val CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        val descriptor: BluetoothGattDescriptor = musicControlCharacteristic.getDescriptor(CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID)

                        btGatt?.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        waitForEvent<GattEvent.DescriptorWritten>()
                        Log.i("startSetup", "Notify properties set for music control uuid")
                    }

                    val notDelCharacteristic = notificationService?.getCharacteristic(FBDEL_UUID)
                    if(notDelCharacteristic != null)
                    {
                        btGatt?.setCharacteristicNotification(notDelCharacteristic, true)
                        val CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        val descriptor: BluetoothGattDescriptor = notDelCharacteristic.getDescriptor(CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID)

                        btGatt?.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        waitForEvent<GattEvent.DescriptorWritten>()
                        Log.i("startSetup", "Notify properties set for notification deletion uuid")
                    }

                    Thread.sleep(100)
                    startCommandWorker()
                }

                catch (e: Exception)
                {
                    Log.e("BLE", "Setup aborted: ${e.message}")
                }
            }
        }

        var delStr = ByteArrayOutputStream()
        var conStr = ByteArrayOutputStream()
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            Log.i("BTGattCallback, onCharacteristicChange", "Value: ${value.decodeToString()}")
            Log.i("BTGattCallback, onCharacteristicChange", "UUID: ${characteristic.uuid}")

            if(characteristic.uuid == MUSICCONTROL_UUID)
            {
                Log.i("Music Control, BTGattCallback, onCharacteristicChange", "Music control, ${value.contentToString()}")
                conStr.write(value)

                if(value.last().toInt() == 0)
                {
                    val aM = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val v = String(conStr.toByteArray().dropLast(1).toByteArray())
                    conStr.reset()
                    Log.i("Music Control, BTGattCallback, onCharacteristicChange", "Final String: $v")
                    if(v.compareTo("1") == 0)
                    {
                        val eventTime = android.os.SystemClock.uptimeMillis()

                        val downEvent =
                            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
                        aM.dispatchMediaKeyEvent(downEvent)

                        val upEvent =
                            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)
                        aM.dispatchMediaKeyEvent(upEvent)
                    }

                    else if(v.compareTo("2") == 0)
                    {
                        val eventtime = android.os.SystemClock.uptimeMillis()

                        val downEvent =
                            KeyEvent(eventtime, eventtime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0)
                        aM.dispatchMediaKeyEvent(downEvent)

                        val upEvent =
                            KeyEvent(eventtime, eventtime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT, 0)
                        aM.dispatchMediaKeyEvent(upEvent)
                    }

                    else if(v.compareTo("3") == 0)
                    {
                        val eventtime = android.os.SystemClock.uptimeMillis()

                        val downEvent =
                            KeyEvent(eventtime, eventtime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0)
                        aM.dispatchMediaKeyEvent(downEvent)

                        val upEvent =
                            KeyEvent(eventtime, eventtime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0)
                        aM.dispatchMediaKeyEvent(upEvent)
                    }
                }
            }

            else if(characteristic.uuid == FBDEL_UUID)
            {
                Log.i("Notification Delete, BTGattCallback, onCharacteristicChange", value.contentToString())
                delStr.write(value)
                if(value.last().toInt() == 0)
                {
                    val idStr = String(delStr.toByteArray().dropLast(1).toByteArray())
                    Log.i("Notification Delete, BTGattCallback, onCharacteristicChange", "Final ID: $idStr")
                    Log.i("Notification Delete, BTGattCallback, onCharacteristicChange", "Creating intent.")

                    val intent = Intent("com.fitboymk2.DELETENOTIFICATION").apply {
                        putExtra("CODE", idStr)
                    }

                    this@BTService.sendBroadcast(intent)
                    delStr.reset()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate() {
        super.onCreate()
        val chan = NotificationChannel(
            "channelId",
            "channelName", NotificationManager.IMPORTANCE_NONE
        )
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        val tDN = Notification.Builder(this, "channelId")
            .setContentTitle("FBMk2")
            .setContentText("ConnectionHandler")
            .build()
        startForeground(1, tDN)
        try {
            registerReceiver(receiver, IntentFilter("com.fitboymk2.SEND_BLE_COMMAND"),
                RECEIVER_EXPORTED
            )
        }

        catch (_:Exception)
        {
            unregisterReceiver(receiver)
            registerReceiver(receiver, IntentFilter("com.fitboymk2.SEND_BLE_COMMAND"),
                RECEIVER_EXPORTED
            )
        }

        //startCommandWorker()
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    {
        //initialize connection start
        if(btGatt == null)
        {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val btAdapter = btManager.adapter
            val bd = btAdapter.getRemoteDevice("E4:8C:E2:F3:90:B8") as BluetoothDevice
            btGatt = bd.connectGatt(applicationContext, true, callback) as BluetoothGatt
        }

        return(START_STICKY)
    }
}