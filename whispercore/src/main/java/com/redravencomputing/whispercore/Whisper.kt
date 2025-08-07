package com.redravencomputing.whispercore

import android.content.Context
import android.util.Log
import com.redravencomputing.whispercore.media.DefaultAudioDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


/**
 * Public API for interacting with the Whisper speech-to-text engine.
 * This class aims to provide an interface similar to its Swift counterpart.
 */
class Whisper(applicationContext: Context) {

	private val appContext: Context = applicationContext.applicationContext // Ensure app context

	private val controller: WhisperState = WhisperState(appContext, DefaultAudioDecoder())

	// Scope for API methods that launch coroutines and ensure callbacks on Main if needed
	private val apiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

	companion object {
		private const val TAG = "WhisperAPI"
	}


	/**
	 * A delegate that receives updates about transcription results and errors.
	 */
	var delegate: WhisperDelegate?
		get() = controller.delegate
		set(value) {
			controller.delegate = value
			Log.d(TAG, "Delegate ${if (value != null) "set" else "cleared"}.")
		}

	// --- State Properties (mirroring Swift API as functions) ---

	/**
	 * Checks whether a Whisper model has been successfully loaded.
	 * (Corresponds to `isModelLoaded()` in Swift)
	 * @return `true` if a model is loaded, `false` otherwise.
	 */
	val isModelLoaded: Boolean
		get() = controller.isModelLoaded

	val canTranscribe: Boolean
		get() = controller.canTranscribe

	val isRecording: Boolean
		get() = controller.isRecording

	val isMicPermissionGranted: Boolean // You might want this to be read-only
		get() = controller.isMicPermissionGranted

	/**
	 * Checks if microphone permission is currently granted according to the system.
	 * This method also refreshes the framework's internal understanding of the permission state.
	 *
	 * @return `true` if permission is granted, `false` otherwise.
	 */
	fun isMicrophonePermissionGranted(): Boolean {
		return controller.refreshAndCheckSystemMicPermission()
	}


	// MARK: Setup APIs

	/**
	 * Loads the Whisper model asynchronously from a given file path.
	 * (Corresponds to `initializeModel(at modelPath: String, log: Bool) async throws` in Swift)
	 *
	 * @param modelPath The file system path to the Whisper model.
	 * @param log Whether to enable internal logging for this operation. Default is `false`.
	 * @throws WhisperLoadError if loading fails.
	 */
	suspend fun initializeModel(modelPath: String, log: Boolean = false) {
		Log.d(TAG, "initializeModel suspend called with path: $modelPath, log: $log")
		controller.loadModelFromPath(modelPath, log).getOrThrow()
		Log.i(TAG, "Model initialization from path finished. isModelLoaded: $isModelLoaded")
	}

	/**
	 * Loads the Whisper model from a file system path with a completion handler.
	 * (Corresponds to `initializeModel(at modelPath: String, log: Bool, completion: @escaping (Result<Void, Error>) -> Void)` in Swift)
	 *
	 * @param modelPath The file system path to the Whisper model.
	 * @param log Whether to enable internal logging for this operation. Default is `false`.
	 * @param completion Completion handler. Callback is invoked on the main thread.
	 * @return A [Job] representing the launched coroutine.
	 */
	fun initializeModel(
		modelPath: String,
		log: Boolean = false,
		completion: (Result<Unit>) -> Unit // Result<Unit> mirrors Result<Void, Error>
	): Job {
		Log.d(TAG, "initializeModel with completion handler for path: $modelPath, log: $log")
		return apiScope.launch { // Ensure completion is on Main
			val result = controller.loadModelFromPath(modelPath, log)
			Log.i(TAG, "Model initialization from path with completion finished. Result: ${result.isSuccess}, isModelLoaded: $isModelLoaded")
			completion(result)
		}
	}

	/**
	 * Loads the Whisper model asynchronously from an asset file.
	 * Android-specific addition.
	 *
	 * @param assetPath The path to the model file within the app's assets directory.
	 * @param log Whether to enable internal logging for this operation. Default is `false`.
	 * @throws WhisperLoadError if loading fails.
	 */
	suspend fun initializeModelFromAsset(assetPath: String, log: Boolean = false) {
		Log.d(TAG, "initializeModelFromAsset suspend called with asset: $assetPath, log: $log")
		controller.loadModelFromAsset(appContext.assets, assetPath, log).getOrThrow()
		Log.i(TAG, "Model initialization from asset finished. isModelLoaded: $isModelLoaded")
	}

