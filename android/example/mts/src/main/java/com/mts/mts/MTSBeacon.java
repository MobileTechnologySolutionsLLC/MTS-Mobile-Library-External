package com.mts.mts;

// Copyright © 2020 Mobile Technology Solutions, Inc. All rights reserved.


import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.util.Arrays;

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
    public String mfgIdentifier;
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

    private void commonScanResultAssignments(ScanResult scanResult) {
//        device = scanResult.getDevice();
        name = scanResult.getDevice().getName();

        if (name== null) {
            name = noValuePlaceholder;
        }
        rssi = scanResult.getRssi();
        address =  scanResult.getDevice().getAddress();
        //                                       FFFF00A050DD6929 // match the iOS value for CBAdvertisementDataManufacturerDataKey
        // 0201060E09477265656B746F776E20424C4509FFFFFF00A050DD692900000000000000000000000000000000000000000000000000000000000000000000
        byte[] b = scanRecordBytes;
        int lengthToEndOfMfgData = 29;
        if (b.length < lengthToEndOfMfgData) {
            return;
        }
        byte[] manufacturerData = Arrays.copyOfRange(b, 11, 17);
        mfgIdentifier = bytesToHex(manufacturerData);
        Log.v("", "mfgIdentifier: " + mfgIdentifier);
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
