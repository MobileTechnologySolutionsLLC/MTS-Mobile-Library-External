package com.mts.mts;

import java.io.Serializable;
import java.util.UUID;

public class MTSBluetoothConnectionEvent {
    public final MTSService.BluetoothConnectionEvent connectionEvent;
    public final MTSBeacon mtsBeacon;

    public MTSBluetoothConnectionEvent(MTSService.BluetoothConnectionEvent connectionEvent, MTSBeacon mtsBeacon) {
        this.connectionEvent = connectionEvent;
        this.mtsBeacon = mtsBeacon;
    }
}
