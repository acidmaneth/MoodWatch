import Foundation

enum Moods {
    static let contract = "0xa6ebbdfcfeafdc7a5a778d6fe514f45bd0a0133d"
    static let defaultTokenId = 1184
    static let fallbackSupply = 3333
    static let appGroup = "group.com.moodwatch.app"

    static let rpcURL = URL(string: "https://rpc.mainnet.chain.robinhood.com")!
    static let userAgent = "Mozilla/5.0 (Apple Watch; watchOS) MoodWatch/1.0"

    /// Apple Watch screens are up to 514px tall, so the face image is a little larger than Android's 450px.
    static let faceSize = 512
    static let thumbSize = 120
}

struct MoodInfo: Codable, Equatable, Sendable {
    var tokenId: Int
    var name: String
    var expression: String?
    var mood: String?
    var imageURL: String
    var updatedAt: Date

    /// "Mood · Expression", as in the Collection rows.
    var traits: String? { Self.traits(mood: mood, expression: expression) }

    static func traits(mood: String?, expression: String?) -> String? {
        let parts = [mood, expression].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

struct CollectionThumb: Sendable {
    var tokenId: Int
    var expression: String?
    var mood: String?
    var image: Data
    var fetchedAt: Date

    /// False once Moods have changed expression since this was fetched.
    var isFresh: Bool { fetchedAt >= DriftClock.lastDriftAt() }

    var traits: String? { MoodInfo.traits(mood: mood, expression: expression) }
}

enum MoodError: LocalizedError, Equatable {
    case notFound(Int)
    case noImage(Int)
    case http(status: Int, host: String)
    case badResponse(String)
    case unreadableImage

    var errorDescription: String? {
        switch self {
        case .notFound(let id): "Mood #\(id) not found"
        case .noImage(let id): "Mood #\(id) has no image"
        case .http(let status, let host): "Server returned \(status) for \(host)"
        case .badResponse(let detail): "Unexpected response: \(detail)"
        case .unreadableImage: "Could not read the Mood image"
        }
    }
}
