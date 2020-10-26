package com.mts.mts;

// Copyright © 2020 Mobile Technology Solutions, Inc. All rights reserved.


import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.ListIterator;
import java.util.UUID;

import static android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH;
import static android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
import static com.mts.mts.BluetoothPeripheral.GATT_SUCCESS;
import static com.mts.mts.BluetoothPeripheral.STATE_CONNECTED;
import static com.mts.mts.BluetoothPeripheral.STATE_CONNECTING;
import static com.mts.mts.MTSService.BluetoothConnectionState.attemptingToReconnect;
import static com.mts.mts.MTSService.BluetoothConnectionState.connected;
import static com.mts.mts.MTSService.BluetoothConnectionState.inactive;
import static com.mts.mts.MTSService.BluetoothConnectionState.notReady;
import static com.mts.mts.MTSService.BluetoothConnectionState.scanning;

public class MTSService extends Service {

    // Intent Types
    public final static String BluetoothConnectionStateChanged =
            "com.mts.BluetoothConnectionStateChanged";
    public final static String DidReceiveTerminalKind =
            "com.mts.DidReceiveTerminalKind";
    public final static String DidReceiveCardData =
            "com.mts.DidReceiveCardData";
    public final static String DidWriteCardDataToBluetooth =
            "com.mts.DidWriteCardDataToBluetooth ";
    public final static String DidReceiveStickyConnectionState =
            "com.mts.DidReceiveStickyConnectionState";
    public final static String ReconnectAttemptTimedOut =
            "com.mts.ReconnectAttemptTimedOut";
    public final static String UpdateOnConnectedRSSIReceipt =
            "com.mts.UpdateOnConnectedRSSIReceipt";

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
    public BluetoothConnectionState bluetoothConnectionState = notReady;
    public MTSBeacon connectedMTSBeacon;
    public ArrayList<MTSBeacon> detectedBeacons = new ArrayList<MTSBeacon>();

    private final static String TAG = "MTSService";

    public int cardDataCharacterCountMax = 195; // 195 + automatic null termination, so 196 total accepted by the peripheral.
    private int kRSSIUnavailableValue = 127;
    private UUID mtsServiceUUID = UUID.fromString("C1FB6CDA-3F15-4BC0-8A46-8E9C341065F8");
    private ParcelUuid cardDataCharacteristicUUID = ParcelUuid.fromString("60D11359-FEB2-411D-A430-CA6167052BD6");
    private ParcelUuid terminalKindCharacteristicUUID = ParcelUuid.fromString("D308DFDE-9F06-4A73-A2C7-EB952E40A184");
    private ParcelUuid stickyConnectCharacteristicUUID = ParcelUuid.fromString("4B6A91D8-EA3E-42A4-B39B-B300F5F64C86");
    private ParcelUuid userDisconnectedCharacteristicUUID = ParcelUuid.fromString("4E3A829D-4830-47A0-995F-EE923710A469");

