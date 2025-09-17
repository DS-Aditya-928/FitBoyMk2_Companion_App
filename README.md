# FitBoyMk2 Companion App

This Android application serves as a companion app for the FitBoyMk2 smartwatch. Its primary functions are to:

1.  **Relay notifications:** Capture notifications from the connected Android phone and send them to the smartwatch for display.
2.  **Synchronize media controls:** Share media playback information (song title, artist, album, duration, playback state) with the smartwatch and allow the watch to control media playback on the phone.
3.  **Facilitate Bluetooth Low Energy (BLE) communication:** Manage the BLE connection and data transfer between the phone and the smartwatch.

## Key Features

*   **Notification Mirroring:**
    *   Listens for new notifications on the phone.
    *   Extracts relevant information like app name, title, subtext, and message content.
    *   Formats and sends notification data to the smartwatch via BLE.
    *   Handles both standard notifications and messaging-style notifications.
    *   Sends a command to the smartwatch when a notification is dismissed on the phone.
*   **Media Control Synchronization:**
    *   Detects active media sessions on the phone.
    *   Retrieves metadata (track name, artist, album, duration) and playback state (playing/paused, current position).
    *   Sends this information to the smartwatch.
    *   Listens for media key events (e.g., play, pause, next, previous) originating from the smartwatch and applies them to the active media session on the phone.
    *   Sends a "KILL" command to the smartwatch to disable media controls when the media session is destroyed.

## Core Components

*   **`NotificationListener.kt`**: This is the heart of the companion app.
    *   Extends `NotificationListenerService` to intercept notifications.
    *   Manages `MediaSessionManager` to interact with media sessions.
    *   Implements callbacks for notification posting/removal and media metadata/playback state changes.
    *   Uses `BroadcastReceiver` to handle internal actions like deleting a specific notification from the smartwatch.
    *   Sends data to the `BTService` via broadcasts, which then handles the BLE transmission.

## How it Works

1.  **Permissions:** The app requires notification access permission to read notifications. It also needs Bluetooth permissions for BLE communication.
2.  **Service Initialization:** When the `NotificationListener` is connected, it starts the `BTService` (foreground service) and initializes the `MediaSessionManager`.
3.  **Notification Handling:**
    *   `onNotificationPosted()`: When a new notification arrives, the service extracts its content, formats it into a specific string protocol, and broadcasts an intent (`com.fitboymk2.SEND_BLE_COMMAND`) containing this data and the target UUID for the notification characteristic on the smartwatch.
    *   `onNotificationRemoved()`: When a notification is dismissed, its key is broadcasted with the target UUID for the notification deletion characteristic.
4.  **Media Control Handling:**
    *   The app registers a `MediaSessionManager.OnMediaKeyEventSessionChangedListener` and a `MediaController.Callback`.
    *   When the active media session changes or its metadata/playback state is updated, the `metadataCallback` formats the media details into a string protocol.
    *   This string is then broadcast via an intent (`com.fitboymk2.SEND_BLE_COMMAND`) with the target UUID for the media details characteristic.
    *   If a media session is destroyed, a "KILL" message is sent to the watch.


## Setup and Usage

1.  **Installation:** Install the app on an Android phone.
2.  **Permissions:**
    *   Grant **Notification Access** to the app through the phone's settings. This is crucial for the app to read notifications.
    *   Ensure **Bluetooth** is enabled and necessary Bluetooth permissions (like `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` if applicable for discovery, and potentially location for BLE scanning on older Android versions) are granted.
3.  **Pairing:** The FitBoyMk2 smartwatch is currently programmed to scan for a specific macID.
4.  **Running the App:** The `NotificationListenerService` should automatically start once permissions are granted. The `BTService` is started by the listener.

## String Protocols

The communication with the smartwatch relies on custom string protocols:

*   **Notifications:**
    *   Format: `<0>AppName<1>Title<2>SubText<3>[T/D]<4>Content<5>NotificationKey`
    *   `[T/D]`: 'T' for messaging style (multiple lines), 'D' for standard text.
*   **Media Details:**
    *   Format: `<AD>TrackName<1>Artist<2>Album<3>TrackLengthSeconds<4>CurrentPositionSeconds<5>PlaybackState`
    *   `PlaybackState`: `0` for playing, `-1` for paused (derived from `(pbS.state == PlaybackState.STATE_PAUSED).compareTo(true)`).
    *   "KILL" is sent if `trackName` is empty or the session is destroyed.
*   **Notification Deletion:**
    *   The `NotificationKey` of the dismissed notification.