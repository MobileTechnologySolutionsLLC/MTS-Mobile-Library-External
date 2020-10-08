package com.mts.mts;

// Copyright © 2020 Mobile Technology Solutions, Inc. All rights reserved.


import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import com.idevicesinc.sweetblue.BleDevice;
import com.idevicesinc.sweetblue.BleDeviceState;
import com.idevicesinc.sweetblue.BleManager;
import com.idevicesinc.sweetblue.BleManagerConfig;
import com.idevicesinc.sweetblue.utils.BluetoothEnabler;
import com.idevicesinc.sweetblue.utils.Interval;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ListIterator;
import java.util.UUID;

import static com.mts.mts.MTSService.BluetoothConnectionState.attemptingToReconnect;
import static com.mts.mts.MTSService.BluetoothConnectionState.inactive;
import static com.mts.mts.MTSService.BluetoothConnectionState.notReady;
import static com.mts.mts.MTSService.BluetoothConnectionState.scanning;
public class MTSService extends Service {

    // Intent Types
    public final static String AccessoryConnectionStateChanged =
            "com.mts.AccessoryConnectionStateChanged";
    public final static String BluetoothConnectionStateChanged =
            "com.mts.BluetoothConnectionStateChanged";
    public final static String DidReceiveTerminalKind =
            "com.mts.DidReceiveTerminalKind";
    public final static String DidReceiveCardData =
            "com.mts.DidReceiveCardData";
    public final static String DidWriteCardDataToBluetooth =
            "com.mts.DidWriteCardDataToBluetooth ";
    public final static String DidWriteCardDataToAccessory =
            "com.mts.DidWriteCardDataToAccessory";
    public final static String DidReceiveStickyConnectionState =
            "com.mts.DidReceiveStickyConnectionState";
    public final static String ReconnectAttemptTimedOut =
            "com.mts.ReconnectAttemptTimedOut";
    public final static String UpdateOnConnectedRSSIReceipt =
            "com.mts.UpdateOnConnectedRSSIReceipt";

    public enum AccessoryConnectionState {
        notReady,
        connected,
    }

    public enum BluetoothConnectionState {
        notReady,
        inactive,
        scanning,
        connected,
        attemptingToReconnect,
    }

    public MTSService() {
        super();
    }
    public AccessoryConnectionState accessoryConnectionState = AccessoryConnectionState.notReady;
    public BluetoothConnectionState bluetoothConnectionState = notReady;
    public MTSBeacon connectedMTSBeacon;
    public ArrayList<MTSBeacon> detectedBeacons = new ArrayList<MTSBeacon>();

    private final static String TAG = "MTSService";

    public int cardDataCharacterCountMax = 195; // 195 + automatic null termination, so 196 total accepted by the peripheral.
    private int kRSSIUnavailableValue = 127;
    private BleManager sweetBlue_BleManager;
    private UUID mtsServiceUUID = UUID.fromString("C1FB6CDA-3F15-4BC0-8A46-8E9C341065F8");
    private UUID cardDataCharacteristicUUID = UUID.fromString("60D11359-FEB2-411D-A430-CA6167052BD6");
    private UUID terminalKindCharacteristicUUID = UUID.fromString("D308DFDE-9F06-4A73-A2C7-EB952E40A184");
    private UUID stickyConnectCharacteristicUUID = UUID.fromString("4B6A91D8-EA3E-42A4-B39B-B300F5F64C86");
    private UUID userDisconnectedCharacteristicUUID = UUID.fromString("4E3A829D-4830-47A0-995F-EE923710A469");

    private byte[] playerIdDataToWriteOnUSBConnect;

    private static final String ACTION_USB_PERMISSION = "com.AccessoryInterface.USB_PERMISSION";
    private boolean permissionRequestPending = false;
    PendingIntent permissionIntent;
    UsbManager usbManager;
    UsbAccessory usbAccessory;
    private ParcelFileDescriptor usbFileDescriptor;
    private FileInputStream inputStream;
    private FileOutputStream outputStream;


    // Properties persisted to SharedPreferences

