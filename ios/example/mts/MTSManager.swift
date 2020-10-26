//
//
//  Copyright © 2020 Mobile Technology Solutions, Inc. All rights reserved.
//

import UIKit
import CoreBluetooth

public enum BluetoothConnectionState {
    case notReady
    case inactive
    case scanning
    case connected
    case attemptingToReconnect
}

public protocol MTSManagerDelegate {
    func bluetoothConnectionStateChanged()
    func receivedCardData(cardData: String)
    func didWriteCardDataToBluetooth(error: Error?)
    func receivedCardData(data: Data)
    func receivedStickyConnectionState(isSticky: Bool)
    func receivedTerminalKind(kind: MTSBeacon.TerminalKind)
    func reconnectAttemptTimedOut()
    func updateOnConnectedRSSIReceipt(rssi: Int)
}

public extension MTSManagerDelegate {
    func bluetoothConnectionStateChanged(){}
    func receivedCardData(cardData: String){}
    func didWriteCardDataToBluetooth(error: Error?){}
    func receivedCardData(data: Data){}
    func receivedStickyConnectionState(isSticky: Bool){}
    func receivedTerminalKind(kind: MTSBeacon.TerminalKind){}
    func reconnectAttemptTimedOut(){}
    func updateOnConnectedRSSIReceipt(rssi: Int){}
}

struct MTSConstants {
    static let noValuePlaceholder = "- - -"
    static let rssiUnavailableValue = 127 // Reserved per CBCentralManager.h
}

enum MTSError: Error {
    case invalidCardDataCharacterCount(Int)
    case writeFailure(description: String)
}

