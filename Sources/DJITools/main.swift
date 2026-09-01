import CoreBluetooth
import DJIProtocol
import Foundation

private let version = "0.1.0"
private let regulatoryFlag = "--i-understand-regulatory-risk"

private enum Action {
    case devices
    case status
    case getCountry
    case setCountry(String)
    case getBand
    case setBand(OA4WiFiBand)
    case setFrequency(UInt16)
    case restart

    var writesRadioConfiguration: Bool {
        switch self {
        case .setCountry, .setBand, .setFrequency, .restart: return true
        default: return false
        }
    }
}

private struct Options {
    let action: Action
    let device: String?
    let timeout: TimeInterval
    let json: Bool
    let regulatoryAcknowledgement: Bool
}

private enum CLIError: Error, CustomStringConvertible {
    case message(String)

    var description: String {
        switch self { case .message(let text): return text }
    }
}

private func usage() -> String {
    """
    DJI-Tools \(version) — experimental DJI camera controls

    USAGE
      dji-tools devices [--timeout SECONDS] [--json]
      dji-tools wifi status [--device NAME_OR_UUID] [--json]
      dji-tools wifi country get [--device NAME_OR_UUID] [--json]
      dji-tools wifi country set CC \(regulatoryFlag)
      dji-tools wifi band get [--device NAME_OR_UUID] [--json]
      dji-tools wifi band set 2.4|5|auto \(regulatoryFlag)
      dji-tools wifi frequency set MHZ \(regulatoryFlag)
      dji-tools wifi restart \(regulatoryFlag)

    GLOBAL OPTIONS
      --device VALUE    Match a camera advertisement name or Bluetooth UUID.
      --timeout N       Stop after N seconds (default: 30; devices: 8).
      --json            Emit newline-delimited JSON.
      --version         Print the version.
      -h, --help        Show this help.

    Radio rules differ by location. A country or band accepted by firmware is
    not proof that it is legal or client-compatible. Mutating commands require
    \(regulatoryFlag); use settings permitted where the camera is operating.
    """
}

private func parseArguments(_ raw: [String]) throws -> Options? {
    if raw.isEmpty || raw.contains("--help") || raw.contains("-h") {
        print(usage())
        return nil
    }
    if raw == ["--version"] {
        print(version)
        return nil
    }

    var args: [String] = []
    var device: String?
    var timeout: TimeInterval?
    var json = false
    var acknowledged = false
    var index = 0
    while index < raw.count {
        switch raw[index] {
        case "--device":
            guard index + 1 < raw.count else { throw CLIError.message("--device requires a value") }
            device = raw[index + 1]
            index += 2
        case "--timeout":
            guard index + 1 < raw.count, let value = Double(raw[index + 1]), (1...300).contains(value) else {
                throw CLIError.message("--timeout must be between 1 and 300 seconds")
            }
            timeout = value
            index += 2
        case "--json":
            json = true
            index += 1
        case regulatoryFlag:
            acknowledged = true
            index += 1
        default:
            args.append(raw[index])
            index += 1
        }
    }

    let action: Action
    switch args {
    case ["devices"]:
        action = .devices
    case ["wifi", "status"]:
        action = .status
    case ["wifi", "country", "get"]:
        action = .getCountry
    case _ where args.count == 4 && Array(args.prefix(3)) == ["wifi", "country", "set"]:
        let code = args[3]
        guard code.utf8.count == 2, code.utf8.allSatisfy({ (65...90).contains($0) || (97...122).contains($0) }) else {
            throw CLIError.message("country must be a two-letter ISO 3166-1 alpha-2 code")
        }
        action = .setCountry(code.uppercased())
    case ["wifi", "band", "get"]:
        action = .getBand
    case _ where args.count == 4 && Array(args.prefix(3)) == ["wifi", "band", "set"]:
        let value = args[3]
        let band: OA4WiFiBand?
        switch value.lowercased() {
        case "2.4", "2.4ghz", "24": band = .ghz24
        case "5", "5ghz": band = .ghz5
        case "auto", "automatic", "dual": band = .automatic
        default: band = nil
        }
        guard let band else { throw CLIError.message("band must be 2.4, 5, or auto") }
        action = .setBand(band)
    case _ where args.count == 4 && Array(args.prefix(3)) == ["wifi", "frequency", "set"]:
        let value = args[3]
        guard let mhz = UInt16(value), (2_400...5_900).contains(mhz) else {
            throw CLIError.message("frequency must be a value in MHz between 2400 and 5900")
        }
        action = .setFrequency(mhz)
    case ["wifi", "restart"]:
        action = .restart
    default:
        throw CLIError.message("unknown command\n\n\(usage())")
    }

    if action.writesRadioConfiguration && !acknowledged {
        throw CLIError.message("this command changes radio configuration; re-run with \(regulatoryFlag)")
    }
    return Options(
        action: action,
        device: device,
        timeout: timeout ?? (args == ["devices"] ? 8 : 30),
        json: json,
        regulatoryAcknowledgement: acknowledged
    )
}

