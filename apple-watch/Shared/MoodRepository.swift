import Foundation

/// Fetches Moods and saves the selected one. The selected ID only changes once its image is
/// saved, so the face keeps showing the current Mood while another loads, and an unknown ID
/// changes nothing.
actor MoodRepository {
    static let shared = MoodRepository()

    let store: MoodStore
    private let api: MoodAPI
    private let thumbLimiter = AsyncLimiter(permits: 3)

    /// Bumped by every `load`; only the latest one may save.
    private var loadGeneration = 0
    /// The Mood the latest `load` still in progress is fetching.
    private var loadingId: Int?

    init(store: MoodStore = .shared, api: MoodAPI = MoodAPI()) {
        self.store = store
        self.api = api
    }

    private enum Requester {
        case load(generation: Int)
        case refresh
    }

    /// Switches to Mood `id` once its image has downloaded. Superseded loads are dropped.
    func load(_ id: Int) async throws {
        loadGeneration += 1
        let generation = loadGeneration
        loadingId = id
        defer { if loadGeneration == generation { loadingId = nil } }
        _ = try await fetch(id, onlyIfNewImage: false, for: .load(generation: generation))
    }

    /// Re-fetches the selected Mood. With `onlyIfNewImage`, the image is only downloaded when
    /// its URL changed. Returns true if a new image was saved.
    @discardableResult
    func refresh(onlyIfNewImage: Bool = false) async throws -> Bool {
        try await fetch(store.tokenId, onlyIfNewImage: onlyIfNewImage, for: .refresh)
    }

    /// One check for the daily change. Returns true if a new image was saved.
    ///
    /// Without a baseline from before today's change, the first fetch can't tell old from new,
    /// so it only becomes the baseline and checks continue until `DriftClock.giveUp`.
    @discardableResult
    func checkForDrift(now: Date = Date()) async -> Bool {
        let start = DriftClock.windowStart(on: now)
        let stale = store.checkedAt < DriftClock.lastDriftAt(now)
        guard now >= start, !store.isDoneToday(now) else {
            return stale ? ((try? await refresh(onlyIfNewImage: true)) ?? false) : false
        }
        let hasBaseline = store.hasBaseline(since: DriftClock.adding(days: -1, to: start))
        let saved = (try? await refresh(onlyIfNewImage: true)) ?? false
        if (saved && hasBaseline) || now >= DriftClock.giveUpTime(on: now) {
            store.markDoneToday(now)
        }
        return saved
    }

    private func isWanted(_ id: Int, by requester: Requester) -> Bool {
        switch requester {
        case .load(let generation):
            return generation == loadGeneration
        case .refresh:
            return store.tokenId == id && (loadingId == nil || loadingId == id)
        }
    }

    private func fetch(_ id: Int, onlyIfNewImage: Bool, for requester: Requester) async throws -> Bool {
        let metadata = try await metadata(for: id)
        guard let imageURL = metadata.image else { throw MoodError.noImage(id) }

        let saved = store.savedInfo
        let sameImage = saved?.tokenId == id && saved?.imageURL == imageURL && store.hasImageFile
        if onlyIfNewImage && sameImage {
            if isWanted(id, by: requester) { store.checkedAt = Date() }
            return false
        }

        let jpeg = try await api.squareJPEG(from: MoodAPI.resolve(imageURL), size: Moods.faceSize)
        try Task.checkCancellation()
        guard isWanted(id, by: requester) else { return false }
        let info = MoodInfo(
            tokenId: id,
            name: metadata.name ?? "Moods #\(id)",
            expression: metadata.expression,
            mood: metadata.mood,
            imageURL: imageURL,
            updatedAt: Date()
        )
        try store.saveCurrent(jpeg: jpeg, info: info)
        return !sameImage
    }

    /// Mood `id`'s traits and a thumbnail, cached for the Collection.
    func fetchThumb(_ id: Int) async throws -> CollectionThumb {
        try await thumbLimiter.run {
            let metadata = try await self.metadata(for: id)
            guard let imageURL = metadata.image else { throw MoodError.noImage(id) }
            let image = try await self.api.squareJPEG(from: MoodAPI.resolve(imageURL), size: Moods.thumbSize)
            let thumb = CollectionThumb(
                tokenId: id, expression: metadata.expression, mood: metadata.mood, image: image, fetchedAt: Date()
            )
            try self.store.saveThumb(thumb)
            return thumb
        }
    }

    /// Caches the selected Mood's thumbnail from its already-downloaded image.
    func seedThumbFromCurrent() {
        guard let info = store.currentInfo, let jpeg = store.currentImage,
              let image = ImageTools.cgImage(from: jpeg, maxPixelSize: Moods.thumbSize * 2),
              let thumbJPEG = try? ImageTools.jpeg(ImageTools.squareCropped(image, size: Moods.thumbSize))
        else { return }
        try? store.saveThumb(CollectionThumb(
            tokenId: info.tokenId, expression: info.expression, mood: info.mood,
            image: thumbJPEG, fetchedAt: store.checkedAt
        ))
    }

    /// Mood IDs run from 1 to the contract's totalSupply(), re-read at most once a day.
    func totalSupply() async -> Int {
        let cached = store.cachedSupply
        if let cached, Date().timeIntervalSince(cached.at) < 86_400 { return cached.value }
        do {
            let supply = try await api.totalSupply()
            guard supply > 0 else { throw MoodError.badResponse("totalSupply 0") }
            store.saveSupply(supply)
            return supply
        } catch {
            return cached?.value ?? Moods.fallbackSupply
        }
    }

    /// Metadata straight from the cached URI prefix when known (a 404 there means no such
    /// Mood); any other failure on that path falls back to asking the chain.
    private func metadata(for id: Int) async throws -> MoodMetadata {
        if let prefix = store.uriPrefix {
            do {
                return try await api.metadata(at: prefix + String(id), id: id)
            } catch MoodError.notFound(let missing) {
                throw MoodError.notFound(missing)
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                try Task.checkCancellation()
            }
        }
        let tokenURI = MoodAPI.resolve(try await api.tokenURI(id))
        if tokenURI.hasPrefix("http"), tokenURI.hasSuffix("/\(id)") {
            store.uriPrefix = String(tokenURI.dropLast(String(id).count))
        }
        return try await api.metadata(at: tokenURI, id: id)
    }
}

/// Caps how many thumbnails download at once while scrolling a long Collection.
actor AsyncLimiter {
    private var available: Int
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(permits: Int) { available = permits }

    func run<T: Sendable>(_ body: @Sendable () async throws -> T) async throws -> T {
        await acquire()
        defer { release() }
        return try await body()
    }

    private func acquire() async {
        if available > 0 {
            available -= 1
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    private func release() {
        if waiters.isEmpty {
            available += 1
        } else {
            waiters.removeFirst().resume()
        }
    }
}
