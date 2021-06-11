//
//
//  Copyright © 2021 Mobile Technology Solutions, Inc. All rights reserved.
//

import UIKit
import AVFoundation

class ExampleViewController: UITableViewController, MTSManagerDelegate, UITextFieldDelegate {

    @IBOutlet var headerVersionLabel: UILabel?
    @IBOutlet var autoConnectThresholdTextField: UITextField?
    @IBOutlet var autoDisconnectThresholdTextField: UITextField?
    @IBOutlet var autoDisconnectIntervalTextField: UITextField?
    @IBOutlet var scanTimeoutTextField: UITextField?
    @IBOutlet var cardDataTextField: UITextField?
    @IBOutlet var lastWriteAtLabel: UILabel?
    @IBOutlet var writeCardDataButton: UIButton?
    @IBOutlet var terminalKindLabel: UILabel?
    @IBOutlet var cardDataLabel: UILabel?
    @IBOutlet var connectedRSSILabel1: UILabel?
    @IBOutlet var connectedRSSILabel2: UILabel?
    @IBOutlet var connectedTerminalPeripheralIdentifier: UILabel?
    @IBOutlet var connectedTerminalMfgDataLabel: UILabel?
    @IBOutlet var sasSerialNumberLabel: UILabel?
    @IBOutlet var locationLabel: UILabel?
    @IBOutlet var assetNumberLabel: UILabel?
    @IBOutlet var denominationLabel: UILabel?
    @IBOutlet var gmiLinkActiveLabel: UILabel?
    @IBOutlet var txAttenuationLevelLabel: UILabel?
    
    let mtsManager = MTSManager.sharedInstance
    let statusCellIndexPath = IndexPath(row: 0, section: 0)
    let firstSectionIndexSet: IndexSet = [0]
    var waitingForBLEPostWriteRead = false
    
    var mtsBeacon1: MTSBeacon?
    var mtsBeacon2: MTSBeacon?
    
    // This serviceUUID is implementation-specific, i.e. MTS will provide it to you.
    let kMTSServiceUUID = "6289B88C-E219-45AA-868E-92286187DEDF";
    
