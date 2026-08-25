package com.example.fitboymk2

import java.util.UUID

object Constants {
    const val MAC_ADDRESS = "E4:8C:E2:F3:90:B8"

    object Actions {
        const val SEND_BLE_COMMAND = "com.fitboymk2.SEND_BLE_COMMAND"
        const val WATCH_CONNECTED = "com.fitboymk2.WATCH_CONNECTED"
        const val WATCH_DISCONNECTED = "com.fitboymk2.WATCH_DISCONNECTED"
        const val DELETE_NOTIFICATION = "com.fitboymk2.DELETENOTIFICATION"
        const val MUSIC_CONTROL = "com.fitboymk2.MUSIC_CONTROL"
    }

    object UUIDs {
        val TIME_SERVICE = UUID.fromString("1f55d926-12bb-11ee-be56-0242ac120007")
        val TIME_CHAR = UUID.fromString("93c37a10-1f37-11ee-be56-0242ac120002")

        val NOTIFICATION_SERVICE = UUID.fromString("d2fa52f9-4c5d-4a05-a010-c26a1b99f5e6")
        val NOTIFICATION_CHAR = UUID.fromString("05590c96-12bb-11ee-be56-0242ac120002")
        val NOTIFICATION_DEL_BUF_CHAR = UUID.fromString("19e04166-12bb-11ee-be56-0242ac120002")
        val FBDEL_CHAR = UUID.fromString("c533a7ba-272e-11ee-be56-0242ac120002")

        val MUSIC_SERVICE = UUID.fromString("019c9698-ccae-7bd0-9976-3017ee420aba")
        val MUSIC_CONTROL_CHAR = UUID.fromString("6ddb28be-a927-11ee-a506-0242ac120002")
        val MUSIC_DEETS_CHAR = UUID.fromString("5df4d2b0-a927-11ee-a506-0242ac120002")
        val MUSIC_QUEUE_CHAR = UUID.fromString("019d3745-7fec-7a03-8ab8-4b91c344b29b")

        val DESCRIPTOR_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}