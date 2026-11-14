import SwiftUI

@main
struct MoodWatchApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            FaceView()
                .fontDesign(MoodFont.systemDesign)
                .environmentObject(model)
        }
        .backgroundTask(.appRefresh(DriftScheduler.taskIdentifier)) {
            await DriftScheduler.runBackgroundCheck()
        }
    }
}
