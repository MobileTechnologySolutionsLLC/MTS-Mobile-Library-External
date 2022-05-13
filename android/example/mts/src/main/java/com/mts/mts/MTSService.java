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

import org.greenrobot.eventbus.EventBus;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.ListIterator;
import java.util.UUID;
import java.util.stream.Stream;

import static android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH;
import static android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
import static com.mts.mts.BluetoothPeripheral.GATT_SUCCESS;

public class MTSService extends Service {

    public enum BluetoothDiscoveryState {
        notReady,
        inactive,
        scanning
    }

    public enum BluetoothConnectionEvent {
        connect,
        pendingUserDisconnect,
        disconnect,
        disabled
    }

    public enum MTSEventType {
        didReceiveTerminalKind,
        didReceiveCardData,
        didWriteCardDataToBluetooth,
        updateOnConnectedRSSIReceipt,
        didReceiveSasSerialNumber,
        didReceiveLocation,
        didReceiveAssetNumber,
        didReceiveDenomination,
        didReceiveGmiLinkActive,
        didReceiveTxAttenuationLevel
    }

    public enum TxAttenuationLevel {
        zero  (0),
        one   (1),
        two   (2),
        three (3);
        private final int value;
        private TxAttenuationLevel(int value) {
            this.value = value;
        }
    }

    public MTSService() {
        super();
    }
    public BluetoothDiscoveryState bluetoothDiscoveryState = BluetoothDiscoveryState.notReady;
    public ArrayList<MTSBeacon> connectedMTSBeacons = new ArrayList<MTSBeacon>();
    public ArrayList<MTSBeacon> detectedBeacons = new ArrayList<MTSBeacon>();

    private final static String TAG = "MTSService";

