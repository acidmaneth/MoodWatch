import Foundation

/// The parts of a Moods metadata JSON the app uses.
struct MoodMetadata: Sendable {
    var name: String?
    var image: String?
    var expression: String?
    var mood: String?

    init(json: Data) throws {
        guard let object = try JSONSerialization.jsonObject(with: json) as? [String: Any] else {
            throw MoodError.badResponse("metadata is not a JSON object")
        }
        name = Self.nonBlank(object["name"])
        image = Self.nonBlank(object["image"])
        let attributes = object["attributes"] as? [[String: Any]] ?? []
        func trait(_ type: String) -> String? {
            attributes.first { Self.nonBlank($0["trait_type"]) == type }.flatMap { Self.nonBlank($0["value"]) }
        }
        expression = trait("Expression")
        mood = trait("Mood")
    }

    private static func nonBlank(_ value: Any?) -> String? {
        let text: String? = switch value {
        case let string as String: string
        case let number as NSNumber: number.stringValue
        default: nil
        }
        guard let text, !text.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return text
    }
}

/// Stateless access to the Moods contract, metadata and images.
struct MoodAPI: Sendable {
    private static let tokenURISelector = "c87b56dd"
    private static let totalSupplySelector = "18160ddd"
    private static let resizeBase = "https://wsrv.nl/?url="
    /// URLEncoder.encode's unreserved set, so the proxy URL matches Android's.
    private static let queryValueAllowed = CharacterSet(
        charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._*"
    )

    let session: URLSession

    init(requestTimeout: TimeInterval = 20, resourceTimeout: TimeInterval = 45) {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = requestTimeout
        config.timeoutIntervalForResource = resourceTimeout
        config.waitsForConnectivity = false
        config.httpAdditionalHeaders = ["User-Agent": Moods.userAgent, "Accept": "*/*"]
        session = URLSession(configuration: config)
    }

    // MARK: Chain

    func tokenURI(_ id: Int) async throws -> String {
        let data = "0x" + Self.tokenURISelector + Self.uint256Hex(id)
        let result: String
        do {
            result = try await ethCall(data: data)
        } catch RPCError.rejected {
            throw MoodError.notFound(id)
        }
        guard result.count > 2 else { throw MoodError.notFound(id) }
        return try Self.decodeABIString(result)
    }

    func totalSupply() async throws -> Int {
        let result = try await ethCall(data: "0x" + Self.totalSupplySelector)
        let hex = Self.stripHexPrefix(result).drop { $0 == "0" }
        guard !hex.isEmpty else { return 0 }
        guard hex.count <= 15, let value = Int(hex, radix: 16) else {
            throw MoodError.badResponse("totalSupply \(result)")
        }
        return value
    }

    private enum RPCError: Error { case rejected(String) }

    private func ethCall(data: String) async throws -> String {
        let body: [String: Any] = [
            "jsonrpc": "2.0",
            "id": 1,
            "method": "eth_call",
            "params": [["to": Moods.contract, "data": data], "latest"],
        ]
        var request = URLRequest(url: Moods.rpcURL)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let response = try await fetch(request)
        guard let json = try JSONSerialization.jsonObject(with: response) as? [String: Any] else {
            throw MoodError.badResponse("RPC reply is not a JSON object")
        }
        if let error = json["error"] as? [String: Any] {
            throw RPCError.rejected(error["message"] as? String ?? "")
        }
        return json["result"] as? String ?? ""
    }

    // MARK: Metadata

    /// Metadata at a token URI: an http(s) URL, or an inline data: URI.
    func metadata(at uri: String, id: Int) async throws -> MoodMetadata {
        if uri.hasPrefix("data:application/json") {
            return try MoodMetadata(json: Self.decodeDataURI(uri))
        }
        guard let url = URL(string: uri) else { throw MoodError.badResponse("bad token URI \(uri)") }
        do {
            return try await MoodMetadata(json: fetch(URLRequest(url: url)))
        } catch MoodError.http(status: 404, host: _) {
            throw MoodError.notFound(id)
        }
    }

