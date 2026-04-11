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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import kotlin.math.min

//var timeCharacteristic : BluetoothGattCharacteristic? = null

class BTService : Service() {

    val sendMutex = Mutex()
    private var btGatt: BluetoothGatt? = null
    private var serviceScope = CoroutineScope(SupervisorJob())
    private val receiver = object : BroadcastReceiver()
    {
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.fitboymk2.SEND_BLE_COMMAND") {
                val data = intent.getStringExtra("TOSEND")
                val serviceUUID = intent.getStringExtra("serviceUUID")
                val characteristicUUID = intent.getStringExtra("characteristicUUID")

                if ((serviceUUID?.isNotEmpty() == true) && (characteristicUUID?.isNotEmpty() == true) && (btGatt != null))
                {
                    Log.i("BTService, Tx Intent", "Attempting servicescope.launch")
                    //serviceScope = CoroutineScope(SupervisorJob())
                    serviceScope.launch {
                        Log.i("BTService, Tx Intent", "servicescope.launch OK")
                        sendMutex.lock()
                        val stringBytes = data?.toByteArray(StandardCharsets.UTF_8)
                        if(stringBytes != null) {
                            val charCount = stringBytes.size
                            val swapped = ((charCount and 0xFF) shl 8) or ((charCount and 0xFF00) ushr 8)
                            val buffer = ByteBuffer.allocate(2 + stringBytes.size)

                            buffer.putShort(swapped.toShort())
                            buffer.put(stringBytes)

                            val writeChar = btGatt!!.getService(UUID.fromString(serviceUUID))?.getCharacteristic(UUID.fromString(characteristicUUID))
                            //find the characteristic
                            if ((writeChar != null) && (buffer != null))
                            {
                                for (i in 0 until buffer.array().size step 60) {
                                    val end = min(i + 60, buffer.array().size)
                                    val chunk = buffer.array().copyOfRange(i, end)
                                    callback.initMutex.lock()
                                    Log.i("BTService, Tx Intent", "Sending packet at $i. Content: ${chunk.contentToString()}")
                                    btGatt?.writeCharacteristic(
                                        writeChar,
                                        chunk,
                                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                    )
                                }
                            }
                        }

                        sendMutex.unlock()
                    }
                }
            }
        }
    }

    private val callback = object : BluetoothGattCallback()
    {
        var initMutex = Mutex()
        var phyMutex = Mutex()
        val MUSICCONTROL_UUID : UUID = UUID.fromString("6ddb28be-a927-11ee-a506-0242ac120002")
        val FBDEL_UUID: UUID = UUID.fromString("c533a7ba-272e-11ee-be56-0242ac120002")

        override fun onPhyUpdate(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyUpdate(gatt, txPhy, rxPhy, status)
            phyMutex.unlock()
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int)
        {
            super.onConnectionStateChange(gatt, status, newState)
            if(newState == BluetoothProfile.STATE_CONNECTED && gatt != null)
            {
                //start post connect setup.
                Log.i("BTGattCallback, onConnectionStateChange", "Watch connected")
                if(initMutex.isLocked)
                {
                    initMutex.unlock()
                }

                btGatt = gatt

                serviceScope = CoroutineScope(SupervisorJob())
                serviceScope.launch{
                    initMutex.lock()
                    phyMutex.lock()
                    while(phyMutex.isLocked){yield()}

                    gatt.requestMtu(64)
                    initMutex.lock()
                    gatt.discoverServices()
                    while(initMutex.isLocked){yield()}

                    Log.i("BTGattCallback, onConnectionStateChange", "Base setup complete. Doing service discovery...")

                    val timeService : UUID = UUID.fromString("1f55d926-12bb-11ee-be56-0242ac120007")
                    val timeCharacteristic = gatt.getService(timeService)?.getCharacteristic(TIME_UUID)
                    val notificationServiceUUID : UUID = UUID.fromString("d2fa52f9-4c5d-4a05-a010-c26a1b99f5e6")
                    val notificationService = gatt.getService(notificationServiceUUID)
                    val musicServiceUUID : UUID = UUID.fromString("019c9698-ccae-7bd0-9976-3017ee420aba")
                    val musicControlCharacteristic = gatt.getService(musicServiceUUID)?.getCharacteristic(MUSICCONTROL_UUID)

                    //notBufC = gattService?.getCharacteristic(NOTBUF_UUID)
                    //notDelBufC = gattService?.getCharacteristic(NOTDELBUF_UUID)
                    //fbDel = gattService?.getCharacteristic(FBDEL_UUID)
                    //deetsCharacteristic = gattService?.getCharacteristic(MUSICDEETS_UUID)

                    if (timeCharacteristic != null)
                    {
                        Log.i("BTGattCallback, onConnectionStateChange", "Found time service, writing time")
                        var unixTime = (Calendar.getInstance().timeInMillis/1000)
                        val tz = TimeZone.getDefault() as TimeZone
                        unixTime += (tz.getOffset(Calendar.getInstance().timeInMillis)/1000)

                        val utString = unixTime.toString()
                        initMutex.lock()
                        val x: ByteBuffer = ByteBuffer.allocate(Long.SIZE_BYTES)
                        x.putLong(unixTime)

                        gatt.writeCharacteristic(timeCharacteristic,  x.array().reversedArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        while(initMutex.isLocked){yield()}
                        Log.i("BTGattCallback, onConnectionStateChange", "Time set $utString " + unixTime + " " + tz.getOffset(unixTime)/1000)
                    }

                    //also set up music controls and notDels callback notify flags
                    if(musicControlCharacteristic != null)
                    {
                        btGatt?.setCharacteristicNotification(musicControlCharacteristic, true)
                        val CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        val descriptor: BluetoothGattDescriptor = musicControlCharacteristic.getDescriptor(CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID)

                        initMutex.lock()
                        btGatt?.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        while(initMutex.isLocked){yield()}
                        Log.i("BTGattCallback, onConnectionStateChange", "Notify properties set for music control uuid")
                    }

                    val notDelCharacteristic = notificationService?.getCharacteristic(FBDEL_UUID)
                    if(notDelCharacteristic != null)
                    {
                        btGatt?.setCharacteristicNotification(notDelCharacteristic, true)
                        val CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        val descriptor: BluetoothGattDescriptor = notDelCharacteristic.getDescriptor(CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID)

                        initMutex.lock()
                        btGatt?.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        while(initMutex.isLocked){yield()}
                        Log.i("BTGattCallback, onConnectionStateChange", "Notify properties set for notification deletion uuid")
                    }
                }
            }

            else
            {
                Log.i("BTGattCallback, onConnectionStateChange", "Watch disconnected")
                serviceScope.cancel()
                btGatt = null
                if(initMutex.isLocked)
                {
                    initMutex.unlock()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int)
        {
            super.onMtuChanged(gatt, mtu, status)
            Log.i("BTGattCallback, onMtuChanged", "MTU changed")
            initMutex.unlock()
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int)
        {
            super.onServicesDiscovered(gatt, status)
            Log.i("BTGattCallback, onServicesDiscovered", "Services discovered")
            initMutex.unlock()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            Log.i("BTGattCallback, onDescriptorWrite", "Descriptor written")
            initMutex.unlock()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            Log.i("BTGattCallback, onCharacteristicWrite", "CW Done")
            initMutex.unlock()
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