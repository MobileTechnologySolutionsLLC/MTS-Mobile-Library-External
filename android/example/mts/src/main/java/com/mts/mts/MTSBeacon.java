package com.mts.mts;

// Copyright © 2020 Mobile Technology Solutions, Inc. All rights reserved.


import android.annotation.SuppressLint;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static android.content.ContentValues.TAG;

public class MTSBeacon {

    BluetoothPeripheral peripheral;
    public String address;
    public String name;
    public int rssi;
    public byte[] scanRecordBytes;
    public Boolean isSelected = false;
    public Boolean isGmiLinkActive = false;
    private String noValuePlaceholder = "- - -";
    public long firstDiscoveredAt;
    public long lastDiscoveredAt;
    public long elapsedSinceLastDiscovered;
    public int filteredRSSI;

    /// Uniquely identifies this MTSBeacon across Android and iOS instances.
    public String mtsIdentifier;
    private Context context;
    // Used by the MTSManager to handle RSSI threshold disconnect evaluation for this beacon
    public Boolean isCharacteristicDiscoveryComplete = false;

    MTSBeacon(BluetoothPeripheral peripheral, ScanResult scanResult, Context c) {
        this.peripheral = peripheral;
        scanRecordBytes = scanResult.getScanRecord().getBytes();

        context = c;
        if (null == scanRecordBytes) {
            Log.v("","MTSBeacon init returned early due to null == scanRecordBytes.");
            return;
        }
        firstDiscoveredAt = SystemClock.elapsedRealtime();
        lastDiscoveredAt = firstDiscoveredAt;
        commonScanResultAssignments(scanResult);
        filteredRSSI = rssi;

        // 0201060B09475420436F6E6E65637409FFFFFF00A050DD692900000000000000000000000000000000000000000000000000000000000000000000000000
        // 0201060B09475420436F6E6E65637409FFFFFF
        //                                       00A050 kCompanyIdentifier
        //                                             DD692900000000000000000000000000000000000000000000000000000000000000000000000000
        byte[] b = scanRecordBytes;
        int lengthToEndOfCompanyId = 29;
        if (b.length < lengthToEndOfCompanyId) {
            return;
        }
    }

    public void updateOnDiscovery(ScanResult scanResult) {
        scanRecordBytes = scanResult.getScanRecord().getBytes();
        rssi = scanResult.getRssi();
        long now = SystemClock.elapsedRealtime();
        if (lastDiscoveredAt > 0) {
            elapsedSinceLastDiscovered = now - lastDiscoveredAt;
        }
        lastDiscoveredAt = now;
        commonScanResultAssignments(scanResult);
        filteredRSSI = calculateFilteredRSSI(rssi);
    }

    @SuppressLint("MissingPermission")
    private void commonScanResultAssignments(ScanResult scanResult) {

        name = scanResult.getDevice().getName();

        if (name== null) {
            name = noValuePlaceholder;
        }
        rssi = scanResult.getRssi();
        address =  scanResult.getDevice().getAddress();

        // Duplicate the six-byte range iOS uses from CBAdvertisementDataManufacturerDataKey:
        // * second byte of mfg data key as hex string concatenated with
        // * manufacturerSpecificData-minus-last-byte as hex string.
        SparseArray<byte[]> manufacturerSpecificData = scanResult.getScanRecord().getManufacturerSpecificData();
        if (0 == manufacturerSpecificData.size()) {
            return;
        }
        int key = manufacturerSpecificData.keyAt(0);
        byte[] manufacturerSpecificDataBytes = manufacturerSpecificData.valueAt(0);
        int shiftedKey = key >> Byte.SIZE;
        String keyByteString =  String.format("%02X", shiftedKey);
        byte[] truncated = Arrays.copyOf(manufacturerSpecificDataBytes, manufacturerSpecificDataBytes.length-1);
        String payloadString =  bytesToHex(truncated);
        mtsIdentifier = keyByteString + payloadString;
    }

    public void updateOnConnectedRSSIReceipt(int r) {
        // This updates at an interval determined by the app (1 second), so doesn't need filtering + elapsedSinceLastDiscovered aspect isn't relevant.
        rssi = r;
        filteredRSSI = rssi;
    }

    public int calculateFilteredRSSI(int newRSSI) {
        double kMaxAverageFactor = 0.8;
        double k = Math.min(elapsedSinceLastDiscovered, kMaxAverageFactor);
        double filtered = (1 - k) * (double)filteredRSSI + k * (double)newRSSI;
        return (int)filtered;
    }

    final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    // AutoDisconnectCountdown - RSSI threshold + interval triggers disconnect.
    Handler autoDisconnectTimeoutHandler = new Handler();
    Boolean isAutoDisconnectTimerActive = false;


}
