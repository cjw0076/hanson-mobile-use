// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "HansonMobileUseIOS",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(
            name: "HansonMobileUseIOS",
            targets: ["HansonMobileUseIOS"]
        ),
    ],
    targets: [
        .target(
            name: "HansonMobileUseIOS",
            path: "Sources/HansonMobileUseIOS"
        ),
        .testTarget(
            name: "HansonMobileUseIOSTests",
            dependencies: ["HansonMobileUseIOS"],
            path: "Tests/HansonMobileUseIOSTests"
        ),
    ]
)
