//
//
//  Copyright © 2021 Mobile Technology Solutions, Inc. All rights reserved.
//

import UIKit
import CoreBluetooth

public class MTSBeacon {

    public enum TerminalKind: CustomStringConvertible {
        case remote
        case kiosk
        case cardReader
        case unknown
        init(value: Data){
            let string = value.utf8 ?? ""
            if string.hasPrefix("REMOTE") {
                self = .remote
            }
            else if string.hasPrefix("KIOSK") {
                self = .kiosk
            }
            else if string.hasPrefix("CARDREADER") {
                self = .cardReader
            }
            else {
                self = .unknown
            }
        }
        public var description: String {
            switch self {
            case .remote:
                return "Remote"
            case .kiosk:
                return "Kiosk"
            case .cardReader:
                return "Card Reader"
            case .unknown:
                return "Uknown"
            }
        }
    }
    
    public var name: String = MTSConstants.noValuePlaceholder
    public var rssi: Int = MTSConstants.rssiUnavailableValue
    /// Provides RSSI through low-pass filter.  Use this if you find the rssi property value jumps around.
    public var filteredRSSI: Int = MTSConstants.rssiUnavailableValue
    public var peripheral: CBPeripheral
    public var firstDiscoveredAt: CFTimeInterval = 0
    public var lastDiscoveredAt: CFTimeInterval = 0
    public var elapsedSinceLastDiscovered: CFTimeInterval = 0
    public var advertisementData: [String : Any]?
    public var manufacturerData: Data?
    public var advertisementBluetoothDeviceAddress: Data?
    public var terminalKind: TerminalKind = .unknown
    private static let kCompanyIdentifier = Data([0x00, 0xA0, 0x50])
    public var mfgIdentifier: Data?
    /// Used by the MTSManager to handle RSSI threshold disconnect evaluation for this beacon
    public var autoDisconnectTimer = Timer()
    public var isCharacteristicDiscoveryComplete: Bool = false
    
    public init(peripheral p: CBPeripheral) {
        peripheral = p
        firstDiscoveredAt = CACurrentMediaTime()
    }
    
    public func updateOnDiscovery(peripheral p: CBPeripheral, rssi r: Int, advertisementData a: [String : Any]) {
        peripheral = p
        rssi = r
        advertisementData = a
        let now = CACurrentMediaTime()
        if lastDiscoveredAt > 0 {
            elapsedSinceLastDiscovered = now - lastDiscoveredAt
        }
        lastDiscoveredAt = now
        filteredRSSI = calculateFilteredRSSI(newRSSI: r)
        name = advertisementData?[CBAdvertisementDataLocalNameKey] as? String ?? MTSConstants.noValuePlaceholder
        manufacturerData = advertisementData?[CBAdvertisementDataManufacturerDataKey] as? Data        
        
        if let manufacturerData = manufacturerData, 8 == manufacturerData.count {
            var index = manufacturerData.startIndex
            index = index.advanced(by: MemoryLayout<UInt8>.size)
            let subData = manufacturerData.subdata(in: index..<index.advanced(by: 6))
            mfgIdentifier = subData
        } else {
            NSLog("\(#function) manufacturerData mismatch: \(String(describing: manufacturerData?.hex))")
        }
        
        // Note that there is an undocumented advertisementData key kCBAdvDataLeBluetoothDeviceAddress which is sometimes populated in didDiscover callbacks.
        // It doesn't reliably contain the BLE address value. It isn't documented, so not clear that it is meant to be reliable / consumed.
        // e.g. advertisementBluetoothDeviceAddress: Optional("002969DD964208")
        // https://developer.apple.com/forums/thread/127280
        advertisementBluetoothDeviceAddress = advertisementData?["kCBAdvDataLeBluetoothDeviceAddress"] as? Data
    }

    public func updateOnConnectedRSSIReceipt(rssi r: Int) {
        // This is called at an interval determined by the MTSManager rssiRefreshWhileConnectedInterval,
        // so doesn't need filtering + elapsedSinceLastDiscovered aspect isn't relevant.
        rssi = r
        filteredRSSI = rssi
    }
    
    private func calculateFilteredRSSI(newRSSI: Int) -> Int {
        let kMaxAverageFactor = 0.8
        let k = min(elapsedSinceLastDiscovered, kMaxAverageFactor)
        let filtered = (1 - k) * Double(filteredRSSI) + k * Double(newRSSI)
        if MTSConstants.rssiUnavailableValue == filteredRSSI {
            return newRSSI
        } else {
            return Int(filtered)
        }
    }

}

extension MTSBeacon: Equatable {
    public static func == (lhs: MTSBeacon, rhs: MTSBeacon) -> Bool {
        return lhs.peripheral == rhs.peripheral
    }
}

extension MTSBeacon: Hashable {
    public func hash(into hasher: inout Hasher) {
        hasher.combine(peripheral)
    }
}

extension RawRepresentable where RawValue == String {
    var uuid: CBUUID {
        get {
            return CBUUID(string: rawValue)
        }
    }
}
