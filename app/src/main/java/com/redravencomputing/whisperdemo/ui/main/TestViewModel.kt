package com.redravencomputing.whisperdemo.ui.main

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.redravencomputing.whisper.whispercore.Whisper
import com.redravencomputing.whisper.whispercore.WhisperDelegate
import com.redravencomputing.whisper.whispercore.WhisperOperationError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException


private const val LOG_TAG = "TestViewModel"

class TestViewModel(private val application: Application) : ViewModel(), WhisperDelegate {

	var canTranscribe by mutableStateOf(false)
		private set
	var dataLog by mutableStateOf("")
		private set
	var isRecording by mutableStateOf(false)
		private set

	private val modelsPath = File(application.filesDir, "models")
	private val samplesPath = File(application.filesDir, "samples")
	private val whisper = Whisper(application)

	init {
		whisper.delegate = this@TestViewModel
		viewModelScope.launch {
			whisper.callRequestRecordPermission()
			loadData()
		}

	}

	private suspend fun loadData() {
		try {
			//copyAssets()
			//loadBaseModel()
			copySamples()
			loadBaseModelUsingFilePath()
		} catch (e: Exception) {
			Log.w(LOG_TAG, e)
		}
	}

	private suspend fun copyAssets() = withContext(Dispatchers.IO) {
		modelsPath.mkdirs()
		samplesPath.mkdirs()
		//application.copyData("models", modelsPath, ::printMessage)
		application.copyData("samples",samplesPath, ::printMessage)
	}

	private suspend fun loadBaseModel() = withContext(Dispatchers.IO) {
		printMessage("Loading model...\n")
		val models = application.assets.list("models/")
		if (models != null) {
			whisper.initializeModelFromAsset("models/" + models[0])
			printMessage("Loaded model ${models[0]}.\n")
			canTranscribe = whisper.canTranscribe
		}

		//val firstModel = modelsPath.listFiles()!!.first()
		//whisperContext = WhisperContext.createContextFromFile(firstModel.absolutePath)
	}

	private suspend fun printMessage(msg: String) = withContext(Dispatchers.Main) {
		dataLog += msg
	}

	fun benchmark() = viewModelScope.launch {
		whisper.benchmark()
	}

	private suspend fun getFirstSample(): File = withContext(Dispatchers.IO) {
		samplesPath.listFiles()!!.first()
	}

	private fun transcribeAudio(file: File) {
		whisper.transcribeAudioFile(file, log = true)
	}
	fun transcribeSample() = viewModelScope.launch {
		transcribeAudio(getFirstSample())
	}

	fun toggleRecord() = viewModelScope.launch {
//		isRecording = whisper.isRecording()
		Log.d(LOG_TAG, "VM: toggleRecord called. Current VM isRecording (before calling API): $isRecording")
		whisper.toggleRecording()
	}

	override fun onCleared() {
		runBlocking {
			whisper.cleanup()
		}
	}

	override fun didTranscribe(text: String) {
		Log.d(LOG_TAG, "**********************************")
		Log.d(LOG_TAG, "VM Delegate: Transcription result: $text")
		viewModelScope.launch {
			printMessage(text + "\n")
		}
		Log.d(LOG_TAG, "**********************************")
	}


	override fun didStartRecording() {
		viewModelScope.launch(Dispatchers.Main) { // Ensure UI update on main thread
			Log.i(LOG_TAG, "VM Delegate: didStartRecording CALLED")
			isRecording = true
			printMessage("VM: Recording Started.\n")
		}
	}

	override fun didStopRecording() {
		viewModelScope.launch(Dispatchers.Main) { // Ensure UI update on main thread
			Log.i(LOG_TAG, "VM Delegate: didStopRecording CALLED")
			isRecording = false
			printMessage("VM: Recording Stopped.\n")
			// Transcription is likely triggered by WhisperState after this,
			// so didTranscribe or failedToTranscribe will follow.
		}
	}

	override fun recordingFailed(error: WhisperOperationError) {
		viewModelScope.launch {
			printMessage("VM Delegate: Recording Failed! Error: ${error.message}\n")
			// Update isRecording state if applicable
			withContext(Dispatchers.Main) { isRecording = false }
		}
	}

	override fun failedToTranscribe(error: WhisperOperationError) {
		viewModelScope.launch {
			printMessage("VM Delegate: Transcription Failed! Error: ${error.message}\n")
		}
	}

	override fun permissionRequestNeeded() {
		viewModelScope.launch {
			printMessage("VM Delegate: Microphone permission request needed. Please handle in UI.\n")
			// Here you could set a flag for the UI to show a message or prompt,
			// or if your UI button directly triggers the system permission dialog,
			// this might just be for logging or more complex UI flows.
		}
	}

