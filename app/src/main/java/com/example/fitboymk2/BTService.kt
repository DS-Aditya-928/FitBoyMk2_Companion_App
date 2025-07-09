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
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

//var timeCharacteristic : BluetoothGattCharacteristic? = null

class BTService : Service() {

    private var btGatt: BluetoothGatt? = null
    private val serviceScope = CoroutineScope(SupervisorJob())
    val receiver = object : BroadcastReceiver()
    {
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.fitboymk2.SEND_BLE_COMMAND") {
                val data = intent.getStringExtra("TOSEND")
                val uuid = intent.getStringExtra("uuid")
                Log.i("Intent", "Intent Recieved")
                Log.i("DB", ((uuid?.isNotEmpty() == true).toString()))
                if ((uuid?.isNotEmpty() == true) && (btGatt != null))
                {
                    serviceScope.launch {
                        Log.i("L", "Slaunch")

                        val writeChar = callback.gattService?.getCharacteristic(UUID.fromString(uuid))
                        //find the characteristic
                        if ((writeChar != null) && (data != null))
                        {
                            //acquire lock
                            callback.initMutex.lock()
                            Log.i("CW", "CW try")
                            btGatt?.writeCharacteristic(writeChar, data.toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        }
                    }
                }
            }
        }
    }

    private val callback = object : BluetoothGattCallback()
    {
        var initMutex = Mutex()
        var gattService : BluetoothGattService? = null

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int)
        {
            super.onConnectionStateChange(gatt, status, newState)
            if(newState == BluetoothProfile.STATE_CONNECTED && gatt != null)
            {
                //start post connect setup.
                Log.i("BTStatus", "Connected")
                serviceScope.launch{
                    initMutex.lock()
                    gatt.requestMtu(512)
                    initMutex.lock()
                    gatt.discoverServices()
                    initMutex.lock()//wait for service discovery to finish
                    gattService = gatt.getService(SERVICE_UUID)
                    val fbdC = gattService?.getCharacteristic(FBDEL_UUID)
                    initMutex.unlock()
                    if(fbdC != null)
                    {
                        gatt.setCharacteristicNotification(fbdC, true)
                        val descriptor: BluetoothGattDescriptor = fbdC.getDescriptor(CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID)
                        initMutex.lock()
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    }
                    while(initMutex.isLocked){};
                    Log.i("BTStatus", "Setup complete.")

                    val timeCharacteristic = gattService?.getCharacteristic(TIME_UUID)
                    //notBufC = gattService?.getCharacteristic(NOTBUF_UUID)
                    //notDelBufC = gattService?.getCharacteristic(NOTDELBUF_UUID)
                    //fbDel = gattService?.getCharacteristic(FBDEL_UUID)
                    //deetsCharacteristic = gattService?.getCharacteristic(MUSICDEETS_UUID)

                    if (timeCharacteristic != null)
                    {
                        var unixTime = (Calendar.getInstance().timeInMillis/1000)
                        val tz = TimeZone.getDefault() as TimeZone
                        unixTime += (tz.getOffset(Calendar.getInstance().timeInMillis)/1000)

                        val utString = unixTime.toString()
                        initMutex.lock()
                        gatt.writeCharacteristic(timeCharacteristic!!,  utString.toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        while(initMutex.isLocked){}
                        Log.i("Time", "Set $utString " + unixTime + " " + tz.getOffset(unixTime)/1000)
                    }

                    btGatt = gatt
                }
            }

            else
            {
                Log.i("BTStatus", "Disconnected")
                btGatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int)
        {
            super.onMtuChanged(gatt, mtu, status)
            Log.i("BTINIT", "MTU CHANGED")
            initMutex.unlock()
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int)
        {
            super.onServicesDiscovered(gatt, status)
            Log.i("BTINIT", "Services discovered")
            initMutex.unlock()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            Log.i("BITINIT", "Descriptor Written")
            initMutex.unlock()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            Log.i("CW ", "CW Done")
            initMutex.unlock()
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
        registerReceiver(receiver, IntentFilter("com.fitboymk2.SEND_BLE_COMMAND"),
            RECEIVER_EXPORTED
        )
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    {
        //initialize connection start
        if(btGatt == null)
        {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val btAdapter = btManager.adapter
            val bd = btAdapter.getRemoteDevice("EC:62:60:32:C6:4E") as BluetoothDevice
            btGatt = bd.connectGatt(applicationContext, true, callback) as BluetoothGatt
        }

        return(START_STICKY)
    }
}