    override func viewDidLoad() {
        super.viewDidLoad()
        mtsManager.setServiceUUID(uuidString: kMTSServiceUUID)
        mtsManager.loggingEnabled = true
        mtsManager.delegate.addDelegate(self)
        addToolbarToNumberPads()
        registerUserDefaults()
        updateInterface()
        setupAudioPlayers()
    }
    
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
    }
        
    func updateInterface() {
        headerVersionLabel?.text = versionString
        autoConnectThresholdTextField?.text = String(mtsManager.autoConnectRSSIThreshold)
        autoDisconnectThresholdTextField?.text = String(mtsManager.autoDisconnectRSSIThreshold)
        autoDisconnectIntervalTextField?.text = String(mtsManager.autoDisconnectInterval)
        scanTimeoutTextField?.text = String(mtsManager.scanTimeoutInterval)
        updateCardDataInputStates()
        updateConnectedTerminalIdentifiers()
        txAttenuationLevelLabel?.text = "\(lastTxAttenuationLevel.rawValue)"
        if nil == mtsBeacon1 {
            connectedRSSILabel1?.text = MTSConstants.noValuePlaceholder
        }

        if nil == mtsBeacon2 {
            connectedRSSILabel2?.text = MTSConstants.noValuePlaceholder
        }
    }
    
    func updateConnectedTerminalIdentifiers() {
        if let beacon = mtsBeacon1 {
            connectedTerminalPeripheralIdentifier?.text = "ID: \(beacon.peripheral.identifier.uuidString)"
            connectedTerminalMfgDataLabel?.text = "MFG: \(beacon.manufacturerData?.hex ?? MTSConstants.noValuePlaceholder)"
        } else {
            connectedTerminalPeripheralIdentifier?.text = MTSConstants.noValuePlaceholder
            connectedTerminalMfgDataLabel?.text = MTSConstants.noValuePlaceholder
        }
    }
    
    func updateCardDataInputStates() {
        var isValid = false
        if let cardData = cardDataTextField?.text {
            isValid = mtsManager.isValidCardData(cardData)
        }
        let isBLEConnected = nil != mtsBeacon1
        let isConnected = isBLEConnected
        writeCardDataButton?.isEnabled =  isValid && isConnected
        if isValid {
            lastWriteAtLabel?.textColor = UIColor.label
            cardDataTextField?.textColor = UIColor.label
            if isConnected {
                lastWriteAtLabel?.text = "Ready to write card data."
                if isBLEConnected {
                    writeCardDataButton?.setTitle("Write to BLE", for: .normal)
                } else {
                    writeCardDataButton?.setTitle("Write", for: .normal)
                }
            } else {
                lastWriteAtLabel?.textColor = .red
                lastWriteAtLabel?.text = "Connect BLE or MFi to write card data."
            }
            
        } else {
            if 0 == (cardDataTextField?.text?.count ?? 0) {
                lastWriteAtLabel?.text = ""
            } else {
                lastWriteAtLabel?.text = "Valid Card Data is \(mtsManager.cardDataCharacterCountMax) digits."
                lastWriteAtLabel?.textColor = .red
                cardDataTextField?.textColor = .red
            }
        }
    }
    
    func openBluetoothSettings() {
        if let url = URL(string: "App-Prefs:root=Bluetooth"){
            UIApplication.shared.open(url, options: convertToUIApplicationOpenExternalURLOptionsKeyDictionary([:]), completionHandler: nil)
        }
    }
    
    func addToolbarToNumberPads() {
        let toolbar = UIToolbar()
        let doneButton = UIBarButtonItem(barButtonSystemItem: .done, target: self, action: #selector(dismissKeyboard))
        toolbar.items = [doneButton]
        toolbar.sizeToFit()
        autoConnectThresholdTextField?.inputAccessoryView = toolbar
        autoDisconnectThresholdTextField?.inputAccessoryView = toolbar
        autoDisconnectIntervalTextField?.inputAccessoryView = toolbar
        scanTimeoutTextField?.inputAccessoryView = toolbar
        cardDataTextField?.inputAccessoryView = toolbar
    }
    
    @objc func dismissKeyboard() {
        autoConnectThresholdTextField?.resignFirstResponder()
        autoDisconnectThresholdTextField?.resignFirstResponder()
        autoDisconnectIntervalTextField?.resignFirstResponder()
        scanTimeoutTextField?.resignFirstResponder()
        cardDataTextField?.resignFirstResponder()
    }

    var versionString: String {
        get {
            let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? MTSConstants.noValuePlaceholder
            let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? MTSConstants.noValuePlaceholder
            return "MTS Example \(version) (\(build))"
        }
    }
    
    // MARK: UITableViewController
    
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        var cell = super.tableView(tableView, cellForRowAt: indexPath)
        guard statusCellIndexPath.section == indexPath.section else {
            return cell
        }
        switch indexPath.row {
        case 0:
            if nil != mtsBeacon1 {
                cell.textLabel?.text = "Connected"
                cell.detailTextLabel?.text = "Disconnect"
            } else {
                cell = updateCellForState(cell: cell)
            }
            
        case 1:
            if nil != mtsBeacon2 {
                cell.textLabel?.text = "Connected"
                cell.detailTextLabel?.text = "Disconnect"
            } else {
                cell = updateCellForState(cell: cell)
            }
            
        default:
            return cell
        }
        
        return cell
    }
    
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        // Don't show the second beacon row unless the first is already connected.
        if 0 == section && nil == mtsBeacon1 && nil == mtsBeacon2 {
            return 1
        }
        // Normal case
        else {
            return super.tableView(tableView, numberOfRowsInSection: section)
        }
    }
    
    func updateCellForState(cell: UITableViewCell) -> UITableViewCell {

        switch mtsManager.bluetoothDiscoveryState {
        case .notReady:
            cell.textLabel?.text = "Not Ready"
            cell.detailTextLabel?.text = "Open Bluetooth Settings"
        case .inactive:
            cell.textLabel?.text = "Inactive"
            cell.detailTextLabel?.text = "Start Scanning"
        case .scanning:
            cell.textLabel?.text = "Scanning"
            cell.detailTextLabel?.text = "Stop Scanning"
        }
        return cell
    }
    
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        
        if 2 == indexPath.section {
            showTxAttenuationOptions()
            return
        }
        
        guard statusCellIndexPath.section == indexPath.section else {
            return
        }

        switch indexPath.row {
        case 0:
            if let mtsBeacon1 = mtsBeacon1 {
                mtsManager.disconnect(mtsBeacon: mtsBeacon1)
            } else {
                switch mtsManager.bluetoothDiscoveryState {
                case .notReady:
                    openBluetoothSettings()
                case .inactive:
                    mtsManager.startScanning()
                case .scanning:
                    mtsManager.stopScanning()
                }
            }

        case 1:
            if let mtsBeacon2 = mtsBeacon2 {
                mtsManager.disconnect(mtsBeacon: mtsBeacon2)
            } else {
                switch mtsManager.bluetoothDiscoveryState {
                case .notReady:
                    openBluetoothSettings()
                case .inactive:
                    mtsManager.startScanning()
                case .scanning:
                    mtsManager.stopScanning()
                }
            }
        default:
            break
        }

    }
    
    //MARK: MTSManagerDelegate - Bluetooth Example
    func bluetoothDiscoveryStateChanged(oldState: BluetoothDiscoveryState, newState: BluetoothDiscoveryState) {
        tableView.reloadData()
    }
    
    func bluetoothConnectionEvent(bluetoothEvent: BluetoothConnectionEvent, mtsBeacon: MTSBeacon?) {
        updateDisplayBeacons(event: bluetoothEvent, mtsBeacon: mtsBeacon)
        
        if .connect == bluetoothEvent, let mtsBeacon = mtsBeacon {
            playConnectSound()
            writeExampleCardData(mtsBeacon: mtsBeacon)
            writeTxAttenuationLevel(mtsBeacon: mtsBeacon)
        }
        else if [.disconnect, .disabled].contains(bluetoothEvent) {
            lastWriteAtLabel?.textColor = .black
            lastWriteAtLabel?.text = "Last write: none since connect."
        }
        waitingForBLEPostWriteRead = false
        updateCardDataInputStates()
        updateConnectedTerminalIdentifiers()
        tableView.reloadData()
    }
    

    // Likely not relevant to customer implementation, but what is happening here:
    // Demo support is requested for 0-2 two beacon connections, so find conditional rather than array handling in this example.
    // The first beacon to connect is assigned mtsBeacon1.
    // Only if a new beacon is connected while mtsBeacon1 is already assigned do we assign the new beacon to mtsBeacon2.
    func updateDisplayBeacons(event: BluetoothConnectionEvent, mtsBeacon: MTSBeacon?) {
        
        switch event {
        case .connect:
            if nil == mtsBeacon1 {
                mtsBeacon1 = mtsBeacon
            } else if nil == mtsBeacon2 {
                mtsBeacon2 = mtsBeacon
            } else {
                NSLog("\(#function) unexpected assignment of more than two connected beacons.")
            }
        case .pendingUserDisconnect:
            break
        case .disconnect:
            if mtsBeacon == mtsBeacon1 {
                mtsBeacon1 = nil
            } else if mtsBeacon == mtsBeacon2 {
                mtsBeacon2 = nil
            } else {
                NSLog("\(#function) unexpected disconnect of an untracked beacon.")
            }
        case .disabled:
            mtsBeacon1 = nil
            mtsBeacon2 = nil
        }
        tableView.reloadData()
    }
    
    func writeTxAttenuationLevel(mtsBeacon: MTSBeacon) {
        mtsManager.writeTxAttenuationLevel(level: lastTxAttenuationLevel, mtsBeacon: mtsBeacon)
    }
    
    func writeExampleCardData(mtsBeacon: MTSBeacon) {
        // Example flow for MTSManagerDelegate.  Just demonstrates delegate callbacks.
        writeCardDataToBeacon(mtsBeacon)
    }
    
    func writeCardDataToBeacon(_ mtsBeacon: MTSBeacon) {
        guard let cardDataString = cardDataTextField?.text else {
            NSLog("\(#function) called with nil cardDataTextField.")
            return
        }
        do {
            try mtsManager.writeCardDataToBluetooth(cardDataString: cardDataString, mtsBeacon: mtsBeacon)
        } catch MTSError.invalidCardDataCharacterCount(let requiredCount) {
            NSLog("\(#function) The cardData parameter character count must be \(requiredCount.description) digits.")
        } catch {
            NSLog("\(#function) Failed with error: \(error.localizedDescription)")
        }
    }
    
    func receivedTerminalId(terminalId: String) {
        NSLog("\(#function) terminalId: \(terminalId)")
    }
    
    func didWriteCardDataToBluetooth(error: Error?, mtsBeacon: MTSBeacon) {
        if nil == error {
            NSLog("\(#function) waitingForBLEPostWriteRead")
            waitingForBLEPostWriteRead = true
            mtsManager.requestCardData(mtsBeacon: mtsBeacon)
        } else {
            NSLog("\(#function) Failed due to error: \(String(describing: error?.localizedDescription))")
        }
    }
    
    func receivedCardData(cardData: Data, mtsBeacon: MTSBeacon) {
        
        cardDataLabel?.text = cardData.hex
                
        // 5. Receive a delegate callback from the player ID request.
        guard waitingForBLEPostWriteRead else {
            // This is e.g. the result of a post-connect characteristic read.
            return
        }
        waitingForBLEPostWriteRead = false
        let receivedAtString = dateFormatter.string(from: Date())
        lastWriteAtLabel?.textColor = .secondaryLabel
        lastWriteAtLabel?.text = "Last successful BLE write: \(receivedAtString)."
    }

    let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .iso8601)
        formatter.dateFormat = "yyyy-MM-dd' 'HH:mm:ss"
        return formatter
    }()
        
    func receivedTerminalKind(kind: MTSBeacon.TerminalKind, mtsBeacon: MTSBeacon) {
        guard mtsBeacon == mtsBeacon1 else { return }
        terminalKindLabel?.text = kind.description
    }
    
    func updateOnConnectedRSSIReceipt(rssi: Int, mtsBeacon: MTSBeacon) {
        switch mtsBeacon {
        case mtsBeacon1:
            connectedRSSILabel1?.text = "\(rssi)"
        case mtsBeacon2:
            connectedRSSILabel2?.text = "\(rssi)"
        default:
            break
        }
        
        if nil == mtsBeacon1 {
            connectedRSSILabel1?.text = MTSConstants.noValuePlaceholder
        }

        if nil == mtsBeacon2 {
            connectedRSSILabel2?.text = MTSConstants.noValuePlaceholder
        }
    }
    
    func receivedSasSerialNumber(serialNumber: String?, mtsBeacon: MTSBeacon) {
        guard mtsBeacon == mtsBeacon1 else { return }
        sasSerialNumberLabel?.text = serialNumber ?? MTSConstants.noValuePlaceholder
    }
    
    func receivedLocation(location: String?, mtsBeacon: MTSBeacon) {
        guard mtsBeacon == mtsBeacon1 else { return }
        locationLabel?.text = location ?? MTSConstants.noValuePlaceholder
    }
    
    func receivedAssetNumber(assetNumber: UInt32, mtsBeacon: MTSBeacon) {
        guard mtsBeacon == mtsBeacon1 else { return }
        assetNumberLabel?.text = String(assetNumber)
    }
    
    func receivedDenomination(denomination: UInt32, mtsBeacon: MTSBeacon) {
        guard mtsBeacon == mtsBeacon1 else { return }
        denominationLabel?.text = String(denomination)
    }
    
    func receivedGmiLinkActive(isActive: Bool, mtsBeacon: MTSBeacon) {
        guard mtsBeacon == mtsBeacon1 else { return }
        gmiLinkActiveLabel?.text = isActive ? "Active" : "Inactive"
    }
    
    func receivedTxAttenuationLevel(level: TxAttenuationLevel, mtsBeacon: MTSBeacon) {
        guard mtsBeacon == mtsBeacon1 else { return }
        // N.B. interface in the example shows the last value selected by the operator.
        // This function need not update the interface with read response values.
    }

    // MARK: Connect/Disconnect Sounds
    
    var connectAVAudioPlayer: AVAudioPlayer?
    var disconnectAVAudioPlayer: AVAudioPlayer?
    
    private func setupAudioPlayers() {
        guard let connectPath = Bundle.main.path(forResource: "connect", ofType: "mp3") else {
            return
        }
        let connectURL = NSURL.fileURL(withPath: connectPath)
        connectAVAudioPlayer = try? AVAudioPlayer(contentsOf: connectURL)

        guard let disconnectPath = Bundle.main.path(forResource: "disconnect", ofType: "mp3") else {
            return
        }
        let disconnectURL = NSURL.fileURL(withPath: disconnectPath)
        disconnectAVAudioPlayer = try? AVAudioPlayer(contentsOf: disconnectURL)
    }
    
    public func playConnectSound() {
        connectAVAudioPlayer?.play()
    }
    
    public func playDisconnectSound() {
        disconnectAVAudioPlayer?.play()
    }
    
    // MARK: UITextField
    
    // The text field validation is just for the example app interface, in practice these RSSI values wouldn't be exposed via UI, rather hard-coded after init of mtsManager like:
    // mtsManager.autoConnectRSSIThreshold    = -45
    // mtsManager.autoDisconnectRSSIThreshold = -70
    
    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        textField.resignFirstResponder()
        return true
    }
    
    func textField(_ textField: UITextField, shouldChangeCharactersIn range: NSRange, replacementString string: String) -> Bool {
        let allowedCharacters = CharacterSet.decimalDigits
        let characterSet = CharacterSet(charactersIn: string)
        return allowedCharacters.isSuperset(of: characterSet)
    }
    
    @IBAction func autoConnectRSSITextFieldEditingDidChange(_ textField: UITextField) {
        let text = textField.text ?? ""
        var rssi = Int(text) ?? 0
        if rssi > 0 {
            rssi = -rssi
        }
        mtsManager.autoConnectRSSIThreshold = rssi
        updateInterface()
    }
    
    @IBAction func autoDisconnectRSSITextFieldEditingDidChange(_ textField: UITextField) {
        let text = textField.text ?? ""
        var rssi = Int(text) ?? 0
        if rssi > 0 {
            rssi = -rssi
        }
        mtsManager.autoDisconnectRSSIThreshold = rssi
        updateInterface()
    }

    @IBAction func autoDisconnectIntervalTextFieldEditingDidChange(_ textField: UITextField) {
        let text = textField.text ?? ""
        var interval = Int(text) ?? 0
        if interval < 0 {
            interval = -interval
        }
        mtsManager.autoDisconnectInterval = interval
        updateInterface()
    }
    
    @IBAction func scanDurationTextFieldEditingDidChange(_ textField: UITextField) {
        let text = textField.text ?? ""
        let duration = Int(text) ?? 0
        mtsManager.scanTimeoutInterval = duration
        updateInterface()
    }

    @IBAction func cardDataTextFieldEditingDidChange(_ textField: UITextField) {
        updateInterface()
    }

    @IBAction func touchUpInsideCardDataButton(_ sender: Any) {
        if let mtsBeacon1 = mtsBeacon1 {
            writeCardDataToBeacon(mtsBeacon1)
        }
    }
    
    @IBAction func showTxAttenuationOptions() {
        let alert = UIAlertController(title: "Tx Attenuation Level",
                   message: "",
                   preferredStyle: .actionSheet)
        
        for level in TxAttenuationLevel.allCases {
            alert.addAction(
                UIAlertAction(title: "\(level.rawValue)",
                          style: .default) { (action) in
                    self.lastTxAttenuationLevel = level
                    self.updateInterface()
                }
            )
        }
       self.present(alert, animated: true) {}
    }

    var lastTxAttenuationLevel: TxAttenuationLevel {
        get {
            var level = TxAttenuationLevel.zero
            let raw = UserDefaults.standard.integer(forKey: kTxAttenuationLevelUserDefaultKey)
            if let value = TxAttenuationLevel(rawValue: UInt8(raw)) {
                level = value
            }
            return level
        }
        set {
            UserDefaults.standard.set(newValue.rawValue, forKey: kTxAttenuationLevelUserDefaultKey)
        }
    }

    let kTxAttenuationLevelUserDefaultKey = "kTxAttenuationLevelUserDefaultKey"
    private func registerUserDefaults() {
        UserDefaults.standard.register(defaults: [
            kTxAttenuationLevelUserDefaultKey : TxAttenuationLevel.zero.rawValue
        ])
    }
    
}

// Helper function inserted by Swift 4.2 migrator.
fileprivate func convertToUIApplicationOpenExternalURLOptionsKeyDictionary(_ input: [String: Any]) -> [UIApplication.OpenExternalURLOptionsKey: Any] {
    return Dictionary(uniqueKeysWithValues: input.map { key, value in (UIApplication.OpenExternalURLOptionsKey(rawValue: key), value)})
}
