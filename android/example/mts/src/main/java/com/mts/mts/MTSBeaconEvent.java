package com.mts.mts;

import java.io.Serializable;
import java.util.UUID;

public class MTSBeaconEvent {
    public final MTSService.MTSEventType eventType;
    public final Serializable value;
    public final MTSBeacon mtsBeacon;
//    public final byte[] bytes;

    public MTSBeaconEvent(MTSService.MTSEventType eventType, @android.support.annotation.Nullable Serializable value, MTSBeacon mtsBeacon) {
        this.eventType = eventType;
        this.value = value;
//        this.bytes = bytes;
        this.mtsBeacon = mtsBeacon;
    }
}
