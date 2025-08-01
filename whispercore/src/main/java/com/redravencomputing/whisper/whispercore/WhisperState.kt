package com.redravencomputing.whisper.whispercore


import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.redravencomputing.whisper.whispercore.media.AudioDecoder
import com.redravencomputing.whisper.whispercore.recorder.IRecorder
import com.redravencomputing.whisper.whispercore.recorder.Recorder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.ObjectStreamException
import kotlin.math.min


// For model loading errors
sealed class WhisperLoadError(message: String, cause: Throwable? = null) : Exception(message, cause) {
	object PathToModelEmpty : WhisperLoadError("Path to the model file is empty.") {
		// Ensure that deserialization returns the singleton instance
		@Throws(ObjectStreamException::class)
		private fun readResolve(): Any = PathToModelEmpty
	}

	object CouldNotLocateModel : WhisperLoadError("Model file not found at the specified path.") {
		// Ensure that deserialization returns the singleton instance
		@Throws(ObjectStreamException::class)
		private fun readResolve(): Any = CouldNotLocateModel
	}

	// Regular class, does not need readResolve for singleton purposes
	class UnableToLoadModel(details: String, cause: Throwable? = null) :
		WhisperLoadError("Unable to load model: $details", cause)

	// Add other specific load errors if needed
	// If you add more 'object' declarations here, they will also need 'readResolve()'
}

// For runtime/operational errors (analogous to WhisperCoreError)
sealed class WhisperOperationError(message: String, cause: Throwable? = null) : Exception(message, cause) {
	object MissingRecordedFile : WhisperOperationError("No recorded audio file found.") {
		@Throws(ObjectStreamException::class)
		private fun readResolve(): Any = MissingRecordedFile
	}

	object MicPermissionDenied : WhisperOperationError("Microphone access denied.") {
		@Throws(ObjectStreamException::class)
		private fun readResolve(): Any = MicPermissionDenied
	}

	object ModelNotLoaded : WhisperOperationError("Model has not been loaded for transcription.") {
		@Throws(ObjectStreamException::class)
		private fun readResolve(): Any = ModelNotLoaded
	}

	object RecordingFailed : WhisperOperationError("Audio recording failed.") {
		@Throws(ObjectStreamException::class)
		private fun readResolve(): Any = RecordingFailed
	}

	class TranscriptionFailed(details: String, cause: Throwable? = null) :
		WhisperOperationError("Transcription failed: $details", cause)
	// Add other specific operation errors
}

interface WhisperDelegate {
	fun didTranscribe(text: String)
	fun recordingFailed(error: WhisperOperationError) // Or specific sub-errors
	fun failedToTranscribe(error: WhisperOperationError) // Or specific sub-errors
	// Optional: fun onStateUpdated(isRecording: Boolean, canTranscribe: Boolean, etc.)
	fun permissionRequestNeeded()
	fun didStartRecording() // NEW
	fun didStopRecording()
}

