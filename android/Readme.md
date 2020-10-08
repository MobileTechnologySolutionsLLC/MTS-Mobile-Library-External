# Greektown Connect Library and Example App

## Hello world with Example

### Bluetooth
1. Build and run the example project on an actual Android device.
2. Confirm an MTS BLE beacon is powered and nearby.
3. Launch the app, confirm Bluetooth is enabled, tap Start Scanning.
4. Hold the Android device near the MTS Beacon, it should auto-connect and play a sound.
5. After connecting, scroll down the tableview to see values which confirm read of Terminal Kind, Sticky Connect state, and read/write of Card Data.

### USB
1. Build and run the example project on an actual Android device.
2. Connect your Android device to an MTS card reader via USB.
3. Accept the USB permissions prompt.
4. The example app will automatically write the card data assigned in the source.
5. The card reader should provide a visual indication that it received card data via USB (green bezel flash).

## Adding to your Project

1. Copy the MTS module to your Android Studio project (example/mts).
2. Update your app's build.gradle and settings.gradle:
3. build.gradle > dependencies > compile project(':mts')
4. settings.gradle > include > ':mts'
5. Refer to the example AndroidManifest.xml for configuration.
6. Refer to the service lifecycle calls in ExampleActivity.
7. Refer to the MTSServiceUpdateIntentFilter for a list of actions.
8. Use the assignCardDataForWriteOnNextUSBConnect(String cardDataString) to set card data.

## Licensing
The MTS package depends on GPL v3.0 code from SweetBlue:
https://github.com/iDevicesInc/SweetBlue/blob/master/LICENSE
Commercial licensing is available at https://solutions.idevicesinc.com

The example project uses MIT license Perfect Tune Library for event tones:
https://github.com/karlotoy/perfectTune/blob/master/LICENSE.txt
