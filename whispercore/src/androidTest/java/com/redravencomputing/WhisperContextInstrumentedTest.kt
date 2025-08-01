package com.redravencomputing

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider // Correct for context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.redravencomputing.whisper.whispercore.WhisperContext
import com.redravencomputing.whisper.whispercore.WhisperJNIBridge // Import for explicit loading
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.IOException // For asset copying
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class WhisperContextInstrumentedTest {
	private lateinit var instrumentationContext: Context // Renamed for clarity
	private var whisperContext: WhisperContext? = null

	private val TEST_MODEL_ASSET_NAME = "ggml-tiny.en.bin"
	private val TEST_AUDIO_ASSET_NAME = "jfk.wav" // Assuming you have a test WAV

	@Before
	fun setUp() {
		instrumentationContext = ApplicationProvider.getApplicationContext()
		// **IMPORTANT for Instrumentation Tests using the modified WhisperJNIBridge**
		// Load the native library explicitly before any JNI calls.
		try {
			Log.i("WhisperContextInstrumentedTest", "Attempting to load native library in setUp...")
			WhisperJNIBridge.loadNativeLibrary()
			Log.i("WhisperContextInstrumentedTest", "Native library loaded successfully in setUp.")
		} catch (e: Throwable) {
			Log.e("WhisperContextInstrumentedTest", "Error loading native library in setUp", e)
			fail("Failed to load native library for WhisperContextInstrumentedTest: ${e.message}")
		}
	}

	private fun getTestModelPath(): String {
		val assetManager = instrumentationContext.assets // Use instrumentationContext
		val modelFile = File(instrumentationContext.cacheDir, TEST_MODEL_ASSET_NAME)

		try {
			if (!modelFile.exists() || modelFile.length() == 0L) { // Ensure it's not empty either
				Log.d("WhisperContextInstrumentedTest", "Model file does not exist or is empty. Copying from assets.")
				assetManager.open(TEST_MODEL_ASSET_NAME).use { inputStream ->
					FileOutputStream(modelFile).use { outputStream ->
						inputStream.copyTo(outputStream)
					}
				}
				Log.d("WhisperContextInstrumentedTest", "Model copied to ${modelFile.absolutePath}")
			} else {
				Log.d("WhisperContextInstrumentedTest", "Model file already exists: ${modelFile.absolutePath}")
			}
		} catch (e: IOException) {
			Log.e("WhisperContextInstrumentedTest", "Error copying test model asset", e)
			fail("Failed to copy test model asset: ${e.message}")
		}
		return modelFile.absolutePath
	}

	@Test
	fun createContextFromFile_and_Release() { // Return type is Unit (void)
		runBlocking { // runBlocking is called inside
			val modelPath = getTestModelPath()
			assertNotNull("Test model path should not be null", modelPath)
			assertTrue("Test model file should exist", File(modelPath).exists())

			whisperContext = WhisperContext.createContextFromFile(modelPath)
			assertNotNull("WhisperContext should not be null after creation from file", whisperContext)
			whisperContext?.release()
		}
	}

	@Test
	fun createContextFromAsset_and_Release() { // Return type is Unit (void)
		runBlocking { // runBlocking is called inside
			whisperContext = WhisperContext.createContextFromAsset(
				instrumentationContext.assets, // Use instrumentationContext.assets
				TEST_MODEL_ASSET_NAME
			)
			assertNotNull("WhisperContext should not be null after creation from asset", whisperContext)
			whisperContext?.release()
		}
	}

	@Test
	fun transcribeData_withDummyData_returnsSomething() { // Return type is Unit (void)
		runBlocking { // runBlocking is called inside
			whisperContext = WhisperContext.createContextFromAsset(
				instrumentationContext.assets, // Use instrumentationContext.assets
				TEST_MODEL_ASSET_NAME
			)
			assertNotNull("WhisperContext should not be null for transcription test", whisperContext)

			// Using a very short actual audio file is more reliable than pure silence.
			// If you have a jfk.wav or similar short 16kHz mono wav in your androidTest/assets:
			val audioData = readWavAssetToFloatArray(instrumentationContext, TEST_AUDIO_ASSET_NAME)
			// For now, let's stick to dummy data but be aware of its limitations.
//			val dummyAudioData = FloatArray(1600) // 0.1 seconds of silence

			val result = whisperContext?.transcribeData(audioData)
			assertNotNull("Transcription result should not be null", result)
			Log.d("WhisperContextInstrumentedTest", "Transcription result (dummy data): $result")
			// Add more specific assertions if your model produces predictable output for silence.
			// assertTrue(result.isEmpty()) // This might be true for silence with some models

			whisperContext?.release()
		}
	}

	@Test
	fun getSystemInfo_returnsNonEmptyString() { // No coroutine needed here
		val sysInfo = WhisperContext.getSystemInfo()
		assertNotNull("System info string should not be null", sysInfo)
		assertTrue("System info string should not be empty", sysInfo.isNotEmpty())
		Log.d("WhisperContextInstrumentedTest", "System Info: $sysInfo")
	}

	@Test(expected = RuntimeException::class)
	fun createContextWithInvalidPath_throwsException() { // No coroutine needed here
		// This will call the JNI function which should return 0,
		// leading to RuntimeException in WhisperContext.createContextFromFile
		WhisperContext.createContextFromFile("/path/to/nonexistent/model.bin")
		// The test will pass if the expected RuntimeException is thrown
	}

	@Test
	fun release_multipleTimes_doesNotCrash() { // Return type is Unit (void)
		runBlocking { // runBlocking is called inside
			whisperContext = WhisperContext.createContextFromAsset(
				instrumentationContext.assets, // Use instrumentationContext.assets
				TEST_MODEL_ASSET_NAME
			)
			assertNotNull("WhisperContext should not be null for multiple release test", whisperContext)
			whisperContext?.release()
			whisperContext?.release() // Should be safe due to ptr check in WhisperContext.release()
		}
	}

	@After
	fun tearDown() { // Return type is Unit (void)
		runBlocking { // runBlocking is called inside
			whisperContext?.release() // Ensure release in case a test failed before its own release
		}
	}

	// Optional: Helper function if you want to test with real audio
	// (You would need to add a test WAV file like "jfk.wav" to src/androidTest/assets)
	private fun readWavAssetToFloatArray(context: Context, assetName: String): FloatArray {
		// ... (Simplified WAV reader as provided in JniDirectTest) ...
		// Ensure this matches the expected format (16kHz, mono, Float PCM)
		context.assets.open(assetName).use { ais ->
			// Skip WAV header (typically 44 bytes for a simple PCM WAV)
			val headerSize = 44
			ais.skip(headerSize.toLong()) // Simple skip, a real parser is better

			val audioDataBytes = ais.readBytes()
			val numSamples = audioDataBytes.size / 2 // 2 bytes per 16-bit sample
			val floatArray = FloatArray(numSamples)
			val byteBuffer = ByteBuffer.wrap(audioDataBytes).order(ByteOrder.LITTLE_ENDIAN)
			for (i in 0 until numSamples) {
				val sampleShort = byteBuffer.getShort()
				floatArray[i] = sampleShort / 32768.0f
			}
			return floatArray
		}
	}

}
