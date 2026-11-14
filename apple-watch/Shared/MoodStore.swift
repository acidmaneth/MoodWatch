import Foundation

/// Everything the app and the widget share, in the App Group: settings in UserDefaults and
/// images in the group container.
final class MoodStore: @unchecked Sendable {
    static let shared = MoodStore()

    let defaults: UserDefaults
    let directory: URL

    private enum Key {
        static let tokenId = "token_id"
        static let checkedAt = "checked_at"
        static let uriPrefix = "token_uri_prefix"
        static let supply = "total_supply"
        static let supplyAt = "total_supply_at"
        static let doneDate = "drift_done_date"
        static let nextRefreshAt = "next_refresh_at"
        static let collectionIds = "collection_ids"
    }

    init(groupIdentifier: String = Moods.appGroup, directory: URL? = nil) {
        defaults = UserDefaults(suiteName: groupIdentifier) ?? .standard
        let fileManager = FileManager.default
        self.directory = directory
            ?? fileManager.containerURL(forSecurityApplicationGroupIdentifier: groupIdentifier)
            ?? fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("Moods", isDirectory: true)
        try? fileManager.createDirectory(at: collectionDirectory, withIntermediateDirectories: true)
    }

    // MARK: Selected Mood

    var tokenId: Int {
        get { (defaults.object(forKey: Key.tokenId) as? Int) ?? Moods.defaultTokenId }
        set { defaults.set(newValue, forKey: Key.tokenId) }
    }

    private var imageFile: URL { directory.appendingPathComponent("current.jpg") }
    private var infoFile: URL { directory.appendingPathComponent("info.json") }

    /// Info about the saved image, which may belong to a previously selected Mood.
    var savedInfo: MoodInfo? {
        guard let data = try? Data(contentsOf: infoFile) else { return nil }
        return try? JSONDecoder().decode(MoodInfo.self, from: data)
    }

    /// Info about the selected Mood, if its image is saved.
    var currentInfo: MoodInfo? {
        guard let info = savedInfo, info.tokenId == tokenId else { return nil }
        return info
    }

    /// The saved image, only if it belongs to the selected Mood.
    var currentImage: Data? {
        guard currentInfo != nil else { return nil }
        return try? Data(contentsOf: imageFile)
    }

    var hasImageFile: Bool { FileManager.default.fileExists(atPath: imageFile.path) }

    /// Saves a new image and makes its Mood the selected one.
    func saveCurrent(jpeg: Data, info: MoodInfo) throws {
        try jpeg.write(to: imageFile, options: .atomic)
        try JSONEncoder().encode(info).write(to: infoFile, options: .atomic)
        tokenId = info.tokenId
        checkedAt = info.updatedAt
    }

    /// When the metadata was last fetched successfully, whether or not the image changed.
    var checkedAt: Date {
        get { date(Key.checkedAt) ?? .distantPast }
        set { defaults.set(newValue.timeIntervalSince1970, forKey: Key.checkedAt) }
    }

    /// True if the saved image URL for the selected Mood was confirmed at or after `since`.
    func hasBaseline(since: Date) -> Bool {
        currentInfo != nil && checkedAt >= since
    }

    // MARK: Daily drift

    func isDoneToday(_ now: Date = Date()) -> Bool {
        defaults.string(forKey: Key.doneDate) == DriftClock.dayKey(now)
    }

    func markDoneToday(_ now: Date = Date()) {
        defaults.set(DriftClock.dayKey(now), forKey: Key.doneDate)
    }

    /// When the app's next background refresh is due, as last scheduled.
    var nextRefreshAt: Date? {
        get { date(Key.nextRefreshAt) }
        set { defaults.set(newValue?.timeIntervalSince1970, forKey: Key.nextRefreshAt) }
    }

    // MARK: Chain caches

    /// Token URIs are "<prefix><id>"; once known, metadata is fetched without the chain.
    var uriPrefix: String? {
        get { defaults.string(forKey: Key.uriPrefix) }
        set { defaults.set(newValue, forKey: Key.uriPrefix) }
    }

    var cachedSupply: (value: Int, at: Date)? {
        let value = defaults.integer(forKey: Key.supply)
        guard value > 0, let at = date(Key.supplyAt) else { return nil }
        return (value, at)
    }

    func saveSupply(_ value: Int, at: Date = Date()) {
        defaults.set(value, forKey: Key.supply)
        defaults.set(at.timeIntervalSince1970, forKey: Key.supplyAt)
    }

    // MARK: Collection

    private var collectionDirectory: URL { directory.appendingPathComponent("collection", isDirectory: true) }
    private func thumbImageFile(_ id: Int) -> URL { collectionDirectory.appendingPathComponent("\(id).jpg") }
    private func thumbInfoFile(_ id: Int) -> URL { collectionDirectory.appendingPathComponent("\(id).json") }

    /// Saved Mood IDs, newest first.
    var collectionIds: [Int] {
        get { defaults.array(forKey: Key.collectionIds) as? [Int] ?? [] }
        set { defaults.set(newValue, forKey: Key.collectionIds) }
    }

    func isCollected(_ id: Int) -> Bool { collectionIds.contains(id) }

    func addToCollection(_ id: Int) {
        collectionIds = [id] + collectionIds.filter { $0 != id }
    }

    func removeFromCollection(_ id: Int) {
        collectionIds = collectionIds.filter { $0 != id }
        try? FileManager.default.removeItem(at: thumbImageFile(id))
        try? FileManager.default.removeItem(at: thumbInfoFile(id))
    }

    private struct ThumbInfo: Codable {
        var expression: String?
        var mood: String?
        var fetchedAt: Date
    }

    func cachedThumb(_ id: Int) -> CollectionThumb? {
        guard let image = try? Data(contentsOf: thumbImageFile(id)),
              let data = try? Data(contentsOf: thumbInfoFile(id)),
              let info = try? JSONDecoder().decode(ThumbInfo.self, from: data)
        else { return nil }
        return CollectionThumb(
            tokenId: id, expression: info.expression, mood: info.mood, image: image, fetchedAt: info.fetchedAt
        )
    }

    func saveThumb(_ thumb: CollectionThumb) throws {
        try thumb.image.write(to: thumbImageFile(thumb.tokenId), options: .atomic)
        let info = ThumbInfo(expression: thumb.expression, mood: thumb.mood, fetchedAt: thumb.fetchedAt)
        try JSONEncoder().encode(info).write(to: thumbInfoFile(thumb.tokenId), options: .atomic)
    }

    private func date(_ key: String) -> Date? {
        guard let seconds = defaults.object(forKey: key) as? Double else { return nil }
        return Date(timeIntervalSince1970: seconds)
    }
}
