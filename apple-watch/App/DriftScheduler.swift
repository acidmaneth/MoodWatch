import Foundation
import WatchKit
import WidgetKit

/// Schedules the daily background check: first at 9:05 AM Pacific, then every few minutes
/// until the new image arrives or `DriftClock.giveUp` passes.
enum DriftScheduler {
    /// Also passed as the refresh's userInfo, which is how SwiftUI routes it to `.appRefresh`.
    static let taskIdentifier = "com.moodwatch.app.drift-check"
    static let retryInterval: TimeInterval = 3 * 60

    static func nextCheck(after now: Date, store: MoodStore = .shared) -> Date {
        if !store.isDoneToday(now), now < DriftClock.giveUpTime(on: now) {
            return max(now.addingTimeInterval(retryInterval), DriftClock.settleTime(on: now))
        }
        return DriftClock.settleTime(on: DriftClock.adding(days: 1, to: now))
    }

    /// Schedules the next check unless one at or before that time is already pending.
    @MainActor
    static func ensureScheduled() {
        let now = Date()
        let target = nextCheck(after: now)
        if let pending = MoodStore.shared.nextRefreshAt, pending > now, pending <= target { return }
        schedule(at: target)
    }

    @MainActor
    static func schedule(at date: Date) {
        MoodStore.shared.nextRefreshAt = date
        WKApplication.shared().scheduleBackgroundRefresh(
            withPreferredDate: date,
            userInfo: taskIdentifier as NSString
        ) { error in
            if error != nil {
                DispatchQueue.main.async { MoodStore.shared.nextRefreshAt = nil }
            }
        }
    }

    static func runBackgroundCheck() async {
        if await MoodRepository.shared.checkForDrift() {
            WidgetCenter.shared.reloadAllTimelines()
        }
        await MainActor.run { schedule(at: nextCheck(after: Date())) }
    }
}
