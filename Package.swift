// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "DJI-Tools",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "dji-tools", targets: ["DJITools"]),
        .library(name: "DJIProtocol", targets: ["DJIProtocol"]),
    ],
    targets: [
        .target(name: "DJIProtocol"),
        .executableTarget(
            name: "DJITools",
            dependencies: ["DJIProtocol"],
            linkerSettings: [.linkedFramework("CoreBluetooth")]
        ),
        .testTarget(name: "DJIProtocolTests", dependencies: ["DJIProtocol"]),
    ]
)
