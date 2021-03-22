//
//
//  Copyright © 2021 Mobile Technology Solutions, Inc. All rights reserved.
//

import UIKit
import CoreBluetooth

public enum BluetoothDiscoveryState {
    case notReady
    case inactive
    case scanning
}

public enum BluetoothConnectionEvent {
    case connect
    case pendingUserDisconnect
    case disconnect
}

public protocol MTSManagerDelegate {
    func bluetoothConnectionEvent(bluetoothEvent: BluetoothConnectionEvent, mtsBeacon: MTSBeacon?)
    func bluetoothDiscoveryStateChanged(oldState: BluetoothDiscoveryState, newState: BluetoothDiscoveryState)
    func receivedTerminalId(terminalId: String, mtsBeacon: MTSBeacon)
    func receivedCardData(cardData: Data, mtsBeacon: MTSBeacon)
    func didWriteCardDataToBluetooth(error: Error?, mtsBeacon: MTSBeacon)
    func receivedTerminalKind(kind: MTSBeacon.TerminalKind, mtsBeacon: MTSBeacon)
    func updateOnConnectedRSSIReceipt(rssi: Int, mtsBeacon: MTSBeacon)
    func receivedSasSerialNumber(serialNumber: String?, mtsBeacon: MTSBeacon)
    func receivedLocation(location: String?, mtsBeacon: MTSBeacon)
    func receivedAssetNumber(assetNumber: UInt32, mtsBeacon: MTSBeacon)
    func receivedDenomination(denomination: UInt32, mtsBeacon: MTSBeacon)
    func receivedGmiLinkActive(isActive: Bool, mtsBeacon: MTSBeacon)
    func receivedTxAttenuationLevel(level: TxAttenuationLevel, mtsBeacon: MTSBeacon)
}

public extension MTSManagerDelegate {
    func bluetoothConnectionEvent(bluetoothEvent: BluetoothConnectionEvent, mtsBeacon: MTSBeacon?){}
    func bluetoothDiscoveryStateChanged(oldState: BluetoothDiscoveryState, newState: BluetoothDiscoveryState){}
    func receivedTerminalId(terminalId: String, mtsBeacon: MTSBeacon){}
    func receivedCardData(cardData: Data, mtsBeacon: MTSBeacon){}
    func didWriteCardDataToBluetooth(error: Error?, mtsBeacon: MTSBeacon){}
    func receivedTerminalKind(kind: MTSBeacon.TerminalKind, mtsBeacon: MTSBeacon){}
    func updateOnConnectedRSSIReceipt(rssi: Int, mtsBeacon: MTSBeacon){}
    func receivedSasSerialNumber(serialNumber: String?, mtsBeacon: MTSBeacon){}
    func receivedLocation(location: String?, mtsBeacon: MTSBeacon){}
    func receivedAssetNumber(assetNumber: UInt32, mtsBeacon: MTSBeacon){}
    func receivedDenomination(denomination: UInt32, mtsBeacon: MTSBeacon){}
    func receivedGmiLinkActive(isActive: Bool, mtsBeacon: MTSBeacon){}
    func receivedTxAttenuationLevel(level: TxAttenuationLevel, mtsBeacon: MTSBeacon){}
}

struct MTSConstants {
    static let noValuePlaceholder = "- - -"
    static let rssiUnavailableValue = 127 // Reserved per CBCentralManager.h
}

enum MTSError: Error {
    case invalidCardDataCharacterCount(Int)
    case writeFailure(description: String)
}

public enum TxAttenuationLevel: UInt8, CaseIterable {
    case zero  = 0
    case one   = 1
    case two   = 2
    case three = 3
}