	/**
	 * Loads the Whisper model from an asset file with a completion handler.
	 * Android-specific addition.
	 *
	 * @param assetPath The path to the model file within the app's assets directory.
	 * @param log Whether to enable internal logging for this operation. Default is `false`.
	 * @param completion Completion handler. Callback is invoked on the main thread.
	 * @return A [Job] representing the launched coroutine.
	 */
	fun initializeModelFromAsset(
		assetPath: String,
		log: Boolean = false,
		completion: (Result<Unit>) -> Unit
	): Job {
		Log.d(TAG, "initializeModelFromAsset with completion handler for asset: $assetPath, log: $log")
		return apiScope.launch {
			val result = controller.loadModelFromAsset(appContext.assets, assetPath, log)
			Log.i(TAG, "Model initialization from asset with completion finished. Result: ${result.isSuccess}, isModelLoaded: $isModelLoaded")
			completion(result)
		}
	}

	/**
	 * Initiates the process for requesting microphone permission.
	 *
	 * On Android, this method cannot directly show the system permission dialog.
	 * Instead, it checks if permission is already granted.
	 * - If granted, it does nothing further.
	 * - If not granted, it informs the application (via [WhisperDelegate.permissionRequestNeeded])
	 *   that it needs to trigger the system permission dialog.
	 *
	 * After the application shows the dialog and the user responds, the application
	 * MUST call [onRecordPermissionResult] with the outcome.
	 *
	 * This is the closest equivalent to the iOS `callRequestRecordPermission()` within
	 * Android's permission model.
	 */
	fun callRequestRecordPermission() { // Matches your iOS API name
		Log.i(TAG, "callRequestRecordPermission called.")

		// Refresh and check current system permission
		val currentlyGranted = controller.refreshAndCheckSystemMicPermission()

		if (currentlyGranted) {
			Log.i(TAG, "Microphone permission is already granted. No request needed.")
		} else {
			Log.w(TAG, "Microphone permission is NOT granted. Application needs to request it.")
			// SIGNAL THE APP TO DO THE WORK
			// This is the crucial part for Android.
			// The delegate needs a way to tell the Activity/Fragment to act.
			apiScope.launch { // Ensure delegate callback is on main thread
				delegate?.permissionRequestNeeded()
			}
		}
	}

	/**
	 * Updates Whisper's internal state regarding microphone permission.
	 * Call this from your Activity/Fragment after the system's permission dialog is dismissed
	 * (e.g., from the ActivityResultLauncher callback).
	 *
	 * @param granted `true` if permission was granted, `false` otherwise.
	 */
	fun onRecordPermissionResult(granted: Boolean) {
		Log.i(TAG, "onRecordPermissionResult called with granted: $granted")
		controller.updateInternalMicPermissionStatus(granted) // Update WhisperState
		if (granted) {
			Log.i(TAG, "Microphone permission reported as GRANTED by app.")
			// If you have a specific delegate method for permission outcome:
			// delegate?.permissionOutcome(true, "Permission granted by user")
		} else {
			Log.w(TAG, "Microphone permission reported as DENIED by app.")
			// delegate?.permissionOutcome(false, "Permission denied by user")
			// If startRecording is attempted now, it will fail via its own checks.
		}
	}

	// MARK: Function APIs

	/**
	 * Starts recording audio.
	 * (Corresponds to `startRecording()` in Swift, which is async there)
	 * This Kotlin version also launches a coroutine to ensure any potential blocking
	 * work in the controller is off the main thread.
	 * Errors are reported via the delegate.
	 */
	fun startRecording() { // Matches Swift's async nature by being callable from main
		Log.d(TAG, "startRecording called.")
		apiScope.launch { // Perform actual recording start off the main thread
			controller.startRecording()
			// State update (isRecording) happens within controller.startRecording()
			// Delegate calls for errors also happen within controller.startRecording()
		}
	}

	/**
	 * Stops ongoing audio recording.
	 * Transcription of the recorded audio will be attempted automatically by the controller.
	 * Results and errors are reported via the delegate.
	 * (Corresponds to `stopRecording()` in Swift)
	 */
	fun stopRecording() { // Matches Swift's async nature
		Log.d(TAG, "stopRecording called.")
		// `controller.stopRecording()` is a suspend function, so it will run on the
		// dispatcher of apiScope or be shifted by withContext within stopRecording if needed.
		apiScope.launch {
			controller.stopRecording()
			// Delegate calls for transcription result/error happen within controller.stopRecording()
		}
	}

	/**
	 * Toggles recording state.
	 * If currently recording, stops recording (which then triggers transcription).
	 * Otherwise, starts recording.
	 * (Corresponds to `toggleRecording()` in Swift)
	 */
	fun toggleRecording() {
		Log.d(TAG, "toggleRecording called. Currently recording: $isRecording")
		if (isRecording) {
			apiScope.launch { // for stopRecording (suspend)
				controller.stopRecording()
			}
		} else {
			apiScope.launch { // for startRecording (potentially blocking)
				controller.startRecording()
			}
		}
	}

