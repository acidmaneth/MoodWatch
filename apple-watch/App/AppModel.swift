import SwiftUI
import WidgetKit

enum MenuRoute: Hashable {
    case collection
    case keypad
}

@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var tokenId: Int
    @Published private(set) var info: MoodInfo?
    @Published private(set) var image: CGImage?
    @Published private(set) var collectionIds: [Int]
    /// Replaces the traits under the title while loading or after an error.
    @Published private(set) var status: String?
    @Published private(set) var isLoading = false
    @Published private(set) var switchingTo: Int?
    @Published var collectionMessage: String?
    @Published var showingMenu = false
    @Published var menuPath: [MenuRoute] = []

    private let store = MoodStore.shared
    private let repository = MoodRepository.shared
    private var loadTask: Task<Void, Never>?
    private var loadTicket = 0
    private var pollTask: Task<Void, Never>?

    private nonisolated static let randomTries = 3

    init() {
        tokenId = store.tokenId
        collectionIds = store.collectionIds
        render()
    }

    // MARK: State

    var title: String { info?.name ?? "Moods #\(tokenId)" }

    var detail: String {
        if let status { return status }
        guard let info else { return "Not loaded yet" }
        let updated = info.updatedAt.formatted(.relative(presentation: .named))
        return [info.expression, info.mood, "Updated \(updated)"].compactMap { $0 }.joined(separator: "\n")
    }

    var isCollected: Bool { collectionIds.contains(tokenId) }
    var canCollect: Bool { image != nil }

    func render() {
        tokenId = store.tokenId
        collectionIds = store.collectionIds
        let current = store.currentInfo
        if current != info || (current != nil && image == nil) {
            image = store.currentImage.flatMap { ImageTools.cgImage(from: $0) }
        }
        info = current
    }

    // MARK: Lifecycle

    func scenePhaseChanged(_ phase: ScenePhase) {
        switch phase {
        case .active:
            render()
            DriftScheduler.ensureScheduled()
            if store.currentImage == nil {
                if !isLoading { refreshNow() }
            } else {
                Task { await checkForDrift() }
            }
            startPolling()
        case .background:
            pollTask?.cancel()
            pollTask = nil
        default:
            break
        }
    }

    /// While the app is open during the drift window, checks once a minute like the Android app.
    private func startPolling() {
        guard pollTask == nil else { return }
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 60_000_000_000)
                guard let self, !Task.isCancelled else { return }
                let now = Date()
                guard now >= DriftClock.windowStart(on: now), now < DriftClock.giveUpTime(on: now),
                      !store.isDoneToday(now)
                else { continue }
                await checkForDrift()
            }
        }
    }

    private func checkForDrift() async {
        if await repository.checkForDrift() { didChange() }
    }

    // MARK: Actions

    func openMenu() {
        if !isLoading { status = nil }
        collectionMessage = nil
        render()
        menuPath = []
        showingMenu = true
    }

    func show(_ id: Int) {
        startLoad(id) { [repository] in try await repository.load(id) }
    }

    func refreshNow() {
        startLoad(tokenId) { [repository] in try await repository.refresh() }
    }

    /// Some IDs may be missing (e.g. burned), so a miss just rolls again.
    func pickRandom() {
        startLoad(nil) { [repository] in
            let supply = await repository.totalSupply()
            let current = repository.store.tokenId
            var miss: Error = MoodError.notFound(0)
            for _ in 0..<Self.randomTries {
                var id = Int.random(in: 1...supply)
                if id == current { id = id % supply + 1 }
                do {
                    try await repository.load(id)
                    return
                } catch MoodError.notFound(let missing) {
                    miss = MoodError.notFound(missing)
                }
            }
            throw miss
        }
    }

    func toggleCollected() {
        let id = tokenId
        if store.isCollected(id) {
            store.removeFromCollection(id)
        } else {
            store.addToCollection(id)
            Task { [repository] in await repository.seedThumbFromCurrent() }
        }
        collectionIds = store.collectionIds
    }

    func removeFromCollection(_ id: Int) {
        store.removeFromCollection(id)
        collectionIds = store.collectionIds
    }

    /// Puts a Collection Mood on the face, then returns to the face.
    func switchTo(_ id: Int) {
        guard switchingTo == nil else { return }
        if id == tokenId && image != nil {
            showingMenu = false
            return
        }
        switchingTo = id
        collectionMessage = nil
        Task {
            defer { switchingTo = nil }
            do {
                try await repository.load(id)
                didChange()
                showingMenu = false
            } catch MoodError.notFound {
                collectionMessage = "Mood #\(id) not found"
            } catch {
                if !Self.isCancellation(error) { collectionMessage = "Couldn't load #\(id). Try again." }
            }
        }
    }

    #if DEBUG
    @Published var showingWidgetGallery = false

    func debugReplaceCollection(_ ids: [Int]) {
        for id in store.collectionIds where !ids.contains(id) { store.removeFromCollection(id) }
        store.collectionIds = ids
        collectionIds = ids
    }

    var widgetEntry: MoodEntry {
        MoodEntry(date: Date(), tokenId: tokenId, traits: info?.traits, image: image)
    }
    #endif

    func cachedThumb(_ id: Int) -> CollectionThumb? { store.cachedThumb(id) }

    func fetchThumb(_ id: Int) async throws -> CollectionThumb { try await repository.fetchThumb(id) }

    // MARK: Loading

    /// The current Mood stays on screen until `work` has saved the new one. `id` is the Mood
    /// being loaded, or nil for a random pick.
    private func startLoad(_ id: Int?, _ work: @escaping @Sendable () async throws -> Void) {
        loadTask?.cancel()
        loadTicket += 1
        let ticket = loadTicket
        isLoading = true
        status = id.map { "Loading Moods #\($0)…" } ?? "Picking a random Mood…"
        loadTask = Task {
            defer { if ticket == loadTicket { isLoading = false } }
            do {
                try await work()
                guard ticket == loadTicket else { return }
                status = nil
                didChange()
            } catch {
                guard ticket == loadTicket, !Self.isCancellation(error) else { return }
                if case MoodError.notFound = error {
                    status = id.map { "Mood #\($0) not found" } ?? "No Mood found, try again"
                } else {
                    status = error.localizedDescription
                }
            }
        }
    }

    private func didChange() {
        render()
        WidgetCenter.shared.reloadAllTimelines()
    }

    private static func isCancellation(_ error: Error) -> Bool {
        error is CancellationError || (error as? URLError)?.code == .cancelled
    }
}
