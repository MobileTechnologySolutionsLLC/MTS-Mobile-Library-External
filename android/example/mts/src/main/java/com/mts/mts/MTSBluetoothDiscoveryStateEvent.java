package com.mts.mts;

import java.io.Serializable;
import java.util.UUID;

public class MTSBluetoothDiscoveryStateEvent {
    public final MTSService.BluetoothDiscoveryState oldState;
    public final MTSService.BluetoothDiscoveryState newState;

    public MTSBluetoothDiscoveryStateEvent(MTSService.BluetoothDiscoveryState oldState, MTSService.BluetoothDiscoveryState newState) {
        this.oldState = oldState;
        this.newState = newState;
    }
}