public class MTSManager: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    
    // MARK: Public Properties
    
    public static let sharedInstance = MTSManager()
    public let delegate = MulticastDelegate<MTSManagerDelegate>()
    public var bluetoothDiscoveryState = BluetoothDiscoveryState.notReady
    public var loggingEnabled = true
    public let cardDataCharacterCountMax = 195 // 195 + automatic null termination, so 196 total accepted by the peripheral.
    
    // MARK: Private Properties
    
    private var centralManager: CBCentralManager!
    private let bluetoothDispatchQueue = DispatchQueue(label: "com.mts.example.bluetooth")
    private var requiredCharacteristics = [MTSCharacteristic]()
    public var connectedMTSBeacons = [MTSBeacon]()
    private var discoveredBeacons = [MTSBeacon]()
    private var serviceUUID: CBUUID?
    
    private var scanRefreshTimer = Timer()
    private let kScanRefreshInterval = 5.0
    
    private var scanTimeoutTimer = Timer()
    private let kScanTimeoutEvalInterval = 1.0
    
    private var expirationTimer = Timer()
    private let expirationInterval: CFTimeInterval = 5.0
    private let expirationEvalInterval: CFTimeInterval = 1.0

    private var rssiRefreshWhileConnectedTimer = Timer()
    private let rssiRefreshWhileConnectedInterval: CFTimeInterval = 1.0

    private let autoDisconnectEvaluationInterval: CFTimeInterval = 1.0

    private let maxRSSIThresholdValue = 0
    private let minRSSIThresholdValue = -100
    
    private enum UserDefaultKey: String {
        case autoConnectEnabled
        case autoConnectThreshold
        case autoDisconnectEnabled
        case autoDisconnectThreshold
        case autoDisconnectInterval
        case scanTimeoutInterval
        case cardData
        case sentinelState
    }
    
    private enum MTSService: String {
        case machineInfoSvc     = "C83FE52E-0AB5-49D9-9817-98982B4C48A3"
        var uuid: CBUUID {
            return CBUUID(string: self.rawValue)
        }
    }
    
    private enum MTSCharacteristic: String {
        case cardData           = "60D11359-FEB2-411D-A430-CA6167052BD6"
        case stickyConnect      = "4B6A91D8-EA3E-42A4-B39B-B300F5F64C86"    // For potential future use, not currently supported.
        case terminalKind       = "D308DFDE-9F06-4A73-A2C7-EB952E40A184"
        case userDisconnected   = "4E3A829D-4830-47A0-995F-EE923710A469"
        case sasSerialNumber    = "9D77E2CF-5D20-44EA-8D2F-A221B976C605"    // utf8s[41], read-only, 40 characters + required null termination.
        case location           = "42C458D7-86B9-4ED8-B57E-1352C7F5100A"    // utf8s[41], read-only, 40 characters + required null termination.
        case assetNumber        = "D77A787D-E75D-4370-8CAC-6DCFE37DBB92"    // uint32, read-only.
        case denomination       = "7B9432C6-465A-40FA-A13B-03544B6F0742"    // uint32, read-only, unit is cents.
        case gmiLinkActive      = "023B4A4A-579C-495F-A61E-D3BBBFD63C4A"    // bool, read/notify, cardreader's link state for it's GMI interface as active (0x01) or inactive (0x00).
        case txAttenuationLevel = "51D25B72-68BB-4022-9F71-0CC3DD23A032"    // uint8, read/write, change the attenuation on the device transmitter.  Allowable range is `0x00` = no attenuation through `0x03` = max attenuation (approx. -18dB).
                
        var uuid: CBUUID {
            return CBUUID(string: self.rawValue)
        }
        // TODO: Evaluate whether prior fw is in use which will not be updated to include all
        // characteristics.  If all deployed mtsBeacons will support all characteristics, it
        // is preferable to include them all in this list.  Why: ensure characteristic
        // discovery completes prior to read/write attempts.
        static let requiredCharacteristics = [cardData, terminalKind, userDisconnected, txAttenuationLevel]
    }

    // MARK: Public Methods
    
    public func setServiceUUID(uuidString: String) {
        serviceUUID = CBUUID(string: uuidString)
    }
    
    public func startScanning() {
        changeBluetoothDiscoveryState(newState: .scanning)
    }

    public func stopScanning() {
        changeBluetoothDiscoveryState(newState: .inactive)
    }
    
    public func disconnect(mtsBeacon: MTSBeacon) {
        
        mtsBeacon.isCharacteristicDiscoveryComplete = false
        
        let peripheral = mtsBeacon.peripheral
        
        guard let characteristic = characteristic(.userDisconnected, mtsBeacon: mtsBeacon) else {
            logIfEnabled("\(#function) Failed at characteristic lookup.")
            return
        }
        
        peripheral.writeValue(Data([1]), for: characteristic, type: .withResponse)
    }
    
    private func disconnectUponDidWriteofUserDisconnected(mtsBeacon: MTSBeacon) {
        disconnectIfNeeded(mtsBeacon)
        bluetoothConnectionEventOccurred(bluetoothEvent: .pendingUserDisconnect, mtsBeacon: mtsBeacon)
    }
    
    public func requestCardData(mtsBeacon: MTSBeacon) {
        readCharacteristic(.cardData, mtsBeacon: mtsBeacon)
    }
    
    
    public func writeCardDataToBluetooth(cardDataString: String, mtsBeacon: MTSBeacon) throws {
        let peripheral = mtsBeacon.peripheral
        
        guard let characteristic = characteristic(.cardData, mtsBeacon: mtsBeacon) else {
            logIfEnabled("\(#function) Failed at characteristic lookup.")
            throw MTSError.writeFailure(description: "Bluetooth characteristic lookup failed.")
        }
        
        let data = try validatedCardData(cardData: cardDataString)
        
        peripheral.writeValue(data, for: characteristic, type: .withResponse)
    }

    // https://github.com/MobileTechnologySolutionsLLC/dpc-mobile/issues/5
    // https://github.com/MobileTechnologySolutionsLLC/gt-connect/blob/develop/firmware/gt_ble/Readme.md
    // https://github.com/MobileTechnologySolutionsLLC/dpc-mobile/issues/3
    // TODO: Consider character vs byte count requirement given UTF-8.  Status quo assumes 1-1.
    private func validatedCardData(cardData: String) throws -> Data {
        guard isValidCardData(cardData) else {
            throw MTSError.invalidCardDataCharacterCount(cardDataCharacterCountMax)
        }

        let truncatedString = String(cardData.prefix(cardDataCharacterCountMax))
        guard let data = truncatedString.data(using: .utf8) else {
            throw MTSError.writeFailure(description: "String conversion to UTF-8 failed.")
        }
        let terminatedData = data + Data([0x0])
        
        // N.B. the cardData string won't log to Xcode console as a string due to %.
        logIfEnabled("\(#function) data: \(terminatedData.hex))")
        return terminatedData
    }
    
    public func isValidCardData(_ cardDataString: String) -> Bool {
        return cardDataCharacterCountMax >= cardDataString.count
    }
    
    public func writeTxAttenuationLevel(level: TxAttenuationLevel, mtsBeacon: MTSBeacon) {
        let peripheral = mtsBeacon.peripheral
        
        guard let characteristic = characteristic(.txAttenuationLevel, mtsBeacon: mtsBeacon) else {
            logIfEnabled("\(#function) Failed at characteristic lookup.")
            return
        }
        
        let data = Data([level.rawValue])
        NSLog("\(#function) level: \(level) data: \(data.hex)")
        peripheral.writeValue(data, for: characteristic, type: .withResponse)
    }
    
    //MARK: Setup
    
    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: bluetoothDispatchQueue)
        centralManager.delegate = self
        registerUserDefaults()
    }
    
    private func registerUserDefaults() {
        UserDefaults.standard.register(defaults: [
            UserDefaultKey.autoConnectThreshold.rawValue : -45,
            UserDefaultKey.autoDisconnectThreshold.rawValue: -70,
            UserDefaultKey.autoDisconnectInterval.rawValue: 1,
            UserDefaultKey.sentinelState.rawValue: 2
            ])
    }
    
    
    //MARK: BLE Connection Lifecycle
    
    private func startScan() {
        guard centralManager.state == .poweredOn else {
            logIfEnabled("\(#function) failed because centralManager.state != .poweredOn")
            changeBluetoothDiscoveryState(newState: .notReady)
            return
            
        }
        guard let serviceUUID = serviceUUID else {
            logIfEnabled("Please assign the serviceUUID provided by MTS.")
            return
        }
        centralManager.scanForPeripherals(
            withServices: [serviceUUID],
            options:[CBCentralManagerScanOptionAllowDuplicatesKey:true]
        )
    }
    
    private func stopScan() {
        guard centralManager.state == .poweredOn else {
            return
        }
        centralManager.stopScan()
    }
    
    private func startScanRefreshTimer() {
        scanRefreshTimer = Timer.scheduledTimer(withTimeInterval: kScanRefreshInterval, repeats: true, block: { (Timer) in
            self.stopScan()
            self.startScan()
        })
    }
    
    private func stopScanRefreshTimer() {
        scanRefreshTimer.invalidate()
    }
    
    private func connect(mtsBeacon: MTSBeacon) {
        guard centralManager.state == .poweredOn else {
            logIfEnabled("\(#function) failed because centralManager.state != .poweredOn")
            return
        }
        
        if let _ = connectedMTSBeacons.firstIndex(of: mtsBeacon) {
            logIfEnabled("\(#function) called for existing member mtsBeacon.peripheral: \(mtsBeacon.peripheral)")
        } else {
            connectedMTSBeacons.append(mtsBeacon)
        }
        
        stopScan()
        stopScanRefreshTimer()
        stopExpirationTimer()
        clearDiscoveredBeacons()
        centralManager.connect(mtsBeacon.peripheral, options: nil)
    }
    
    private func clearDiscoveredBeacons() {
        DispatchQueue.main.async {
            self.discoveredBeacons = [MTSBeacon]()
        }
    }
    
    private func disconnectIfNeeded(_ mtsBeacon: MTSBeacon?) {
        guard let mtsBeacon = mtsBeacon else {
            return
        }
        centralManager.cancelPeripheralConnection(mtsBeacon.peripheral)
    }
    
    private func changeBluetoothDiscoveryState(newState: BluetoothDiscoveryState) {
        if bluetoothDiscoveryState == newState {
            logIfEnabled("\(#function) returning early since new/old states are same: \(newState)")
            return
        }
        let oldState = bluetoothDiscoveryState
        bluetoothDiscoveryState = newState
        logIfEnabled("\(#function) \(oldState) -> \(newState)")

        switch bluetoothDiscoveryState {
        case .notReady:
            stopScan()
            stopScanRefreshTimer()
            stopExpirationTimer()
            stopScanTimeoutTimer()
        case .inactive:
            stopScan()
            stopScanRefreshTimer()
            stopExpirationTimer()
            stopScanTimeoutTimer()
        case .scanning:
            startScan()
            startScanRefreshTimer()
            startScanTimeoutTimer()
            startExpirationTimer()
        }
        invokeBluetoothDiscoveryStateChangedDelegate(oldState: oldState, newState: newState)
    }
    
    // N.B. scanning is required to stop upon connect.  This is a change from prior behavior where disconnect would
    // transition to scanning without user intervention.
    private func bluetoothConnectionEventOccurred(bluetoothEvent: BluetoothConnectionEvent, mtsBeacon: MTSBeacon?) {
        mtsBeacon?.autoDisconnectTimer.invalidate()
        if 0 == connectedMTSBeacons.count {
            stopRSSIRefreshWhileConnectedTimer()
        }
        changeBluetoothDiscoveryState(newState: .inactive)
        switch bluetoothEvent {
        case .connect:
            startRSSIRefreshWhileConnectedTimer()
        case .pendingUserDisconnect:
            break
        case .disconnect:
            break
        }
        invokeBluetoothBluetoothEventOccurred(bluetoothEvent: bluetoothEvent, mtsBeacon: mtsBeacon)
    }
    
        
    //MARK: Timers Active While Connected
    
    // Read connected RSSI from each connected mtsBeacon each rssiRefreshWhileConnectedInterval.
    // N.B. this is tested with two beacons.  Revisit the interval if larger beacon counts are used.
    private func startRSSIRefreshWhileConnectedTimer() {
        rssiRefreshWhileConnectedTimer.invalidate()
        DispatchQueue.main.async {
            self.rssiRefreshWhileConnectedTimer = Timer.scheduledTimer(withTimeInterval: self.rssiRefreshWhileConnectedInterval, repeats: true, block: { (Timer) in
                self.bluetoothDispatchQueue.async {
                    self.connectedMTSBeacons.forEach { (mtsBeacon) in
                        mtsBeacon.peripheral.readRSSI()
                    }
                }
            })
        }
    }
    
    private func stopRSSIRefreshWhileConnectedTimer() {
        rssiRefreshWhileConnectedTimer.invalidate()
    }
    
    private func evaluateVsAutoDisconnectThreshold(rssi: Int, mtsBeacon: MTSBeacon) {
        guard thresholdAutoDisconnectEnabled else {
            return
        }
        if autoDisconnectRSSIThreshold > rssi {
            guard !mtsBeacon.autoDisconnectTimer.isValid else {
                return
            }
            startAutoDisconnectTimer(mtsBeacon: mtsBeacon)
        } else {
            mtsBeacon.autoDisconnectTimer.invalidate()
        }
    }
    
    private func startAutoDisconnectTimer(mtsBeacon: MTSBeacon) {
        DispatchQueue.main.async {
            mtsBeacon.autoDisconnectTimer = Timer.scheduledTimer(withTimeInterval: Double(self.autoDisconnectInterval), repeats: false, block: { (Timer) in
                self.disconnectIfNeeded(mtsBeacon)
            })
        }
    }
    
    private func evaluateVsThreshold(beacon: MTSBeacon) {
        if beacon.filteredRSSI > autoConnectRSSIThreshold {
            thresholdCrossed(mtsBeacon: beacon)
        }
    }
    
    private func thresholdCrossed(mtsBeacon: MTSBeacon) {
        guard thresholdAutoConnectEnabled else {
            logIfEnabled("\(#function) exited early because thresholdAutoConnectEnabled was false")
            return
        }
        connect(mtsBeacon: mtsBeacon)
    }
    
    //MARK: Scan Timeout

    private func startScanTimeoutTimer() {
        scanTimeoutTimer.invalidate()
        if 0 == scanTimeoutInterval {
            return
        }
        scanTimeoutTimer = Timer.scheduledTimer(
            timeInterval: Double(scanTimeoutInterval),
            target: self,
            selector: #selector(scanTimedOut),
            userInfo: nil,
            repeats: false)
    }
    
    private func stopScanTimeoutTimer() {
        scanTimeoutTimer.invalidate()
    }
    
    @objc private func scanTimedOut() {
        if .scanning == bluetoothDiscoveryState {
            stopScanning()
        }
    }
    
    
    //MARK: Stale beacon expiration
    
    private func startExpirationTimer() {
        expirationTimer.invalidate()
        expirationTimer = Timer.scheduledTimer(
            timeInterval: expirationEvalInterval,
            target: self,
            selector: #selector(self.clearExpiredBeacons),
            userInfo: nil,
            repeats: true)
    }
    
    private func stopExpirationTimer() {
        expirationTimer.invalidate()
    }
    
    @objc private func clearExpiredBeacons() {
        DispatchQueue.main.async {
            let unexpiredBeacons = self.discoveredBeacons.filter {
                let elapsedTime = CACurrentMediaTime() - $0.lastDiscoveredAt
                return elapsedTime < self.expirationInterval
            }
            self.discoveredBeacons = unexpiredBeacons
        }
    }
    
    
    //MARK: CBCentralManagerDelegate
    
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            changeBluetoothDiscoveryState(newState: .inactive)
        default:
            changeBluetoothDiscoveryState(newState: .notReady)
        }
    }
    
    public func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
        guard  MTSConstants.rssiUnavailableValue != RSSI.intValue else {
            // Discard discovery events when the RSSI value is not available. The focus of this app is using
            // ScanOptionAllowDuplicates to monitor RSSI changes, so a discovery without RSSI is not useful.
            return
        }
        
        DispatchQueue.main.async {
            let beacon = self.addOrUpdateBeacon(
                peripheral: peripheral,
                rssi: RSSI.intValue,
                advertisementData: advertisementData
            )
            self.evaluateVsThreshold(beacon: beacon)
        }
    }

    private func addOrUpdateBeacon(peripheral p: CBPeripheral, rssi r: Int, advertisementData a: [String : Any]) -> MTSBeacon {

        // Find the matching beacon in discoveredBeacons or add a new one.
        let beaconUUIDStrings = discoveredBeacons.map { $0.peripheral.identifier.uuidString }
        let beacon: MTSBeacon
        if let i = beaconUUIDStrings.firstIndex(of: p.identifier.uuidString) {
            beacon = discoveredBeacons[i]
        } else {
            beacon = MTSBeacon(peripheral: p)
            discoveredBeacons.append(beacon)
        }

        // Common method to update both new and existing beacons with discovered values.
        beacon.updateOnDiscovery(peripheral: p, rssi: r, advertisementData: a)
        
        discoveredBeacons = discoveredBeacons.sorted {
            return $0.firstDiscoveredAt < $1.firstDiscoveredAt
        }
        // NSLog("\(#function) beacon: \(String(describing: beacon.advertisementData?.description))")
        
        return beacon
    }
    
    private func updateRSSI(peripheral p: CBPeripheral, rssi r: Int) {
        guard let mtsBeacon = connectedMTSBeaconFromPeripheral(p) else {
            logIfEnabled("\(#function) failed at guard let mtsBeacon = connectedMTSBeaconFromPeripheral")
            return
        }
        guard p == mtsBeacon.peripheral else {
            logIfEnabled("\(#function) failed because you received an RSSI value for a beacon other than connectedMTSBeacon.")
            return
        }
        // logIfEnabled("\(#function) peripheral: \(p.description) rssi: \(r)")
        mtsBeacon.updateOnConnectedRSSIReceipt(rssi: r)
        invokeUpdateOnConnectedRSSIReceipt(rssi: r, mtsBeacon: mtsBeacon)
        evaluateVsAutoDisconnectThreshold(rssi: r, mtsBeacon: mtsBeacon)
    }
    
    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard let serviceUUID = serviceUUID else {
            logIfEnabled("Please assign the serviceUUID provided by MTS.")
            return
        }
        stopScan()
        requiredCharacteristics = MTSCharacteristic.requiredCharacteristics
        peripheral.delegate = self
        peripheral.discoverServices([serviceUUID, MTSService.machineInfoSvc.uuid])
        // N.B. Defer the .connected state transition until discovery of required characteristics.
    }
    
    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        var disconnectedMTSBeacon: MTSBeacon? = nil
        if let beacon = connectedMTSBeaconFromPeripheral(peripheral) {
            if let index = connectedMTSBeacons.firstIndex(of: beacon) {
                connectedMTSBeacons.remove(at: index)
            } else {
                logIfEnabled("\(#function) called for mtsBeacon with peripheral.identifier \(peripheral.identifier) already absent from connectedMTSBeacons.")
            }
            disconnectedMTSBeacon = beacon
        }
        bluetoothConnectionEventOccurred(bluetoothEvent: .disconnect, mtsBeacon: disconnectedMTSBeacon)
    }
    
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        NSLog("\(#function)")
        guard let services = peripheral.services else {
            NSLog("\(#function) returned early at guard let services = peripheral.services.")
            return
        }
        NSLog("\(#function) services.count: \(services.count)")
        for service in services {
            NSLog("\(#function) service.uuid: \(service.uuid)")
            peripheral.discoverCharacteristics(nil, for: service)
        }
    }
    
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let serviceUUID = serviceUUID else {
            logIfEnabled("Please assign the serviceUUID provided by MTS.")
            return
        }
        guard [serviceUUID, MTSService.machineInfoSvc.uuid].contains(service.uuid) else {
            return
        }
        
        guard let characteristics = service.characteristics else {
            return
        }
        
        guard let mtsBeacon = connectedMTSBeaconFromPeripheral(peripheral) else {
            logIfEnabled("Returned early at guard let connectedMTSBeacon.")
            return
        }
        
        for characteristic in characteristics {
            NSLog("\(#function) characteristic: \(characteristic.uuid.uuidString) value: \(String(describing: characteristic.value))")
            guard let mtsCharacteristic = MTSCharacteristic(rawValue: characteristic.uuid.uuidString) else {
                continue
            }

            if mtsCharacteristic == MTSCharacteristic.gmiLinkActive {
                NSLog("\(#function) .gmiLinkActive setNotifyValue uuid: \(characteristic.uuid.uuidString)")
                peripheral.setNotifyValue(true, for: characteristic)
            }
            
            requiredCharacteristics = requiredCharacteristics.filter{$0 != mtsCharacteristic}
            peripheral.readValue(for: characteristic)
        }

        if 0 == requiredCharacteristics.count &&
           false == mtsBeacon.isCharacteristicDiscoveryComplete
        {
            NSLog("\(#function) 0 == requiredCharacteristics.count, calling .connect bluetoothConnectionEventOccurred...")
            mtsBeacon.isCharacteristicDiscoveryComplete = true
            bluetoothConnectionEventOccurred(bluetoothEvent: .connect, mtsBeacon: mtsBeacon)
        } else {
            logIfEnabled("\(#function) Failed to discover required characteristics \(requiredCharacteristics.description) for MTSService.mts.")
        }
    }
    
    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        
        guard let connectedMTSBeacon = connectedMTSBeaconFromPeripheral(peripheral) else {
            logIfEnabled("\(#function) Failed at guard let mtsBeacon = connectedMTSBeaconFromPeripheral.")
            return
        }
        
        guard let mtsCharacteristic = MTSCharacteristic(rawValue: characteristic.uuid.uuidString) else {
            logIfEnabled("\(#function) Received update for unexpected characteristic: \(characteristic.uuid.uuidString)")
            return
        }

        guard let value = characteristic.value else {
            logIfEnabled("\(#function) Received a nil value for characteristic: \(characteristic.uuid.uuidString)")
            return
        }

        NSLog("\(#function) mtsCharacteristic: \(mtsCharacteristic.uuid.uuidString) value.hex: \(value.hex)")
        
        switch mtsCharacteristic {
        case .stickyConnect:
            break
        case .cardData:
            invokeReceivedCardData(cardData: value, mtsBeacon: connectedMTSBeacon)
        case .terminalKind:
            let kind = MTSBeacon.TerminalKind(value: value)
            invokeReceivedTerminalKind(terminalKind: kind, mtsBeacon: connectedMTSBeacon)
        case .userDisconnected:
            break
        case .sasSerialNumber:
            invokeReceivedSasSerialNumber(serialNumber: value.utf8, mtsBeacon: connectedMTSBeacon)
        case .location:
            invokeReceivedLocation(location: value.utf8, mtsBeacon: connectedMTSBeacon)
        case .assetNumber:
            var assetNumber = value.to(type: UInt32.self)
            assetNumber = UInt32(littleEndian: assetNumber)
            invokeReceivedAssetNumber(assetNumber: assetNumber, mtsBeacon: connectedMTSBeacon)
        case .denomination:
            var denomination = value.to(type: UInt32.self)
            denomination = UInt32(littleEndian: denomination)
            invokeReceivedDenomination(denomination: denomination, mtsBeacon: connectedMTSBeacon)
        case .gmiLinkActive:
            let active   = UInt8(0x01)
            let isActive = active == value[0]
            invokeReceivedGmiLinkActive(isActive: isActive, mtsBeacon: connectedMTSBeacon)
        case .txAttenuationLevel:
            let byte = value[0];
            guard let level = TxAttenuationLevel(rawValue: byte) else {
                logIfEnabled("Unexpected txAttenuationLevel: \(value.hex)")
                return;
            }
            invokeReceivedTxAttenuationLevel(level: level, mtsBeacon: connectedMTSBeacon)
        }
    }
    
    public func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?){
           updateRSSI(peripheral: peripheral, rssi: RSSI.intValue)
       }
       
       private func connectedMTSBeaconFromPeripheral(_ peripheral: CBPeripheral) -> MTSBeacon? {
           return connectedMTSBeacons.filter({$0.peripheral == peripheral}).first
       }

       private func mtsBeaconFromPeripheral(_ peripheral: CBPeripheral) -> MTSBeacon? {
           if let mtsBeacon = connectedMTSBeacons.filter({$0.peripheral == peripheral}).first {
               return mtsBeacon
           }
           else if let mtsBeacon = discoveredBeacons.filter({$0.peripheral == peripheral}).first {
               return mtsBeacon
           }
           return nil
       }
    
    public func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
            logIfEnabled("\(#function) \(String(describing:characteristic.uuid.uuidString)) error: \(String(describing: error?.localizedDescription))")
            
            guard let mtsBeacon = mtsBeaconFromPeripheral(peripheral) else {
                logIfEnabled("\(#function) Failed at guard let mtsBeacon = mtsBeaconFromPeripheral.")
                return
            }
            
            switch characteristic.uuid {
            case MTSCharacteristic.cardData.uuid:
                invokeDidWriteCardDataToBluetooth(error: error, mtsBeacon: mtsBeacon)
            case MTSCharacteristic.userDisconnected.uuid:
                disconnectUponDidWriteofUserDisconnected(mtsBeacon: mtsBeacon)
            default:
                break
            }
        }
        
    private func readCharacteristic(_ mtsCharacteristic: MTSCharacteristic, mtsBeacon: MTSBeacon) {
        let peripheral = mtsBeacon.peripheral
        guard let characteristic = characteristic(mtsCharacteristic, mtsBeacon: mtsBeacon) else {
            return
        }
        peripheral.readValue(for: characteristic)
    }
    
    private func characteristic(_ mtsCharacteristic: MTSCharacteristic, mtsBeacon: MTSBeacon) -> CBCharacteristic? {
        let peripheral = mtsBeacon.peripheral
        guard let services = peripheral.services else {
            logIfEnabled("\(#function) Failed at guard let services = peripheral.services.")
            return nil
        }
        guard let serviceUUID = serviceUUID else {
            logIfEnabled("Please assign the serviceUUID provided by MTS.")
            return nil
        }
        for service in services {
            guard serviceUUID == service.uuid else {
                continue
            }
            guard let characteristics = service.characteristics else {
                logIfEnabled("\(#function) Failed due to nil characteristics.")
                return nil
            }
            for characteristic in characteristics {
                if characteristic.uuid == mtsCharacteristic.uuid {
                    return characteristic
                }
            }
        }
        logIfEnabled("\(#function) Failed to find characteristic: \(mtsCharacteristic)")
        return nil
    }

    
    // MARK: Simple Log Toggle
    
    private func logIfEnabled(_ description: String) {
        if loggingEnabled {
            NSLog("MTS \(description)")
        }
    }
}

