import XCTest

final class GemmaPocketUITests: XCTestCase {
    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }
    private func capture(_ app: XCUIApplication, _ title: String) {
        let image = XCTAttachment(screenshot: app.screenshot()); image.name = title; image.lifetime = .keepAlways; add(image)
    }
    private func wait(_ app: XCUIApplication, _ predicate: String, _ object: Any, timeout: TimeInterval = 90) {
        let result = XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: predicate), object: object)], timeout: timeout)
        XCTAssertEqual(result, .completed, app.debugDescription)
    }
    private func ready(_ app: XCUIApplication) {
        let status = element(app, "app-status"); XCTAssertTrue(status.waitForExistence(timeout: 30), app.debugDescription)
        wait(app, "label == 'Ready'", status, timeout: 180)
    }
    private func search(_ app: XCUIApplication, _ query: String) {
        let field = element(app, "search-query"); field.tap()
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 100)); field.typeText(query)
        element(app, "search-go").tap(); ready(app)
        if !query.isEmpty { XCTAssertTrue(element(app, "search-timing").exists) }
    }
    private func photo(_ app: XCUIApplication, _ name: String) -> XCUIElement {
        element(app, "photo-GemmaFixture-\(name).jpg")
    }
    private func systemAlert() -> XCUIElement {
        XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.firstMatch
    }
    func testSystemLibraryWorkflow() throws {
        continueAfterFailure = false
        var permissionHandled = false
        let permissionMonitor = addUIInterruptionMonitor(withDescription: "Photos access") { alert in
            guard alert.buttons["Allow Full Access"].exists else { return false }
            self.capture(XCUIApplication(bundleIdentifier: "com.apple.springboard"), "System Photos permission")
            alert.buttons["Allow Full Access"].tap(); permissionHandled = true; return true
        }
        defer { removeUIInterruptionMonitor(permissionMonitor) }
        let app = XCUIApplication(); app.launchArguments = ["--photo-audit"]; app.launch()
        if element(app, "connect-photos").waitForExistence(timeout: 4) {
            element(app, "connect-photos").tap()
            let alert = systemAlert()
            if alert.waitForExistence(timeout: 4) {
                capture(XCUIApplication(bundleIdentifier: "com.apple.springboard"), "System Photos permission")
                XCTAssertTrue(alert.buttons["Allow Full Access"].exists, alert.debugDescription)
                alert.buttons["Allow Full Access"].tap(); permissionHandled = true
            }
        }
        let toggle = element(app, "scan-toggle")
        if toggle.waitForExistence(timeout: 60) && toggle.label == "Pause" {
            toggle.tap(); wait(app, "label == 'Scan paused'", element(app, "app-status"), timeout: 60)
            capture(app, "Paused system photo scan"); toggle.tap()
        }
        ready(app)
        XCTAssertTrue(permissionHandled, "The normal OS authorization prompt must be exercised")
        XCTAssertFalse(app.buttons["+ Add photos"].exists)
        XCTAssertTrue(photo(app, "dog").exists, app.debugDescription)
        capture(app, "System photo library indexed")
        search(app, "a dog")
        XCTAssertTrue(photo(app, "dog").exists)
        XCTAssertFalse(photo(app, "cat").exists); XCTAssertFalse(photo(app, "beach").exists)
        XCTAssertTrue(element(app, "search-timing").label.hasPrefix("1 matches"))
        capture(app, "Dog search filters unrelated system photos")
        search(app, "a cat")
        XCTAssertTrue(photo(app, "cat").exists); XCTAssertFalse(photo(app, "dog").exists)
        search(app, "pets")
        XCTAssertTrue(photo(app, "dog").exists); XCTAssertTrue(photo(app, "cat").exists)
        XCTAssertFalse(photo(app, "bicycle").exists); XCTAssertFalse(photo(app, "coffee").exists)
        capture(app, "Pets search keeps multiple relevant photos")
        for query in ["a computer", "a car", "a motorcycle"] {
            search(app, query)
            XCTAssertTrue(element(app, "search-empty").exists, app.debugDescription)
            XCTAssertTrue(element(app, "search-timing").label.hasPrefix("0 matches"))
            XCTAssertFalse(photo(app, "dog").exists); XCTAssertFalse(photo(app, "bicycle").exists)
        }
        capture(app, "Unrelated query returns no matching photos")
        search(app, "a dog")
        photo(app, "dog").tap(); XCTAssertTrue(app.buttons["Close"].waitForExistence(timeout: 5)); capture(app, "System photo preview"); app.buttons["Close"].tap()
        photo(app, "dog").press(forDuration: 1.0)
        element(app, "organize-selected").tap()
        let name = element(app, "album-name"); XCTAssertTrue(name.waitForExistence(timeout: 5)); name.tap(); name.typeText("Gemma Test Album")
        element(app, "apply-album").tap(); ready(app)
        element(app, "album-filter").tap(); app.staticTexts["Gemma Test Album"].firstMatch.tap()
        XCTAssertTrue(photo(app, "dog").waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(photo(app, "beach").exists)
        capture(app, "Real Photos album contains selected photo")
        element(app, "album-filter").tap(); app.staticTexts["All photos"].firstMatch.tap()
        search(app, "")
        // Cancel both the app confirmation and the OS confirmation. Neither may prune the index.
        photo(app, "beach").press(forDuration: 1.0); element(app, "delete-selected").tap()
        app.buttons["Cancel"].tap(); XCTAssertTrue(photo(app, "beach").exists)
        element(app, "delete-selected").tap(); element(app, "confirm-delete").tap()
        let alert = systemAlert(); XCTAssertTrue(alert.waitForExistence(timeout: 10), app.debugDescription)
        capture(XCUIApplication(bundleIdentifier: "com.apple.springboard"), "System deletion consent")
        let cancel = alert.buttons.matching(NSPredicate(format: "label CONTAINS 'Allow' OR label == 'Cancel'")).firstMatch
        XCTAssertTrue(cancel.exists, alert.debugDescription); cancel.tap(); ready(app)
        XCTAssertTrue(photo(app, "beach").exists)
        capture(app, "Cancelled deletion preserves system photo")
        element(app, "delete-selected").tap(); element(app, "confirm-delete").tap()
        XCTAssertTrue(alert.waitForExistence(timeout: 10)); XCTAssertTrue(alert.buttons["Delete"].exists, alert.debugDescription); alert.buttons["Delete"].tap()
        ready(app); XCTAssertFalse(photo(app, "beach").exists)
        capture(app, "System deletion reconciled with SQLite index")
        app.terminate(); app.launch(); ready(app)
        XCTAssertTrue(photo(app, "dog").exists); XCTAssertFalse(photo(app, "beach").exists)
        capture(app, "Relaunch reuses system index")
    }
    func testDeniedAccessHidesLibrary() throws {
        continueAfterFailure = false
        let app = XCUIApplication(); app.launchArguments = ["--photo-audit"]; app.launch()
        XCTAssertTrue(element(app, "connect-photos").waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertFalse(element(app, "search-query").exists)
        XCTAssertFalse(photo(app, "dog").exists)
        capture(app, "Denied Photos access hides results")
    }
}
