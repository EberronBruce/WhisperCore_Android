package com.whispercpp.whisper


import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.whispercpp.whisper.media.AudioDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
}

class WhisperState(
	private val applicationContext: Context, // Needed for file paths, permissions, MediaRecorder
	private val audioDecoder: AudioDecoder
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
	private var mediaRecorder: MediaRecorder? = null
	private var recordedFile: File? = null
	private var mediaPlayer: MediaPlayer? = null
	private var audioPlaybackEnabled: Boolean = true // Default as per your MainScreenViewModel

	// Scope for this controller's operations
	private val controllerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())


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
		} catch (e: java.io.FileNotFoundException) {
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
		}

		stopPlayback() // Stop any ongoing playback

		try {
			val file = File(applicationContext.cacheDir, "output.wav") // Using cacheDir
			recordedFile = file

			val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				MediaRecorder(applicationContext)
			} else {
				createLegacyMediaRecorder()
			}
			mediaRecorder = recorder.apply {
				setAudioSource(MediaRecorder.AudioSource.MIC)
				setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP) // Common format, check if your JNI needs PCM/WAV
				setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)    // Or MediaRecorder.AudioEncoder.AAC
				setOutputFile(file.absolutePath)
				prepare()
				start()
			}
			isRecording = true
			canTranscribe = false // Can't transcribe while recording (or after until stop)
			appendToLog("Recording started to ${file.absolutePath}.\n")
		} catch (e: IOException) {
			Log.e(TAG, "MediaRecorder prepare() failed", e)
			appendToLog("Recording start failed: ${e.localizedMessage}\n")
			isRecording = false
			recordedFile = null
			delegate?.recordingFailed(WhisperOperationError.RecordingFailed)
		} catch (e: IllegalStateException) {
			Log.e(TAG, "MediaRecorder start() failed or other state issue", e)
			appendToLog("Recording start failed (state): ${e.localizedMessage}\n")
			isRecording = false
			recordedFile = null
			delegate?.recordingFailed(WhisperOperationError.RecordingFailed)
		}
	}

	@Suppress("DEPRECATION")
	private fun createLegacyMediaRecorder(): MediaRecorder {
		return MediaRecorder()
	}

	suspend fun stopRecording() {
		if (!isRecording) {
			appendToLog("Stop recording: Not recording.\n")
			return
		}
		try {
			mediaRecorder?.apply {
				stop()
				release()
			}
		} catch (e: RuntimeException) {
			// Can happen if stop() is called too soon after start() or in a bad state
			Log.w(TAG, "MediaRecorder stop/release failed: ${e.message}")
			appendToLog("MediaRecorder stop/release issue: ${e.localizedMessage}\n")
		}
		mediaRecorder = null
		isRecording = false
		appendToLog("Recording stopped.\n")

		if (isModelLoaded) {
			canTranscribe = true // Ready to transcribe the recorded file
			val fileToTranscribe = recordedFile
			if (fileToTranscribe != null && fileToTranscribe.exists()) {
				appendToLog("Attempting to transcribe recorded file: ${fileToTranscribe.name}\n")
				// Automatically transcribe after stopping
				transcribeAudioFile(fileToTranscribe, true) // logToInternal = true
			} else {
				appendToLog("No valid recorded file found to transcribe.\n")
				delegate?.failedToTranscribe(WhisperOperationError.MissingRecordedFile)
				canTranscribe = false // Or keep true if model is loaded generally
			}
		} else {
			appendToLog("Model not loaded, cannot transcribe recorded file.\n")
			canTranscribe = false
			delegate?.failedToTranscribe(WhisperOperationError.ModelNotLoaded)
		}
	}

	// --- Transcription ---
	suspend fun transcribeAudioFile(file: File, logToInternal: Boolean) {
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
			val text = currentContext.transcribeData(audioData) // This is your JNI call
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
//	suspend fun transcribeAudioFile(file: File, logToInternal: Boolean) {
//		if (!isModelLoaded) {
//			val error = WhisperOperationError.ModelNotLoaded
//			if (logToInternal) appendToLog("Transcription error: ${error.message} for file ${file.name}\n")
//			delegate?.failedToTranscribe(error)
//			return
//		}
//		val currentContext = whisperContext
//		if (currentContext == null) {
//			val error = WhisperOperationError.ModelNotLoaded // Context is gone
//			if (logToInternal) appendToLog("Transcription error: ${error.message} (null context) for file ${file.name}\n")
//			delegate?.failedToTranscribe(error)
//			return
//		}
//		if (!canTranscribe) { // Model is loaded but something else prevents (e.g. already transcribing)
//			if (logToInternal) appendToLog("Cannot transcribe ${file.name} now, (already busy or flag false).\n")
//			// Decide if this is an error to send to delegate or just an internal state
//			return
//		}
//
//
//		canTranscribe = false // Mark as busy
//		if (logToInternal) appendToLog("Reading audio samples from ${file.name}...\n")
//
//		try {
//			val audioData = withContext(Dispatchers.IO) {
//				// Your decodeWaveFile needs to be robust for different formats
//				// or ensure MediaRecorder saves in a compatible WAV format if decodeWaveFile expects that.
//				// For simplicity, using your existing decodeWaveFile
//				// This might need adjustment based on MediaRecorder output.
//				// If MediaRecorder outputs 3GP/AMR, decodeWaveFile needs to handle that or convert first.
//				stopPlayback() // Stop if playing something else
//				if (audioPlaybackEnabled) {
//					startPlayback(file) // Play the file being transcribed
//				}
//				decodeWaveFile(file) // This is from your MainScreenViewModel context. Ensure it's accessible.
//			}
//			if (logToInternal) appendToLog("${audioData.size / (16000F / 1000F)} ms of audio data read.\n")
//
//			if (logToInternal) appendToLog("Transcribing data...\n")
//			val startTime = System.currentTimeMillis()
//			val text = currentContext.transcribeData(audioData) // This is your JNI call
//			val elapsedTime = System.currentTimeMillis() - startTime
//			if (logToInternal) appendToLog("Transcription done ($elapsedTime ms): $text\n")
//			withContext(Dispatchers.Main) { // Call delegate on Main thread
//				delegate?.didTranscribe(text)
//			}
//		} catch (e: Exception) {
//			Log.e(TAG, "Transcription failed for ${file.name}", e)
//			val error = WhisperOperationError.TranscriptionFailed(e.localizedMessage ?: "Unknown error", e)
//			if (logToInternal) appendToLog("Error during transcription: ${error.message}\n")
//			withContext(Dispatchers.Main) {
//				delegate?.failedToTranscribe(error)
//			}
//		} finally {
//			if (isModelLoaded) canTranscribe = true // Ready for new transcription if model still loaded
//		}
//	}

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
		if (isRecording) { // Stop recording if active during reset
			try {
				mediaRecorder?.apply {
					// It's safer to call stop() and release() in separate try-catch
					// blocks if you want to ensure release() is attempted even if stop() fails.
					// However, MediaRecorder docs often show them chained.
					// If stop() fails, release() might also fail or be irrelevant.
					stop()
					release()
				}
			} catch (e: IllegalStateException) {
				Log.w(TAG, "MediaRecorder stop/release failed during reset (IllegalStateException): ${e.message}")
				// Usually indicates it was already stopped/released or in a bad state.
			} catch (e: RuntimeException) {
				// Catching a broader RuntimeException can be for unexpected issues
				Log.e(TAG, "MediaRecorder stop/release failed during reset (RuntimeException): ${e.message}", e)
			} finally {
				// Ensure these are set regardless of exceptions during stop/release
				isRecording = false
				mediaRecorder = null
			}
		}
		mediaRecorder = null
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


