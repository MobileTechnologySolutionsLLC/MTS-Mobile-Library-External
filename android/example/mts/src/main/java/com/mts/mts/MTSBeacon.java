package com.mts.mts;

// Copyright (c) 2017 Greektown Casino, L.L.C.. All rights reserved.


import android.content.Context;
import android.os.SystemClock;

import com.idevicesinc.sweetblue.BleDevice;
import com.idevicesinc.sweetblue.BleManager;

import java.util.Arrays;

public class MTSBeacon {

    public BleDevice device;
    public String address;
    public String name;
    public int rssi;
    public byte[] scanRecordBytes;
    public Boolean isSelected = false;
    public Boolean wantsStickyConnection = false;
    private String noValuePlaceholder = "- - -";
    public long firstDiscoveredAt;
    public long lastDiscoveredAt;
    public long elapsedSinceLastDiscovered;
    public int filteredRSSI;
    public String manufacturerDataString;
    private Context context;

    MTSBeacon(BleManager.DiscoveryListener.DiscoveryEvent event, Context c) {
        scanRecordBytes = event.device().getScanRecord();
        context = c;
        if (null == scanRecordBytes) {
            return;
        }
        firstDiscoveredAt = SystemClock.elapsedRealtime();
        lastDiscoveredAt = firstDiscoveredAt;
        commonScanResultAssignments(event);
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

    public void updateOnDiscovery(BleManager.DiscoveryListener.DiscoveryEvent event) {
        scanRecordBytes = event.device().getScanRecord();
        rssi = event.rssi();
        long now = SystemClock.elapsedRealtime();
        if (lastDiscoveredAt > 0) {
            elapsedSinceLastDiscovered = now - lastDiscoveredAt;
        }
        lastDiscoveredAt = now;
        commonScanResultAssignments(event);
        filteredRSSI = calculateFilteredRSSI(rssi);
    }

    private void commonScanResultAssignments(BleManager.DiscoveryListener.DiscoveryEvent event) {
        name = event.device().getName_normalized();
        device = event.device();
        if (name== null) {
            name = noValuePlaceholder;
        }
        rssi = event.rssi();
        address =  event.macAddress();
        //                                       FFFF00A050DD6929 // match the iOS value for CBAdvertisementDataManufacturerDataKey
        // 0201060E09477265656B746F776E20424C4509FFFFFF00A050DD692900000000000000000000000000000000000000000000000000000000000000000000
        byte[] b = scanRecordBytes;
        int lengthToEndOfMfgData = 29;
        if (b.length < lengthToEndOfMfgData) {
            return;
        }
        byte[] manufacturerData = Arrays.copyOfRange(b, 20, 28);
        manufacturerDataString = bytesToHex(manufacturerData);
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
}
