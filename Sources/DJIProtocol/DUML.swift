import Foundation

public struct DUMLMessage: Equatable, Sendable {
    public let target: UInt16
    public let messageID: UInt16
    public let flags: UInt8
    public let commandSet: UInt8
    public let commandID: UInt8
    public let payload: Data

    public init(
        target: UInt16,
        messageID: UInt16,
        flags: UInt8 = 0x40,
        commandSet: UInt8,
        commandID: UInt8,
        payload: Data = Data()
    ) {
        self.target = target
        self.messageID = messageID
        self.flags = flags
        self.commandSet = commandSet
        self.commandID = commandID
        self.payload = payload
    }

    public func encoded() -> Data {
        let length = 13 + payload.count
        precondition(length <= 0x3ff, "DUML frame exceeds the 10-bit length field")
        var frame = Data([
            0x55,
            UInt8(length & 0xff),
            UInt8(((length >> 8) & 0x03) | 0x04),
            0,
            UInt8(target & 0xff),
            UInt8(target >> 8),
            UInt8(messageID >> 8),
            UInt8(messageID & 0xff),
            flags,
            commandSet,
            commandID,
        ])
        frame[3] = DJICRC.crc8(frame.prefix(3))
        frame.append(payload)
        let check = DJICRC.crc16(frame)
        frame.append(UInt8(check & 0xff))
        frame.append(UInt8(check >> 8))
        return frame
    }
}

public enum DUMLDecodeError: Error, Equatable {
    case invalidLength
    case invalidStart
    case invalidCRC8
    case invalidCRC16
}

public extension DUMLMessage {
    init(frame: Data) throws {
        guard frame.count >= 13 else { throw DUMLDecodeError.invalidLength }
        guard frame[0] == 0x55 else { throw DUMLDecodeError.invalidStart }
        let declaredLength = Int(frame[1]) | (Int(frame[2] & 0x03) << 8)
        guard declaredLength == frame.count else { throw DUMLDecodeError.invalidLength }
        guard DJICRC.crc8(frame.prefix(3)) == frame[3] else { throw DUMLDecodeError.invalidCRC8 }
        let expectedCRC16 = UInt16(frame[frame.count - 2]) | (UInt16(frame[frame.count - 1]) << 8)
        guard DJICRC.crc16(frame.dropLast(2)) == expectedCRC16 else { throw DUMLDecodeError.invalidCRC16 }

        self.init(
            target: UInt16(frame[4]) | (UInt16(frame[5]) << 8),
            messageID: (UInt16(frame[6]) << 8) | UInt16(frame[7]),
            flags: frame[8],
            commandSet: frame[9],
            commandID: frame[10],
            payload: Data(frame.dropFirst(11).dropLast(2))
        )
    }
}

public enum DJICRC {
    public static func crc8<C: Collection>(_ bytes: C) -> UInt8 where C.Element == UInt8 {
        var crc: UInt8 = 0x77
        for byte in bytes {
            crc ^= byte
            for _ in 0..<8 {
                crc = (crc & 1) != 0 ? (crc >> 1) ^ 0x8c : crc >> 1
            }
        }
        return crc
    }

    public static func crc16<C: Collection>(_ bytes: C) -> UInt16 where C.Element == UInt8 {
        var crc: UInt16 = 0x3692
        for byte in bytes {
            crc ^= UInt16(byte)
            for _ in 0..<8 {
                crc = (crc & 1) != 0 ? (crc >> 1) ^ 0x8408 : crc >> 1
            }
        }
        return crc
    }
}

public struct DUMLStreamDecoder: Sendable {
    private var buffer = Data()

    public init() {}

    public mutating func append(_ data: Data) -> [DUMLMessage] {
        buffer.append(data)
        var messages: [DUMLMessage] = []
        while buffer.count >= 13 {
            guard let start = buffer.firstIndex(of: 0x55) else {
                buffer.removeAll()
                break
            }
            if start > buffer.startIndex { buffer = Data(buffer[start...]) }
            guard buffer.count >= 4 else { break }
            let length = Int(buffer[1]) | (Int(buffer[2] & 0x03) << 8)
            guard (13...1023).contains(length) else {
                buffer = Data(buffer.dropFirst())
                continue
            }
            guard buffer.count >= length else { break }
            let frame = Data(buffer.prefix(length))
            // Re-materialize the remainder so its Data indices begin at zero.
            // Data.removeFirst preserves a non-zero slice index on macOS and a
            // later buffer[1] would trap after the first decoded BLE frame.
            buffer = Data(buffer.dropFirst(length))
            if let message = try? DUMLMessage(frame: frame) { messages.append(message) }
        }
        return messages
    }
}

public extension Data {
    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}
