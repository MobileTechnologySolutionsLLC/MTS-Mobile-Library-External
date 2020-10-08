# MTS Module and Example

Last confirmed to build clean with Xcode 9.2.

## Hello world with Example
1. Build and run the example Xcode project on an actual iOS device.
2. Confirm an MTS BLE beacon is powered and nearby.
3. Launch the app, confirm Bluetooth is enabled, tap Start Scanning.
4. Hold the iOS device near the MTS Beacon, it should auto-connect and play a sound.
5. After connecting, scroll down the tableview to see values which confirm read of Terminal ID read/write of Player ID.

## Adding to your Project
1. Copy the contents of the example/mts folder to your Xcode project.
2. Add an Info.plist entry for Privacy - Bluetooth Peripheral Usage Description
3. Add an Info.plist entry for Privacy - Bluetooth Always Usage Description
4. GTConnectManager loggingEnabled = true for verbose logging.
