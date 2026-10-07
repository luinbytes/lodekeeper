import AppKit
import AVFoundation
import CoreGraphics
import Darwin
import Foundation
import ScreenCaptureKit

struct RecorderError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

struct Options {
    let pid: pid_t
    let output: URL
    let seconds: Int

    static let help = """
    Usage: record-gameplay-window --pid N --output newpath.mp4 --seconds 1..120
    Requires macOS 15 and existing Screen Recording permission. Captures only the
    largest on-screen Minecraft game window owned by N. Equal-size matches fail.
    Writes original H264 MP4, up to 1920x1080 at 30 fps, without audio or microphone.
    Never overwrites output. Prints JSON metadata. Failed partial files are retained.
    """

    static func parse(_ arguments: [String]) throws -> Options {
        guard arguments.count == 6 else { throw RecorderError(message: help) }
        var values: [String: String] = [:]
        for index in stride(from: 0, to: arguments.count, by: 2) {
            let key = arguments[index]
            guard ["--pid", "--output", "--seconds"].contains(key), values[key] == nil else {
                throw RecorderError(message: "Unknown or repeated option \(key)")
            }
            values[key] = arguments[index + 1]
        }
        guard let pidText = values["--pid"], let pid = Int32(pidText), pid > 0,
              let secondsText = values["--seconds"], let seconds = Int(secondsText),
              (1...120).contains(seconds), let path = values["--output"], !path.isEmpty else {
            throw RecorderError(message: "Provide a positive PID, output path, and seconds in 1...120")
        }
        let output = URL(fileURLWithPath: path).standardizedFileURL
        guard output.pathExtension.lowercased() == "mp4" else {
            throw RecorderError(message: "Output must have an .mp4 extension")
        }
        var entry = stat()
        guard lstat(output.path, &entry) != 0 else {
            throw RecorderError(message: "Output already exists; refusing to overwrite")
        }
        guard errno == ENOENT else { throw RecorderError(message: "Cannot inspect output path") }
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: output.deletingLastPathComponent().path,
                                            isDirectory: &isDirectory), isDirectory.boolValue else {
            throw RecorderError(message: "Output parent directory must already exist")
        }
        return Options(pid: pid, output: output, seconds: seconds)
    }
}

final class Report {
    private let lock = NSLock()
    private var fields: [String: Any] = [:]
    private var original: URL?

    func update(_ values: [String: Any], original: URL? = nil) {
        lock.lock()
        defer { lock.unlock() }
        fields.merge(values) { _, new in new }
        if let original { self.original = original }
    }

    func emit(error: Error? = nil) {
        lock.lock()
        var result = fields
        let original = self.original
        lock.unlock()
        result["status"] = error == nil ? "success" : "failure"
        if let error {
            result["error"] = error.localizedDescription
            if let original, FileManager.default.fileExists(atPath: original.path) {
                result["partialOutputPath"] = original.path
                result["partialPreserved"] = true
                let attributes = try? FileManager.default.attributesOfItem(atPath: original.path)
                result["partialBytes"] = attributes?[.size] ?? 0
            }
        }
        let data = try! JSONSerialization.data(withJSONObject: result, options: [.sortedKeys])
        FileHandle.standardOutput.write(data)
        FileHandle.standardOutput.write(Data([10]))
    }
}

@available(macOS 15.0, *)
final class RecordingEvents: NSObject, SCRecordingOutputDelegate, SCStreamDelegate {
    enum State {
        case waiting, recording, finished
        case failed(Error)
    }

    private let lock = NSLock()
    private var state: State = .waiting

    func snapshot() -> State {
        lock.lock()
        defer { lock.unlock() }
        return state
    }

    private func change(_ newState: State) {
        lock.lock()
        defer { lock.unlock() }
        if case .failed = state { return }
        state = newState
    }

    func recordingOutputDidStartRecording(_ recordingOutput: SCRecordingOutput) {
        change(.recording)
    }

    func recordingOutputDidFinishRecording(_ recordingOutput: SCRecordingOutput) {
        change(.finished)
    }