private func isActionCamera(name: String, manufacturerData: Data?) -> Bool {
    if name.localizedCaseInsensitiveContains("OA4") { return true }
    guard let data = manufacturerData, data.count >= 4 else { return false }
    let company = UInt16(data[0]) | (UInt16(data[1]) << 8)
    let model = UInt16(data[2]) | (UInt16(data[3]) << 8)
    return (company == 0x08aa || company == 0xf7aa) && model == 0x0014
}

private final class Reporter {
    private let json: Bool
    init(json: Bool) { self.json = json }

    func event(_ name: String, _ fields: [String: Any] = [:], human: String? = nil) {
        if json {
            var object = fields
            object["event"] = name
            if let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
               let line = String(data: data, encoding: .utf8) {
                print(line)
            }
        } else if let human {
            print(human)
        }
        fflush(stdout)
    }
}

private final class DeviceScanner: NSObject, CBCentralManagerDelegate {
    private var central: CBCentralManager!
    private let reporter: Reporter
    private let done = DispatchSemaphore(value: 0)
    private var seen = Set<UUID>()

    init(reporter: Reporter) {
        self.reporter = reporter
        super.init()
        central = CBCentralManager(delegate: self, queue: DispatchQueue(label: "dji-tools.scan"))
    }

    func run(seconds: TimeInterval) -> Int32 {
        DispatchQueue.global().asyncAfter(deadline: .now() + seconds) { [weak self] in
            self?.central.stopScan()
            self?.done.signal()
        }
        _ = done.wait(timeout: .now() + seconds + 2)
        return 0
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central.state == .poweredOn else {
            if central.state == .unsupported || central.state == .unauthorized || central.state == .poweredOff {
                reporter.event("bluetoothUnavailable", ["state": central.state.rawValue], human: "Bluetooth unavailable (state \(central.state.rawValue)).")
                done.signal()
            }
            return
        }
        central.scanForPeripherals(withServices: [CBUUID(string: "FFF0")], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard seen.insert(peripheral.identifier).inserted else { return }
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name ?? "Unknown"
        let manufacturer = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data
        reporter.event("device", [
            "name": name,
            "identifier": peripheral.identifier.uuidString,
            "rssi": RSSI.intValue,
            "recognizedAction4": isActionCamera(name: name, manufacturerData: manufacturer),
        ], human: "\(name)  \(peripheral.identifier.uuidString)  RSSI \(RSSI)")
    }
}

private final class CameraSession: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private let options: Options
    private let reporter: Reporter
    private let done = DispatchSemaphore(value: 0)
    private var central: CBCentralManager!
    private var camera: CBPeripheral?
    private var writeCharacteristic: CBCharacteristic?
    private var notifyReady = Set<String>()
    private var decoder = DUMLStreamDecoder()
    private var operationStarted = false
    private var finished = false
    private var exitCode: Int32 = 2
    private var statusCountry: String?
    private var phase = ""
    private var previousCountry: String?
    private var previousBand: OA4WiFiBand?

