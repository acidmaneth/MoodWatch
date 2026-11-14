import CoreGraphics
import Foundation
import ImageIO

enum ImageTools {
    static func pixelSize(of data: Data) -> (width: Int, height: Int)? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = props[kCGImagePropertyPixelWidth] as? Int,
              let height = props[kCGImagePropertyPixelHeight] as? Int
        else { return nil }
        return (width, height)
    }

    /// Decodes `data`, downsampled so its longer side is at most `maxPixelSize` (never upscaled).
    static func cgImage(from data: Data, maxPixelSize: Int? = nil) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        guard let maxPixelSize else { return CGImageSourceCreateImageAtIndex(source, 0, nil) }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    /// A `size`×`size` JPEG, center-cropped from the image in `data`.
    static func squareJPEG(from data: Data, size: Int, quality: Double = 0.88) throws -> Data {
        guard let dims = pixelSize(of: data), dims.width > 0, dims.height > 0 else {
            throw MoodError.unreadableImage
        }
        let shorter = min(dims.width, dims.height), longer = max(dims.width, dims.height)
        let decodeSize = max(size, Int((Double(size) * Double(longer) / Double(shorter)).rounded(.up)))
        guard let image = cgImage(from: data, maxPixelSize: decodeSize) else { throw MoodError.unreadableImage }
        return try jpeg(squareCropped(image, size: size), quality: quality)
    }

    static func squareCropped(_ image: CGImage, size: Int) throws -> CGImage {
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(
                  data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                  space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
              )
        else { throw MoodError.unreadableImage }
        context.interpolationQuality = .high
        let scale = Double(size) / Double(min(image.width, image.height))
        let w = Double(image.width) * scale, h = Double(image.height) * scale
        context.draw(image, in: CGRect(x: (Double(size) - w) / 2, y: (Double(size) - h) / 2, width: w, height: h))
        guard let result = context.makeImage() else { throw MoodError.unreadableImage }
        return result
    }

    static func jpeg(_ image: CGImage, quality: Double = 0.88) throws -> Data {
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output as CFMutableData, "public.jpeg" as CFString, 1, nil)
        else { throw MoodError.unreadableImage }
        let options: [CFString: Any] = [kCGImageDestinationLossyCompressionQuality: quality]
        CGImageDestinationAddImage(destination, image, options as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw MoodError.unreadableImage }
        return output as Data
    }
}
