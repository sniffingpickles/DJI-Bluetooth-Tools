import XCTest
@testable import DJIProtocol

final class DJIProtocolTests: XCTestCase {
    func testDUMLEncodeDecodeRoundTrip() throws {
        let original = DUMLMessage(
            target: 0x1b02,
            messageID: 0x8d18,
            commandSet: 0x07,
            commandID: 0x18,
            payload: Data([0xff, 0x55, 0x53, 0])
        )
        let decoded = try DUMLMessage(frame: original.encoded())
        XCTAssertEqual(decoded, original)
    }

    func testStreamDecoderHandlesFragmentedNotifications() {
        let frame = OA4WiFiCommand.getCountry().encoded()
        var decoder = DUMLStreamDecoder()
        XCTAssertTrue(decoder.append(frame.prefix(5)).isEmpty)
        XCTAssertEqual(decoder.append(frame.dropFirst(5)), [OA4WiFiCommand.getCountry()])
    }

    func testStreamDecoderRebasesAfterBackToBackFramesAndGarbage() {
        let first = OA4WiFiCommand.getCountry()
        let second = OA4WiFiCommand.getBand()
        var bytes = Data([0x99, 0x98])
        bytes.append(first.encoded())
        bytes.append(second.encoded())
        var decoder = DUMLStreamDecoder()
        XCTAssertEqual(decoder.append(bytes), [first, second])
    }

    func testCountryCommandValidationAndParsing() throws {
        XCTAssertEqual(try OA4WiFiCommand.setCountry("us").payload, Data([0xff, 0x55, 0x53, 0]))
        XCTAssertThrowsError(try OA4WiFiCommand.setCountry("USA"))
        XCTAssertEqual(OA4WiFiStatus.country(from: Data([0, 0xff, 0x4a, 0x50, 0])), "JP")
    }

    func testBandAndFrequencyPayloads() throws {
        XCTAssertEqual(OA4WiFiCommand.setBand(.ghz5).payload, Data([1]))
        XCTAssertEqual(OA4WiFiStatus.band(from: Data([0, 0, 0])), .ghz24)
        XCTAssertEqual(OA4WiFiStatus.band(from: Data([0, 1, 1])), .ghz5)
        XCTAssertEqual(OA4WiFiStatus.band(from: Data([0, 0, 1])), .automatic)
        XCTAssertNil(OA4WiFiStatus.band(from: Data([0, 1, 0])))
        XCTAssertEqual(try OA4WiFiCommand.setFrequency(mhz: 5180).payload, Data([0, 0x3c, 0x14]))
        XCTAssertThrowsError(try OA4WiFiCommand.setFrequency(mhz: 100))
    }
}
