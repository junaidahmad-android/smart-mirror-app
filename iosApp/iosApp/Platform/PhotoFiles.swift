import CoreGraphics
import CoreImage
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Temp-file handling for captured photos. The file only lives until `PhotoLibrarySaver` moves it
/// into Photos (MVP-08.AC7), and it never contains location metadata (MVP-08.AC6).
enum PhotoFiles {
    static let jpegQuality: CGFloat = 0.92

    static func newTempURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("capture-\(UUID().uuidString)")
            .appendingPathExtension("jpg")
    }

    /// Accepts both `file://` URLs and plain paths.
    static func url(fromLocalUri uri: String) -> URL {
        if let url = URL(string: uri), url.isFileURL { return url }
        return URL(fileURLWithPath: uri)
    }

    static func removeQuietly(_ url: URL) {
        try? FileManager.default.removeItem(at: url)
    }

    /// Writes [image] as JPEG (quality ≥ 0.9, no metadata), optionally flipped horizontally.
    static func writeJPEG(_ image: CGImage, mirrored: Bool, to url: URL) throws {
        let output = mirrored ? (flippedHorizontally(image) ?? image) : image
        guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
            throw CocoaError(.fileWriteUnknown)
        }
        let options: [CFString: Any] = [kCGImageDestinationLossyCompressionQuality: jpegQuality]
        CGImageDestinationAddImage(destination, output, options as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw CocoaError(.fileWriteUnknown) }
    }

    /// Largest centered rect of [imageSize] with the given width/height [aspect]: the same crop
    /// `AVLayerVideoGravity.resizeAspectFill` applies on screen.
    static func centerCropRect(imageSize: CGSize, aspect: CGFloat?) -> CGRect {
        let full = CGRect(origin: .zero, size: imageSize)
        guard let aspect, aspect > 0, imageSize.width > 0, imageSize.height > 0 else { return full }
        let imageAspect = imageSize.width / imageSize.height
        if abs(imageAspect - aspect) < 0.001 { return full }
        if imageAspect > aspect {
            let width = (imageSize.height * aspect).rounded()
            return CGRect(x: ((imageSize.width - width) / 2).rounded(), y: 0, width: width, height: imageSize.height)
        }
        let height = (imageSize.width / aspect).rounded()
        return CGRect(x: 0, y: ((imageSize.height - height) / 2).rounded(), width: imageSize.width, height: height)
    }

    static func centerCropped(_ image: CGImage, aspect: CGFloat?) -> CGImage {
        let rect = centerCropRect(imageSize: CGSize(width: image.width, height: image.height), aspect: aspect)
        guard rect.size != CGSize(width: image.width, height: image.height) else { return image }
        return image.cropping(to: rect) ?? image
    }

    private static let ciContext = CIContext(options: [.cacheIntermediates: false])

    /// Decodes an encoded photo (applying its EXIF orientation, so pixels end up upright) and
    /// center-crops it to [aspect]. Mirroring was already applied by the capture connection.
    static func uprightCropped(photoData data: Data, aspect: CGFloat?) -> CGImage? {
        guard let image = CIImage(data: data, options: [.applyOrientationProperty: true]) else { return nil }
        let extent = image.extent.integral
        let crop = centerCropRect(imageSize: extent.size, aspect: aspect).offsetBy(dx: extent.minX, dy: extent.minY)
        // CIImage's origin is bottom-left; a centered crop is symmetric, so no flip is needed.
        return ciContext.createCGImage(image, from: crop)
    }

    static func flippedHorizontally(_ image: CGImage) -> CGImage? {
        let colorSpace = image.colorSpace ?? CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: nil, width: image.width, height: image.height, bitsPerComponent: 8, bytesPerRow: 0,
            space: colorSpace, bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        ) else { return nil }
        context.translateBy(x: CGFloat(image.width), y: 0)
        context.scaleBy(x: -1, y: 1)
        context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        return context.makeImage()
    }
}