//MARK: MultiDelegate Invocations

private extension MTSManager {
    
    func invokeBluetoothDiscoveryStateChangedDelegate(oldState: BluetoothDiscoveryState, newState: BluetoothDiscoveryState) {
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.bluetoothDiscoveryStateChanged(oldState: oldState, newState: newState)
            })
        }
    }

    func invokeBluetoothBluetoothEventOccurred(bluetoothEvent: BluetoothConnectionEvent, mtsBeacon: MTSBeacon?) {
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.bluetoothConnectionEvent(bluetoothEvent: bluetoothEvent ,mtsBeacon: mtsBeacon)
            })
        }
    }

    func invokeReceivedTerminalId(terminalId: String, mtsBeacon: MTSBeacon) {
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.receivedTerminalId(terminalId: terminalId, mtsBeacon: mtsBeacon)
            })
        }
    }

    func invokeReceivedCardData(cardData: Data, mtsBeacon: MTSBeacon) {
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.receivedCardData(cardData: cardData, mtsBeacon: mtsBeacon)
            })
        }
    }

    func invokeDidWriteCardDataToBluetooth(error: Error?, mtsBeacon: MTSBeacon) {
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.didWriteCardDataToBluetooth(error: error, mtsBeacon: mtsBeacon)
            })
        }
    }
    
    func invokeReceivedTerminalKind(terminalKind: MTSBeacon.TerminalKind, mtsBeacon: MTSBeacon) {
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.receivedTerminalKind(kind: terminalKind, mtsBeacon: mtsBeacon)
            })
        }
    }
    
    func invokeUpdateOnConnectedRSSIReceipt(rssi: Int, mtsBeacon: MTSBeacon) {
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.updateOnConnectedRSSIReceipt(rssi: rssi, mtsBeacon: mtsBeacon)
            })
        }
    }
    
    func invokeReceivedSasSerialNumber(serialNumber: String?, mtsBeacon: MTSBeacon) {
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.receivedSasSerialNumber(serialNumber: serialNumber, mtsBeacon: mtsBeacon)
            })
        }
    }
    
    func invokeReceivedLocation(location: String?, mtsBeacon: MTSBeacon) {
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.receivedLocation(location: location, mtsBeacon: mtsBeacon)
            })
        }
    }
    
    func invokeReceivedAssetNumber(assetNumber: UInt32, mtsBeacon: MTSBeacon) {
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.receivedAssetNumber(assetNumber: assetNumber, mtsBeacon: mtsBeacon)
            })
        }
    }
    
    func invokeReceivedDenomination(denomination: UInt32, mtsBeacon: MTSBeacon) {
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.receivedDenomination(denomination: denomination, mtsBeacon: mtsBeacon)
            })
        }
    }
    
    func invokeReceivedGmiLinkActive(isActive: Bool, mtsBeacon: MTSBeacon) {
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.receivedGmiLinkActive(isActive: isActive, mtsBeacon: mtsBeacon)
            })
        }
    }
    
    func invokeReceivedTxAttenuationLevel(level: TxAttenuationLevel, mtsBeacon: MTSBeacon) {
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.receivedTxAttenuationLevel(level: level, mtsBeacon: mtsBeacon)
            })
        }
    }
    
}


