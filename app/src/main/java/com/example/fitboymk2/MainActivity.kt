package com.example.fitboymk2

import android.content.Intent
import android.os.Bundle
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.fitboymk2.databinding.ActivityMainBinding
import java.util.UUID

val SERVICE_UUID: UUID = UUID.fromString("1f55d926-12bb-11ee-be56-0242ac120002")
val NOTBUF_UUID: UUID = UUID.fromString("05590c96-12bb-11ee-be56-0242ac120002")
val NOTDELBUF_UUID: UUID = UUID.fromString("19e04166-12bb-11ee-be56-0242ac120002")
val TIME_UUID: UUID = UUID.fromString("93c37a10-1f37-11ee-be56-0242ac120002")
val FBDEL_UUID: UUID = UUID.fromString("c533a7ba-272e-11ee-be56-0242ac120002")


val CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView

        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_home, R.id.navigation_dashboard, R.id.navigation_notifications
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        if (!NotificationManagerCompat.getEnabledListenerPackages(this)
                .contains(packageName)
        )
        {        //ask for permission
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            startActivity(intent)
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf("android.permission.ACCESS_COARSE_LOCATION", "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"),
            2
        )
    }
}