package com.example.fitboymk2

import android.content.Context
import android.content.Intent
import java.util.UUID

data class BleWriteCommand(
    val serviceUUID: UUID,
    val characteristicUUID: UUID,
    val data: String
)

sealed class GattEvent {
    object MtuChanged : GattEvent()
    object ServicesDiscovered : GattEvent()
    object CharacteristicWritten : GattEvent()
    object DescriptorWritten : GattEvent()
    object PhyUpdated : GattEvent()
    data class Disconnected(val status: Int) : GattEvent()
}

fun Context.sendBleCommand(service: UUID, characteristic: UUID, data: String) {
    val intent = Intent(Constants.Actions.SEND_BLE_COMMAND).apply {
        putExtra("TOSEND", data)
        putExtra("serviceUUID", service.toString())
        putExtra("characteristicUUID", characteristic.toString())
    }
    sendBroadcast(intent)
}