    private BluetoothCentral central;
    private Context context;

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
        return super.onUnbind(intent);
    }

    private final IBinder binder = new LocalBinder();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        changeBluetoothConnectionState(inactive);
        return START_NOT_STICKY;
    }

    public boolean initialize(Context context) {
        this.context = context;
        central = new BluetoothCentral(context, bluetoothCentralCallback, new Handler());
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopScanning();
    }


    // Bluetooth

    ArrayList<ParcelUuid> requiredCharacteristics;
    ArrayList<ParcelUuid> allCharacteristics = new ArrayList<ParcelUuid>(Arrays.asList(
            cardDataCharacteristicUUID,
            terminalKindCharacteristicUUID,
            stickyConnectCharacteristicUUID,
            userDisconnectedCharacteristicUUID
        ));

    private void handleCharacteristicDiscovery(BluetoothGattCharacteristic characteristic) {
        markCharacteristicDiscovered(characteristic);
        isCharacteristicDiscoveryDone();
    }

    private void markCharacteristicDiscovered(BluetoothGattCharacteristic characteristic) {
        ParcelUuid parcelUuid = new ParcelUuid(characteristic.getUuid());
        requiredCharacteristics.remove(parcelUuid);
    }


    private void isCharacteristicDiscoveryDone() {
        if (0 == requiredCharacteristics.size() && scanning == bluetoothConnectionState) {
            changeBluetoothConnectionState(connected);
        } else {
            Log.v("","requiredCharacteristics.size(): " + requiredCharacteristics.size() + " bluetoothConnectionState: " + bluetoothConnectionState);
            if (requiredCharacteristics.size() <= 2) {
                Log.v("","requiredCharacteristics: " + requiredCharacteristics.toString());
            }
        }
    }

    // Callback for peripherals
    private final BluetoothPeripheralCallback peripheralCallback = new BluetoothPeripheralCallback() {
        @Override
        public void onServicesDiscovered(BluetoothPeripheral peripheral) {
            Log.v("","onServicesDiscovered");
            peripheral.requestConnectionPriority(CONNECTION_PRIORITY_HIGH);

            BluetoothGattService service = peripheral.getService(mtsServiceUUID);
            if(null == service) {
                Log.v("","onServicesDiscovered failed at null == service.");
                return;
            }

            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                handleCharacteristicDiscovery(characteristic);
            }
        }

        @Override
        public void onNotificationStateUpdate(BluetoothPeripheral peripheral, BluetoothGattCharacteristic characteristic, int status) {
        }

        @Override
        public void onCharacteristicWrite(BluetoothPeripheral peripheral, byte[] value, BluetoothGattCharacteristic characteristic, int status) {
            UUID characteristicUUID = characteristic.getUuid();
            if (cardDataCharacteristicUUID.getUuid().equals(characteristicUUID)) {
                Log.v("","writeCardDataToBluetooth: event.wasSuccess(): " + (status == GATT_SUCCESS));
                final Intent intent = new Intent(DidWriteCardDataToBluetooth);
                intent.putExtra("DidWriteCardDataToBluetooth", status == GATT_SUCCESS);
                sendBroadcast(intent);
            } else if (userDisconnectedCharacteristicUUID.getUuid().equals(characteristicUUID)) {
                if( status == GATT_SUCCESS) {
                    connectedMTSBeacon = null;
                    changeBluetoothConnectionState(inactive);
                }
            }
        }

        @Override
        public void onCharacteristicUpdate(BluetoothPeripheral peripheral, byte[] value, BluetoothGattCharacteristic characteristic, int status) {
            if(status != GATT_SUCCESS) return;
            handleOnCharacteristicChanged(characteristic, value);
        }

        @Override
        public void onMtuChanged(BluetoothPeripheral peripheral, int mtu, int status) {
            super.onMtuChanged(peripheral, mtu, status);
        }

        @Override
        public void onReadRemoteRssi(final BluetoothPeripheral peripheral, int rssi, int status) {
            if (GATT_SUCCESS == status) {

                if (null == connectedMTSBeacon) {
                    Log.v("","onReadRemoteRssi: " + rssi +" returning early due to null == connectedMTSBeacon.");
                    return;
                }
                evaluateVsAutoDisconnectThreshold(rssi);

                // Broadcast the RSSI update
                String connectedRSSIValue = "- - -";
                final Intent intent = new Intent(UpdateOnConnectedRSSIReceipt);
                connectedRSSIValue = String.valueOf(rssi);
                Log.v(TAG, connectedRSSIValue);
                intent.putExtra("connectedRSSIValue", connectedRSSIValue);
                sendBroadcast(intent);
            }
        }

    };

    BluetoothPeripheral lastConnectedBluetoothPeripheral;

    // Callback for central
    private final BluetoothCentralCallback bluetoothCentralCallback = new BluetoothCentralCallback() {

        @Override
        public void onConnectedPeripheral(BluetoothPeripheral peripheral) {
            //Timber.i("connected to '%s'", peripheral.getName());
            requiredCharacteristics = allCharacteristics;
            connectedMTSBeacon.peripheral = peripheral;
            connectedMTSBeacon.peripheral.requestMtu(256);

            // Disconnects intermittently fail or take unpredictable durations.
            // The null == connectedMTSBeacon test is used to evaluate whether a discovered+connected
            // peripheral is meant to be available to the user.  So we want connectedMTSBeacon to be
            // null upon disconnect.  But it is also sometimes necessary to make additional disconnect
            // attempts after the apparent completion of a disconnect.  Use this lastConnectedBluetoothPeripheral
            // to make these fallback disconnect attempts;
            lastConnectedBluetoothPeripheral = peripheral;
        }

        @Override
        public void onConnectionFailed(BluetoothPeripheral peripheral, final int status) {
            //Timber.e("connection '%s' failed with status %d", peripheral.getName(), status);
        }

        @Override
        public void onDisconnectedPeripheral(final BluetoothPeripheral peripheral, final int status) {
            Log.v("","onDisconnectedPeripheral "+peripheral.getName()+" with status: "+status);
            detectedBeacons.clear();
            if (inactive == bluetoothConnectionState) {
                return;
            }
            connectedMTSBeacon = null;
            changeBluetoothConnectionState(scanning);
        }

        @Override
        public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {
            stopScanRestartTimer();

            if (scanning != bluetoothConnectionState) {
                return;
            }

            if (kRSSIUnavailableValue == scanResult.getRssi()) {
                // Discard discovery events when the RSSI value is not available: the focus of this app is
                // using duplicate discoveries to monitor RSSI changes, so a discovery without RSSI is not useful.
                return;
            }

            MTSBeacon beacon = new MTSBeacon(peripheral, scanResult, MTSService.this);
            addOrUpdateBeacon(beacon, scanResult);
            evaluateVsAutoConnectThreshold();
        }

        @Override
        public void onBluetoothAdapterStateChanged(int state) {
            if(state == BluetoothAdapter.STATE_ON) {
                changeBluetoothConnectionState(scanning);
            }
        }
    };

    private void handleOnCharacteristicChanged(BluetoothGattCharacteristic characteristic, byte[] value) {

        byte[] characteristicBytes = characteristic.getValue();

        if (null == characteristicBytes) {
            Log.v(TAG, "handleOnCharacteristicChanged: null == characteristicValue");
            return;
        }

        if (null == connectedMTSBeacon) {
            Log.v(TAG, "handleOnCharacteristicChanged: null == connectedMTSBeacon");
            return;
        }

        UUID characteristicUUID = characteristic.getUuid();
        if (terminalKindCharacteristicUUID.getUuid().equals(characteristicUUID)) {
            String terminalKind = "- - -";
            final Intent intent = new Intent(DidReceiveTerminalKind);
            terminalKind = new String(value, Charset.forName("UTF-8"));
            Log.v(TAG, "terminalKind: " + terminalKind);
            intent.putExtra("terminalKind", terminalKind);
            sendBroadcast(intent);
        }
        else if (stickyConnectCharacteristicUUID.getUuid().equals(characteristicUUID)) {
            boolean wantsStickyConnection = false;
            final Intent intent = new Intent(DidReceiveStickyConnectionState);
            wantsStickyConnection = (value[0] == (byte)0x01);
            if (null != connectedMTSBeacon) {
                connectedMTSBeacon.wantsStickyConnection = wantsStickyConnection;
            }
            intent.putExtra("stickyConnectionState", wantsStickyConnection);
            sendBroadcast(intent);
        }
        else if (cardDataCharacteristicUUID.getUuid().equals(characteristicUUID)) {
            final Intent intent = new Intent(DidReceiveCardData);
            if(null != value) {
                String cardData = new String(value, Charset.forName("UTF-8"));
                intent.putExtra("cardData",cardData);
            } else {
                String empty = null;
                intent.putExtra("cardData", empty);
            }
            sendBroadcast(intent);
        }
    }


    // Bluetooth

    public void startScanning() {
        changeBluetoothConnectionState(BluetoothConnectionState.scanning);
    }

    public void stopScanning() {
        changeBluetoothConnectionState(BluetoothConnectionState.inactive);
    }

    public void disconnect() {
        byte[] bytes = new byte[1];
        writeCharacteristic(userDisconnectedCharacteristicUUID, bytes, WRITE_TYPE_DEFAULT);
    }

    public void readCharacteristic(ParcelUuid characteristicUUID) {

        if (null == connectedMTSBeacon) {
            Log.v("","readCharacteristic failed at if (null == connectedMTSBeacon).");
            return;
        }

        if (null == connectedMTSBeacon.peripheral) {
            Log.v("","readCharacteristic failed at if (null == connectedMTSBeacon.peripheral).");
            return;
        }

        BluetoothGattCharacteristic characteristic = connectedMTSBeacon.peripheral.getCharacteristic(mtsServiceUUID, characteristicUUID.getUuid());
        if (null == characteristic) {
            Log.v("", "readCharacteristic failed at null == characteristic.");
            return;
        }

        connectedMTSBeacon.peripheral.readCharacteristic(characteristic);
    }


    public void writeCharacteristic(ParcelUuid characteristicUUID, final byte[] value, int writeType) {

        if (null == connectedMTSBeacon) {
            Log.v("","writeCharacteristic failed at if (null == connectedMTSBeacon).");
            return;
        }

        if (null == connectedMTSBeacon.peripheral) {
            Log.v("","writeCharacteristic failed at if (null == connectedMTSBeacon.peripheral).");
            return;
        }

        BluetoothGattCharacteristic characteristic = connectedMTSBeacon.peripheral.getCharacteristic(mtsServiceUUID, characteristicUUID.getUuid());
        connectedMTSBeacon.peripheral.writeCharacteristic(characteristic, value, writeType);
        Log.v("","writeCharacteristic complete for " + characteristicUUID.toString());
    }

    public void requestTerminalKind() {
        if (null == connectedMTSBeacon) {
            return;
        }
        readCharacteristic(terminalKindCharacteristicUUID);
    }

    public void requestStickyConnectState() {
        if (null == connectedMTSBeacon) {
            return;
        }
        readCharacteristic(stickyConnectCharacteristicUUID);
    }

    public void requestCardData() {
        if (null == connectedMTSBeacon) {
            return;
        }
        readCharacteristic(cardDataCharacteristicUUID);
    }

    public Boolean writeCardDataToBluetooth(String cardDataString) {
        if (null == connectedMTSBeacon) {
            return false;
        }

        byte[] data = validatedCardData(cardDataString);

        if (null == data) {
            return false;
        }

        writeCharacteristic(cardDataCharacteristicUUID, data, WRITE_TYPE_DEFAULT);

        return true;
    }

    private byte[] validatedCardData(String cardDataString) {
        String truncatedCardDataString = cardDataString.substring(0, Math.min(cardDataString.length(), cardDataCharacterCountMax));
        byte[] cardDataBytes = truncatedCardDataString.getBytes(Charset.forName("UTF-8"));
        byte[] nullBytes = new byte[0];
        byte[] terminatedBytes = new byte[cardDataBytes.length + nullBytes.length];
        System.arraycopy(cardDataBytes, 0, terminatedBytes, 0, cardDataBytes.length);
        System.arraycopy(nullBytes, 0, terminatedBytes, cardDataBytes.length, nullBytes.length);
        return terminatedBytes;
    }

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

    void addOrUpdateBeacon(MTSBeacon discoveredBeacon, ScanResult scanResult) {
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
        beacon.updateOnDiscovery(scanResult);

        Collections.sort(detectedBeacons, new Comparator<MTSBeacon>() {
            @Override public int compare(MTSBeacon a, MTSBeacon b) {
                return (int)(b.rssi-a.rssi);
            }
        });

    }

    Handler scanTimeoutHandler = new Handler();
    Runnable scanTimeoutRunnable;

    // Maintenance note: startScanTimeoutTimer() and startRestartTimer() should not run
    // simultaneously.  They can be called in the same sequence as long as the scanTimeoutInterval()
    // condition at the start remains.  One requires zero value, the other non-zero.
    private void startScanTimeoutTimer() {
        if (scanTimeoutInterval() <= 0) {
            return;
        }
        stopConnectedRSSIReads();
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
    private static long kBeaconExpirationInterval = 3000;

    private void startExpirationTimer() {
        stopConnectedRSSIReads();
        beaconExpirationHandler.postDelayed(new Runnable()
        {
            public void run()
            {
                Log.v("","");
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
        if (central.isScanning()) {
            return;
        }
        startScan();
    }

    // This handles a case where:
    // a) a scan start attempt is made before a prior connection has been closed properly.
    // b) scanTimeoutInterval() <= 0
    // The symptom that occurs without scanRestartHandling is intermittent failure to resume
    // scanning after a disconnect.  Usually repros within a few attempts (Samsung S9 Android 10).
    Handler scanRestartHandler = new Handler();
    Runnable scanRestartRunnable;
    int scanRestartTimeoutInterval = 5000;

    private void startRestartTimer() {
        if (scanTimeoutInterval() > 0) {
            return;
        }
        scanRestartHandler.postDelayed(new Runnable()
        {
            public void run()
            {
                scanRestartRunnable=this;
                if (scanning != bluetoothConnectionState) {
                    Log.v("","startRestartTimer() fired, but returning due to scanning != bluetoothConnectionState.");
                    return;
                }
                if (null == central) {
                    Log.v("","startRestartTimer() fired, but returning due to null == central.");
                    return;
                }
                central.stopScan();
                central.scanForPeripheralsWithServices(new UUID[]{mtsServiceUUID});
            }
        }, scanRestartTimeoutInterval);
    }

    private void stopScanRestartTimer() {
        scanRestartHandler.removeCallbacks(scanRestartRunnable);
    }

    private void clearDiscoveredBeacons() {
        detectedBeacons = new ArrayList<MTSBeacon>();
    }

    private void clearAnyExpiredBeacons() {

        if (null != connectedMTSBeacon) {
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
        stopScan();
        clearDiscoveredBeacons();
        connectedMTSBeacon = beacon;
        central.connectPeripheral(connectedMTSBeacon.peripheral, peripheralCallback);
    }

    private void reconnect() {
        if (null == connectedMTSBeacon) {
            return;
        }

        if (null == connectedMTSBeacon.peripheral) {
            return;
        }

        if (connectedMTSBeacon.peripheral.getState() == STATE_CONNECTED ||
            connectedMTSBeacon.peripheral.getState() == STATE_CONNECTING
        ) {
            return;
        }

        if (!bluetoothConnectionState.equals(attemptingToReconnect)) {
            return;
        }

        connect(connectedMTSBeacon);

        startReconnectTimeout();
    }

    private void disconnectIfNeeded() {
        Log.v(TAG, "disconnectIfNeeded");
        if (null != connectedMTSBeacon) {
            connectedMTSBeacon.peripheral.cancelConnection();
            // Beacons frequently fail to disconnect properly; wait until scanning state change to
            // clear: connectedMTSBeacon = null;

        // Handle the case where a prior attempt to disconnect was attempted while connectedMTSBeacon
        // was assigned, but it is now null, yet the connection failed to close.
        } else if (null != lastConnectedBluetoothPeripheral && lastConnectedBluetoothPeripheral.getState() == STATE_CONNECTED) {
            lastConnectedBluetoothPeripheral.cancelConnection();

        } else {
            Log.v(TAG, "disconnectIfNeeded called while null == connectedMTSBeacon annd lastConnectedBluetoothPeripheral.getState() != STATE_CONNECTED");
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
            autoConnectThresholdCrossed(beacon);
            return;
        } else {
            //Timber.i("evaluateVsAutoConnectThreshold beacon.filteredRSSI %s !> autoConnectRSSIThreshold() %s.", beacon.filteredRSSI, autoConnectRSSIThreshold());
        }
    }

    private void evaluateVsAutoDisconnectThreshold(int rssi) {
        if (!autoDisconnectThresholdEnabled()) {
            return;
        }
        if (null == connectedMTSBeacon) {
            return;
        }
        if (autoDisconnectRSSIThreshold() > rssi) {
            if (isAutoDisconnectTimerActive) {
                // AutoDisconnectTimer is already running, don't restart it.
                Log.v("","evaluateVsAutoDisconnectThreshold: " + rssi +" returning early since AutoDisconnectTimer is already running, don't restart it.");
                return;
            }
            startAutoDisconnectCountdown();
        } else {
            stopAutoDisconnectCountdown();
        }
    }

    void autoDisconnectThresholdCrossed() {
        if (null == connectedMTSBeacon) {
            return;
        }
        disconnectIfNeeded();
    }


    // AutoDisconnectCountdown - RSSI threshold + interval triggers disconnect.
    Handler autoDisconnectTimeoutHandler = new Handler();
    Boolean isAutoDisconnectTimerActive = false;

    private void startAutoDisconnectCountdown() {
        isAutoDisconnectTimerActive = true;
        autoDisconnectTimeoutHandler.postDelayed(new Runnable()
        {
            public void run()
            {
                if (null == connectedMTSBeacon) {
                    stopAutoDisconnectCountdown();
                } else {
                    autoDisconnectThresholdCrossed();
                }
            }
        }, autoDisconnectInterval() * 1000);
    }

    private void stopAutoDisconnectCountdown() {
        autoDisconnectTimeoutHandler.removeCallbacksAndMessages(null);
        isAutoDisconnectTimerActive = false;
    }


    // Connected RSSI read.  Supports auto-disconnect and interface RSSI display.
    private Handler connectedRSSIReadHandler = new android.os.Handler();

    private Runnable connectedRSSIReadRunnable = new Runnable() {
        public void run() {

            if (null == connectedMTSBeacon) {
                stopConnectedRSSIReads();
                return;
            }
            connectedMTSBeacon.peripheral.readRemoteRssi();
            connectedRSSIReadHandler.postDelayed(connectedRSSIReadRunnable, 1 * 1000);
        }
    };

    void startConnectedRSSIReads() {
        connectedRSSIReadHandler.postDelayed(connectedRSSIReadRunnable, 1 * 1000);
    }

    void stopConnectedRSSIReads() {
        connectedRSSIReadHandler.removeCallbacks(connectedRSSIReadRunnable);
    }

    private void startScan() {
        if (null == central) {
            return;
        }

        if (null != lastConnectedBluetoothPeripheral) {
            int state = lastConnectedBluetoothPeripheral.getState();
            if (state == STATE_CONNECTED) {
                lastConnectedBluetoothPeripheral.cancelConnection();
            }
        }

        central.startPairingPopupHack();
        central.scanForPeripheralsWithServices(new UUID[]{mtsServiceUUID});
    }

    private void stopScan() {
        if (null == central) {
            return;
        }
        central.stopScan();
    }

    public void changeBluetoothConnectionState(BluetoothConnectionState newState) {
        if (newState == bluetoothConnectionState) {
            return;
        }
        Log.v(TAG, "changeBluetoothConnectionState from: " + bluetoothConnectionState.toString() + " to: " + newState);
        stopScanTimeoutTimer();
        stopReconnectTimeout();
        stopAutoDisconnectCountdown();
        stopConnectedRSSIReads();
        bluetoothConnectionState = newState;
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
                startRestartTimer();
                startScanTimeoutTimer();
                startExpirationTimer();
                break;
            case connected:
                stopScan();
                stopExpirationTimer();
                startConnectedRSSIReads();
                break;
            case attemptingToReconnect:
              startReconnectTimeout();
        }

        final Intent connectionStateChangedIntent = new Intent(BluetoothConnectionStateChanged);
        sendBroadcast(connectionStateChangedIntent);
    }

    Handler reconnectTimeoutHandler = new Handler();
    Boolean isisReconnectTimerActive = false;

    private void startReconnectTimeout() {
        isisReconnectTimerActive = true;
        reconnectTimeoutHandler.postDelayed(new Runnable()
        {
            public void run()
            {
                connectedMTSBeacon = null;
                changeBluetoothConnectionState(scanning);
            }
        }, autoDisconnectInterval() * 10000);
    }

    private void stopReconnectTimeout() {
        reconnectTimeoutHandler.removeCallbacksAndMessages(null);
        isisReconnectTimerActive = false;
    }

}