	/**
	 * Transcribes an audio file.
	 * (Corresponds to `transcribeSample(from url: URL)` in Swift)
	 *
	 * @param audioFile The [File] object pointing to the audio file to transcribe.
	 * @param log Whether to enable internal logging for this operation. Default is `false`.
	 */
	fun transcribeAudioFile(audioFile: File, log: Boolean = false, timestamp: Boolean = false) { // Changed to non-suspend to match Swift and use apiScope
		Log.d(TAG, "transcribeAudioFile called for: ${audioFile.path}, log: $log")
		apiScope.launch {
			if (!audioFile.exists() || !audioFile.isFile) {
				val error = WhisperOperationError.MissingRecordedFile
				Log.e(TAG, "transcribeAudioFile: File error at ${audioFile.absolutePath}")
				// Delegate call should be ensured on Main thread by WhisperState or here
				withContext(Dispatchers.Main) { // Ensure delegate is on Main
					controller.delegate?.failedToTranscribe(error)
				}
				return@launch
			}
			// controller.transcribeAudioFile is suspend
			controller.transcribeAudioFile(audioFile, log, timestamp = timestamp)
		}
	}


	/**
	 * Enables or disables audio playback after transcription.
	 * (Corresponds to `enablePlayback(_ enabled: Bool)` in Swift)
	 *
	 * @param enabled Set `true` to enable playback, `false` to disable.
	 */
	fun enablePlayback(enabled: Boolean) { // Renamed from setAudioPlaybackEnabled
		Log.d(TAG, "enablePlayback called with: $enabled")
		controller.setAudioPlaybackEnabled(enabled)
	}

	/**
	 * Resets the internal Whisper state.
	 * (Corresponds to `reset()` in Swift)
	 */
	fun reset() {
		Log.d(TAG, "reset called.")
		apiScope.launch(Dispatchers.Default) { // Reset might involve file ops or context release
			controller.resetState()
			Log.i(TAG, "Whisper API reset complete.")
		}
	}

	/**
	 * Returns a string containing the internal log messages from Whisper.
	 * (Corresponds to `getMessageLogs()` in Swift)
	 *
	 * @return A string of accumulated internal messages and logs.
	 */
	fun getMessageLogs(): String { // Renamed from getMessageLog
		val logs = controller.messageLog.toString()
		// Log.v(TAG, "getMessageLogs called. Returning ${logs.length} chars.") // Verbose
		return logs
	}


	/**
	 * Runs a benchmark test on the currently loaded model.
	 * This is typically used for debugging and performance analysis.
	 * The results of the benchmark are appended to the internal message log.
	 * (Corresponds to `benchmark()` in Swift)
	 *
	 * Note: The Swift version is DEBUG only. This Kotlin version doesn't have that
	 * compile-time guard, but it could be added if desired (e.g., by checking BuildConfig.DEBUG).
	 */
	fun benchmark() { // Changed to non-suspend, matches Swift's fire-and-forget nature (prints to log)
		Log.d(TAG, "benchmark called.")
		if (!BuildConfig.DEBUG) {
			Log.w(TAG, "Benchmark is only available in DEBUG builds. Skipping.")
			// Optionally append to your internal log as well
			// controller.messageLog.append("Benchmark skipped: Only available in DEBUG builds.\n")
			return
		}
		// Check if this is a debug build
		if (!isModelLoaded) {
			Log.w(TAG, "Benchmark skipped: Model not loaded.")
			controller.messageLog.append("Benchmark skipped: Model not loaded.\n")
			return
		}
		apiScope.launch(Dispatchers.Default) { // Benchmarking is CPU intensive
			Log.i(TAG, "Starting benchmark...")
			controller.benchmarkCurrentModel() // This appends to controller.messageLog
			Log.i(TAG, "Benchmark finished. Check message logs.")
			// Swift version also prints the log, so we can do that here or rely on getMessageLogs()
			// For Flutter, you'd likely fetch the log via getMessageLogs() after calling benchmark.
		}
	}

	/**
	 * Gets system information relevant to the Whisper library's native components.
	 * (No direct public Swift equivalent, but useful for debugging)
	 *
	 * @param log Whether to enable internal logging for this operation. Default is `true`.
	 * @return A [String] containing system information.
	 */
	fun getSystemInfo(log: Boolean = true): String {
		Log.d(TAG, "getSystemInfo called with log: $log")
		return controller.getSystemInfo(log)
	}

	/**
	 * Cleans up resources used by the Whisper instance.
	 * This should be called when the Whisper API is no longer needed to prevent leaks.
	 * It cancels any ongoing operations and releases resources.
	 */
	fun cleanup() {
		Log.i(TAG, "Whisper API cleanup initiated.")
		// Cancel coroutines started by this API layer first
		apiScope.cancel("Whisper API cleanup requested.")
		// Then tell the controller to clean up its resources and scopes
		// The controller's cleanup might also be on a background thread if it involves heavy ops
		// For simplicity, direct call; if controller.cleanup() is heavy, it should manage its own threading.
		controller.cleanup()
		Log.i(TAG, "Whisper API cleanup finished.")
	}
}