    public int cardDataCharacterCountMax = 195; // 195 + automatic null termination, so 196 total accepted by the peripheral.
    private int kRSSIUnavailableValue = 127;
    public  UUID mtsServiceUUID = null;
    private UUID machineInfoServiceUUID = UUID.fromString("C83FE52E-0AB5-49D9-9817-98982B4C48A3");
    private ParcelUuid cardDataCharacteristicUUID = ParcelUuid.fromString("60D11359-FEB2-411D-A430-CA6167052BD6");
    private ParcelUuid terminalKindCharacteristicUUID = ParcelUuid.fromString("D308DFDE-9F06-4A73-A2C7-EB952E40A184");
    private ParcelUuid stickyConnectCharacteristicUUID = ParcelUuid.fromString("4B6A91D8-EA3E-42A4-B39B-B300F5F64C86");
    private ParcelUuid userDisconnectedCharacteristicUUID = ParcelUuid.fromString("4E3A829D-4830-47A0-995F-EE923710A469");
    private ParcelUuid sasSerialNumberCharacteristicUUID = ParcelUuid.fromString("9D77E2CF-5D20-44EA-8D2F-A221B976C605");   // utf8s[41], read-only, 40 characters + required null termination.
    private ParcelUuid locationCharacteristicUUID = ParcelUuid.fromString("42C458D7-86B9-4ED8-B57E-1352C7F5100A");  // utf8s[41], read-only, 40 characters + required null termination.
    private ParcelUuid assetNumberCharacteristicUUID = ParcelUuid.fromString("D77A787D-E75D-4370-8CAC-6DCFE37DBB92");   // uint32, read-only.
    private ParcelUuid denominationCharacteristicUUID = ParcelUuid.fromString("7B9432C6-465A-40FA-A13B-03544B6F0742");  // uint32, read-only, unit is cents.
    private ParcelUuid gmiLinkActiveCharacteristicUUID = ParcelUuid.fromString("023B4A4A-579C-495F-A61E-D3BBBFD63C4A"); // bool, read/notify, cardreader's link state for it's GMI interface as active (0x01) or inactive (0x00).
    private ParcelUuid txAttenLevelCharacteristicUUID = ParcelUuid.fromString("51D25B72-68BB-4022-9F71-0CC3DD23A032"); // uint8, read/write, change the attenuation on the device transmitter.  Allowable range is `0x00` = no attenuation through `0x03` = max attenuation (approx. -18dB).

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
        System.out.println("MTSService onStartCommand");
        super.onStartCommand(intent, flags, startId);
        updateDiscoveryStateBasedOnAdapterState();
        return START_NOT_STICKY;
    }

    public boolean initialize(Context context, UUID serviceUUID) {
        this.context = context;
        this.mtsServiceUUID = serviceUUID;
        central = new BluetoothCentral(context, bluetoothCentralCallback, new Handler());
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        stopScanning();
        super.onDestroy();
    }


    // Bluetooth

    ArrayList<ParcelUuid> requiredCharacteristics;
    ArrayList<ParcelUuid> allCharacteristics = new ArrayList<ParcelUuid>(Arrays.asList(
            cardDataCharacteristicUUID,
            terminalKindCharacteristicUUID,
            userDisconnectedCharacteristicUUID,
            sasSerialNumberCharacteristicUUID,
            locationCharacteristicUUID,
            assetNumberCharacteristicUUID,
            denominationCharacteristicUUID,
            gmiLinkActiveCharacteristicUUID,
            txAttenLevelCharacteristicUUID
        ));

    private void handleCharacteristicDiscovery(BluetoothGattCharacteristic characteristic, BluetoothPeripheral peripheral) {
        markCharacteristicDiscovered(characteristic);
        isCharacteristicDiscoveryDone(peripheral);
    }

    private void markCharacteristicDiscovered(BluetoothGattCharacteristic characteristic) {
        ParcelUuid parcelUuid = new ParcelUuid(characteristic.getUuid());
        requiredCharacteristics.remove(parcelUuid);
    }

    private void isCharacteristicDiscoveryDone(BluetoothPeripheral peripheral) {
        if (0 == requiredCharacteristics.size() && BluetoothDiscoveryState.scanning == bluetoothDiscoveryState) {
            MTSBeacon mtsBeacon = connectedMTSBeaconFromPeripheral(peripheral);
            if (null != mtsBeacon) {
                bluetoothConnectionEventOccurred(BluetoothConnectionEvent.connect, mtsBeacon);
            } else {
                Log.v("", "Characteristic discovery is complete, but no matching MTSBeacon in connectedMTSBeacons.");
            }
        } else {
            Log.v("","requiredCharacteristics.size(): " + requiredCharacteristics.size() + " list: " + requiredCharacteristics.toString());
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
            for (BluetoothGattService service : peripheral.getServices()) {
                for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                    handleCharacteristicDiscovery(characteristic, peripheral);
                    // Skip the initial read of userDisconnectedCharacteristic.  The didUpdate for
                    // this is evaluated as a response to a disconnect request.
                    if (!userDisconnectedCharacteristicUUID.getUuid().equals(characteristic.getUuid())) {
                        peripheral.readCharacteristic(characteristic);
                    }
                }
            }
        }

        @Override
        public void onNotificationStateUpdate(BluetoothPeripheral peripheral, BluetoothGattCharacteristic characteristic, int status) {
        }

        @Override
        public void onCharacteristicWrite(BluetoothPeripheral peripheral, byte[] value, BluetoothGattCharacteristic characteristic, int status) {
            UUID characteristicUUID = characteristic.getUuid();
            if (cardDataCharacteristicUUID.getUuid().equals(characteristicUUID)) {
                Log.v("","writeCardDataToBluetooth: event.wasSuccess(): " + (status == GATT_SUCCESS) + " for value: "  + bytesToHex(value));
                MTSBeacon mtsBeacon = connectedMTSBeaconFromPeripheral(peripheral);
                if (null == mtsBeacon) { return; }
                MTSBeaconEvent messageEvent = new MTSBeaconEvent(
                        MTSEventType.didWriteCardDataToBluetooth,
                        status == GATT_SUCCESS,
                        mtsBeacon

                );
                EventBus.getDefault().post(messageEvent);
            } else if (userDisconnectedCharacteristicUUID.getUuid().equals(characteristicUUID)) {
                if( status == GATT_SUCCESS) {
                    final MTSBeacon mtsBeacon = connectedMTSBeaconFromPeripheral(peripheral);
                    if (null != mtsBeacon) {
                        readCharacteristic(userDisconnectedCharacteristicUUID, mtsBeacon);
                    }
                }
            }
        }

        @Override
        public void onCharacteristicUpdate(BluetoothPeripheral peripheral, byte[] value, BluetoothGattCharacteristic characteristic, int status) {
            if(status != GATT_SUCCESS) return;
            handleOnCharacteristicChanged(peripheral, characteristic, value);
        }

        @Override
        public void onMtuChanged(BluetoothPeripheral peripheral, int mtu, int status) {
            super.onMtuChanged(peripheral, mtu, status);
        }

        @Override
        public void onReadRemoteRssi(final BluetoothPeripheral peripheral, int rssi, int status) {
            if (GATT_SUCCESS == status) {

                MTSBeacon mtsBeacon = connectedMTSBeaconFromPeripheral(peripheral);
                if (null == mtsBeacon) {
                    Log.v("","onReadRemoteRssi: " + rssi +" returning early due to null == mtsBeacon.");
                    return;
                }
                evaluateVsAutoDisconnectThreshold(rssi, mtsBeacon);

                // Broadcast the RSSI update
                String connectedRSSIValue = "- - -";
                connectedRSSIValue = String.valueOf(rssi);

                MTSBeaconEvent messageEvent = new MTSBeaconEvent(
                        MTSEventType.updateOnConnectedRSSIReceipt,
                        connectedRSSIValue,
                        mtsBeacon
                );
                EventBus.getDefault().post(messageEvent);
            } else {
                Log.v("","onReadRemoteRssi failed with status: " + status);
            }
        }

    };


    // Callback for central
    private final BluetoothCentralCallback bluetoothCentralCallback = new BluetoothCentralCallback() {

        @Override
        public void onConnectedPeripheral(BluetoothPeripheral peripheral) {
            //N.B. what does not happen here:
            //1) the relationship between an MTSBeacon and BluetoothPeripheral
            //2) bluetoothConnectionEventOccurred(...) callback
            //Why: completion of the characteristic discovery process is when
            //the beacon is ready for interaction.  Defer these until then.

            // If a deployment includes only beacons which support machineInfoServiceUUID, assign
            // allCharacteristics rather than the partial set here.
            requiredCharacteristics = new ArrayList<ParcelUuid>(Arrays.asList(
                    cardDataCharacteristicUUID,
                    terminalKindCharacteristicUUID,
                    userDisconnectedCharacteristicUUID
                    // N.B. requiring txAttenuationLevel breaks compatibility with some Resorts fw instances.
            ));
            peripheral.requestMtu(256);
        }

        @Override
        public void onConnectionFailed(BluetoothPeripheral peripheral, final int status) {
            //Timber.e("connection '%s' failed with status %d", peripheral.getName(), status);
        }

        @Override
        public void onDisconnectedPeripheral(final BluetoothPeripheral peripheral, final int status) {
            Log.v("","onDisconnectedPeripheral "+peripheral.getName()+" with status: "+status);
            MTSBeacon mtsBeacon = connectedMTSBeaconFromPeripheral(peripheral);
            if (null != mtsBeacon) {
                connectedMTSBeacons.remove(mtsBeacon);
            } else {
                Log.v("","onDisconnectedPeripheral called for beacon already absent from connectedMTSBeacons.");
            }
            bluetoothConnectionEventOccurred(BluetoothConnectionEvent.disconnect, mtsBeacon);
        }

        @Override
        public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {
            stopScanRestartTimer();

            if (BluetoothDiscoveryState.scanning != bluetoothDiscoveryState) {
                System.out.println("onDiscoveredPeripheral " + peripheral.getName() + " returning early at BluetoothDiscoveryState.scanning != bluetoothDiscoveryState.");
                return;
            }

            if (kRSSIUnavailableValue == scanResult.getRssi()) {
                // Discard discovery events when the RSSI value is not available: the focus of this app is
                // using duplicate discoveries to monitor RSSI changes, so a discovery without RSSI is not useful.
                System.out.println("onDiscoveredPeripheral " + peripheral.getName() + " returning early at kRSSIUnavailableValue == scanResult.getRssi().");
                return;
            }

            MTSBeacon beacon = new MTSBeacon(peripheral, scanResult, MTSService.this);
            addOrUpdateBeacon(beacon, scanResult);
            evaluateVsAutoConnectThreshold();
        }

        @Override
        public void onBluetoothAdapterStateChanged(int state) {
            if(state == BluetoothAdapter.STATE_ON) {
                changeBluetoothDiscoveryState(BluetoothDiscoveryState.inactive);
            } else {
                clearBeaconState();
                changeBluetoothDiscoveryState(BluetoothDiscoveryState.notReady);
            }
        }
    };

    private void updateDiscoveryStateBasedOnAdapterState() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (null == bluetoothAdapter) {
            return;
        }
        if (BluetoothAdapter.STATE_ON == bluetoothAdapter.getState()) {
            changeBluetoothDiscoveryState(BluetoothDiscoveryState.inactive);
        } else {
            changeBluetoothDiscoveryState(BluetoothDiscoveryState.notReady);
        }
    }

    private void clearBeaconState() {
        detectedBeacons = new ArrayList<MTSBeacon>();
        connectedMTSBeacons = new ArrayList<MTSBeacon>();
        bluetoothConnectionEventOccurred(BluetoothConnectionEvent.disabled, null);
    }

    private MTSBeacon connectedMTSBeaconFromPeripheral(BluetoothPeripheral peripheral) {
        for (MTSBeacon beacon : connectedMTSBeacons) {
            if (peripheral.equals(beacon.peripheral)) {
                return beacon;
            }
        }
        return null;
    }

    private void handleOnCharacteristicChanged(BluetoothPeripheral peripheral, BluetoothGattCharacteristic characteristic, byte[] value) {

        MTSBeacon mtsBeacon = connectedMTSBeaconFromPeripheral(peripheral);

        if (null == mtsBeacon) {
            Log.v(TAG, "handleOnCharacteristicChanged: null == mtsBeacon, no match in connectedMTSBeaconFromPeripheral.");
            return;
        }

        byte[] characteristicBytes = characteristic.getValue();

        if (null == characteristicBytes) {
            Log.v(TAG, "handleOnCharacteristicChanged: null == characteristicValue");
            return;
        }

        UUID characteristicUUID = characteristic.getUuid();
        if (terminalKindCharacteristicUUID.getUuid().equals(characteristicUUID)) {
            String terminalKind = "- - -";
            terminalKind = new String(value, Charset.forName("UTF-8"));
            Log.v(TAG, "terminalKind: " + terminalKind);
            MTSBeaconEvent messageEvent = new MTSBeaconEvent(
                    MTSEventType.didReceiveTerminalKind,
                    terminalKind,
                    mtsBeacon
            );
            EventBus.getDefault().post(messageEvent);
        }
        else if (cardDataCharacteristicUUID.getUuid().equals(characteristicUUID)) {
            Serializable cardData;
            if(null != value) {
                cardData = new String(value, Charset.forName("UTF-8"));
                System.out.println("handleOnCharacteristicChanged cardDataCharacteristicUUID cardDataString: " + cardData + " hex: " + bytesToHex(value));
            } else {
                cardData = null;
                System.out.println("handleOnCharacteristicChanged cardDataCharacteristicUUID ");
            }
            MTSBeaconEvent messageEvent = new MTSBeaconEvent(
                    MTSEventType.didReceiveCardData,
                    cardData,
                    mtsBeacon
            );
            EventBus.getDefault().post(messageEvent);
        }
        else if (userDisconnectedCharacteristicUUID.getUuid().equals(characteristicUUID)) {
            // Even with BLEssed queueing, Android doesn't handling an immediate disconnect after writing,
            // the intent characteristic as iOS does.  Wait to allow the receiving device to receive.
            System.out.println("handleOnCharacteristicChanged userDisconnectedCharacteristicUUID");
            disconnectIfNeeded(mtsBeacon);
            bluetoothConnectionEventOccurred(BluetoothConnectionEvent.pendingUserDisconnect, mtsBeacon);
        }
        else if (sasSerialNumberCharacteristicUUID.getUuid().equals(characteristicUUID)) {
            String sasSerialNumber = "- - -";
            sasSerialNumber = new String(value, Charset.forName("UTF-8"));
            Log.v(TAG, "sasSerialNumber: " + sasSerialNumber);
            MTSBeaconEvent messageEvent = new MTSBeaconEvent(
                    MTSEventType.didReceiveSasSerialNumber,
                    sasSerialNumber,
                    mtsBeacon
            );
            EventBus.getDefault().post(messageEvent);
        }

        else if (locationCharacteristicUUID.getUuid().equals(characteristicUUID)) {
            String location = "- - -";
            location = new String(value, Charset.forName("UTF-8"));
            Log.v(TAG, "location: " + location);
            MTSBeaconEvent messageEvent = new MTSBeaconEvent(
                MTSEventType.didReceiveLocation,
                location,
                mtsBeacon
            );
            EventBus.getDefault().post(messageEvent);
        }

        else if (assetNumberCharacteristicUUID.getUuid().equals(characteristicUUID)) {
            Integer assetNumber = 0;
            ByteBuffer byteBuffer = ByteBuffer.wrap(value);
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
            assetNumber = byteBuffer.getInt();
            Log.v(TAG, "assetNumber: " + assetNumber);
            MTSBeaconEvent messageEvent = new MTSBeaconEvent(
                MTSEventType.didReceiveAssetNumber,
                assetNumber,
                mtsBeacon
            );
            EventBus.getDefault().post(messageEvent);
        }

        else if (denominationCharacteristicUUID.getUuid().equals(characteristicUUID)) {
            Integer denomination = 0;
            ByteBuffer byteBuffer = ByteBuffer.wrap(value);
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
            denomination = byteBuffer.getInt();
            MTSBeaconEvent messageEvent = new MTSBeaconEvent(
                    MTSEventType.didReceiveDenomination,
                denomination,
                mtsBeacon
            );
            EventBus.getDefault().post(messageEvent);
        }

        else if (gmiLinkActiveCharacteristicUUID.getUuid().equals(characteristicUUID)) {
            boolean isGmiLinkActive = false;
            isGmiLinkActive = (value[0] == (byte)0x01);
            mtsBeacon.isGmiLinkActive = isGmiLinkActive;
            Log.v(TAG, "isGmiLinkActive: " + isGmiLinkActive);
            MTSBeaconEvent messageEvent = new MTSBeaconEvent(
                MTSEventType.didReceiveGmiLinkActive,
                isGmiLinkActive,
                mtsBeacon
            );
            EventBus.getDefault().post(messageEvent);
        }

        else if (txAttenLevelCharacteristicUUID.getUuid().equals(characteristicUUID)) {
            byte txAttenuationLevel = value[0];
            MTSBeaconEvent messageEvent = new MTSBeaconEvent(
                    MTSEventType.didReceiveTxAttenuationLevel,
                    txAttenuationLevel,
                    mtsBeacon
            );
            EventBus.getDefault().post(messageEvent);
        }
    }


    // Bluetooth

    public void startScanning() {
        changeBluetoothDiscoveryState(BluetoothDiscoveryState.scanning);
    }

    public void stopScanning() {
        changeBluetoothDiscoveryState(BluetoothDiscoveryState.inactive);
    }

    public void disconnect(MTSBeacon mtsBeacon) {
        byte[] bytes = new byte[]{1} ;
        writeCharacteristic(userDisconnectedCharacteristicUUID, bytes, WRITE_TYPE_DEFAULT, mtsBeacon);
    }

    public void readCharacteristic(ParcelUuid characteristicUUID, MTSBeacon mtsBeacon) {

        if (null == mtsBeacon) {
            Log.v("","readCharacteristic failed at if (null == mtsBeacon).");
            return;
        }

        if (null == mtsBeacon.peripheral) {
            Log.v("","readCharacteristic failed at if (null == mtsBeacon.peripheral).");
            return;
        }

        BluetoothGattCharacteristic characteristic;
        characteristic = mtsBeacon.peripheral.getCharacteristic(mtsServiceUUID, characteristicUUID.getUuid());
        if (null == characteristic) {
            characteristic = mtsBeacon.peripheral.getCharacteristic(machineInfoServiceUUID, characteristicUUID.getUuid());
        }
        if (null == characteristic) {
            Log.v("", "readCharacteristic failed at null == characteristic for uuid: " + characteristicUUID.getUuid());
            return;
        }

        mtsBeacon.peripheral.readCharacteristic(characteristic);
    }

    public void writeCharacteristic(ParcelUuid characteristicUUID, final byte[] value, int writeType, MTSBeacon mtsBeacon) {

        if (null == mtsBeacon) {
            Log.v("","writeCharacteristic failed at if (null == mtsBeacon).");
            return;
        }

        BluetoothGattCharacteristic characteristic;
        characteristic = mtsBeacon.peripheral.getCharacteristic(mtsServiceUUID, characteristicUUID.getUuid());
        if (null == characteristic) {
            characteristic = mtsBeacon.peripheral.getCharacteristic(machineInfoServiceUUID, characteristicUUID.getUuid());
        }
        if (null == characteristic) {
            Log.v("", "writeCharacteristic failed at null == characteristic for uuid: " + characteristicUUID.getUuid());
            return;
        }

        mtsBeacon.peripheral.writeCharacteristic(characteristic, value, writeType);
        Log.v("","writeCharacteristic complete for " + characteristicUUID.toString() + " with data: " + bytesToHex(value) );
    }

    public void requestTerminalKind(MTSBeacon mtsBeacon) {
        readCharacteristic(terminalKindCharacteristicUUID, mtsBeacon);
    }

    public void requestCardData(MTSBeacon mtsBeacon) {
        readCharacteristic(cardDataCharacteristicUUID, mtsBeacon);
    }

    public Boolean writeCardDataToBluetooth(String cardDataString, MTSBeacon mtsBeacon) {

        byte[] data = validatedCardData(cardDataString);

        System.out.println("writeCardDataToBluetooth data: " + bytesToHex(data));

        if (null == data) {
            System.out.println("writeCardDataToBluetooth failed at null == data.");
            return false;
        }

        writeCharacteristic(cardDataCharacteristicUUID, data, WRITE_TYPE_DEFAULT, mtsBeacon);

        return true;
    }

    public void writeTxAttenuationLevel(TxAttenuationLevel level, MTSBeacon mtsBeacon) {
        byte b = (byte)level.value;
        byte[] bytes = new byte[]{b} ;
        writeCharacteristic(txAttenLevelCharacteristicUUID, bytes, WRITE_TYPE_DEFAULT, mtsBeacon);
    }

    final private static char[] hexArray = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    private byte[] validatedCardData(String cardDataString) {
        System.out.println("validatedCardData cardDataString: " + cardDataString);
        String truncatedCardDataString = cardDataString.substring(0, Math.min(cardDataString.length(), cardDataCharacterCountMax));
        System.out.println("validatedCardData truncatedCardDataString: " + truncatedCardDataString);
        byte[] cardDataBytes = truncatedCardDataString.getBytes(Charset.forName("UTF-8"));
        System.out.println("validatedCardData cardDataBytes: " + bytesToHex(cardDataBytes));
        byte[] nullBytes = new byte[0];
        byte[] terminatedBytes = new byte[cardDataBytes.length + nullBytes.length];
        System.arraycopy(cardDataBytes, 0, terminatedBytes, 0, cardDataBytes.length);
        System.arraycopy(nullBytes, 0, terminatedBytes, cardDataBytes.length, nullBytes.length);
        System.out.println("validatedCardData terminatedBytes: " + bytesToHex(terminatedBytes));
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
                System.out.println("");
                if (BluetoothDiscoveryState.scanning == bluetoothDiscoveryState) {
                    stopScanning();
                }
            }
        }, scanTimeoutInterval() * 1000);
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
        if (BluetoothDiscoveryState.scanning != bluetoothDiscoveryState) {
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
                if (BluetoothDiscoveryState.scanning != bluetoothDiscoveryState) {
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

    private void connect(MTSBeacon mtsBeacon) {
        if (connectedMTSBeacons.contains(mtsBeacon)) {
            Log.v("","called for existing member mtsBeacon, returning early.");
        } else {
            connectedMTSBeacons.add(mtsBeacon);
        }
        stopScan();
        stopScanRestartTimer();
        stopExpirationTimer();
        clearDiscoveredBeacons();
        central.connectPeripheral(mtsBeacon.peripheral, peripheralCallback);
    }

    private void disconnectIfNeeded(MTSBeacon mtsBeacon) {
        Log.v(TAG, "disconnectIfNeeded");
        if (null != mtsBeacon) {
            mtsBeacon.peripheral.cancelConnection();
            // Beacons frequently fail to disconnect properly; wait until disconnect callback to
            // remove from connectedMTSBeacons.
        } else {
            Log.v(TAG, "disconnectIfNeeded called while null == connectedMTSBeacon");
        }
    }

    private void autoConnectThresholdCrossed(MTSBeacon mtsBeacon) {
        if (!autoConnectThresholdEnabled()) {
            return;
        }
        connect(mtsBeacon);
    }

    private void evaluateVsAutoConnectThreshold() {

        MTSBeacon beacon = highestRSSIBeacon();
        if (null == beacon) {
            System.out.println("evaluateVsAutoConnectThreshold null == beacon");
            return;
        }

        if (beacon.filteredRSSI > autoConnectRSSIThreshold()) {
            autoConnectThresholdCrossed(beacon);
            return;
        } else {
            System.out.println("beacon.filteredRSSI: " + beacon.filteredRSSI + "autoConnectRSSIThreshold(): " + autoConnectRSSIThreshold());
            //Timber.i("evaluateVsAutoConnectThreshold beacon.filteredRSSI %s !> autoConnectRSSIThreshold() %s.", beacon.filteredRSSI, autoConnectRSSIThreshold());
        }
    }

    private void evaluateVsAutoDisconnectThreshold(int rssi, MTSBeacon mtsBeacon) {
        if (!autoDisconnectThresholdEnabled()) {
            return;
        }
        if (autoDisconnectRSSIThreshold() > rssi) {
            if (mtsBeacon.isAutoDisconnectTimerActive) {
                // AutoDisconnectTimer is already running, don't restart it.
                Log.v("","evaluateVsAutoDisconnectThreshold: " + rssi +" returning early since AutoDisconnectTimer is already running, don't restart it.");
                return;
            }
            startAutoDisconnectCountdown(mtsBeacon);
        } else {
            stopAutoDisconnectCountdown(mtsBeacon);
        }
    }

    void autoDisconnectThresholdCrossed(MTSBeacon mtsBeacon) {
        disconnectIfNeeded(mtsBeacon);
    }

    private void startAutoDisconnectCountdown(final MTSBeacon mtsBeacon) {
        mtsBeacon.isAutoDisconnectTimerActive = true;
        mtsBeacon.autoDisconnectTimeoutHandler.postDelayed(new Runnable()
        {
            public void run()
            {
                autoDisconnectThresholdCrossed(mtsBeacon);
            }
        }, autoDisconnectInterval() * 1000);
    }

    private void stopAutoDisconnectCountdown(MTSBeacon mtsBeacon) {
        mtsBeacon.autoDisconnectTimeoutHandler.removeCallbacksAndMessages(null);
        mtsBeacon.isAutoDisconnectTimerActive = false;
    }

    // Connected RSSI read.  Supports auto-disconnect and interface RSSI display.
    private Handler connectedRSSIReadHandler = new android.os.Handler();

    // Even with the queued commands, a tight loop of connectedMTSBeacons fails to return RSSI reads
    // for all but the first beacon.  Cycle through each beacon in turn.
    int rssiReadTurn = 0;
    private Runnable connectedRSSIReadRunnable = new Runnable() {
        public void run() {
            int size = connectedMTSBeacons.size();
            if (rssiReadTurn >= size) { rssiReadTurn = 0; }
            MTSBeacon mtsBeacon = connectedMTSBeacons.get(rssiReadTurn);
            mtsBeacon.peripheral.readRemoteRssi();
            rssiReadTurn++;
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
        central.startPairingPopupHack();
        central.scanForPeripheralsWithServices(new UUID[]{mtsServiceUUID});
    }

    private void stopScan() {
        if (null == central) {
            return;
        }
        central.stopScan();
    }

    public void changeBluetoothDiscoveryState(BluetoothDiscoveryState newState) {
        if (newState == bluetoothDiscoveryState) {
            return;
        }
        BluetoothDiscoveryState oldState = bluetoothDiscoveryState;
        bluetoothDiscoveryState = newState;
        Log.v(TAG, "changeBluetoothDiscoveryState from: " + oldState.toString() + " to: " + newState.toString());

        stopScanTimeoutTimer();
        stopConnectedRSSIReads();

        switch (bluetoothDiscoveryState) {
            case notReady:
                stopScan();
                stopScanRestartTimer();
                stopExpirationTimer();
                stopScanTimeoutTimer();
                break;
            case inactive:
                stopScan();
                stopScanRestartTimer();
                stopExpirationTimer();
                stopScanTimeoutTimer();
                break;
            case scanning:
                startScan();
                startRestartTimer();
                startScanTimeoutTimer();
                startExpirationTimer();
                break;
        }
        Log.v("","changeBluetoothDiscoveryState, sending event...");
        MTSBluetoothDiscoveryStateEvent messageEvent = new MTSBluetoothDiscoveryStateEvent(
            oldState,
            newState
        );
        EventBus.getDefault().post(messageEvent);
    }

    // N.B. scanning is required to stop upon connect.  This is a change from prior behavior where disconnect would
    // transition to scanning without user intervention.
    private void bluetoothConnectionEventOccurred(BluetoothConnectionEvent bluetoothConnectionEvent, MTSBeacon mtsBeacon) {
        //TODO: mtsBeacon.autoDisconnectTimeoutHandler.removeCallbacks();
        if (0 == connectedMTSBeacons.size()) {
            stopConnectedRSSIReads();
        }
        switch (bluetoothConnectionEvent) {
            case connect:
                changeBluetoothDiscoveryState(BluetoothDiscoveryState.inactive);
                startConnectedRSSIReads();
                break;
            case pendingUserDisconnect:
                changeBluetoothDiscoveryState(BluetoothDiscoveryState.inactive);
                break;
            case disconnect:

                break;
            case disabled:
                changeBluetoothDiscoveryState(BluetoothDiscoveryState.notReady);
                break;
        }
        MTSBluetoothConnectionEvent messageEvent = new MTSBluetoothConnectionEvent(
                bluetoothConnectionEvent,
                mtsBeacon
        );
        Log.v("","bluetoothConnectionEventOccurred, sending event...");
        EventBus.getDefault().post(messageEvent);
    }

}