	/**
	 * Called by the UI after the system permission dialog has been handled.
	 * This updates the Whisper API's internal permission state.
	 */
	fun onUIPermissionResult(granted: Boolean) {
		Log.i("TestViewModel", "VM: UI reported permission result: $granted")
		whisper.onRecordPermissionResult(granted) // Inform the Whisper API

		if (granted) {
			// Optional: If you want to immediately try an action after permission is granted by UI.
			// For example, if the user clicked "record", permission was requested,
			// and now it's granted, you might want to automatically start recording.
			// Be careful with this, ensure it's the desired UX.
			// viewModelScope.launch {
			//     printMessage("VM: Permission granted by UI, attempting to toggle record...\n")
			//     toggleRecord() // Or whatever the original onClick action was
			// }
		} else {
			viewModelScope.launch {
				printMessage("VM: Microphone permission was denied by user via UI dialog.\n")
			}
		}
	}

	companion object {
		fun factory() = viewModelFactory {
			initializer {
				val application =
					this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
				TestViewModel(application)
			}
		}
	}

	private suspend fun loadBaseModelUsingFilePath() {
		printMessage("Preparing to load model using file path...\n")

		val assetModelFolderName = "models"
		// Get the first model file name from the "models" asset folder
		val assetModelFileName: String? = application.assets.list(assetModelFolderName)?.firstOrNull()

		if (assetModelFileName == null) {
			printMessage("Error: No models found in assets folder '$assetModelFolderName'.\n")
			Log.e(LOG_TAG, "No models found in assets/$assetModelFolderName")
			return
		}

		val fullAssetPath = "$assetModelFolderName/$assetModelFileName"
		// Name for the copied file in internal storage
		val destinationFileNameInInternalStorage = "test_model.bin"

		// Copy the asset to internal storage to get a usable file path
		val modelFileOnDevice: File? = copyAssetToInternalStorage(
			assetPathToCopy = fullAssetPath,
			destinationFileName = destinationFileNameInInternalStorage
		)

		if (modelFileOnDevice != null && modelFileOnDevice.exists()) {
			printMessage("Model asset '$fullAssetPath' copied to: ${modelFileOnDevice.absolutePath}\n")
			printMessage("Loading model from path: ${modelFileOnDevice.absolutePath}...\n")
			try {
				// *** This is where you use your Whisper API's file path loading method ***
				whisper.initializeModel(modelPath = modelFileOnDevice.absolutePath, log = true)

				// After the call, check if the model is loaded and update UI state
				// Ideally, a delegate callback from 'whisper' would update this.
				// If not, you might need to check the state directly if initializeModel is fully synchronous
				// or if your WhisperAPI updates its internal state that canTranscribe() reflects.
				// For now, let's assume canTranscribe might be updated by a delegate or
				// initializeModel is synchronous enough for the next line to be somewhat accurate.
				canTranscribe = whisper.canTranscribe // Check state
				if (canTranscribe) {
					printMessage("Model loaded successfully from path (canTranscribe is true).\n")
				} else {
					printMessage("Model loading from path might have failed or is pending (canTranscribe is false).\n")
				}

			} catch (e: Exception) {
				printMessage("Error initializing model from path '${modelFileOnDevice.absolutePath}': ${e.localizedMessage}\n")
				Log.e(LOG_TAG, "Error in whisper.initializeModel from path", e)
				canTranscribe = false
			}
		} else {
			printMessage("Error: Failed to copy model asset '$fullAssetPath' to internal storage.\n")
			Log.e(LOG_TAG, "Failed to obtain a valid file for the model from asset: $fullAssetPath")
			canTranscribe = false
		}
	}

	private suspend fun copyAssetToInternalStorage(assetPathToCopy: String, destinationFileName: String): File? = withContext(Dispatchers.IO) {
		try {
			val destinationFile = File(application.filesDir, destinationFileName)

			if (destinationFile.exists()) {
				destinationFile.delete() // Overwrite if exists for fresh test
			}

			application.assets.open(assetPathToCopy).use { inputStream ->
				destinationFile.outputStream().use { outputStream ->
					inputStream.copyTo(outputStream)
				}
			}
			Log.i(LOG_TAG, "Asset '$assetPathToCopy' copied to '${destinationFile.absolutePath}'")
			destinationFile // Return the File object of the copied file
		} catch (e: IOException) {
			printMessage("IOError copying asset '$assetPathToCopy' to '$destinationFileName': ${e.message}\n")
			Log.e(LOG_TAG, "Failed to copy asset '$assetPathToCopy'", e)
			null
		}
	}

	private suspend fun copySamples() = withContext(Dispatchers.IO) {
		val samplesPath = File(application.filesDir, "samples")
		if (!samplesPath.exists()) {
			samplesPath.mkdirs()
		}
		// Assuming you have 'copyData' as an extension function like in your original code
		application.copyData("samples", samplesPath, ::printMessage)
	}

}

suspend fun Context.copyData(
	assetDirName: String,
	destDir: File,
	printMessage: suspend (String) -> Unit
) = withContext(Dispatchers.IO) {
	assets.list(assetDirName)?.forEach { name ->
		val assetPath = "$assetDirName/$name"
		Log.v(LOG_TAG, "Processing $assetPath...")
		val destination = File(destDir, name)
		Log.v(LOG_TAG, "Copying $assetPath to $destination...")
		printMessage("Copying $name...\n")
		assets.open(assetPath).use { input ->
			destination.outputStream().use { output ->
				input.copyTo(output)
			}
		}
		Log.v(LOG_TAG, "Copied $assetPath to $destination")
	}
}