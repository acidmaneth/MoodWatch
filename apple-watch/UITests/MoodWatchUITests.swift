import XCTest

/// Drives the app like a user: number pad, Pick random, Collection add/switch/remove.
/// Needs network access. Set TEST_RUNNER_SCREENSHOT_DIR to save PNGs of each screen.
@MainActor
final class MoodWatchUITests: XCTestCase {
    private let app = XCUIApplication()

    func testChooseRandomAndCollection() throws {
        continueAfterFailure = false
        app.launchArguments = ["-seedCollection", ""]
        app.launch()
        let face = app.descendants(matching: .any)["face"]
        XCTAssertTrue(face.waitForExistence(timeout: 15))
        sleep(3)
        screenshot("face-start")
        face.tap()
        waitForTitle(prefix: "Moods #")

        // Number pad: 555
        app.buttons["Type a Mood ID"].tapWhenVisible(in: app)
        for key in ["5", "5", "5"] { app.buttons[key].tap() }
        screenshot("keypad")
        app.buttons["Show this Mood"].tap()
        waitForTitle("Moods #555")
        sleep(1)
        screenshot("menu")

        // Collection add/remove toggle
        app.buttons["Add to Collection"].tapWhenVisible(in: app)
        XCTAssertTrue(app.buttons["In Collection ✓ (remove)"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Collection (1)"].reveal(in: app))
        app.buttons["In Collection ✓ (remove)"].tapWhenVisible(in: app)
        XCTAssertTrue(app.buttons["Add to Collection"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Collection (0)"].reveal(in: app))
        app.buttons["Add to Collection"].tapWhenVisible(in: app)

        // Pick random three times, adding each
        var picked: [String] = []
        for _ in 0..<3 {
            scrollToTop()
            let before = title
            app.buttons["Pick random"].tapWhenVisible(in: app)
            scrollToTop()
            let changed = waitUntil(timeout: 45) { title != before && title.hasPrefix("Moods #") }
            if !changed {
                screenshot("fail-random")
                print("TREE", app.debugDescription)
            }
            XCTAssertTrue(changed, "title stayed \(before)")
            picked.append(title)
            app.buttons["Add to Collection"].tapWhenVisible(in: app)
            XCTAssertTrue(app.buttons["In Collection ✓ (remove)"].waitForExistence(timeout: 5))
        }
        XCTAssertTrue(app.buttons["Collection (4)"].reveal(in: app), "picked \(picked)")
        screenshot("menu-buttons")

        // Collection: thumbnails load, tapping a row switches the face and closes the menu
        scrollToTop()
        app.buttons["Collection (4)"].tapWhenVisible(in: app)
        let firstRow = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'row-'")).firstMatch
        if !firstRow.waitForExistence(timeout: 10) { print("TREE", app.debugDescription) }
        XCTAssertTrue(firstRow.exists)
        sleep(6)
        screenshot("collection")
        let row555 = app.descendants(matching: .any)["row-555"]
        row555.tapWhenVisible(in: app)
        XCTAssertTrue(app.buttons["Pick random"].waitForNonExistence(timeout: 30))
        screenshot("face-555")
        face.tap()
        waitForTitle("Moods #555")

        // Swipe to remove from the Collection
        scrollToTop()
        app.buttons["Collection (4)"].tapWhenVisible(in: app)
        XCTAssertTrue(row555.reveal(in: app))
        row555.swipeLeft()
        let remove = app.buttons["Remove"]
        XCTAssertTrue(remove.waitForExistence(timeout: 5))
        remove.tap()
        XCTAssertTrue(row555.waitForNonExistence(timeout: 5))
    }

    func testCollectionRowSwitchesFace() throws {
        continueAfterFailure = false
        app.launchArguments = ["-seedCollection", "904,1247,3094", "-screen", "collection"]
        app.launch()
        let row = app.descendants(matching: .any)["row-1247"]
        let found = row.waitForExistence(timeout: 15)
        if !found { print("TREE", app.debugDescription) }
        XCTAssertTrue(found)
        sleep(6)
        screenshot("collection-seeded")
        row.tap()
        XCTAssertTrue(app.descendants(matching: .any)["face"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["title"].waitForNonExistence(timeout: 30))
        app.descendants(matching: .any)["face"].tap()
        waitForTitle("Moods #1247")
    }

    private var title: String {
        let element = app.staticTexts["title"]
        return element.exists ? element.label : ""
    }

    private func scrollToTop() {
        for _ in 0..<3 { XCUIDevice.shared.rotateDigitalCrown(delta: -1) }
        for _ in 0..<4 where !app.staticTexts["title"].isHittable {
            XCUIDevice.shared.rotateDigitalCrown(delta: -1)
        }
    }

    private func waitForTitle(_ expected: String? = nil, prefix: String = "") {
        scrollToTop()
        let titleText = app.staticTexts["title"]
        XCTAssertTrue(titleText.waitForExistence(timeout: 10))
        let matched = waitUntil(timeout: 45) {
            expected.map { titleText.label == $0 } ?? titleText.label.hasPrefix(prefix)
        }
        XCTAssertTrue(matched, "title is \(titleText.label)")
    }

    private func waitUntil(timeout: TimeInterval, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() > deadline { return false }
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        return true
    }

    private func screenshot(_ name: String) {
        let shot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        if let dir = ProcessInfo.processInfo.environment["SCREENSHOT_DIR"], !dir.isEmpty {
            try? shot.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
        }
    }
}

@MainActor
private extension XCUIElement {
    /// Scrolls with the Digital Crown until the element is on screen. (Swiping down at the
    /// top of a sheet would dismiss it.)
    @discardableResult
    func reveal(in app: XCUIApplication, turns: Int = 6) -> Bool {
        _ = waitForExistence(timeout: 3)
        for turn in 0..<(turns * 3) where !(exists && isHittable) {
            XCUIDevice.shared.rotateDigitalCrown(delta: turn < turns ? 0.5 : -0.5)
            _ = waitForExistence(timeout: 1)
        }
        return exists && isHittable
    }

    func tapWhenVisible(in app: XCUIApplication) {
        XCTAssertTrue(reveal(in: app), "\(self) not found")
        tap()
    }
}
