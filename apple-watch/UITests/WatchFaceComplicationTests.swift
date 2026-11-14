import XCTest

/// Adds a Modular face in the simulator and puts Moods in its large middle slot
/// (accessoryRectangular) and top-left slot (accessoryCircular). Each run adds one more
/// Modular face to the watch's face library.
@MainActor
final class WatchFaceComplicationTests: XCTestCase {
    private let carousel = XCUIApplication(bundleIdentifier: "com.apple.Carousel")

    func testAddMoodsToModularFace() throws {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["face"].waitForExistence(timeout: 15))
        sleep(4)
        XCUIDevice.shared.press(.home)
        sleep(2)

        carousel.press(forDuration: 1.5)
        let addFace = carousel.scrollViews["Add new face"]
        for _ in 0..<12 where !(addFace.exists && addFace.isHittable) { carousel.swipeLeft(); sleep(1) }
        addFace.tap()

        let dataRich = carousel.buttons["Data Rich"]
        XCTAssertTrue(carousel.buttons["xmark"].waitForExistence(timeout: 10))
        for _ in 0..<10 where !(dataRich.exists && dataRich.isHittable) { XCUIDevice.shared.rotateDigitalCrown(delta: 0.5) }
        dataRich.tap()

        let modularAdd = carousel.cells["Modular"].buttons["Add"]
        XCTAssertTrue(modularAdd.waitForExistence(timeout: 10))
        modularAdd.tap()

        let middle = carousel.buttons["Middle complication"]
        XCTAssertTrue(middle.waitForExistence(timeout: 10))
        for _ in 0..<4 where !middle.isHittable { carousel.swipeLeft(); sleep(1) }
        try pickMoods(for: middle)
        try pickMoods(for: carousel.buttons["Top Left complication"])
        screenshot("complication-edit")

        XCUIDevice.shared.press(.home)
        sleep(2)
        XCUIDevice.shared.press(.home)
        sleep(5)
        screenshot("complication")
    }

    private func pickMoods(for slot: XCUIElement) throws {
        XCTAssertTrue(slot.exists)
        slot.tap()
        let group = carousel.cells["AppGroupCell -- Moods"]
        XCTAssertTrue(carousel.buttons["Close"].waitForExistence(timeout: 10))
        for _ in 0..<40 where !(group.exists && group.isHittable) { XCUIDevice.shared.rotateDigitalCrown(delta: 0.5) }
        XCTAssertTrue(group.isHittable)
        group.tap()
        let complication = carousel.cells.matching(NSPredicate(format: "identifier BEGINSWITH 'ComplicationListCell'")).firstMatch
        XCTAssertTrue(complication.waitForExistence(timeout: 10))
        complication.tap()
        XCTAssertTrue(slot.waitForExistence(timeout: 10))
        sleep(2)
    }

    private func screenshot(_ name: String) {
        let png = XCUIScreen.main.screenshot().pngRepresentation
        let attachment = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        if let dir = ProcessInfo.processInfo.environment["SCREENSHOT_DIR"] {
            try? png.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
        }
    }
}
