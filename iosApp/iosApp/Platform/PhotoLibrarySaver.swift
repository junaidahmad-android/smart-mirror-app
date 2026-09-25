import Foundation
import Photos
import SharedLogic

/// Thin seam over PhotoKit so the saver's logic is unit-testable.
protocol PhotoLibraryClient {
    func authorizationStatus() -> PHAuthorizationStatus
    func requestAuthorization(_ completion: @escaping (PHAuthorizationStatus) -> Void)
    /// Moves the file into the library; returns the new asset's local identifier.
    func addPhoto(fileURL: URL, completion: @escaping (Result<String, Error>) -> Void)
}

struct SystemPhotoLibrary: PhotoLibraryClient {
    func authorizationStatus() -> PHAuthorizationStatus {
        PHPhotoLibrary.authorizationStatus(for: .addOnly)
    }

    func requestAuthorization(_ completion: @escaping (PHAuthorizationStatus) -> Void) {
        PHPhotoLibrary.requestAuthorization(for: .addOnly, handler: completion)
    }

    func addPhoto(fileURL: URL, completion: @escaping (Result<String, Error>) -> Void) {
        var identifier: String?
        PHPhotoLibrary.shared().performChanges({
            let request = PHAssetCreationRequest.forAsset()
            let options = PHAssetResourceCreationOptions()
            options.shouldMoveFile = true
            request.addResource(with: .photo, fileURL: fileURL, options: options)
            identifier = request.placeholderForCreatedAsset?.localIdentifier
        }, completionHandler: { success, error in
            if success {
                completion(.success(identifier ?? ""))
            } else {
                completion(.failure(error ?? CocoaError(.fileWriteUnknown)))
            }
        })
    }
}

/// `PhotoSaver` port: Photos with **add-only** authorization (MVP-08.AC3). The temp file is always
/// gone afterwards, moved into the library or deleted (MVP-08.AC7).
final class PhotoLibrarySaver: NSObject, PhotoSaver {
    private let library: PhotoLibraryClient

    init(library: PhotoLibraryClient = SystemPhotoLibrary()) {
        self.library = library
    }

    func save(photo: CapturedPhoto, onResult: @escaping (SaveOutcome) -> Void) {
        let url = PhotoFiles.url(fromLocalUri: photo.localUri)
        let once = OnceCallback<SaveOutcome> { outcome in
            PhotoFiles.removeQuietly(url)
            onResult(outcome)
        }
        switch library.authorizationStatus() {
        case .authorized, .limited:
            write(url, once)
        case .notDetermined:
            library.requestAuthorization { [library] status in
                if status == .authorized || status == .limited {
                    Self.add(url, library: library, once)
                } else {
                    once.call(SaveOutcomeFailed(reason: .permissionDenied))
                }
            }
        default:
            once.call(SaveOutcomeFailed(reason: .permissionDenied))
        }
    }

    private func write(_ url: URL, _ once: OnceCallback<SaveOutcome>) {
        Self.add(url, library: library, once)
    }

    private static func add(_ url: URL, library: PhotoLibraryClient, _ once: OnceCallback<SaveOutcome>) {
        library.addPhoto(fileURL: url) { result in
            switch result {
            case .success(let id): once.call(SaveOutcomeSaved(uri: id))
            case .failure(let error): once.call(SaveOutcomeFailed(reason: failure(for: error)))
            }
        }
    }

    static func failure(for error: Error) -> SaveFailure {
        let ns = error as NSError
        if ns.domain == NSCocoaErrorDomain && ns.code == NSFileWriteOutOfSpaceError { return .storageFull }
        if ns.domain == NSPOSIXErrorDomain && ns.code == Int(ENOSPC) { return .storageFull }
        if ns.domain == PHPhotosErrorDomain {
            switch PHPhotosError.Code(rawValue: ns.code) {
            case .accessUserDenied?, .accessRestricted?: return .permissionDenied
            case .notEnoughSpace?: return .storageFull
            default: return .unknown
            }
        }
        return .unknown
    }
}