    // MARK: Images

    /// A `size`px square JPEG of the image at `url`. The originals are 2048px and 2–5 MB, so a
    /// resized copy comes from the wsrv.nl proxy, falling back to resizing the original here.
    func squareJPEG(from url: String, size: Int) async throws -> Data {
        if url.hasPrefix("https://") || url.hasPrefix("http://"), let resized = Self.resizedURL(url, size: size) {
            if let bytes = try? await fetch(URLRequest(url: resized)),
               let dims = ImageTools.pixelSize(of: bytes), dims.width == size, dims.height == size {
                return bytes
            }
            try Task.checkCancellation()
        }
        guard let original = URL(string: url) else { throw MoodError.badResponse("bad image URL \(url)") }
        return try await ImageTools.squareJPEG(from: fetch(URLRequest(url: original)), size: size)
    }

    static func resizedURL(_ url: String, size: Int) -> URL? {
        guard let encoded = url.addingPercentEncoding(withAllowedCharacters: queryValueAllowed) else { return nil }
        return URL(string: resizeBase + encoded + "&w=\(size)&h=\(size)&fit=cover&output=jpg&q=88")
    }

    static func resolve(_ uri: String) -> String {
        if uri.hasPrefix("ipfs://ipfs/") { return "https://ipfs.io/ipfs/" + uri.dropFirst("ipfs://ipfs/".count) }
        if uri.hasPrefix("ipfs://") { return "https://ipfs.io/ipfs/" + uri.dropFirst("ipfs://".count) }
        if uri.hasPrefix("ar://") { return "https://arweave.net/" + uri.dropFirst("ar://".count) }
        return uri
    }

    // MARK: HTTP

    private func fetch(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw MoodError.badResponse("not HTTP") }
        guard (200..<300).contains(http.statusCode) else {
            throw MoodError.http(status: http.statusCode, host: request.url?.host ?? "server")
        }
        return data
    }

    // MARK: Encoding

    static func uint256Hex(_ value: Int) -> String {
        let hex = String(value, radix: 16)
        return String(repeating: "0", count: max(0, 64 - hex.count)) + hex
    }

    static func decodeABIString(_ hex: String) throws -> String {
        let bytes = try hexBytes(stripHexPrefix(hex))
        func word(at offset: Int) throws -> Int {
            guard offset >= 0, offset + 32 <= bytes.count,
                  bytes[offset..<offset + 24].allSatisfy({ $0 == 0 })
            else { throw MoodError.badResponse("bad ABI string") }
            return bytes[offset + 24..<offset + 32].reduce(0) { $0 << 8 | Int($1) }
        }
        let offset = try word(at: 0)
        let length = try word(at: offset)
        let start = offset + 32
        guard start + length <= bytes.count, let string = String(bytes: bytes[start..<start + length], encoding: .utf8)
        else { throw MoodError.badResponse("bad ABI string") }
        return string
    }

    static func decodeDataURI(_ uri: String) throws -> Data {
        guard let comma = uri.firstIndex(of: ",") else { throw MoodError.badResponse("bad data URI") }
        let header = uri[..<comma], payload = String(uri[uri.index(after: comma)...])
        let data = header.hasSuffix(";base64")
            ? Data(base64Encoded: payload, options: .ignoreUnknownCharacters)
            : payload.removingPercentEncoding?.data(using: .utf8)
        guard let data else { throw MoodError.badResponse("bad data URI") }
        return data
    }

    private static func stripHexPrefix(_ hex: String) -> Substring {
        hex.hasPrefix("0x") ? hex.dropFirst(2) : Substring(hex)
    }

    private static func hexBytes(_ hex: Substring) throws -> [UInt8] {
        guard hex.count.isMultiple(of: 2) else { throw MoodError.badResponse("odd hex length") }
        var bytes: [UInt8] = []
        bytes.reserveCapacity(hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<next], radix: 16) else { throw MoodError.badResponse("bad hex") }
            bytes.append(byte)
            index = next
        }
        return bytes
    }
}