    func recordingOutput(_ recordingOutput: SCRecordingOutput, didFailWithError error: Error) {
        change(.failed(error))
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        change(.failed(error))
    }

    func waitForStart() async throws {
        let deadline = ProcessInfo.processInfo.systemUptime + 10
        while ProcessInfo.processInfo.systemUptime < deadline {
            switch snapshot() {
            case .recording: return
            case .failed(let error): throw error
            case .finished: throw RecorderError(message: "Recording ended before capture began")
            case .waiting: try await Task.sleep(nanoseconds: 50_000_000)
            }
        }
        throw RecorderError(message: "Recording did not start within 10 seconds")
    }

    func waitForFinish() async throws {
        let deadline = ProcessInfo.processInfo.systemUptime + 15
        while ProcessInfo.processInfo.systemUptime < deadline {
            switch snapshot() {
            case .finished: return
            case .failed(let error): throw error
            case .waiting, .recording: try await Task.sleep(nanoseconds: 50_000_000)
            }
        }
        throw RecorderError(message: "Recording did not finish within 15 seconds")
    }
}

@available(macOS 15.0, *)
func record(_ options: Options, report: Report) async throws {
    guard CGPreflightScreenCaptureAccess() else {
        throw RecorderError(message: "Screen Recording permission is absent; no permission request was made")
    }
    let content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: true)
    let candidates = content.windows.filter { window in
        guard window.owningApplication?.processID == options.pid,
              let title = window.title,
              title.range(of: "^Minecraft(?:\\*?\\s+\\d|$)", options: .regularExpression) != nil else {
            return false
        }
        return window.isOnScreen && window.windowLayer == 0 &&
            window.frame.width.isFinite && window.frame.height.isFinite &&
            window.frame.width >= 320 && window.frame.height >= 180
    }.sorted { $0.frame.width * $0.frame.height > $1.frame.width * $1.frame.height }
    guard let window = candidates.first else {
        throw RecorderError(message: "No valid on-screen Minecraft game window belongs to the supplied PID")
    }
    if candidates.count > 1,
       abs(window.frame.width * window.frame.height -
           candidates[1].frame.width * candidates[1].frame.height) < 1 {
        throw RecorderError(message: "Largest Minecraft window is ambiguous; refusing capture")
    }

    let filter = SCContentFilter(desktopIndependentWindow: window)
    let sourceWidth = filter.contentRect.width * CGFloat(filter.pointPixelScale)
    let sourceHeight = filter.contentRect.height * CGFloat(filter.pointPixelScale)
    guard sourceWidth.isFinite, sourceHeight.isFinite, sourceWidth >= 2, sourceHeight >= 2 else {
        throw RecorderError(message: "Selected Minecraft window has invalid capture dimensions")
    }
    let scale = min(1, 1920 / sourceWidth, 1080 / sourceHeight)
    let width = max(2, Int(sourceWidth * scale) / 2 * 2)
    let height = max(2, Int(sourceHeight * scale) / 2 * 2)
    let configuration = SCStreamConfiguration()
    configuration.width = width
    configuration.height = height
    configuration.minimumFrameInterval = CMTime(value: 1, timescale: 30)
    configuration.queueDepth = 3
    configuration.scalesToFit = false
    configuration.preservesAspectRatio = true
    configuration.capturesAudio = false
    configuration.captureMicrophone = false
    configuration.includeChildWindows = false
    configuration.showsCursor = false
    configuration.showMouseClicks = false
    configuration.ignoreShadowsSingleWindow = true
    configuration.captureDynamicRange = .SDR

    let directory = options.output.deletingLastPathComponent()
        .appendingPathComponent(".record-gameplay-window-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false,
                                            attributes: [.posixPermissions: 0o700])
    let original = directory.appendingPathComponent("original.mp4")
    report.update(["windowID": window.windowID, "windowTitle": window.title ?? "",
                   "width": width, "height": height, "frameRateLimit": 30,
                   "queueDepth": 3, "audio": false, "microphone": false,
                   "codec": "H264", "container": "MP4", "original": true], original: original)
    let recordingConfiguration = SCRecordingOutputConfiguration()
    guard recordingConfiguration.availableVideoCodecTypes.contains(.h264),
          recordingConfiguration.availableOutputFileTypes.contains(.mp4) else {
        throw RecorderError(message: "ScreenCaptureKit does not offer H264 MP4 recording")
    }
    recordingConfiguration.outputURL = original
    recordingConfiguration.videoCodecType = .h264
    recordingConfiguration.outputFileType = .mp4
    let events = RecordingEvents()
    let recording = SCRecordingOutput(configuration: recordingConfiguration, delegate: events)
    let stream = SCStream(filter: filter, configuration: configuration, delegate: events)
    try stream.addRecordingOutput(recording)
    do {
        try await stream.startCapture()
        try await events.waitForStart()
        let deadline = ProcessInfo.processInfo.systemUptime + Double(options.seconds)
        while ProcessInfo.processInfo.systemUptime < deadline {
            switch events.snapshot() {
            case .failed(let error): throw error
            case .finished: throw RecorderError(message: "Recording ended before the requested duration")
            case .waiting, .recording: try await Task.sleep(nanoseconds: 50_000_000)
            }
        }
        try await stream.stopCapture()
        try await events.waitForFinish()
    } catch {
        try? await stream.stopCapture()
        throw error
    }

    let attributes = try FileManager.default.attributesOfItem(atPath: original.path)
    let bytes = (attributes[.size] as? NSNumber)?.int64Value ?? 0
    let duration = recording.recordedDuration.seconds
    guard bytes > 0, duration.isFinite, duration > 0 else {
        throw RecorderError(message: "Recording produced no positive file size or duration")
    }
    let asset = AVURLAsset(url: original)
    let fileDuration = try await asset.load(.duration).seconds
    let tracks = try await asset.loadTracks(withMediaType: .video)
    let audioTracks = try await asset.loadTracks(withMediaType: .audio)
    guard fileDuration.isFinite, fileDuration > 0, tracks.count == 1, audioTracks.isEmpty else {
        throw RecorderError(message: "Original MP4 has invalid duration or unexpected tracks")
    }
    let formats = try await tracks[0].load(.formatDescriptions)
    guard formats.contains(where: { CMFormatDescriptionGetMediaSubType($0) == kCMVideoCodecType_H264 }) else {
        throw RecorderError(message: "Original MP4 video is not H264")
    }
    // A hard link publishes the original bytes and refuses any existing destination atomically.
    guard link(original.path, options.output.path) == 0 else {
        throw RecorderError(message: "Cannot publish output without overwrite: \(String(cString: strerror(errno)))")
    }
    report.update(["bytes": bytes, "durationSeconds": fileDuration,
                   "recordedDurationSeconds": duration, "outputPath": options.output.path])
    do {
        try FileManager.default.removeItem(at: original)
        try FileManager.default.removeItem(at: directory)
    } catch {
        report.update(["cleanupWarning": error.localizedDescription, "retainedDirectory": directory.path])
    }
}

@main
struct Main {
    @MainActor
    static func main() async {
        let arguments = Array(CommandLine.arguments.dropFirst())
        if arguments == ["--help"] || arguments == ["-h"] {
            print(Options.help)
            return
        }
        let report = Report()
        do {
            let options = try Options.parse(arguments)
            report.update(["pid": options.pid, "requestedSeconds": options.seconds,
                           "requestedOutputPath": options.output.path])
            guard #available(macOS 15.0, *) else {
                throw RecorderError(message: "macOS 15 or later is required")
            }
            NSApplication.shared.setActivationPolicy(.prohibited)
            let watchdog = DispatchSource.makeTimerSource(queue: .global(qos: .utility))
            watchdog.schedule(deadline: .now() + .seconds(options.seconds + 45))
            watchdog.setEventHandler {
                report.emit(error: RecorderError(message: "Recording exceeded its bounded runtime; partial output retained"))
                exit(1)
            }
            watchdog.resume()
            do {
                try await record(options, report: report)
                watchdog.cancel()
                report.emit()
            } catch {
                watchdog.cancel()
                throw error
            }
        } catch {
            report.emit(error: error)
            exit(1)
        }
    }
}
