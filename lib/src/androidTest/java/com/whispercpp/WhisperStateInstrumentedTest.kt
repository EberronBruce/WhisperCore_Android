package com.whispercpp

import android.Manifest
import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.whispercpp.whisper.WhisperDelegate
import com.whispercpp.whisper.WhisperJNIBridge
import com.whispercpp.whisper.WhisperLoadError
import com.whispercpp.whisper.WhisperOperationError
import com.whispercpp.whisper.WhisperState
import com.whispercpp.whisper.media.AudioDecoder
import com.whispercpp.whisper.recorder.IRecorder
import com.whispercpp.whisper.recorder.Recorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.mockito.Mockito.timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


@ExperimentalCoroutinesApi
class MainCoroutineRule(
	val testDispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {

	override fun starting(description: Description?) {
		super.starting(description)
		Dispatchers.setMain(testDispatcher)
	}

	override fun finished(description: Description?) {
		super.finished(description)
		Dispatchers.resetMain()
		// No cleanupTestCoroutines() needed for StandardTestDispatcher here;
		// runTest handles its own scope cleanup.
	}
}



@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class WhisperStateInstrumentedTest {

	// --- Test Rules ---
	// Rule to grant microphone permission for tests that need it
	@get:Rule
	val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

	// Rule for managing test coroutines
	@get:Rule
	var mainCoroutineRule = MainCoroutineRule() // You'll need to create this, see below

	// --- Mocks & Test Doubles ---
	private lateinit var mockAudioDecoder: AudioDecoder
	private lateinit var mockDelegate: WhisperDelegate

	// --- Test Subject ---
	private lateinit var whisperState: WhisperState

	// --- Test Data & Context ---
	private lateinit var instrumentationContext: Context
	private val testModelAssetName = "ggml-tiny.en.bin" // Ensure this is in androidTest/assets
	private val testAudioAssetName = "jfk.wav"         // Ensure this is in androidTest/assets
	private lateinit var testModelPath: String
	private lateinit var testAudioFile: File
	private lateinit var mockRecorder: IRecorder

	@Before
	fun setUp() {
		instrumentationContext = ApplicationProvider.getApplicationContext()
		println("InstrumentedTest: setUp started") // Logging

		try {
			println("InstrumentedTest: Loading native library...")
			WhisperJNIBridge.loadNativeLibrary()
			println("InstrumentedTest: Native library loaded successfully.")
		} catch (e: Throwable) { // Catch Throwable to be safe
			println("InstrumentedTest: FATAL ERROR loading native library: ${e.message}")
			e.printStackTrace()
			fail("Failed to load native library for tests: ${e.message}")
		}

		mockAudioDecoder = mock()
		mockDelegate = mock()
		mockRecorder = Recorder()
		println("InstrumentedTest: Mocks initialized. mockRecorder is: $mockRecorder")

		// Initialize whisperState *before* asset copying, so if asset copying fails,
		// tearDown still has a (partially unconfigured) whisperState to call cleanup on if needed,
		// or we can handle it more gracefully.
		// However, WhisperState constructor might need context for other things.
		// Let's try initializing it AFTER asset prep for now, but be mindful.

		try {
			println("InstrumentedTest: Copying model asset...")
			testModelPath = copyAssetToCache(instrumentationContext, testModelAssetName)
			println("InstrumentedTest: Model asset copied to $testModelPath")

			println("InstrumentedTest: Copying audio asset...")
			testAudioFile = File(copyAssetToCache(instrumentationContext, testAudioAssetName))
			println("InstrumentedTest: Audio asset copied to ${testAudioFile.absolutePath}")
		} catch (e: Throwable) {
			println("InstrumentedTest: FATAL ERROR copying assets: ${e.message}")
			e.printStackTrace()
			fail("Failed to copy assets for tests: ${e.message}")
		}
		println("InstrumentedTest: Initializing WhisperState...")
		whisperState = WhisperState(instrumentationContext, mockAudioDecoder, mockRecorder)
		try {
			val recorderField = WhisperState::class.java.getDeclaredField("recorder") // Or whatever it's called in WhisperState
			recorderField.isAccessible = true
			recorderField.set(whisperState, mockRecorder) // Inject the mock
			println("InstrumentedTest: Successfully injected mockRecorder into WhisperState.")
		} catch (e: Exception) {
			println("InstrumentedTest: FAILED to inject mockRecorder into WhisperState: $e")
			// Potentially fail the test here if injection is critical for most tests
			// For this specific test, it IS critical.
			fail("Failed to inject mockRecorder: ${e.message}")
		}// This line might throw if context is bad
		whisperState.delegate = mockDelegate
		println("InstrumentedTest: WhisperState initialized.")

		whisperState.updateInternalMicPermissionStatus(true)
		println("InstrumentedTest: setUp finished")
	}

	@After
	fun tearDown() {
		println("InstrumentedTest: tearDown started")
		// Check if whisperState was initialized before trying to use it
		if (::whisperState.isInitialized) {
			println("InstrumentedTest: Cleaning up whisperState...")
			whisperState.cleanup()
			println("InstrumentedTest: whisperState cleaned up.")
		} else {
			println("InstrumentedTest: whisperState was not initialized, skipping cleanup.")
		}

		// It's good practice to ensure paths are initialized before deleting
		if (::testModelPath.isInitialized && testModelPath.isNotEmpty()) {
			File(testModelPath).delete()
			println("InstrumentedTest: Deleted $testModelPath")
		}
		if (::testAudioFile.isInitialized && testAudioFile.exists()) {
			testAudioFile.delete()
			println("InstrumentedTest: Deleted ${testAudioFile.absolutePath}")
		}
		println("InstrumentedTest: tearDown finished")
	}

	// Ensure copyAssetToCache also has robust logging or throws clearly
	private fun copyAssetToCache(context: Context, assetName: String): String {
		val assetManager: AssetManager = context.assets
		val file = File(context.cacheDir, assetName)
		println("InstrumentedTest: Attempting to copy $assetName to ${file.absolutePath}")
		try {
			assetManager.open(assetName).use { inputStream ->
				FileOutputStream(file).use { outputStream ->
					inputStream.copyTo(outputStream)
				}
			}
			println("InstrumentedTest: Successfully copied $assetName")
		} catch (e: IOException) {
			println("InstrumentedTest: FAILED to copy asset $assetName: ${e.message}")
			e.printStackTrace() // Print stack trace for detailed error
			// Re-throw or Assert.fail to ensure the test stops if essential assets aren't copied
			fail("Failed to copy asset $assetName to cache: ${e.message}")
		}
		return file.absolutePath
	}

	// --- Test Cases ---

	@Test
	fun initialState_isCorrect() = runTest {
		Assert.assertFalse(whisperState.isModelLoaded)
		Assert.assertFalse(whisperState.isRecording)
		Assert.assertFalse(whisperState.canTranscribe)
		// isMicPermissionGranted is checked in init, and we also set it in setup
		Assert.assertTrue(whisperState.isMicPermissionGranted)
		Assert.assertNotNull(whisperState.messageLog)
	}

	// --- Model Loading Tests ---
	@Test
	fun loadModelFromPath_success_updatesStateAndContext() = runTest {
		val result = whisperState.loadModelFromPath(testModelPath, false)

		Assert.assertTrue(result.isSuccess)
		Assert.assertTrue(whisperState.isModelLoaded)
		Assert.assertTrue(whisperState.canTranscribe)
		// Cannot directly assert whisperContext is not null without exposing it,
		// but isModelLoaded and canTranscribe are good indicators.
	}

	@Test
	fun loadModelFromPath_emptyPath_returnsFailure() = runTest {
		val result = whisperState.loadModelFromPath("", false)

		Assert.assertTrue(result.isFailure)
		Assert.assertFalse(whisperState.isModelLoaded)
		Assert.assertFalse(whisperState.canTranscribe)
		Assert.assertTrue(result.exceptionOrNull() is WhisperLoadError.PathToModelEmpty)
	}

	@Test
	fun loadModelFromPath_invalidPath_returnsFailure() = runTest {
		val result = whisperState.loadModelFromPath("/invalid/path/to/model.bin", false)

		Assert.assertTrue(result.isFailure)
		Assert.assertFalse(whisperState.isModelLoaded)
		Assert.assertFalse(whisperState.canTranscribe)
		Assert.assertTrue(result.exceptionOrNull() is WhisperLoadError.CouldNotLocateModel)
	}

	@Test
	fun loadModelFromAsset_success_updatesState() = runTest {
		val result = whisperState.loadModelFromAsset(instrumentationContext.assets, testModelAssetName, false)
		Assert.assertTrue(result.isSuccess)
		Assert.assertTrue(whisperState.isModelLoaded)
		Assert.assertTrue(whisperState.canTranscribe)
	}




	@Test
	fun startRecording_whenNoMicPermission_callsDelegateAndDoesNotStart() = runTest {
		// Arrange
		whisperState.updateInternalMicPermissionStatus(false)

		// Act
		whisperState.startRecording()

		// Assert
		Assert.assertFalse(
			"isRecording should be false when internal isMicPermissionGranted is false",
			whisperState.isRecording
		)
		verify(mockDelegate).recordingFailed(eq(WhisperOperationError.MicPermissionDenied))
	}


	// --- Transcription Tests ---
	@Test
	fun transcribeAudioFile_whenModelLoadedAndFileExists_callsDelegateWithResult() = runTest {
		whisperState.loadModelFromPath(testModelPath, false).getOrThrow()
		val dummyAudioData = FloatArray(16000) // 1 sec of dummy audio

		// Ensure 'eq' and 'whenever' are from org.mockito.kotlin
		whenever(mockAudioDecoder.decode(eq(testAudioFile))).thenReturn(dummyAudioData)

		whisperState.transcribeAudioFile(testAudioFile, false)

		// Ensure 'verify', 'timeout', and 'any' are from the correct packages
		// 'verify' from org.mockito.kotlin
		// 'timeout' from org.mockito.Mockito
		// 'any' from org.mockito.kotlin
		verify(mockDelegate, timeout(1000)).didTranscribe(any<String>()) // Use any<String>() or any() from mockito-kotlin
		Assert.assertTrue(whisperState.canTranscribe)
	}


	@Test
	fun transcribeAudioFile_whenModelNotLoaded_callsDelegateWithError() = runTest {
		// Ensure model is not loaded (default state)
		Assert.assertFalse(whisperState.isModelLoaded)

		whisperState.transcribeAudioFile(testAudioFile, false)

		verify(mockDelegate).failedToTranscribe(eq(WhisperOperationError.ModelNotLoaded))
		Assert.assertFalse(whisperState.canTranscribe) // Should remain false
	}


	// --- Other State and Utility Function Tests ---

	@Test
	fun updateMicPermissionStatus_updatesInternalState() {
		whisperState.updateInternalMicPermissionStatus(true)
		Assert.assertTrue(whisperState.isMicPermissionGranted)

		whisperState.updateInternalMicPermissionStatus(false)
		Assert.assertFalse(whisperState.isMicPermissionGranted)
	}

	@Test
	fun resetState_clearsModelAndRecordingFlags() = runTest {
		// Setup some state
		whisperState.loadModelFromPath(testModelPath, false)
		// Simulate recording start if necessary, or just set flags if that's easier for unit-testing reset
		val isRecordingField = WhisperState::class.java.getDeclaredField("isRecording")
		isRecordingField.isAccessible = true
		isRecordingField.set(whisperState, true)

		whisperState.resetState()

		Assert.assertFalse(whisperState.isModelLoaded)
		Assert.assertFalse(whisperState.isRecording)
		Assert.assertFalse(whisperState.canTranscribe)
		// Check if whisperContext was released (indirectly, or by checking a log if it logs release)
	}

	@Test
	fun getSystemInfo_returnsNonEmptyString() {
		val info = whisperState.getSystemInfo(false)
		Assert.assertTrue(info.isNotEmpty())
		Assert.assertFalse(info.contains("Error", ignoreCase = true))
	}

	@Ignore("Ignoring due to native crashes in benchmark functions")
	@Test
	fun testStandaloneBenchmarks() {
		// Assuming WhisperJNIBridge.loadNativeLibrary() is called elsewhere or in init
		try {
			Log.d("StandaloneBenchmarkTest", "Attempting benchMemcpy...")
			val memcpyResult = WhisperJNIBridge.benchMemcpy(1) // Or a known safe thread count
			Log.i("StandaloneBenchmarkTest", "benchMemcpy result: $memcpyResult")
			Assert.assertFalse("Memcpy benchmark should not be empty", memcpyResult.isBlank())

			Log.d("StandaloneBenchmarkTest", "Attempting benchGgmlMulMat...")
			val mulMatResult = WhisperJNIBridge.benchGgmlMulMat(1) // Or a known safe thread count
			Log.i("StandaloneBenchmarkTest", "benchGgmlMulMat result: $mulMatResult")
			Assert.assertFalse("MulMat benchmark should not be empty", mulMatResult.isBlank())

		} catch (e: UnsatisfiedLinkError) {
			fail("Native library not loaded for standalone benchmark test: ${e.message}")
		} catch (e: Exception) {
			Log.e("StandaloneBenchmarkTest", "Standalone benchmark crashed", e)
			fail("Standalone benchmark JNI call crashed: ${e.message}")
		}
	}

	@Ignore("Temporarily disabling due to native benchmark issues on Android. See JIRA-123 or corresponding issue for details.")
	@Test
	fun benchmarkModel_whenModelLoaded_returnsBenchmarkResults() { // Note: Removed `= runTest` for a moment for logging before it
		Log.d("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: Test method started.")

		// --- Part 1: Setup specific to this test that happens BEFORE runTest ---
		// (If you have any such logic, log it. For now, we assume most is in @Before or inside runTest)

		Log.d("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: About to enter runTest block.")

		runTest { // This is the critical point. The error might occur as this block is entered.
			Log.d("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: Entered runTest block successfully.")

			// --- Step 1: Load the model ---
			Log.d("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: STEP 1 - Attempting to load model from path: $testModelPath")
			val loadResult = whisperState.loadModelFromPath(testModelPath, false)

			Log.d("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: STEP 1 - Model loading finished. isSuccess: ${loadResult.isSuccess}")
			if (loadResult.isFailure) {
				val error = loadResult.exceptionOrNull()
				Log.e("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: STEP 1 - Model loading FAILED: ${error?.message}", error)
				fail("Model loading failed before benchmark: ${error?.message}")
				return@runTest // Exit runTest if model loading fails
			}
			Assert.assertTrue("Model should be loaded in WhisperState after loadModelFromPath", whisperState.isModelLoaded)
			Assert.assertTrue("canTranscribe should be true after successful model load", whisperState.canTranscribe)
			Log.d("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: STEP 1 - Model loaded and assertions passed.")

			// --- Step 2: Clear message log (if you do this specifically for the benchmark) ---
			Log.d("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: STEP 2 - Clearing messageLog.")
			whisperState.messageLog.clear() // Assuming this is non-suspend
			Log.d("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: STEP 2 - messageLog cleared.")

			// --- Step 3: Call the benchmark function ---
			Log.d("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: STEP 3 - Attempting to call whisperState.benchmarkCurrentModel().")
			try {
				whisperState.benchmarkCurrentModel() // This is a suspend function
				Log.d("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: STEP 3 - whisperState.benchmarkCurrentModel() call completed without throwing an immediate exception.")
			} catch (e: Throwable) { // Catch any potential exceptions from the benchmark call itself
				Log.e("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: STEP 3 - whisperState.benchmarkCurrentModel() THREW an exception: ${e.message}", e)
				fail("benchmarkCurrentModel() threw an exception: ${e.message}")
				return@runTest // Exit runTest if benchmark call fails
			}

			// --- Step 4: Retrieve and assert the contents of messageLog ---
			Log.d("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: STEP 4 - Retrieving benchmark output from messageLog.")
			val benchmarkOutputFromLog = whisperState.messageLog.toString()
			Log.i("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: STEP 4 - Benchmark output from messageLog:\n$benchmarkOutputFromLog")

			Assert.assertNotNull("Benchmark output from log should not be null", benchmarkOutputFromLog)
			Assert.assertFalse("Benchmark output from log should not be empty", benchmarkOutputFromLog.isBlank())

			Log.d("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: STEP 4 - Basic assertions on benchmarkOutputFromLog passed.")

			// --- Step 5: More specific content checks for benchmark output ---
			Log.d("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: STEP 5 - Performing specific content checks on benchmark output.")
			Assert.assertTrue(
				"Log should contain 'Running benchmark'",
				benchmarkOutputFromLog.contains("Running benchmark. This will take minutes...")
			)
			// Add other specific assertions as you had before
			Assert.assertFalse(
				"Log should not indicate 'Model not loaded' from within the JNI calls after successful load",
				benchmarkOutputFromLog.substringAfter("Running benchmark.")
					.contains("Model not loaded", ignoreCase = true)
			)
			Assert.assertTrue(
				"Log should contain indications of memory benchmark execution",
				benchmarkOutputFromLog.contains("memcpy") || benchmarkOutputFromLog.contains("Memory Benchmark")
			)
			Assert.assertTrue(
				"Log should contain indications of ggml_mul_mat benchmark execution",
				benchmarkOutputFromLog.contains("ggml_mul_mat") || benchmarkOutputFromLog.contains("GGML MulMat Benchmark")
			)
			Log.d("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: STEP 5 - Specific content checks passed.")

			// --- Step 6: Final state check ---
			Log.d("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: STEP 6 - Checking canTranscribe state.")
			Assert.assertTrue("canTranscribe should be true after benchmark", whisperState.canTranscribe)
			Log.d("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: STEP 6 - canTranscribe state check passed.")

			Log.d("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: Test completed successfully within runTest block.")
		}
		Log.d("InstrumentedTest_Debug", "benchmarkModel_whenModelLoaded: Exited runTest block scope.")
	}

	@Ignore("Temporarily disabling due to native benchmark issues on Android. See JIRA-123 or corresponding issue for details.")
	@Test
	fun benchmarkCurrentModel_whenModelNotLoaded_returnsNotLoadedMessage() = runTest {
		// Ensure model is NOT loaded.
		// This assumes whisperState is in a state where isModelLoaded is false.
		// If tests share state and a previous test might have loaded a model,
		// you would need to ensure a reset or explicitly unload the model here.
		// For this example, we'll assert it's not loaded.
		Assert.assertFalse("Precondition: Model should not be loaded for this test", whisperState.isModelLoaded)
		// Also, whisperContext should ideally be null if no model is loaded.
		// Assert.assertNull("Precondition: WhisperContext should be null", whisperState.whisperContext) // whisperContext is private, can't directly assert from test

		// Clear the message log before the test to ensure we only get output from this call
		whisperState.messageLog.clear()

		// Call the suspend function
		whisperState.benchmarkCurrentModel()

		// Get the result from the messageLog
		val logOutput = whisperState.messageLog.toString()
		Log.d("InstrumentedTest", "Log output for not loaded model: '$logOutput'") // For debugging the test

		// Assert that the messageLog contains the expected "Model not loaded" message
		Assert.assertTrue(
			"Log should contain 'Model not loaded. Cannot benchmark.'",
			logOutput.contains("Model not loaded. Cannot benchmark.", ignoreCase = false) // exact match
		)

		// Assert that it DIDN'T proceed to the actual benchmarking messages
		Assert.assertFalse(
			"Log should NOT contain 'Running benchmark' if model wasn't loaded",
			logOutput.contains("Running benchmark. This will take minutes...", ignoreCase = true)
		)
	}



}

