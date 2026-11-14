import Foundation

/// Moods drift to a new expression every day around 9:00–9:15 AM Pacific.
enum DriftClock {
    static let zone = TimeZone(identifier: "America/Los_Angeles")!

    /// The first check, once most Moods have changed.
    static let settle = (hour: 9, minute: 5)
    /// Watch background refreshes run late, so checks keep going a while past Android's 9:15.
    static let giveUp = (hour: 9, minute: 30)
    static let windowStartTime = (hour: 9, minute: 0)

    static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        return calendar
    }

    static func windowStart(on day: Date = Date()) -> Date { at(windowStartTime, on: day) }
    static func settleTime(on day: Date = Date()) -> Date { at(settle, on: day) }
    static func giveUpTime(on day: Date = Date()) -> Date { at(giveUp, on: day) }

    /// Roughly when Moods last changed expression: the latest 9:05 AM Pacific at or before `now`.
    static func lastDriftAt(_ now: Date = Date()) -> Date {
        let today = settleTime(on: now)
        return now >= today ? today : settleTime(on: adding(days: -1, to: now))
    }

    /// The next 9:05 AM Pacific after `now`.
    static func nextSettle(after now: Date = Date()) -> Date {
        let today = settleTime(on: now)
        return now < today ? today : settleTime(on: adding(days: 1, to: now))
    }

    /// The Pacific calendar date of `now`, e.g. "2026-11-13".
    static func dayKey(_ now: Date = Date()) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: now)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    static func adding(days: Int, to date: Date) -> Date {
        calendar.date(byAdding: .day, value: days, to: date) ?? date.addingTimeInterval(Double(days) * 86_400)
    }

    private static func at(_ time: (hour: Int, minute: Int), on day: Date) -> Date {
        calendar.date(bySettingHour: time.hour, minute: time.minute, second: 0, of: day) ?? day
    }
}
