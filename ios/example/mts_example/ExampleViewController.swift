//
//
//  Copyright © 2020 Mobile Technology Solutions, Inc. All rights reserved.
//

import UIKit
import AudioToolbox

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
    @IBOutlet var stickyConnectionLabel: UILabel?
    @IBOutlet var cardDataLabel: UILabel?
    @IBOutlet var sentinelSegmentedControl: UISegmentedControl?
    @IBOutlet var connectedRSSILabel: UILabel?
    @IBOutlet var connectedTerminalPeripheralIdentifier: UILabel?
    @IBOutlet var connectedTerminalMfgDataLabel: UILabel?

    let mtsManager = MTSManager.sharedInstance
    let statusCellIndexPath = IndexPath(row: 0, section: 0)
    var lastConnectionStatus = BluetoothConnectionState.notReady
    var waitingForBLEPostWriteRead = false
    
    override func viewDidLoad() {
        super.viewDidLoad()
        mtsManager.loggingEnabled = false
        mtsManager.delegate.addDelegate(self)
        addToolbarToNumberPads()
        updateInterface()
        cardDataTextField?.text = mtsManager.cardData
    }
    
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        mtsManager.setupForAccessory()
    }
    
    func updateInterface() {
        headerVersionLabel?.text = versionString
        autoConnectThresholdTextField?.text = String(mtsManager.autoConnectRSSIThreshold)
        autoDisconnectThresholdTextField?.text = String(mtsManager.autoDisconnectRSSIThreshold)
        autoDisconnectIntervalTextField?.text = String(mtsManager.autoDisconnectInterval)
        scanTimeoutTextField?.text = String(mtsManager.scanTimeoutInterval)
        sentinelSegmentedControl?.selectedSegmentIndex = mtsManager.sentinelState
        updateCardDataInputStates()
        updateConnectedTerminalIdentifiers()
        if .connected != mtsManager.bluetoothConnectionState {
            connectedRSSILabel?.text = MTSConstants.noValuePlaceholder
        }
    }
    
    func updateConnectedTerminalIdentifiers() {
        if let beacon = mtsManager.connectedMTSBeacon {
            connectedTerminalPeripheralIdentifier?.text = "ID: \(beacon.peripheral.identifier.uuidString)"
            connectedTerminalMfgDataLabel?.text = "MFG: \(beacon.manufacturerData?.hex ?? MTSConstants.noValuePlaceholder)"
        } else {
            NSLog("\(#function) no connectedMTSBeacon")
            connectedTerminalPeripheralIdentifier?.text = MTSConstants.noValuePlaceholder
            connectedTerminalMfgDataLabel?.text = MTSConstants.noValuePlaceholder
        }
    }
    
    func updateCardDataInputStates() {
        let cardData = mtsManager.cardData
        let isValid = mtsManager.isValidCardData(cardData)
        let isBLEConnected = .connected == mtsManager.bluetoothConnectionState
        let isMFiConnected = .connected == mtsManager.accessoryConnectionState
        let isConnected = isBLEConnected || isMFiConnected
        writeCardDataButton?.isEnabled =  isValid && isConnected
        if isValid {
            lastWriteAtLabel?.textColor = UIColor.label
            cardDataTextField?.textColor = UIColor.label
            if isConnected {
                lastWriteAtLabel?.text = "Ready to write card data."
                if isBLEConnected {
                    writeCardDataButton?.setTitle("Write to BLE", for: .normal)
                }
                else if isMFiConnected {
                    writeCardDataButton?.setTitle("Write to MFi", for: .normal)
                } else {
                    writeCardDataButton?.setTitle("Write", for: .normal)
                }
            } else {
                lastWriteAtLabel?.textColor = .red
                lastWriteAtLabel?.text = "Connect BLE or MFi to write card data."
            }
            
        } else {
            if 0 == cardData.count {
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
        let cell = super.tableView(tableView, cellForRowAt: indexPath)
        guard indexPath == statusCellIndexPath else {
            return cell
        }
        switch mtsManager.bluetoothConnectionState {
        case .notReady:
            cell.textLabel?.text = "Not Ready"
            cell.detailTextLabel?.text = "Open Bluetooth Settings"
        case .inactive:
            cell.textLabel?.text = "Inactive"
            cell.detailTextLabel?.text = "Start Scanning"
        case .scanning:
            cell.textLabel?.text = "Scanning"
            cell.detailTextLabel?.text = "Stop Scanning"
        case .connected:
            cell.textLabel?.text = "Connected"
            cell.detailTextLabel?.text = "Disconnect"
        case .attemptingToReconnect:
            cell.textLabel?.text = "Attempting Reconnect"
            cell.detailTextLabel?.text = "Disconnect"
        }
        return cell
    }
    
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard indexPath == statusCellIndexPath else {
            return
        }
        switch mtsManager.bluetoothConnectionState {
        case .notReady:
            openBluetoothSettings()
        case .inactive:
            mtsManager.startScanning()
        case .scanning:
            mtsManager.stopScanning()
        case .connected:
            mtsManager.disconnect()
        case .attemptingToReconnect:
            mtsManager.disconnect()
        }
    }

    
    //MARK: MTSManagerDelegate - Bluetooth Example

    func bluetoothConnectionStateChanged() {
        tableView.reloadData()
        if .connected == mtsManager.bluetoothConnectionState {
            playConnectSound()
            writeExampleCardData()
        }
        else if .scanning  == mtsManager.bluetoothConnectionState &&
                .connected == lastConnectionStatus
        {
            playDisconnectSound()
            cardDataTextField?.text = mtsManager.cardData
            lastWriteAtLabel?.textColor = .black
            lastWriteAtLabel?.text = "Last write: none since connect."
        }
        waitingForBLEPostWriteRead = false
        lastConnectionStatus = mtsManager.bluetoothConnectionState
        updateCardDataInputStates()
        updateConnectedTerminalIdentifiers()
    }

    func writeExampleCardData() {
        // Example flow for MTSManagerDelegate.  Just demonstrates delegate callbacks.
        writeCardDataToBeacon()
    }
    
    func writeCardDataToBeacon() {
        do {
            try mtsManager.writeCardDataToBluetooth(cardDataString: mtsManager.cardData)
        } catch MTSError.invalidCardDataCharacterCount(let requiredCount) {
            NSLog("\(#function) The cardData parameter character count must be \(requiredCount.description) digits.")
        } catch {
            NSLog("\(#function) Failed with error: \(error.localizedDescription)")
        }
    }
    
    func receivedTerminalId(terminalId: String) {
        NSLog("\(#function) terminalId: \(terminalId)")
    }
    
    func didWriteCardDataToBluetooth(error: Error?) {
        if nil == error {
            NSLog("\(#function) waitingForBLEPostWriteRead")
            waitingForBLEPostWriteRead = true
            mtsManager.requestCardData()
        } else {
            NSLog("\(#function) Failed due to error: \(String(describing: error?.localizedDescription))")
        }
    }
    
    func receivedCardData(data: Data) {
        
        cardDataLabel?.text = data.hex
                
        // 5. Receive a delegate callback from the player ID request.
        guard waitingForBLEPostWriteRead else {
            // This is e.g. the result of a post-connect characteristic read.
            return
        }
        waitingForBLEPostWriteRead = false
        let receivedAtString = dateFormatter.string(from: Date())
        lastWriteAtLabel?.textColor = .black
        lastWriteAtLabel?.text = "Last successful BLE write: \(receivedAtString)."
    }

    let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .iso8601)
        formatter.dateFormat = "yyyy-MM-dd' 'HH:mm:ss"
        return formatter
    }()
    
    func receivedStickyConnectionState(isSticky: Bool) {
        stickyConnectionLabel?.text = isSticky ? "Yes" : "No"
    }
    
    func receivedTerminalKind(kind: MTSBeacon.TerminalKind) {
        terminalKindLabel?.text = kind.description
    }
    
    func updateOnConnectedRSSIReceipt(rssi: Int) {
        connectedRSSILabel?.text = "\(rssi)"
    }
    
    //MARK: MTSManagerDelegate - Wired Accessory Example
    
    // Call writeCardDataToAccessory when needed.  In this example the app is writing the Player ID immediately upon connection
    // whether at cold launch of the app or due to plug-in while the app is in use, or if the app is in the background after use.
    func accessoryConnectionStateChanged() {
        updateInterface()
        if .connected == mtsManager.accessoryConnectionState {
            writeCardDataToAccessory()
        } else {
            cardDataTextField?.text = mtsManager.cardData
        }
        waitingForBLEPostWriteRead = false
        updateCardDataInputStates()
    }
    
    func writeCardDataToAccessory() {
        do {
            try mtsManager.writeCardDataToAccessory(
                cardData: mtsManager.cardData
            )
        } catch MTSError.invalidCardDataCharacterCount(let requiredCount) {
            let message = "The cardData parameter character count must be \(requiredCount.description) digits."
            NSLog("\(#function) \(message)")
            displayAccessoryWriteError(description: message)
        } catch MTSError.accessoryConnectionNotReady(let description) {
            let message = "Failed with accessory not ready error."
            NSLog("\(#function) \(message) \(description)")
            displayAccessoryWriteError(description: message)
        } catch {
            NSLog("\(#function) Failed with error: \(error.localizedDescription)")
            displayAccessoryWriteError(description: error.localizedDescription)
        }
    }
    
    func displayAccessoryWriteError(description: String) {
        lastWriteAtLabel?.textColor = .red
        lastWriteAtLabel?.text = "MFi write failed: \(description)."
    }
    
    func didWriteCardDataToAccessory(error: Error?) {
        if nil == error {
            // The wired card reader accessory does not provide a cardData read; at this point we know the bytes have been
            // written to the stream without error.  Look at the card reader bezel for an indication that the write worked.
            NSLog("\(#function)")
            let receivedAtString = dateFormatter.string(from: Date())
            lastWriteAtLabel?.textColor = .black
            lastWriteAtLabel?.text = "Last MFi write: \(receivedAtString)."
        } else {
            NSLog("\(#function) failed with error: \(String(describing: error?.localizedDescription))")
        }
    }
    
    // MARK: Connect/Disconnect Sounds
    
    public func playConnectSound() {
        NSLog("\(#function)")
        let systemSoundID: SystemSoundID = 1115
        AudioServicesPlaySystemSound(systemSoundID)
    }
    
    public func playDisconnectSound() {
        let systemSoundID: SystemSoundID = 1116
        AudioServicesPlaySystemSound(systemSoundID)
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
        NSLog("\(#function) assigning mtsManager.scanTimeoutInterval: \(duration)")
        mtsManager.scanTimeoutInterval = duration
        updateInterface()
    }

    @IBAction func cardDataTextFieldEditingDidChange(_ textField: UITextField) {
        NSLog("\(#function) textField: \(textField)")
        mtsManager.cardData = textField.text ?? ""
        updateInterface()
    }

    @IBAction func touchUpInsideCardDataButton(_ sender: Any) {
        let isBLEConnected = .connected == mtsManager.bluetoothConnectionState
        let isMFiConnected = .connected == mtsManager.accessoryConnectionState
        if isBLEConnected {
            writeCardDataToBeacon()
        }
        else if isMFiConnected {
            writeCardDataToAccessory()
        }
    }

    @IBAction func sentinelSegmentedControlChanged(_ sender: UISegmentedControl) {
        mtsManager.sentinelState = sender.selectedSegmentIndex
    }
    
}

// Helper function inserted by Swift 4.2 migrator.
fileprivate func convertToUIApplicationOpenExternalURLOptionsKeyDictionary(_ input: [String: Any]) -> [UIApplication.OpenExternalURLOptionsKey: Any] {
	return Dictionary(uniqueKeysWithValues: input.map { key, value in (UIApplication.OpenExternalURLOptionsKey(rawValue: key), value)})
}
