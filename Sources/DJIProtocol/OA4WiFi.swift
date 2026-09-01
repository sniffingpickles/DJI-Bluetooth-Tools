import Foundation

public enum OA4WiFiBand: UInt8, CaseIterable, Sendable {
    case ghz24 = 0
    case ghz5 = 1
    case automatic = 2

    public var name: String {
        switch self {
        case .ghz24: return "2.4 GHz"
        case .ghz5: return "5 GHz"
        case .automatic: return "automatic/dual"
        }
    }
}

public enum OA4WiFiCommand {
    public static let receiver: UInt16 = 0x1b02

    public static func getCountry() -> DUMLMessage {
        DUMLMessage(target: receiver, messageID: 0x8d19, commandSet: 0x07, commandID: 0x19)
    }

    public static func setCountry(_ country: String) throws -> DUMLMessage {
        let normalized = country.uppercased()
        let bytes = Array(normalized.utf8)
        guard bytes.count == 2, bytes.allSatisfy({ (65...90).contains($0) }) else {
            throw OA4WiFiCommandError.invalidCountry
        }
        return DUMLMessage(
            target: receiver,
            messageID: 0x8d18,
            commandSet: 0x07,
            commandID: 0x18,
            payload: Data([0xff, bytes[0], bytes[1], 0])
        )
    }

    public static func getBand() -> DUMLMessage {
        DUMLMessage(target: receiver, messageID: 0x8d44, commandSet: 0x07, commandID: 0x44)
    }

    public static func setBand(_ band: OA4WiFiBand) -> DUMLMessage {
        DUMLMessage(
            target: receiver,
            messageID: 0x8d10,
            commandSet: 0x07,
            commandID: 0x10,
            payload: Data([band.rawValue])
        )
    }

    public static func setFrequency(mhz: UInt16) throws -> DUMLMessage {
        guard (2_400...5_900).contains(mhz) else { throw OA4WiFiCommandError.invalidFrequency }
        return DUMLMessage(
            target: receiver,
            messageID: 0x8d2b,
            commandSet: 0x07,
            commandID: 0x2b,
            payload: Data([0, UInt8(mhz & 0xff), UInt8(mhz >> 8)])
        )
    }

    public static func restart() -> DUMLMessage {
        DUMLMessage(target: receiver, messageID: 0x8d15, commandSet: 0x07, commandID: 0x15)
    }
}

public enum OA4WiFiCommandError: Error, Equatable {
    case invalidCountry
    case invalidFrequency
}

public struct OA4WiFiStatus: Equatable, Sendable {
    public let country: String?
    public let band: OA4WiFiBand?

    public init(country: String?, band: OA4WiFiBand?) {
        self.country = country
        self.band = band
    }

    public static func country(from payload: Data) -> String? {
        guard payload.count >= 4 else { return nil }
        for index in 0...(payload.count - 3) where payload[index] == 0xff {
            let bytes = payload[(index + 1)...(index + 2)]
            if bytes.allSatisfy({ (65...90).contains($0) || (97...122).contains($0) }) {
                return String(bytes: bytes, encoding: .ascii)?.uppercased()
            }
        }
        return nil
    }

    public static func band(from payload: Data) -> OA4WiFiBand? {
        // OA4 07/44 does not echo the 07/10 enum. Hardware observations:
        // 00 00 00 = 2.4 GHz, 00 01 01 = forced 5 GHz, and
        // 00 00 01 = automatic/dual. Treat any other shape as unknown.
        guard payload.count >= 3 else { return nil }
        switch Array(payload.suffix(3)) {
        case [0x00, 0x00, 0x00]: return .ghz24
        case [0x00, 0x01, 0x01]: return .ghz5
        case [0x00, 0x00, 0x01]: return .automatic
        default: return nil
        }
    }
}