public class MTSManager: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    
    // MARK: Public Properties
    
    public static let sharedInstance = MTSManager()
    public let delegate = MulticastDelegate<MTSManagerDelegate>()
    public var bluetoothConnectionState = BluetoothConnectionState.notReady
    public var loggingEnabled = true
    public let cardDataCharacterCountMax = 195 // 195 automatic null termination, so 196 total accepted by the peripheral.
    
    // MARK: Private Properties
    
    private var centralManager: CBCentralManager!
    private let bluetoothDispatchQueue = DispatchQueue(label: "com.mts.example.bluetooth")
    private var requiredCharacteristics = [MTSCharacteristic]()
    public var connectedMTSBeacon: MTSBeacon? // TODO: revisit whether this is public.
    private var discoveredBeacons = [MTSBeacon]()

    private var scanRefreshTimer = Timer()
    private let kScanRefreshInterval = 5.0
    
    private var scanTimeoutTimer = Timer()
    private let kScanTimeoutEvalInterval = 1.0
    
    private var expirationTimer = Timer()
    private let expirationInterval: CFTimeInterval = 5.0
    private let expirationEvalInterval: CFTimeInterval = 1.0

    private var rssiRefreshWhileConnectedTimer = Timer()
    private let rssiRefreshWhileConnectedInterval: CFTimeInterval = 1.0

    private var autoDisconnectTimer = Timer()
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
        case mts = "C1FB6CDA-3F15-4BC0-8A46-8E9C341065F8"
        var uuid: CBUUID {
            return CBUUID(string: self.rawValue)
        }
    }
    
    private enum MTSCharacteristic: String {
        case cardData           = "60D11359-FEB2-411D-A430-CA6167052BD6"
        case stickyConnect      = "4B6A91D8-EA3E-42A4-B39B-B300F5F64C86"
        case terminalKind       = "D308DFDE-9F06-4A73-A2C7-EB952E40A184"
        case userDisconnected   = "4E3A829D-4830-47A0-995F-EE923710A469"
        var uuid: CBUUID {
            return CBUUID(string: self.rawValue)
        }
        static let requiredCharacteristics = [cardData, stickyConnect, terminalKind, userDisconnected]
    }

    // MARK: Public Methods
    
    public func startScanning() {
        changeConnectionState(newState: .scanning)
    }

    public func stopScanning() {
        changeConnectionState(newState: .inactive)
    }
    
    public func disconnect() {
        guard let peripheral = connectedMTSBeacon?.peripheral else {
            logIfEnabled("\(#function) Failed due at guard let peripheral = connectedMTSBeacon?.peripheral.")
            return
        }
        
        guard let characteristic = characteristic(.userDisconnected) else {
            logIfEnabled("\(#function) Failed at characteristic lookup.")
            return
        }
        
        peripheral.writeValue(Data([1]), for: characteristic, type: .withResponse)
    }
    
    private func disconnectUponDidWriteofUserDisconnected() {
        changeConnectionState(newState: .scanning)
    }
    
    public func requestCardData() {
        NSLog("\(#function)")
        readCharacteristic(.cardData)
    }
    
    public func writeCardDataToBluetooth(cardDataString: String) throws {
        
        guard let peripheral = connectedMTSBeacon?.peripheral else {
            logIfEnabled("\(#function) Failed due at guard let peripheral = connectedMTSBeacon?.peripheral.")
            throw MTSError.writeFailure(description: "Bluetooth connection failure.")
        }
        
        guard let characteristic = characteristic(.cardData) else {
            logIfEnabled("\(#function) Failed at characteristic lookup.")
            throw MTSError.writeFailure(description: "Bluetooth characteristic lookup failed.")
        }
        
        let data = try validatedCardData(cardData: cardDataString)
        
        peripheral.writeValue(data, for: characteristic, type: .withResponse)
    }
    

    // https://github.com/MobileTechnologySolutionsLLC/dpc-mobile/issues/5
    // https://github.com/MobileTechnologySolutionsLLC/gt-connect/blob/develop/firmware/gt_ble/Readme.md
    // https://github.com/MobileTechnologySolutionsLLC/dpc-mobile/issues/3
    private func validatedCardData(cardData: String) throws -> Data {
        
        guard isValidCardData(cardData) else {
            throw MTSError.invalidCardDataCharacterCount(cardDataCharacterCountMax)
        }

        var sentinels: SentinelState = .noSentinels
        if let state = SentinelState(rawValue: sentinelState) {
            sentinels = state
        }
        
        var max = cardDataCharacterCountMax
        if .noSentinels != sentinels {
            max -= 2
        }
        
        let truncatedString = String(cardData.prefix(max))
        
        let track1StartSentinel = "%"
        let track2StartSentinel   = ":"
        let endSentinel   = "?"
        var wrappedString = ""
        
       switch sentinels {
        case .track1:
            wrappedString.append(track1StartSentinel)
            wrappedString.append(truncatedString)
            wrappedString.append(endSentinel)
        case .track2:
            wrappedString.append(track2StartSentinel)
            wrappedString.append(truncatedString)
            wrappedString.append(endSentinel)
        case .noSentinels:
            wrappedString.append(truncatedString)
        }
        
        guard let data = truncatedString.data(using: .utf8) else {
            throw MTSError.writeFailure(description: "String conversion to UTF-8 failed.")
        }
        let terminatedData = data + Data([0x0])
                
        NSLog("\(#function)  data: \(terminatedData.hex))")
        
        // N.B. the cardData string won't log to Xcode console as a string due to %.
        logIfEnabled("\(#function) data: \(terminatedData.hex))")
        return terminatedData
    }
    
    public func isValidCardData(_ cardDataString: String) -> Bool {
        return cardDataCharacterCountMax >= cardData.count
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
            changeConnectionState(newState: .notReady)
            return
            
        }
        centralManager.scanForPeripherals(
            withServices: [MTSService.mts.uuid],
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
    
    private func connect(beacon: MTSBeacon) {
        guard centralManager.state == .poweredOn else {
            logIfEnabled("\(#function) failed because centralManager.state != .poweredOn")
            return
        }
        connectedMTSBeacon = beacon
        stopScan()
        stopScanRefreshTimer()
        stopExpirationTimer()
        clearDiscoveredBeacons()
        centralManager.connect(beacon.peripheral, options: nil)
    }
    
    private func clearDiscoveredBeacons() {
        DispatchQueue.main.async {
            self.discoveredBeacons = [MTSBeacon]()
        }
    }
    
    private func disconnectIfNeeded() {
        defer {
            connectedMTSBeacon = nil
        }
        guard let peripheral = connectedMTSBeacon?.peripheral else {
            return
        }
        centralManager.cancelPeripheralConnection(peripheral)
    }
    
    private func changeConnectionState(newState: BluetoothConnectionState) {
        if bluetoothConnectionState == newState {
            return
        }
        logIfEnabled("\(#function) \(bluetoothConnectionState) -> \(newState)")
        bluetoothConnectionState = newState
        stopAutoDisconnectTimer()
        stopScanTimeoutTimer()
        stopRSSIRefreshWhileConnectedTimer()
        switch bluetoothConnectionState {
        case .notReady:
            disconnectIfNeeded()
            stopScan()
            stopScanRefreshTimer()
            stopExpirationTimer()
        case .inactive:
            disconnectIfNeeded()
            stopScan()
            stopScanRefreshTimer()
            stopExpirationTimer()
        case .scanning:
            disconnectIfNeeded()
            startScan()
            startScanTimeoutTimer()
            startScanRefreshTimer()
            startExpirationTimer()
        case .connected:
            stopScan()
            stopScanRefreshTimer()
            stopExpirationTimer()
            startRSSIRefreshWhileConnectedTimer()
        case .attemptingToReconnect:
            reconnect()
        }
        invokeBluetoothConnectionStateChangedDelegate()
    }
    
    func reconnect() {
        guard let beacon = connectedMTSBeacon else {
            logIfEnabled("\(#function) returned early due to nil connectedMTSBeacon")
            return
        }
        guard let peripheral = connectedMTSBeacon?.peripheral else {
            logIfEnabled("\(#function) returned early due to nil beacon.peripheral")
            return
        }
        guard ![.connected, .connecting].contains(peripheral.state) else {
            logIfEnabled("\(#function) returned early due to invalid peripheral.state: \(peripheral.state)")
            return
        }
        guard .attemptingToReconnect == bluetoothConnectionState else {
            logIfEnabled("\(#function) returned early because it was called with invalid connectionState \(bluetoothConnectionState)")
            return
        }
        // Tell iOS we want to reconnect when the peripheral is back in range.
        connect(beacon: beacon)
        
        startReconnectTimeout()
    }
    
    //MARK: Lost Connection Reconnect Timeout
    var reconnectTimeOutDispatchWorkItem: DispatchWorkItem?
    let reconnectTimeOutInterval: CFTimeInterval = 10.0
    
    func startReconnectTimeout() {
        reconnectTimeOutDispatchWorkItem = DispatchWorkItem(block: { [weak self] in
            self?.reconnectTimedOut()
        })
        DispatchQueue.main.asyncAfter(
            deadline: DispatchTime.now() + reconnectTimeOutInterval,
            execute: reconnectTimeOutDispatchWorkItem!
        )
    }

    func reconnectTimedOut() {
        if let peripheral = connectedMTSBeacon?.peripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
        changeConnectionState(newState: .scanning)
        invokeReconnectAttemptTimedOut()
    }

    func cancelReconnectTimeOut() {
        DispatchQueue.main.async {
            self.reconnectTimeOutDispatchWorkItem?.cancel()
        }
    }
    
    
    //MARK: Timers Active While Connected
    
    private func startRSSIRefreshWhileConnectedTimer() {
        rssiRefreshWhileConnectedTimer.invalidate()
        DispatchQueue.main.async {
            self.rssiRefreshWhileConnectedTimer = Timer.scheduledTimer(withTimeInterval: self.rssiRefreshWhileConnectedInterval, repeats: true, block: { (Timer) in
                guard let b = self.connectedMTSBeacon else {
                    self.logIfEnabled("\(#function) unexpectedly called while connectedMTSBeacon is nil.")
                    return
                }
                self.bluetoothDispatchQueue.async {
                    b.peripheral.readRSSI()
                }
            })
        }
    }
    
    private func stopRSSIRefreshWhileConnectedTimer() {
        rssiRefreshWhileConnectedTimer.invalidate()
    }
    
    private func evaluateVsAutoDisconnectThreshold(rssi: Int) {
        guard thresholdAutoDisconnectEnabled else {
            return
        }
        if autoDisconnectRSSIThreshold > rssi {
            guard !autoDisconnectTimer.isValid else {
                return
            }
            startAutoDisconnectTimer()
        } else {
            stopAutoDisconnectTimer()
        }
    }
    
    private func startAutoDisconnectTimer() {
        DispatchQueue.main.async {
            self.autoDisconnectTimer = Timer.scheduledTimer(withTimeInterval: Double(self.autoDisconnectInterval), repeats: false, block: { (Timer) in
                self.disconnectIfNeeded()
            })
        }
    }
    
    private func stopAutoDisconnectTimer() {
        autoDisconnectTimer.invalidate()
    }
    
    private func evaluateVsThreshold(beacon: MTSBeacon) {
        if beacon.filteredRSSI > autoConnectRSSIThreshold {
            thresholdCrossed(beacon: beacon)
        }
    }
    
    private func thresholdCrossed(beacon: MTSBeacon) {
        guard thresholdAutoConnectEnabled else {
            NSLog("\(#function) exited early because thresholdAutoConnectEnabled was false")
            return
        }
        
        guard nil == connectedMTSBeacon else {
            NSLog("\(#function) exited early because connectedMTSBeacon was already assigned")
            return
        }
        connect(beacon: beacon)
    }
    
    
    //MARK: Scan Timeout

    private func startScanTimeoutTimer() {
        scanTimeoutTimer.invalidate()
        if 0 == scanTimeoutInterval {
            return
        }
        NSLog("\(#function) scanTimeoutInterval: \(scanTimeoutInterval)")
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
        if .scanning == bluetoothConnectionState {
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
            changeConnectionState(newState: .inactive)
        default:
            changeConnectionState(newState: .notReady)
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
        guard let beacon = connectedMTSBeacon else {
            logIfEnabled("\(#function) failed to update RSSI due to nil connectedMTSBeacon")
            return
        }
        guard p == beacon.peripheral else {
            logIfEnabled("\(#function) failed because you received an RSSI value for a beacon other than connectedMTSBeacon.")
            return
        }
        logIfEnabled("\(#function) connected peripheral rssi: \(r)")
        connectedMTSBeacon?.updateOnConnectedRSSIReceipt(rssi: r)
        invokeUpdateOnConnectedRSSIReceipt(rssi: r)
        evaluateVsAutoDisconnectThreshold(rssi: r)
    }
    
    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        NSLog("\(#function) didConnect")
        cancelReconnectTimeOut()
        stopScan()
        requiredCharacteristics = MTSCharacteristic.requiredCharacteristics
        peripheral.delegate = self
        peripheral.discoverServices([MTSService.mts.uuid])
        // N.B. Defer the .connected state transition until discovery of required characteristics.
    }
    
    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        if .inactive == bluetoothConnectionState {
            return
        }
        
        if let beacon = connectedMTSBeacon,
           true == beacon.wantsStickyConnection,
           .connected == bluetoothConnectionState
        {
            changeConnectionState(newState: .attemptingToReconnect)
        } else {
            changeConnectionState(newState: .scanning)
        }
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
        
        guard MTSService.mts.uuid == service.uuid else {
            return
        }
        
        guard let characteristics = service.characteristics else {
            return
        }
        
        for characteristic in characteristics {
            NSLog("\(#function) characteristic: \(characteristic.uuid.uuidString) value: \(String(describing: characteristic.value))")
            guard let mtsCharacteristic = MTSCharacteristic(rawValue: characteristic.uuid.uuidString) else {
                continue
            }
            
            requiredCharacteristics = requiredCharacteristics.filter{$0 != mtsCharacteristic}
            peripheral.readValue(for: characteristic)
        }

        if 0 == requiredCharacteristics.count {
           changeConnectionState(newState: .connected)
        } else {
            logIfEnabled("\(#function) Failed to discover required characteristics \(requiredCharacteristics.description) for MTSService.mts.")
        }
    }
    
    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {

        NSLog("\(#function)")
        
        guard let mtsCharacteristic = MTSCharacteristic(rawValue: characteristic.uuid.uuidString) else {
            logIfEnabled("\(#function) Received update for unexpected characteristic: \(characteristic.uuid.uuidString)")
            return
        }

        guard let value = characteristic.value else {
            logIfEnabled("\(#function) Received a nil value for characteristic: \(characteristic.uuid.uuidString)")
            return
        }

        switch mtsCharacteristic {
        case .stickyConnect:
            let state = 1 == value[0]
            connectedMTSBeacon?.wantsStickyConnection = state
            invokeReceivedStickyConnection(isSticky: state)
        case .cardData:
            invokeReceivedCardData(data: value)
        case .terminalKind:
            let kind = MTSBeacon.TerminalKind(value: value)
            invokeReceivedTerminalKind(terminalKind: kind)
        case .userDisconnected:
            break
        }
    }
    
    public func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?){
        updateRSSI(peripheral: peripheral, rssi: RSSI.intValue)
    }

    public func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        logIfEnabled("\(#function) \(String(describing:characteristic.uuid.uuidString)) error: \(String(describing: error?.localizedDescription))")
        switch characteristic.uuid {
        case MTSCharacteristic.cardData.uuid:
            invokeDidWriteCardDataToBluetooth(error: error)
        case MTSCharacteristic.userDisconnected.uuid:
            disconnectUponDidWriteofUserDisconnected()
        default:
            break
        }
    }
    
    private func readCharacteristic(_ mtsCharacteristic: MTSCharacteristic) {
        guard let peripheral = connectedMTSBeacon?.peripheral else {
            return
        }
        guard let characteristic = characteristic(mtsCharacteristic) else {
            return
        }
        peripheral.readValue(for: characteristic)
    }
    
    private func characteristic(_ mtsCharacteristic: MTSCharacteristic) -> CBCharacteristic? {
        guard let peripheral = connectedMTSBeacon?.peripheral else {
            logIfEnabled("\(#function) Failed due at guard let peripheral = connectedMTSBeacon?.peripheral.")
            return nil
        }
        guard let services = peripheral.services else {
            logIfEnabled("\(#function) Failed due at guard let services = peripheral.services.")
            return nil
        }
        for service in services {
            guard MTSService.mts.uuid == service.uuid else {
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
    
    func invokeBluetoothConnectionStateChangedDelegate() {
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.bluetoothConnectionStateChanged()
            })
        }
    }

    func invokeReceivedCardData(cardData: String) {
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.receivedCardData(cardData: cardData)
            })
        }
    }

    func invokeDidWriteCardDataToBluetooth(error: Error?) {
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.didWriteCardDataToBluetooth(error: error)
            })
        }
    }

    func invokeReceivedCardData(data: Data) {
        NSLog("\(#function)")
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.receivedCardData(data: data)
            })
        }
    }
    
    func invokeReceivedStickyConnection(isSticky: Bool) {
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.receivedStickyConnectionState(isSticky: isSticky)
            })
        }
    }

    func invokeReceivedTerminalKind(terminalKind: MTSBeacon.TerminalKind) {
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.receivedTerminalKind(kind: terminalKind)
            })
        }
    }
    
    func invokeReconnectAttemptTimedOut() {
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.reconnectAttemptTimedOut()
            })
        }
    }

    func invokeUpdateOnConnectedRSSIReceipt(rssi: Int) {
        DispatchQueue.main.async {
            self.delegate.invokeDelegates({ delegate in
                delegate.updateOnConnectedRSSIReceipt(rssi: rssi)
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

    var cardData: String {
        get {
            return UserDefaults.standard.string(forKey: UserDefaultKey.cardData.rawValue) ?? ""
        }
        set {
            UserDefaults.standard.set(newValue, forKey: UserDefaultKey.cardData.rawValue)
        }
    }
    
    enum SentinelState: Int {
        case track1 = 0 // %
        case track2 = 1 // :
        case noSentinels = 2
    }
    
    var sentinelState: Int {
        get {
            return UserDefaults.standard.integer(forKey: UserDefaultKey.sentinelState.rawValue)
        }
        set {
            UserDefaults.standard.set(newValue, forKey: UserDefaultKey.sentinelState.rawValue)
        }
    }
    
}