internal class WhisperState(
	private val applicationContext: Context, // Needed for file paths, permissions, MediaRecorder
	private val audioDecoder: AudioDecoder,
	private val recorder: IRecorder = Recorder(),
	private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
	private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
)  {

	companion object {
		private const val TAG = "WhisperState" // Shortened for Logcat
	}

	// --- State Properties ---
	var isModelLoaded: Boolean = false
		private set
	var canTranscribe: Boolean = false // Renamed to avoid clash with function
		private set
	var isRecording: Boolean = false
		private set
	var isMicPermissionGranted: Boolean = false
		private set

	val messageLog: StringBuilder = StringBuilder()
	var delegate: WhisperDelegate? = null


	// --- Internal Components ---
	private var whisperContext: WhisperContext? = null

	private var recordedFile: File? = null
	private var mediaPlayer: MediaPlayer? = null
	private var audioPlaybackEnabled: Boolean = true // Default as per your MainScreenViewModel

//	private val recorder : IRecorder = Recorder()

	// Scope for this controller's operations
	private val controllerScope = CoroutineScope(defaultDispatcher + SupervisorJob())


	// This performs the actual system check and updates the internal flag.
	// It returns whether permission is currently granted by the system.
	fun refreshAndCheckSystemMicPermission(): Boolean {
		val systemGranted = ContextCompat.checkSelfPermission(
			applicationContext, Manifest.permission.RECORD_AUDIO
		) == PackageManager.PERMISSION_GRANTED
		if (isMicPermissionGranted != systemGranted) {
			isMicPermissionGranted = systemGranted // Update if different
			// appendToLog("System check: Updated internal permission to $systemGranted\n")
		} else {
			// appendToLog("System check: Internal permission was already $systemGranted\n")
		}
		return systemGranted
	}

	// --- Permission Handling ---
	// Renamed for clarity: this updates the flag based on an external result.
	fun updateInternalMicPermissionStatus(granted: Boolean) {
		isMicPermissionGranted = granted
		// appendToLog("Internal mic permission status updated to: $granted\n") // Your log
	}


	// --- Model Loading ---
	suspend fun loadModelFromPath(path: String, logToInternal: Boolean): Result<Unit> {
		if (logToInternal) appendToLog("Attempting to load model from path: $path\n")
		if (path.isEmpty()) {
			val error = WhisperLoadError.PathToModelEmpty
			if (logToInternal) appendToLog("Error: ${error.message}\n")
			return Result.failure(error)
		}
		val modelFile = File(path)
		if (!modelFile.exists() || !modelFile.isFile) {
			val error = WhisperLoadError.CouldNotLocateModel
			if (logToInternal) appendToLog("Error: ${error.message}\n")
			return Result.failure(error)
		}

		return try {
			withContext(Dispatchers.IO) {
				whisperContext?.release() // Release previous before creating new
				val newContext = WhisperContext.createContextFromFile(path)
				whisperContext = newContext
				isModelLoaded = true
				canTranscribe = true
				if (logToInternal) appendToLog("Model loaded successfully from $path.\n")
				Result.success(Unit)
			}
		} catch (e: Exception) {
			isModelLoaded = false
			canTranscribe = false
			val error = WhisperLoadError.UnableToLoadModel(e.localizedMessage ?: "Unknown JNI error", e)
			if (logToInternal) appendToLog("Error loading model: ${error.message}\n")
			Result.failure(error)
		}
	}

	suspend fun loadModelFromAsset(
		assetManager: AssetManager,
		assetPath: String,
		logToInternal: Boolean
	): Result<Unit> {
		if (logToInternal) appendToLog("Attempting to load model from asset: $assetPath\n")
		if (assetPath.isEmpty()) {
			val error = WhisperLoadError.PathToModelEmpty
			if (logToInternal) appendToLog("Error: ${error.message}\n")
			return Result.failure(error)
		}

		return try {
			withContext(Dispatchers.IO) {
				whisperContext?.release() // Release previous
				// Ensure your WhisperContext.createContextFromAsset can take your IWhisperJNI
				val newContext = WhisperContext.createContextFromAsset(assetManager, assetPath)
				whisperContext = newContext
				isModelLoaded = true
				canTranscribe = true
				if (logToInternal) appendToLog("Model loaded successfully from asset $assetPath.\n")
				Result.success(Unit)
			}
		} catch (e: FileNotFoundException) {
			isModelLoaded = false
			canTranscribe = false
			val error = WhisperLoadError.CouldNotLocateModel
			if (logToInternal) appendToLog("Error: Model asset not found at $assetPath. ${e.message}\n")
			Result.failure(error)
		}
		catch (e: Exception) {
			isModelLoaded = false
			canTranscribe = false
			val error = WhisperLoadError.UnableToLoadModel(e.localizedMessage ?: "Unknown JNI error from asset", e)
			if (logToInternal) appendToLog("Error loading model from asset: ${error.message}\n")
			Result.failure(error)
		}
	}

//	private suspend fun getTempFileForRecording() = withContext(Dispatchers.IO) {
//		File.createTempFile("recording", "wav")
//	}

	private suspend fun getTempFileForRecording(): File = withContext(defaultDispatcher) { // Use injected defaultDispatcher
		val cacheDirToUse = applicationContext.cacheDir // This will be mockCacheDir in tests
		// Ensure the directory exists (important if the system cleans it or for first run)
		if (!cacheDirToUse.exists()) {
			cacheDirToUse.mkdirs()
		}
		try {
			File.createTempFile("recording", ".wav", cacheDirToUse) // Use the 3-argument version
		} catch (e: IOException) {
			Log.e(TAG, "Failed to create temp file in ${cacheDirToUse.absolutePath}", e)
			throw e // Rethrow to make the test fail clearly if this is the issue
		}
	}

	// --- Recording ---
	fun startRecording() {
		if (!isMicPermissionGranted) {
			appendToLog("Start recording: Mic permission denied after re-check.\n")
			delegate?.recordingFailed(WhisperOperationError.MicPermissionDenied)
			return
		}

		if (isRecording) {
			appendToLog("Start recording: Already recording.\n")
			return
		}
		if (!isModelLoaded) { // Or maybe allow recording without model, but can't transcribe later? Your choice.
			appendToLog("Start recording: Model not loaded. Transcription won't be possible immediately.\n")
			// Not necessarily an error to record, but inform delegate or log
			return
		}

		controllerScope.launch {
			try {
				stopPlayback() // Stop any ongoing playback
				val file = getTempFileForRecording()
				recordedFile = file // Update reference
				recorder.startRecording(file) { e ->
					controllerScope.launch(Dispatchers.Main) {
						isRecording = false // Update state
						recordedFile = null
						delegate?.recordingFailed(WhisperOperationError.RecordingFailed)
						Log.e(
							TAG,
							"Recording error callback from Recorder: ${e.localizedMessage}"
						)
						appendToLog("Recording failed in Recorder: ${e.localizedMessage}\n")
					}
				}
				withContext(mainDispatcher) {
					isRecording = true
					canTranscribe = false // Can't transcribe while recording (or after until stop)
					delegate?.didStartRecording()
					appendToLog("Recording started to ${file.absolutePath}.\n")
				}
			} catch (e: Exception) {
				Log.e(TAG, "Failed to initiate startRecording with AudioRecord-based Recorder", e)
				// Ensure state is correct and delegate is notified on the Main thread
				withContext(mainDispatcher) {
					isRecording = false
					recordedFile = null
					delegate?.recordingFailed(WhisperOperationError.RecordingFailed)
					appendToLog("Failed to start AudioRecord recording: ${e.localizedMessage}\n")
				}
			}
		}
	}

	fun stopRecording() {
		Log.d(TAG, "stopRecording called. isRecording: ${this.isRecording}")
		if (!this.isRecording) {
			appendToLog("Stop recording: Not recording.\n")
			return
		}
		controllerScope.launch {
			var successfullyStoppedRecorder = false
			try {
				recorder.stopRecording()
				successfullyStoppedRecorder = true
			} catch (e: Exception) {
				Log.w(TAG, "AudioRecord-based recorder stopRecording() failed: ${e.message}", e)
				appendToLog("Error stopping AudioRecord-based recorder: ${e.localizedMessage}\n")
			}

			withContext(Dispatchers.Main) {
				isRecording = false // CRITICAL: Update state immediately
				delegate?.didStopRecording()
				// Determine if transcription is possible now that recording has stopped
				canTranscribe = isModelLoaded // Can transcribe if model is loaded and not recording
				appendToLog("WhisperState updated: isRecording=false, canTranscribe=$canTranscribe.\n")
			}

			if (successfullyStoppedRecorder && isModelLoaded) {
				val fileToTranscribe = recordedFile // Use the file that was being recorded
				if (fileToTranscribe != null && fileToTranscribe.exists()) {
					appendToLog("Model loaded. Attempting to transcribe recorded file: ${fileToTranscribe.name}\n")
					// transcribeAudioFile is suspend and handles its own delegate calls on Main
					transcribeAudioFile(fileToTranscribe, true)
				} else {
					appendToLog("No valid recorded file found to transcribe (or file missing).\n")
					withContext(Dispatchers.Main) {
						delegate?.failedToTranscribe(WhisperOperationError.MissingRecordedFile)
					}
				}
			} else if (isModelLoaded) {
				appendToLog("Model not loaded, cannot transcribe recorded file.\n")
				// No need to call failedToTranscribe here if didStopRecording already implies this
				// or if the UI primarily relies on canTranscribe state.
				// If you want a specific "transcription skipped due to no model" message, you can add:
				withContext(Dispatchers.Main) {
					delegate?.failedToTranscribe(WhisperOperationError.ModelNotLoaded)
				}
			} else if (!successfullyStoppedRecorder) {
				appendToLog("Recorder did not stop successfully. Skipping transcription of potentially corrupt file.\n")
				withContext(Dispatchers.Main) {
					delegate?.failedToTranscribe(WhisperOperationError.RecordingFailed) // Or a more specific error
				}
			}

			if (recordedFile != null) {
				appendToLog("Clearing reference to recorded file: ${recordedFile?.name}.\n")
				recordedFile = null
			}

			appendToLog("stopRecording function execution complete.\n")
		}
	}

	// --- Transcription ---
	suspend fun transcribeAudioFile(file: File, logToInternal: Boolean, timestamp: Boolean = false) {
		if (!isModelLoaded) {
			val error = WhisperOperationError.ModelNotLoaded
			if (logToInternal) appendToLog("Transcription error: ${error.message} for file ${file.name}\n")
			delegate?.failedToTranscribe(error)
			return
		}
		val currentContext = whisperContext
		if (currentContext == null) {
			val error = WhisperOperationError.ModelNotLoaded // Context is gone
			if (logToInternal) appendToLog("Transcription error: ${error.message} (null context) for file ${file.name}\n")
			delegate?.failedToTranscribe(error)
			return
		}
		if (!canTranscribe) { // Model is loaded but something else prevents (e.g. already transcribing)
			if (logToInternal) appendToLog("Cannot transcribe ${file.name} now, (already busy or flag false).\n")
			return
		}

		canTranscribe = false // Mark as busy
		if (logToInternal) appendToLog("Reading audio samples from ${file.name}...\n")

		try {
			val audioData = withContext(Dispatchers.IO) {
				stopPlayback()
				if (audioPlaybackEnabled) {
					startPlayback(file) // This will still attempt to use the real MediaPlayer
				}
				// VV CH ANGE THIS LINE VV
				this@WhisperState.audioDecoder.decode(file) // Use the injected decoder
				// ^^ CH ANGE THIS LINE ^^
			}
			if (logToInternal) appendToLog("${audioData.size / (16000F / 1000F)} ms of audio data read.\n")

			if (logToInternal) appendToLog("Transcribing data...\n")
			val startTime = System.currentTimeMillis()
			val text = currentContext.transcribeData(audioData, timestamp) // This is your JNI call
			val elapsedTime = System.currentTimeMillis() - startTime
			if (logToInternal) appendToLog("Transcription done ($elapsedTime ms): $text\n")
			withContext(Dispatchers.Main) { // Call delegate on Main thread
				delegate?.didTranscribe(text)
			}
		} catch (e: Exception) {
			Log.e(TAG, "Transcription failed for ${file.name}", e)
			val error = WhisperOperationError.TranscriptionFailed(e.localizedMessage ?: "Unknown error", e)
			if (logToInternal) appendToLog("Error during transcription: ${error.message}\n")
			withContext(Dispatchers.Main) {
				delegate?.failedToTranscribe(error)
			}
		} finally {
			if (isModelLoaded) canTranscribe = true // Ready for new transcription if model still loaded
		}
	}

	// --- Playback ---
	fun setAudioPlaybackEnabled(enabled: Boolean) {
		audioPlaybackEnabled = enabled
		appendToLog("Audio playback after transcription set to: $enabled\n")
		if (!enabled) {
			stopPlayback()
		}
	}

	private fun startPlayback(file: File) {
		if (!audioPlaybackEnabled) return

		stopPlayback() // Stop previous playback
		try {
			mediaPlayer = MediaPlayer().apply {
				setDataSource(applicationContext, Uri.fromFile(file))
				prepareAsync() // Prepare asynchronously
				setOnPreparedListener {
					appendToLog("MediaPlayer prepared, starting playback for ${file.name}.\n")
					it.start()
				}
				setOnCompletionListener {
					appendToLog("MediaPlayer playback completed for ${file.name}.\n")
					stopPlayback() // Release after completion
				}
				setOnErrorListener { mp, what, extra ->
					Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra for ${file.name}")
					appendToLog("MediaPlayer error for ${file.name}: what=$what, extra=$extra.\n")
					stopPlayback()
					true // Error handled
				}
			}
		} catch (e: Exception) {
			Log.e(TAG, "MediaPlayer setup failed for ${file.name}", e)
			appendToLog("MediaPlayer setup error for ${file.name}: ${e.localizedMessage}.\n")
			stopPlayback()
		}
	}

	private fun stopPlayback() {
		mediaPlayer?.let {
			if (it.isPlaying) {
				it.stop()
			}
			it.release()
			appendToLog("MediaPlayer stopped and released.\n")
		}
		mediaPlayer = null
	}

	// --- State & Reset ---
	fun resetState() {
		appendToLog("Resetting WhisperStateController state.\n")
		controllerScope.launch { // Ensure release happens on its designated dispatcher
			whisperContext?.release()
		}
		whisperContext = null
		stopPlayback()
		isRecording = false
		recordedFile = null
		isModelLoaded = false
		canTranscribe = false
		messageLog.clear() // Optional: clear log on reset
		// delegate callbacks for state change?
	}

	// --- Benchmarking ---
	suspend fun benchmarkCurrentModel() {
		if(whisperContext == null) {
			messageLog.append("Model not loaded. Cannot benchmark.")
			return
		}
		if (!isModelLoaded) {
			messageLog.append("Model not loaded. Cannot benchmark.")
			return
		}
		if (!canTranscribe) {
			return
		}
		val nThreads = maxOf(1, min(4, Runtime.getRuntime().availableProcessors()))
		canTranscribe = false

		messageLog.append("Running benchmark. This will take minutes...\n")
		whisperContext?.benchMemory(nThreads)?.let{ messageLog.append(it) }
		messageLog.append("\n")
		whisperContext?.benchGgmlMulMat(nThreads)?.let{ messageLog.append(it) }

		canTranscribe = true
	}



	// --- Logging ---
	private fun appendToLog(message: String) {
		synchronized(messageLog) {
			messageLog.append(message)
		}
		Log.d(TAG, message.trimEnd()) // Also log to Android's Logcat for easier debugging
	}

	fun getSystemInfo(logToInternal: Boolean): String {
		return try {
			val info = WhisperContext.getSystemInfo() // Use your static getter
			if (logToInternal) appendToLog("System Info: $info\n")
			info
		} catch (e: Exception) {
			val errorMsg = "Failed to get system info: ${e.localizedMessage}\n"
			if (logToInternal) appendToLog(errorMsg)
			"Error getting system info: ${e.localizedMessage}"
		}
	}

	// --- Cleanup ---
	fun cleanup() {
		appendToLog("Cleaning up WhisperStateController.\n")
		resetState() // Ensure context is released etc.
		controllerScope.cancel() // Cancel all coroutines started by this controller
	}

}