// MARK: UserDefault Computed Properties

public extension MTSManager {
    
    // This is the user-defined RSSI value required to trigger a connection.
    var autoConnectRSSIThreshold: Int {
        get {
            return UserDefaults.standard.integer(forKey: UserDefaultKey.autoConnectThreshold.rawValue)
        }
        set {
            var valueToPersist = newValue
            if valueToPersist > maxRSSIThresholdValue {
                valueToPersist = maxRSSIThresholdValue
            } else if (valueToPersist < minRSSIThresholdValue) {
                valueToPersist = minRSSIThresholdValue
            }
            UserDefaults.standard.set(valueToPersist, forKey: UserDefaultKey.autoConnectThreshold.rawValue)
        }
    }
    
    var thresholdAutoConnectEnabled: Bool {
        get {
            return autoConnectRSSIThreshold != 0
        }
    }
    
    var autoDisconnectRSSIThreshold: Int {
        get {
            return UserDefaults.standard.integer(forKey: UserDefaultKey.autoDisconnectThreshold.rawValue)
        }
        set {
            var valueToPersist = newValue
            if valueToPersist > maxRSSIThresholdValue {
                valueToPersist = maxRSSIThresholdValue
            } else if (newValue < minRSSIThresholdValue) {
                valueToPersist = minRSSIThresholdValue
            }
            UserDefaults.standard.set(newValue, forKey: UserDefaultKey.autoDisconnectThreshold.rawValue)
        }
    }
    
    var thresholdAutoDisconnectEnabled: Bool {
        get {
            return autoDisconnectRSSIThreshold != 0
        }
    }
    
    var autoDisconnectInterval: Int {
        get {
            return UserDefaults.standard.integer(forKey: UserDefaultKey.autoDisconnectInterval.rawValue)
        }
        set {
            UserDefaults.standard.set(newValue, forKey: UserDefaultKey.autoDisconnectInterval.rawValue)
        }
    }
    
    var scanTimeoutInterval: Int {
        get {
            return UserDefaults.standard.integer(forKey: UserDefaultKey.scanTimeoutInterval.rawValue)
        }
        set {
            UserDefaults.standard.set(newValue, forKey: UserDefaultKey.scanTimeoutInterval.rawValue)
        }
    }
    
}