    init(options: Options, reporter: Reporter) {
        self.options = options
        self.reporter = reporter
        super.init()
        central = CBCentralManager(delegate: self, queue: DispatchQueue(label: "dji-tools.ble"))
    }

    func run() -> Int32 {
        DispatchQueue.global().asyncAfter(deadline: .now() + options.timeout) { [weak self] in
            self?.finish(code: 2, event: "timeout", human: "Timed out waiting for the camera.")
        }
        _ = done.wait(timeout: .now() + options.timeout + 2)
        if let camera { central.cancelPeripheralConnection(camera) }
        return exitCode
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central.state == .poweredOn else {
            if central.state == .unsupported || central.state == .unauthorized || central.state == .poweredOff {
                finish(code: 3, event: "bluetoothUnavailable", human: "Bluetooth is unavailable or permission was denied.")
            }
            return
        }
        central.scanForPeripherals(withServices: [CBUUID(string: "FFF0")])
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name ?? ""
        let manufacturer = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data
        guard isActionCamera(name: name, manufacturerData: manufacturer) else { return }
        if let wanted = options.device?.lowercased() {
            guard name.lowercased().contains(wanted) || peripheral.identifier.uuidString.lowercased() == wanted else { return }
        }
        central.stopScan()
        camera = peripheral
        peripheral.delegate = self
        reporter.event("connecting", ["name": name, "identifier": peripheral.identifier.uuidString], human: "Connecting to \(name)…")
        central.connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([CBUUID(string: "FFF0")])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        finish(code: 3, event: "connectFailed", ["error": error?.localizedDescription ?? "unknown"], human: "Could not connect to the camera.")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        if !finished { finish(code: 3, event: "disconnected", human: "Camera disconnected.") }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil else {
            finish(code: 3, event: "serviceDiscoveryFailed", human: "Could not discover camera services.")
            return
        }
        for service in peripheral.services ?? [] { peripheral.discoverCharacteristics(nil, for: service) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        for characteristic in service.characteristics ?? [] {
            switch characteristic.uuid.uuidString.uppercased() {
            case "FFF4": peripheral.setNotifyValue(true, for: characteristic)
            case "FFF5":
                writeCharacteristic = characteristic
                peripheral.setNotifyValue(true, for: characteristic)
            default: break
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, characteristic.isNotifying else { return }
        notifyReady.insert(characteristic.uuid.uuidString.uppercased())
        guard notifyReady.contains("FFF4"), notifyReady.contains("FFF5"), let fff4 = characteristicFor("FFF4") else { return }
        let type: CBCharacteristicWriteType = fff4.properties.contains(.write) ? .withResponse : .withoutResponse
        peripheral.writeValue(Data([1, 0]), for: fff4, type: type)
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.25) { [weak self] in
            self?.send(DUMLMessage(target: 0xf002, messageID: 0x102b, commandSet: 0x00, commandID: 0x2b, payload: Data([4, 0])))
            self?.sendPairRequest()
        }
    }

    private func characteristicFor(_ uuid: String) -> CBCharacteristic? {
        camera?.services?.flatMap({ $0.characteristics ?? [] }).first(where: { $0.uuid.uuidString.uppercased() == uuid })
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value else { return }
        for message in decoder.append(data) { handle(message) }
    }

    private func sendPairRequest() {
        // Stable public client identity. The camera may request one on-device approval.
        let clientID = pack("646a692d746f6f6c732d6f61342d3031")
        send(DUMLMessage(target: 0x0702, messageID: 0x1092, commandSet: 0x07, commandID: 0x45, payload: clientID + pack("dji-tools")))
    }

    private func handle(_ message: DUMLMessage) {
        if message.flags == 0x40 {
            if message.commandSet == 0x07 && message.commandID == 0x46 {
                let swapped = (message.target << 8) | (message.target >> 8)
                send(DUMLMessage(
                    target: swapped,
                    messageID: message.messageID,
                    flags: 0xc0,
                    commandSet: message.commandSet,
                    commandID: message.commandID,
                    payload: message.payload
                ))
                reporter.event("pairingApproved", human: "Camera approved DJI-Tools.")
                DispatchQueue.global().asyncAfter(deadline: .now() + 0.3) { [weak self] in self?.startOperation() }
            }
            return
        }

        if message.commandSet == 0x07 && message.commandID == 0x45 {
            let status = message.payload.count >= 2 ? message.payload[1] : 0xff
            if status == 1 { startOperation() }
            else if status == 2 { reporter.event("approvalRequired", human: "Approve “dji-tools” on the camera screen…") }
            else { finish(code: 4, event: "pairingFailed", ["status": status], human: "Camera pairing failed (status \(status)).") }
            return
        }

        guard operationStarted else { return }
        switch options.action {
        case .status:
            if message.messageID == 0x8d19 {
                statusCountry = OA4WiFiStatus.country(from: message.payload)
                send(OA4WiFiCommand.getBand())
            } else if message.messageID == 0x8d44 {
                emitStatus(country: statusCountry, band: OA4WiFiStatus.band(from: message.payload))
            }
        case .getCountry where message.messageID == 0x8d19:
            let country = OA4WiFiStatus.country(from: message.payload)
            reporter.event("wifiCountry", ["country": country ?? NSNull()], human: "Country: \(country ?? "unknown")")
            finish(code: country == nil ? 5 : 0)
        case .getBand where message.messageID == 0x8d44:
            let band = OA4WiFiStatus.band(from: message.payload)
            reporter.event("wifiBand", ["band": band?.rawValue ?? -1, "name": band?.name ?? "unknown"], human: "Band: \(band?.name ?? "unknown")")
            finish(code: band == nil ? 5 : 0)
        case .setCountry(let country):
            handleCountryWrite(message, requested: country)
        case .setBand(let band):
            handleBandWrite(message, requested: band)
        case .setFrequency(let mhz) where message.messageID == 0x8d2b:
            finishWrite(name: "frequency", requested: "\(mhz) MHz", payload: message.payload)
        case .restart where message.messageID == 0x8d15:
            finishWrite(name: "Wi-Fi restart", requested: "restart", payload: message.payload)
        default: break
        }
    }

    private func startOperation() {
        guard !operationStarted else { return }
        operationStarted = true
        do {
            switch options.action {
            case .status, .getCountry: send(OA4WiFiCommand.getCountry())
            case .getBand: send(OA4WiFiCommand.getBand())
            case .setCountry:
                phase = "readCountryBeforeWrite"
                send(OA4WiFiCommand.getCountry())
            case .setBand:
                phase = "readBandBeforeWrite"
                send(OA4WiFiCommand.getBand())
            case .setFrequency(let mhz): send(try OA4WiFiCommand.setFrequency(mhz: mhz))
            case .restart: send(OA4WiFiCommand.restart())
            case .devices: break
            }
        } catch {
            finish(code: 1, event: "invalidCommand", ["error": String(describing: error)], human: "Invalid command: \(error)")
        }
    }

    private func emitStatus(country: String?, band: OA4WiFiBand?) {
        reporter.event("wifiStatus", [
            "country": country ?? NSNull(),
            "band": band?.rawValue ?? -1,
            "bandName": band?.name ?? "unknown",
        ], human: "Country: \(country ?? "unknown")\nBand: \(band?.name ?? "unknown")")
        finish(code: (country == nil || band == nil) ? 5 : 0)
    }

    private func handleCountryWrite(_ message: DUMLMessage, requested: String) {
        do {
            if phase == "readCountryBeforeWrite", message.messageID == 0x8d19 {
                previousCountry = OA4WiFiStatus.country(from: message.payload)
                phase = "writeCountry"
                send(try OA4WiFiCommand.setCountry(requested))
            } else if phase == "writeCountry", message.messageID == 0x8d18 {
                guard message.payload.first == 0 else {
                    reportRejected(name: "country", requested: requested, payload: message.payload)
                    return
                }
                phase = "verifyCountry"
                send(OA4WiFiCommand.getCountry())
            } else if phase == "verifyCountry", message.messageID == 0x8d19 {
                let readback = OA4WiFiStatus.country(from: message.payload)
                let verified = readback == requested
                reporter.event(verified ? "writeVerified" : "writeNotVerified", [
                    "setting": "country",
                    "previous": previousCountry ?? NSNull(),
                    "requested": requested,
                    "readback": readback ?? NSNull(),
                ], human: verified
                    ? "Country changed: \(previousCountry ?? "unknown") → \(requested) (verified)."
                    : "Country write was acknowledged, but read-back is \(readback ?? "unknown").")
                finish(code: verified ? 0 : 5)
            }
        } catch {
            finish(code: 1, event: "invalidCommand", ["error": String(describing: error)], human: "Invalid country command.")
        }
    }

    private func handleBandWrite(_ message: DUMLMessage, requested: OA4WiFiBand) {
        if phase == "readBandBeforeWrite", message.messageID == 0x8d44 {
            previousBand = OA4WiFiStatus.band(from: message.payload)
            phase = "writeBand"
            send(OA4WiFiCommand.setBand(requested))
        } else if phase == "writeBand", message.messageID == 0x8d10 {
            guard message.payload.first == 0 else {
                reportRejected(name: "band", requested: requested.name, payload: message.payload)
                return
            }
            phase = "verifyBand"
            send(OA4WiFiCommand.getBand())
        } else if phase == "verifyBand", message.messageID == 0x8d44 {
            let readback = OA4WiFiStatus.band(from: message.payload)
            let verified = readback == requested
            reporter.event(verified ? "writeVerified" : "writeNotVerified", [
                "setting": "band",
                "previous": previousBand?.name ?? "unknown",
                "requested": requested.name,
                "readback": readback?.name ?? "unknown",
            ], human: verified
                ? "Band changed: \(previousBand?.name ?? "unknown") → \(requested.name) (verified)."
                : "Band write was acknowledged, but read-back is \(readback?.name ?? "unknown").")
            finish(code: verified ? 0 : 5)
        }
    }

    private func finishWrite(name: String, requested: String, payload: Data) {
        let accepted = payload.first == 0
        reporter.event(accepted ? "writeAccepted" : "writeRejected", [
            "setting": name,
            "requested": requested,
            "responseHex": payload.hexString,
        ], human: accepted ? "Camera accepted \(name): \(requested)." : "Camera rejected \(name) (response \(payload.hexString)).")
        finish(code: accepted ? 0 : 5)
    }

    private func reportRejected(name: String, requested: String, payload: Data) {
        reporter.event("writeRejected", [
            "setting": name,
            "requested": requested,
            "responseHex": payload.hexString,
        ], human: "Camera rejected \(name) (response \(payload.hexString)).")
        finish(code: 5)
    }

    private func pack(_ string: String) -> Data {
        let bytes = Data(string.utf8)
        return Data([UInt8(bytes.count)]) + bytes
    }

    private func send(_ message: DUMLMessage) {
        guard let camera, let writeCharacteristic else { return }
        camera.writeValue(message.encoded(), for: writeCharacteristic, type: .withoutResponse)
    }

    private func finish(
        code: Int32,
        event: String? = nil,
        _ fields: [String: Any] = [:],
        human: String? = nil
    ) {
        guard !finished else { return }
        finished = true
        exitCode = code
        if let event { reporter.event(event, fields, human: human) }
        central.stopScan()
        done.signal()
    }
}

do {
    guard let options = try parseArguments(Array(CommandLine.arguments.dropFirst())) else { exit(0) }
    let reporter = Reporter(json: options.json)
    let code: Int32
    if case .devices = options.action {
        let scanner = DeviceScanner(reporter: reporter)
        code = scanner.run(seconds: options.timeout)
        withExtendedLifetime(scanner) {}
    } else {
        let session = CameraSession(options: options, reporter: reporter)
        code = session.run()
        withExtendedLifetime(session) {}
    }
    exit(code)
} catch {
    fputs("error: \(error)\n", stderr)
    exit(1)
}