    private static String kSharedPreferenceKey = "com.mts.service";
    private static String kScanTimeoutIntervalKey = "kScanTimeoutIntervalKey";
    private static int    kScanTimeoutIntervalDefault = 0;
    private static String kAutoDisconnectIntervalKey = "kAutoDisconnectIntervalKey";
    private static int    kAutoDisconnectIntervalDefault = 1;
    private static String kAutoConnectRSSIThresholdPreferenceKey = "kAutoConnectRSSIThresholdPreferenceKey";
    private static int kAutoConnectDefaultRSSIThreshold = -45;
    private static int kAutoDisconnectDefaultRSSIThreshold = -80;
    private static String kAutoDisconnectRSSIThresholdPreferenceKey = "kAutoDisconnectRSSIThresholdPreferenceKey";

    public int scanTimeoutInterval() {
        SharedPreferences sharedPreferences = this.getApplicationContext().getSharedPreferences(
                kSharedPreferenceKey, Context.MODE_PRIVATE);
        int interval = sharedPreferences.getInt(kScanTimeoutIntervalKey, kScanTimeoutIntervalDefault);
        return interval;
    }

    public void setScanTimeoutInterval(int seconds) {
        SharedPreferences sharedPreferences =
                this.getApplicationContext().getSharedPreferences(kSharedPreferenceKey, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(kScanTimeoutIntervalKey, seconds);
        editor.commit();
    }

    public int autoConnectRSSIThreshold() {
        SharedPreferences sharedPreferences = this.getApplicationContext().getSharedPreferences(
                kSharedPreferenceKey, Context.MODE_PRIVATE);
        int threshold = sharedPreferences.getInt(kAutoConnectRSSIThresholdPreferenceKey, kAutoConnectDefaultRSSIThreshold);
        return threshold;
    }

    public void setAutoConnectRSSIThreshold(int number) {
        SharedPreferences sharedPreferences =
                this.getApplicationContext().getSharedPreferences(kSharedPreferenceKey, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(kAutoConnectRSSIThresholdPreferenceKey, number);
        editor.commit();
    }

    private boolean autoConnectThresholdEnabled() {
        return autoConnectRSSIThreshold() != 0;
    }

    public int autoDisconnectRSSIThreshold() {
        SharedPreferences sharedPreferences = this.getApplicationContext().getSharedPreferences(
                kSharedPreferenceKey, Context.MODE_PRIVATE);
        int threshold = sharedPreferences.getInt(kAutoDisconnectRSSIThresholdPreferenceKey, kAutoDisconnectDefaultRSSIThreshold);
        return threshold;
    }

    public void setAutoDisconnectRSSIThreshold(int number) {
        SharedPreferences sharedPreferences =
                this.getApplicationContext().getSharedPreferences(kSharedPreferenceKey, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(kAutoDisconnectRSSIThresholdPreferenceKey, number);
        editor.commit();
    }

    public int autoDisconnectInterval() {
        SharedPreferences sharedPreferences = this.getApplicationContext().getSharedPreferences(
                kSharedPreferenceKey, Context.MODE_PRIVATE);
        int interval = sharedPreferences.getInt(kAutoDisconnectIntervalKey, kAutoDisconnectIntervalDefault);
        return interval;
    }

    public void setAutoDisconnectInterval(int seconds) {
        SharedPreferences sharedPreferences =
                this.getApplicationContext().getSharedPreferences(kSharedPreferenceKey, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(kAutoDisconnectIntervalKey, seconds);
        editor.commit();
    }

    private boolean autoDisconnectThresholdEnabled() {
        return autoDisconnectRSSIThreshold() != 0;
    }


    // Service Lifecycle

    public class LocalBinder extends Binder {
        public MTSService getService() {
            return MTSService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanupUSB();
        return super.onUnbind(intent);
    }

    private final IBinder binder = new LocalBinder();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        changeBluetoothConnectionState(inactive);
        return START_NOT_STICKY;
    }

    public boolean initialize() {
        sweetBlue_BleManager = BleManager.get(this);
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (false == initialize()) {
            Log.v(TAG, "Initialize failed onCreate.");
            return;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopScanning();
    }


    // Bluetooth

    public void bluetoothEnabler(final Context context) {
        BluetoothEnabler.start(context, new BluetoothEnabler.DefaultBluetoothEnablerFilter() {
            @Override public BluetoothEnabler.BluetoothEnablerFilter.Please onEvent(BluetoothEnablerEvent e) {
                if( e.isDone() ) {
                    changeBluetoothConnectionState(scanning);
                }
                return super.onEvent(e);
            }
        });
    }

    public void startScanning() {
        changeBluetoothConnectionState(BluetoothConnectionState.scanning);
    }

    public void stopScanning() {
        changeBluetoothConnectionState(BluetoothConnectionState.inactive);
    }

    public void disconnect() {
        changeBluetoothConnectionState(BluetoothConnectionState.scanning);
    }

    public void requestTerminalKind() {
        if (null == connectedMTSBeacon) {
            return;
        }
        connectedMTSBeacon.device.read(terminalKindCharacteristicUUID, new BleDevice.ReadWriteListener()
        {
            @Override public void onEvent(ReadWriteEvent event)
            {
                String terminalKind = "- - -";
                final Intent intent = new Intent(DidReceiveTerminalKind);
                if(event.wasSuccess()) {
                    byte[] data = event.data();
                    terminalKind = new String(data, Charset.forName("UTF-8"));
                    Log.v(TAG, "terminalKind: " + terminalKind);
                } else {
                    Log.v(TAG, "requestTerminalKind failed .");
                }
                intent.putExtra("terminalKind", terminalKind);
                sendBroadcast(intent);
            }
        });
    }

    public void requestStickyConnectState() {
        if (null == connectedMTSBeacon) {
            return;
        }
        connectedMTSBeacon.device.read(stickyConnectCharacteristicUUID, new BleDevice.ReadWriteListener()
        {
            @Override public void onEvent(ReadWriteEvent event)
            {
                boolean wantsStickyConnection = false;
                final Intent intent = new Intent(DidReceiveStickyConnectionState);
                if(event.wasSuccess()) {
                    byte[] data = event.data();
                    wantsStickyConnection = (data[0] == (byte)0x01);
                    if (null != connectedMTSBeacon) {
                        connectedMTSBeacon.wantsStickyConnection = wantsStickyConnection;
                    }
                } else {
                    Log.v(TAG, "requestStickyConnectState failed .");
                }
                intent.putExtra("stickyConnectionState", wantsStickyConnection);
                sendBroadcast(intent);
            }
        });
    }

    public void requestCardData() {
        if (null == connectedMTSBeacon) {
            return;
        }
        connectedMTSBeacon.device.read(cardDataCharacteristicUUID, new BleDevice.ReadWriteListener()
        {
            @Override public void onEvent(ReadWriteEvent event)
            {
                final Intent intent = new Intent(DidReceiveCardData);
                if(event.wasSuccess()) {
                    byte[] data = event.data();
                    String cardData = new String(data, Charset.forName("UTF-8"));
                    intent.putExtra("cardData",cardData);
                } else {
                    String empty = null;
                    intent.putExtra("cardData", empty);
                }
                sendBroadcast(intent);
            }
        });
    }

    public Boolean writeCardDataToBluetooth(String cardDataString) {
        if (null == connectedMTSBeacon) {
            return false;
        }

        byte[] data = validatedCardData(cardDataString, true);
        if (null == data) {
            return false;
        }

        connectedMTSBeacon.device.write(cardDataCharacteristicUUID, data, new BleDevice.ReadWriteListener()
        {
            @Override public void onEvent(ReadWriteEvent event)
            {
            final Intent intent = new Intent(DidWriteCardDataToBluetooth);
            intent.putExtra("DidWriteCardDataToBluetooth", event.wasSuccess());
            sendBroadcast(intent);
            }
        });

        return true;
    }

    // Usage for the needsPadding parameter:
    // BLE expects data count of 20, the USB accessory expects unpadded data, e.g.
    // with padding: 0x253332313132333435363738393F000000000000
    //   no padding: 0x253332313132333435363738393F
    // https://github.com/MobileTechnologySolutionsLLC/dpc-mobile/issues/5
    private byte[] validatedCardData(String cardDataString, Boolean needsPadding) {

        String truncatedCardDataString = cardDataString.substring(0, Math.min(cardDataString.length(), cardDataCharacterCountMax));

        byte[] cardDataBytes = truncatedCardDataString.getBytes(Charset.forName("UTF-8"));

        //TODO: clarify whether:
        // a) there is a revised padding length
        // b) padding is no longer required.
        if (needsPadding) {
//            int requiredPlayerIdByteLength = 20;
//            byte[] paddedBytes = Arrays.copyOf(cardDataBytes, requiredPlayerIdByteLength);
//            cardDataBytes = paddedBytes;
        }
        byte[] nullBytes = new byte[0];
        byte[] terminatedBytes = new byte[cardDataBytes.length + nullBytes.length];
        System.arraycopy(cardDataBytes, 0, terminatedBytes, 0, cardDataBytes.length);
        System.arraycopy(nullBytes, 0, terminatedBytes, cardDataBytes.length, nullBytes.length);

        return terminatedBytes;
    }

    private BleManagerConfig.ScanFilter sweetBlue_ScanFilter = new BleManagerConfig.ScanFilter() {
        @Override
        public BleManagerConfig.ScanFilter.Please onEvent(ScanEvent e) {
            List<UUID> advServices = e.advertisedServices();
            return BleManagerConfig.ScanFilter.Please.acknowledgeIf(
                    advServices.contains(mtsServiceUUID)
            );
        }
    };

    private BleManager.DiscoveryListener sweetBlue_DiscoveryListener = new BleManager.DiscoveryListener() {
        @Override public void onEvent(DiscoveryEvent event) {
            if( event.was(LifeCycle.DISCOVERED) || event.was(LifeCycle.REDISCOVERED) ) {

                // Log.v(TAG, "BleManager.DiscoveryListener DISCOVERED || REDISCOVERED called when bluetoothConnectionState: " + bluetoothConnectionState + " rssi: " + event.rssi());

                if (scanning != bluetoothConnectionState) {
                    return;
                }

                if (kRSSIUnavailableValue == event.rssi()) {
                    // Discard discovery events when the RSSI value is not available: the focus of this app is
                    // using duplicate discoveries to monitor RSSI changes, so a discovery without RSSI is not useful.
                    return;
                }

                MTSBeacon beacon = new MTSBeacon(event, MTSService.this);

                addOrUpdateBeacon(beacon, event);

                evaluateVsAutoConnectThreshold();
            }
        }
    };

    private void startRSSIRefreshWhileConnectedTimer() {
        if (null == connectedMTSBeacon) {
            Log.v(TAG, "RSSI polling attempt failed due to null beacon.");
            return;
        }
        connectedMTSBeacon.device.startRssiPoll(Interval.ONE_SEC, readRSSIListener);
    }

    private void stopRSSIRefreshWhileConnectedTimer() {
        if (null == connectedMTSBeacon) {
            return;
        }
        connectedMTSBeacon.device.stopRssiPoll();
    }

    BleDevice.ReadWriteListener readRSSIListener = new BleDevice.ReadWriteListener() {
        @Override public void onEvent(ReadWriteEvent event) {
            if(event.wasSuccess()) {
                if (null == connectedMTSBeacon) {
                    return;
                }
                int rssi = event.rssi();
                // Log.v(TAG, "Received RSSI value while connected: " + rssi);
                evaluateVsAutoDisconnectThreshold(rssi);

                // Broadcast the RSSI update
                String connectedRSSIValue = "- - -";
                final Intent intent = new Intent(UpdateOnConnectedRSSIReceipt);
                connectedRSSIValue = String.valueOf(rssi);
                Log.v(TAG, connectedRSSIValue);
                intent.putExtra("connectedRSSIValue", connectedRSSIValue);
                sendBroadcast(intent);
            }
            else {
                Log.e("RSSI Read Attempt", event.status().toString()); // Logs the reason why it failed.
            }
        }
    };

    public MTSBeacon selectedBeacon() {
        for (MTSBeacon beacon : detectedBeacons) {
            if (beacon.isSelected) {
                return beacon;
            }
        }
        return null;
    }

    public MTSBeacon highestRSSIBeacon() {
        MTSBeacon highestRSSIBeacon = null;
        for (MTSBeacon beacon : detectedBeacons) {
            if (null == highestRSSIBeacon) {
                highestRSSIBeacon = beacon;
            }
            else if (beacon.filteredRSSI > highestRSSIBeacon.filteredRSSI) {
                highestRSSIBeacon = beacon;
            }
        }
        return highestRSSIBeacon;
    }

    void addOrUpdateBeacon(MTSBeacon discoveredBeacon, BleManager.DiscoveryListener.DiscoveryEvent event) {
        MTSBeacon beacon;

        // Find the matching beacon from detectedBeacons or add a new one.
        int index = getIndexOfBeaconListUpdated(discoveredBeacon);
        if (-1 < index) {
            beacon = detectedBeacons.get(index);
        } else {
            detectedBeacons.add(discoveredBeacon);
            beacon = discoveredBeacon;
        }

        // Common method to update both new and existing beacons with discovered values.
        beacon.updateOnDiscovery(event);

        Collections.sort(detectedBeacons, new Comparator<MTSBeacon>() {
            @Override public int compare(MTSBeacon a, MTSBeacon b) {
                return (int)(b.rssi-a.rssi);
            }
        });

    }

    Handler scanTimeoutHandler = new Handler();
    Runnable scanTimeoutRunnable;

    private void startScanTimeoutTimer() {
        if (scanTimeoutInterval() <= 0) {
            return;
        }
        stopRSSIRefreshWhileConnectedTimer();
        beaconExpirationHandler.postDelayed(new Runnable()
        {
            public void run()
            {
                if (BluetoothConnectionState.scanning == bluetoothConnectionState) {
                    stopScanning();
                }
            }
        }, scanTimeoutInterval());
    }

    private void stopScanTimeoutTimer() {
        scanTimeoutHandler.removeCallbacks(scanTimeoutRunnable);
    }

    Handler beaconExpirationHandler = new Handler();
    Runnable beaconExpirationRunnable;
    private int kBeaconExpirationEvalInterval = 1000;
    private static long kBeaconExpirationInterval = 6000;

    private void startExpirationTimer() {
        stopRSSIRefreshWhileConnectedTimer();
        beaconExpirationHandler.postDelayed(new Runnable()
        {
            public void run()
            {
                restartScanIfNeeded();
                clearAnyExpiredBeacons();
                beaconExpirationRunnable=this;
                beaconExpirationHandler.postDelayed(beaconExpirationRunnable, kBeaconExpirationEvalInterval);
            }
        }, 0);
    }

    private void stopExpirationTimer() {
        beaconExpirationHandler.removeCallbacks(beaconExpirationRunnable);
    }

    private void restartScanIfNeeded() {
        if (scanning != bluetoothConnectionState) {
            return;
        }
        if (sweetBlue_BleManager.isScanning()) {
            return;
        }
        startScan();
    }

    private void clearDiscoveredBeacons() {
        detectedBeacons = new ArrayList<MTSBeacon>();
    }

    private void clearAnyExpiredBeacons() {

        if (null != connectedMTSBeacon) {
            Log.v(TAG, "clearAnyExpiredBeacons called while null != connectedMTSBeacon");
            return;
        }

        long current = SystemClock.elapsedRealtime();
        ListIterator listIterator = detectedBeacons.listIterator();
        while(listIterator.hasNext()) {
            MTSBeacon beacon = (MTSBeacon) listIterator.next();
            long elapsed = current - beacon.lastDiscoveredAt;
            if (elapsed > kBeaconExpirationInterval) {
                listIterator.remove();
            }
        }
    }

    private int getIndexOfBeaconListUpdated(MTSBeacon BeaconListUpdated) {
        for (int i = 0; i < detectedBeacons.size(); i++) {
            MTSBeacon knownBeacon = detectedBeacons.get(i);
            if (BeaconListUpdated.address!=null && BeaconListUpdated.address.equals(knownBeacon.address)) {
                return i;
            }
        }
        return -1;
    }

    private void connect(MTSBeacon beacon) {
        connectedMTSBeacon = beacon;
        clearDiscoveredBeacons();
        stopScan();
        // N.B. SweetBlue BleDevice.StateListener is marked deprecated, but the replacement method
        // DeviceStateListener has an implementation that is commented out with a TODO.
        connectedMTSBeacon.device.connect(connectionStateListener, connectionFailListener);
    }

    BleDevice.StateListener connectionStateListener = new BleDevice.StateListener()
    {
        @Override public void onEvent(StateEvent event)
        {
             Log.i("connectionStateListener", event.device().getName_debug() + event.toString());

            if( event.didEnter(BleDeviceState.INITIALIZED) ) {
                changeBluetoothConnectionState(BluetoothConnectionState.connected);
            }
            else if( event.didEnter(BleDeviceState.CONNECTING) || event.didEnter(BleDeviceState.CONNECTED) ) {
                stopReconnectTimeout();
            }
            else if( event.didEnter(BleDeviceState.DISCONNECTED) ) {
                connectedMTSBeacon = null;
                if (inactive == bluetoothConnectionState) {
                    return;
                }
                changeBluetoothConnectionState(scanning);
            }
            else if( event.didEnter(BleDeviceState.RECONNECTING_SHORT_TERM) ) {
                Log.v(TAG, "RECONNECTING_SHORT_TERM");
                changeBluetoothConnectionState(attemptingToReconnect);
            }
            else if( event.didEnter(BleDeviceState.RETRYING_BLE_CONNECTION) ) {
                // This is not hit in range and unplug test disconnect cases.
                Log.v(TAG, "RETRYING_BLE_CONNECTION");
            }
        }
    };

    BleDevice.ConnectionFailListener.ConnectionFailEvent lastConnectionFailEvent;
    BleDevice.ConnectionFailListener connectionFailListener = new BleDevice.ConnectionFailListener() {
        @Override
        public Please onEvent(ConnectionFailEvent e) {
            Log.v(TAG, "Connection failed: " + e.toString());
            // Expected was connection failure would result in disconnected state, actual is it
            // requires disconnect request.
            // e.device().disconnect();
            lastConnectionFailEvent = e;
            return null;
        }
    };

    private void disconnectIfNeeded() {
        Log.v(TAG, "disconnectIfNeeded");
        if (null != connectedMTSBeacon) {
            connectedMTSBeacon.device.disconnect();
            // Beacons frequently fail to disconnect properly; wait until scanning state change to
            // clear: connectedMTSBeacon = null;
        }
    }

    private void autoConnectThresholdCrossed(MTSBeacon beacon) {
        if (!autoConnectThresholdEnabled()) {
            return;
        }
        if (null != connectedMTSBeacon) {
            Log.v(TAG, "null != bluetoothService.connectedMTSBeacon");
            return;
        }
        connect(beacon);
    }

    private void evaluateVsAutoConnectThreshold() {
        MTSBeacon beacon = highestRSSIBeacon();
        if (null == beacon) {
            return;
        }
        if (beacon.filteredRSSI > autoConnectRSSIThreshold()) {
            Log.v(TAG, "highestRSSIBeacon RSSI: "+ beacon.filteredRSSI);
            autoConnectThresholdCrossed(beacon);
            return;
        }
    }

    private void evaluateVsAutoDisconnectThreshold(int rssi) {
        if (!autoDisconnectThresholdEnabled()) {
            return;
        }
        if (null == connectedMTSBeacon) {
            Log.v(TAG, "evaluateVsAutoDisconnectThreshold called with null connectedMTSBeacon.");
            return;
        }
        if (autoDisconnectRSSIThreshold() > rssi) {
            if (isAutoDisconnectTimerActive) {
                // AutoDisconnectTimer is already running, don't restart it.
                return;
            }
            startAutoDisconnectTimer();
        } else {
            stopAutoDisconnectTimer();
        }
    }

    void autoDisconnectThresholdCrossed() {
        if (null == connectedMTSBeacon) {
            Log.v(TAG, "autoDisconnectThresholdCrossed failed due to null == bluetoothService.connectedMTSBeacon");
            return;
        }
        disconnectIfNeeded();
    }

    Handler autoDisconnectTimeoutHandler = new Handler();
    Boolean isAutoDisconnectTimerActive = false;

    private void startAutoDisconnectTimer() {
        isAutoDisconnectTimerActive = true;
        autoDisconnectTimeoutHandler.postDelayed(new Runnable()
        {
            public void run()
            {
                autoDisconnectThresholdCrossed();
            }
        }, autoDisconnectInterval() * 1000);
    }

    private void stopAutoDisconnectTimer() {
        autoDisconnectTimeoutHandler.removeCallbacksAndMessages(null);
        isAutoDisconnectTimerActive = false;
    }

    private void startScan() {
        // N.B. sweetBlue_BleManager.startPeriodicScan does not work reliably for the RSSI/rediscovery required in MTSService:
        // 1) It fails to rediscover beacons when it includes the MTSServiceUUID scanFilter parameter.
        // 2) It does not reliably restart after the scanPause interval.
        // sweetBlue_BleManager.startPeriodicScan(Interval.TEN_SECS, Interval.ONE_SEC, sweetBlue_DiscoveryListener);
        sweetBlue_BleManager.startScan(sweetBlue_ScanFilter, sweetBlue_DiscoveryListener);
    }

    private void stopScan() {
        sweetBlue_BleManager.stopPeriodicScan();
    }

    public void changeBluetoothConnectionState(BluetoothConnectionState newState) {
        if (newState == bluetoothConnectionState) {
            return;
        }
        Log.v(TAG, "changeBluetoothConnectionState from: " + bluetoothConnectionState.toString() + " to: " + newState);
        stopScanTimeoutTimer();
        stopReconnectTimeout();
        stopRSSIRefreshWhileConnectedTimer();
        bluetoothConnectionState = newState;
        lastConnectionFailEvent = null;
        switch (bluetoothConnectionState) {
            case notReady:
                disconnectIfNeeded();
                stopScan();
                stopExpirationTimer();
                break;
            case inactive:
                disconnectIfNeeded();
                stopScan();
                stopExpirationTimer();
                break;
            case scanning:
                disconnectIfNeeded();
                startScan();
                startScanTimeoutTimer();
                startExpirationTimer();
                break;
            case connected:
                stopScan();
                stopExpirationTimer();
                startRSSIRefreshWhileConnectedTimer();
                break;
            case attemptingToReconnect:
              startReconnectTimeout();
        }

        final Intent connectionStateChangedIntent = new Intent(BluetoothConnectionStateChanged);
        sendBroadcast(connectionStateChangedIntent);
    }

//    private void reconnect() {
//        if (null != connectedMTSBeacon) {
//            Log.v(TAG, "null != bluetoothService.connectedMTSBeacon");
//            return;
//        }
//        if (BluetoothConnectionState.attemptingToReconnect != bluetoothConnectionState) {
//            Log.v(TAG, "reconnect returned early because it was called with invalid connectionState: " + bluetoothConnectionState);
//            return;
//        }
//        // Tell Android we want to reconnect when the peripheral is back in range.
//        connect(connectedMTSBeacon);
//        startReconnectTimeout();
//    }

    Handler reconnectTimeoutHandler = new Handler();
    Boolean isisReconnectTimerActive = false;

    private void startReconnectTimeout() {
        Log.v("","startReconnectTimeout");
        isisReconnectTimerActive = true;
        reconnectTimeoutHandler.postDelayed(new Runnable()
        {
            public void run()
            {
                Log.v("","reconnectTimeout expired, forcing disconnect...");
                if (null != lastConnectionFailEvent) {
                    lastConnectionFailEvent.device().disconnect();
                }
                changeBluetoothConnectionState(scanning);
            }
        }, autoDisconnectInterval() * 10000);
    }

    private void stopReconnectTimeout() {
        Log.v("","stopReconnectTimeout");
        reconnectTimeoutHandler.removeCallbacksAndMessages(null);
        isisReconnectTimerActive = false;
        lastConnectionFailEvent = null;
    }

    // USB Accessory

    public void setupUSB() {
        registerUSBReceiver();
        checkForAccessories("setup USB");
    }

    public void cleanupUSB() {
        try {
            unregisterReceiver(usbReceiver);
        }
        catch (final Exception exception) {
            // Unbalanced register/unregister.
        }
        closeAccessory();
        alreadyWriting = false;
    }


    // Assign this value from the activity at some point after the service is set up.
    // Then the next time MTSService detects a GT USB accessory it will write the playerId.
    // Returns false if invalid parameters are passed.
    // The value is not persisted to e.g. SharedPreferences, so assign it each time you bind to the service.
    // https://github.com/MobileTechnologySolutionsLLC/gt-connect/blob/develop/firmware/gt_ble/Readme.md// https://github.com/MobileTechnologySolutionsLLC/gt-connect/blob/develop/firmware/gt_ble/Readme.md
    public Boolean assignCardDataForWriteOnNextUSBConnect(String cardDataString) {
        byte[] data = validatedCardData(cardDataString, false);
        if (null == data) {
            return false;
        }
        playerIdDataToWriteOnUSBConnect = data;
        return true;
    }

    public void checkForAccessories(String source) {
        if (null == usbManager) {
            return;
        }
        if (usbFileDescriptor != null) {
            return;
        }
        UsbAccessory[] accessoryList = usbManager.getAccessoryList();
        if (null != accessoryList && accessoryList.length > 0) {
            usbAccessory = accessoryList[0];
            if (usbManager.hasPermission(usbAccessory)) {
                openAccessory(usbAccessory);
            } else {
                synchronized (usbReceiver) {
                    if (!permissionRequestPending) {
                        permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
                        usbManager.requestPermission(usbAccessory,permissionIntent);
                        permissionRequestPending = true;
                    }
                }
            }
        }
    }

    BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    usbAccessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        checkForAccessories("permission granted");
                    } else {
                        Log.d(TAG, "onReceive UsbManager.EXTRA_PERMISSION_GRANTED permission denied for accessory " + usbAccessory);
                    }
                    permissionRequestPending = false;
                }
            }
            else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                closeAccessory();
                // N.B. moving alreadyWriting = false to closeAccessory can allow multiple write attempts
                // during a given USB connect sequence.  Clear alreadyWriting here on detach and upon
                // cleanupUSB.
                alreadyWriting = false;
            }
        }
    };

    private void registerUSBReceiver() {
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        filter.addAction(UsbManager.EXTRA_ACCESSORY);
        filter.addAction(UsbManager.EXTRA_PERMISSION_GRANTED);
        registerReceiver(usbReceiver,filter);
    }

    // checkForAccessories() can be called upon activity resume, service bind, or USB permission grant.
    // If the checkForAccessories() results in a write attempt, skip subsequent attempts until the
    // first attempt fails.  If the first attempt succeeds, skip subsequent attempts until the next
    // USB connect sequence.  Resume in particular can be called twice https://stackoverflow.com/a/35166709.
    private boolean alreadyWriting = false;

    private void writePlayerIdIfAssigned() {

        if (alreadyWriting) {
            return;
        }
        alreadyWriting = true;

        if (null == usbFileDescriptor) {
            Log.v(TAG, "writePlayerId failed because usbFileDescriptor was null.");
            return;
        }

        if(null == outputStream){
            Log.v(TAG, "writePlayerId failed because outputStream was null.");
            return;
        }

        if (null == playerIdDataToWriteOnUSBConnect) {
            Log.v(TAG, "writePlayerId failed because outputStream was null.");
            return;
        }
        final Intent intent = new Intent(DidWriteCardDataToAccessory);
        try {
            outputStream.write(playerIdDataToWriteOnUSBConnect);
            intent.putExtra("writeOutcome", true);
            sendBroadcast(intent);
        } catch (IOException e) {
            e.printStackTrace();
            intent.putExtra("writeOutcome", false);
            sendBroadcast(intent);
            alreadyWriting = false;
        }

        closeAccessory();
    }

    private void openAccessory(UsbAccessory accessory) {
        usbFileDescriptor = usbManager.openAccessory(accessory);
        if (usbFileDescriptor != null) {
            FileDescriptor fd = usbFileDescriptor.getFileDescriptor();
            inputStream = new FileInputStream(fd);
            outputStream = new FileOutputStream(fd);
            writePlayerIdIfAssigned();
        } else {
            Log.d(TAG, "openAccessory failed");
        }
    }

    private void closeAccessory() {
        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (IOException e) {
        } finally {
            inputStream = null;
        }

        try {
            if (outputStream != null) {
                outputStream.close();
            }
        } catch (IOException e) {
        } finally {
            outputStream = null;
        }

        try {
            if (usbFileDescriptor != null) {
                usbFileDescriptor.close();
            }
        } catch (IOException e) {
        } finally {
            usbFileDescriptor = null;
            usbAccessory = null;
        }
    }
}
