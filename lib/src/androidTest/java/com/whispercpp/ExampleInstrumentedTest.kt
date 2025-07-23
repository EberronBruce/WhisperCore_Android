
package com.whispercpp

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispercpp.whisper.WhisperLib
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class JniDirectTest {

	companion object {
		private const val TAG = "JniDirectTest"
		// Name of a dummy model file you'll place in src/androidTest/assets
		// This file doesn't need to be a real model for this JNI test,
		// just some bytes to read via InputStream.
		// For a real model, it would be much larger.
		private const val TEST_MODEL_ASSET_NAME = "ggml-tiny.en.bin"
	}

	private lateinit var instrumentationContext: Context

	@Before
	fun setup() {
		Log.i(TAG, "Setup: Waiting for 30 seconds before starting tests to allow system to settle...")
		Thread.sleep(30000) // 30 seconds delay
		Log.i(TAG, "Setup: Delay complete. Proceeding with setup.")

		instrumentationContext = InstrumentationRegistry.getInstrumentation().targetContext
		try {
			// Ensures WhisperLib.init {} is called
			Class.forName(WhisperLib::class.java.name)
			Log.i(TAG, "WhisperLib class accessed, native library should be loaded.")
		} catch (e: Exception) {
			Log.e(TAG, "Error ensuring WhisperLib is loaded", e)
			fail("Failed to load classes or native library for test: ${e.message}")
		}
	}

	@Test
	fun testNativeGetSystemInfo() {
		Log.i(TAG, "Starting testNativeGetSystemInfo...")
		val sysInfo: String?
		try {
			sysInfo = WhisperLib.getSystemInfo()
			Log.i(TAG, "Native System Info: $sysInfo")
			assertNotNull("System info string from JNI should not be null", sysInfo)
			assertFalse("System info string from JNI should not be empty", sysInfo.isEmpty())
		} catch (e: Throwable) {
			Log.e(TAG, "Exception calling native getSystemInfo: ${e.message}", e)
			if (e is UnsatisfiedLinkError) {
				Log.e(TAG, "UnsatisfiedLinkError for getSystemInfo: Ensure C function name is Java_com_whispercpp_whisper_WhisperLib_getSystemInfo")
			}
			fail("testNativeGetSystemInfo failed: ${e.message}")
		}
		Log.i(TAG, "Finished testNativeGetSystemInfo.")
	}

	@Test
	fun testNativeInitAndFreeContextFromInputStream() {
		Log.i(TAG, "Starting testNativeInitAndFreeContextFromInputStream...")
		var contextPtr = 0L
		var inputStream: InputStream? = null

		try {
			// 1. Prepare an InputStream
			// For testing, copy a small dummy file from assets to cache and get an InputStream
			val assetManager = instrumentationContext.assets
			inputStream = assetManager.open(TEST_MODEL_ASSET_NAME)
			Log.i(TAG, "Opened asset: $TEST_MODEL_ASSET_NAME")

			// Create a temporary file in the app's cache directory to simulate a real file stream if needed
			// Or directly use the asset InputStream if your JNI function is robust enough for it.
			// For simplicity, let's try with the direct asset InputStream first.

			// 2. Call initContextFromInputStream
			Log.i(TAG, "Calling WhisperLib.initContextFromInputStream...")
			contextPtr = WhisperLib.initContextFromInputStream(inputStream)
			Log.i(TAG, "Native context pointer from InputStream: $contextPtr")
			assertTrue("Context pointer should be non-zero after init", contextPtr != 0L)

			// Add more assertions here if your initContext returns specific error codes as Long

		} catch (e: Throwable) {
			Log.e(TAG, "Exception during initContextFromInputStream: ${e.message}", e)
			if (e is UnsatisfiedLinkError) {
				Log.e(TAG, "UnsatisfiedLinkError for initContextFromInputStream: Ensure C function name is Java_com_whispercpp_whisper_WhisperLib_initContextFromInputStream")
			}
			fail("testNativeInitContextFromInputStream failed: ${e.message}")
		} finally {
			// 3. Call freeContext if context was initialized
			if (contextPtr != 0L) {
				Log.i(TAG, "Calling WhisperLib.freeContext with pointer: $contextPtr")
				try {
					WhisperLib.freeContext(contextPtr)
					Log.i(TAG, "Successfully called freeContext.")
					// If freeContext is supposed to invalidate the pointer,
					// you might not be able to check its value afterwards from C,
					// but you can ensure no crash occurs.
				} catch (e: Throwable) {
					Log.e(TAG, "Exception during freeContext: ${e.message}", e)
					if (e is UnsatisfiedLinkError) {
						Log.e(TAG, "UnsatisfiedLinkError for freeContext: Ensure C function name is Java_com_whispercpp_whisper_WhisperLib_freeContext")
					}
					// It's often better to let the test fail here if freeContext itself fails
					fail("freeContext failed: ${e.message}")
				}
			}
			// 4. Close the InputStream
			try {
				inputStream?.close()
				Log.i(TAG, "InputStream closed.")
			} catch (ioe: java.io.IOException) {
				Log.w(TAG, "Warning: Could not close input stream", ioe)
			}
		}
		Log.i(TAG, "Finished testNativeInitAndFreeContextFromInputStream.")
	}

	// Helper to create a dummy file in assets if you haven't already
	// You would manually create a file in: your_module/src/androidTest/assets/dummy_ggml_model.bin
	// For this test, just put a few bytes in it.
	// e.g., using a hex editor or echo "test" > dummy_ggml_model.bin


	@Test
	fun testNativeInitAndFreeContextFromAsset() {
		Log.i(TAG, "Starting testNativeInitAndFreeContextFromAsset...")
		var contextPtr  = 0L
		val modelAssetPath = TEST_MODEL_ASSET_NAME // Using the same real model

		try {
			val assetManager: AssetManager = instrumentationContext.assets
			Log.i(TAG, "Calling WhisperLib.initContextFromAsset with path: $modelAssetPath")
			contextPtr = WhisperLib.initContextFromAsset(assetManager, modelAssetPath)
			Log.i(TAG, "Native context pointer from Asset: $contextPtr")
			assertTrue("Context pointer from asset should be non-zero after init", contextPtr != 0L)

		} catch (e: Throwable) {
			Log.e(TAG, "Exception during initContextFromAsset: ${e.message}", e)
			if (e is UnsatisfiedLinkError) {
				Log.e(TAG, "UnsatisfiedLinkError for initContextFromAsset: Ensure C function name is Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContextFromAsset")
			}
			fail("testNativeInitContextFromAsset failed: ${e.message}")
		} finally {
			if (contextPtr != 0L) {
				Log.i(TAG, "Calling WhisperLib.freeContext with asset-loaded pointer: $contextPtr")
				try {
					WhisperLib.freeContext(contextPtr) // freeContext should work for any valid context
					Log.i(TAG, "Successfully called freeContext for asset-loaded context.")
				} catch (e: Throwable) {
					Log.e(TAG, "Exception during freeContext for asset-loaded context: ${e.message}", e)
					fail("freeContext for asset-loaded context failed: ${e.message}")
				}
			}
		}
		Log.i(TAG, "Finished testNativeInitAndFreeContextFromAsset.")
	}

	@Test
	fun testNativeInitAndFreeContextFromFilePath() {
		Log.i(TAG, "Starting testNativeInitAndFreeContextFromFilePath...")
		var contextPtr = 0L
		val modelAssetName = TEST_MODEL_ASSET_NAME
		val cacheFile = File(instrumentationContext.cacheDir, "test_model.bin")

		try {
			// 1. Copy asset to cache
			instrumentationContext.assets.open(modelAssetName).use { inputStream ->
				cacheFile.outputStream().use { outputStream ->
					inputStream.copyTo(outputStream)
				}
			}
			Log.i(TAG, "Copied $modelAssetName to ${cacheFile.absolutePath}")

			// 2. Call initContext with file path
			Log.i(TAG, "Calling WhisperLib.initContext with path: ${cacheFile.absolutePath}")
			contextPtr = WhisperLib.initContext(cacheFile.absolutePath)
			Log.i(TAG, "Native context pointer from File: $contextPtr")
			assertTrue("Context pointer from file path should be non-zero after init", contextPtr != 0L)

		} catch (e: Throwable) {
			Log.e(TAG, "Exception during initContext (file path): ${e.message}", e)
			fail("testNativeInitContextFromFilePath failed: ${e.message}")
		} finally {
			if (contextPtr != 0L) {
				Log.i(TAG, "Calling WhisperLib.freeContext with file-loaded pointer: $contextPtr")
				WhisperLib.freeContext(contextPtr)
				Log.i(TAG, "Successfully called freeContext for file-loaded context.")
			}
			cacheFile.delete() // Clean up the copied file
			Log.i(TAG, "Deleted cache file: ${cacheFile.absolutePath}")
		}
		Log.i(TAG, "Finished testNativeInitAndFreeContextFromFilePath.")
	}

	@Test
	fun testNativeFullTranscribeAndSegments() {
		Log.i(TAG, "Starting testNativeFullTranscribeAndSegments...")
		var contextPtr = 0L
		val modelAssetPath = TEST_MODEL_ASSET_NAME // Ensure this is a real, working model
		val audioAssetPath = "jfk.wav" // Your short 16kHz mono WAV file
		val numThreads = Runtime.getRuntime().availableProcessors() // Or a reasonable number for your device

		try {
			// 1. Initialize context (using Asset for simplicity)
			val assetManager: AssetManager = instrumentationContext.assets
			contextPtr = WhisperLib.initContextFromAsset(assetManager, modelAssetPath)
			Log.i(TAG, "Native context pointer from Asset for transcription: $contextPtr")
			assertTrue("Context pointer for transcription should be non-zero", contextPtr != 0L)

			// 2. Prepare audio data
			val audioData = readWavAssetToFloatArray(instrumentationContext, audioAssetPath)
			assertNotNull("Audio data should not be null", audioData)
			assertTrue("Audio data should not be empty", audioData.isNotEmpty())
			Log.i(TAG, "Loaded audio data, length: ${audioData.size}")

			// 3. Call fullTranscribe
			Log.i(TAG, "Calling WhisperLib.fullTranscribe...")
			WhisperLib.fullTranscribe(contextPtr, numThreads, audioData)
			// No direct return value to assert, but we expect it not to crash
			// and to populate segments in the native context.
			Log.i(TAG, "WhisperLib.fullTranscribe completed.")

			// 4. Get segment count
			val segmentCount = WhisperLib.getTextSegmentCount(contextPtr)
			Log.i(TAG, "Segment count: $segmentCount")
			assertTrue("Segment count should be >= 0", segmentCount >= 0)

			// 5. Get and log segment details (if any)
			if (segmentCount > 0) {
				for (i in 0 until segmentCount) {
					val segmentText = WhisperLib.getTextSegment(contextPtr, i)
					val t0 = WhisperLib.getTextSegmentT0(contextPtr, i)
					val t1 = WhisperLib.getTextSegmentT1(contextPtr, i)
					Log.i(TAG, "Segment $i: [$t0 ms - $t1 ms] '$segmentText'")
					assertNotNull("Segment text should not be null", segmentText)
					// You could add more specific assertions if you know the expected transcription
					// for your test_audio.wav, but for a general JNI test, non-null is good.
				}
			} else {
				Log.w(TAG, "No segments found. This might be okay if the audio was silent or very short.")
			}

		} catch (e: Throwable) {
			Log.e(TAG, "Exception during fullTranscribe or segment retrieval: ${e.message}", e)
			fail("testNativeFullTranscribeAndSegments failed: ${e.message}")
		} finally {
			if (contextPtr != 0L) {
				Log.i(TAG, "Calling WhisperLib.freeContext for transcription test pointer: $contextPtr")
				WhisperLib.freeContext(contextPtr)
				Log.i(TAG, "Successfully called freeContext for transcription test.")
			}
		}
		Log.i(TAG, "Finished testNativeFullTranscribeAndSegments.")
	}

	// Helper function to read a WAV asset and convert to FloatArray (16kHz mono)
// This is a simplified WAV reader. For robust production use, consider a library.
	private fun readWavAssetToFloatArray(context: Context, assetName: String): FloatArray {
		context.assets.open(assetName).use { ais ->
			// Skip WAV header (typically 44 bytes for a simple PCM WAV)
			// A more robust parser would read header fields to confirm format.
			val headerSize = 44
			val headerBytes = ByteArray(headerSize)
			val bytesRead = ais.read(headerBytes)
			if (bytesRead < headerSize) {
				throw RuntimeException("Could not read WAV header from $assetName")
			}

			// Validate some basic header parts (highly simplified)
			// 'RIFF'
			if (headerBytes[0].toInt().toChar() != 'R' || headerBytes[1].toInt().toChar() != 'I' ||
				headerBytes[2].toInt().toChar() != 'F' || headerBytes[3].toInt().toChar() != 'F') {
				Log.w(TAG, "WAV file $assetName does not start with RIFF")
			}
			// 'WAVE'
			if (headerBytes[8].toInt().toChar() != 'W' || headerBytes[9].toInt().toChar() != 'A' ||
				headerBytes[10].toInt().toChar() != 'V' || headerBytes[11].toInt().toChar() != 'E') {
				Log.w(TAG, "WAV file $assetName does not have WAVE format type")
			}
			// 'fmt '
			if (headerBytes[12].toInt().toChar() != 'f' || headerBytes[13].toInt().toChar() != 'm' ||
				headerBytes[14].toInt().toChar() != 't' || headerBytes[15].toInt().toChar() != ' ') {
				Log.w(TAG, "WAV file $assetName missing fmt chunk")
			}

			// Assuming PCM format (audioFormat = 1 from byte 20-21)
			// Assuming Mono (numChannels = 1 from byte 22-23)
			// Assuming 16000 Hz (sampleRate from byte 24-27)
			// Assuming 16-bit samples (bitsPerSample = 16 from byte 34-35)

			val audioDataBytes = ais.readBytes()
			val numSamples = audioDataBytes.size / 2 // 2 bytes per 16-bit sample
			val floatArray = FloatArray(numSamples)

			val byteBuffer = ByteBuffer.wrap(audioDataBytes).order(ByteOrder.LITTLE_ENDIAN)
			for (i in 0 until numSamples) {
				// Read 16-bit sample, convert to short, then to float in range [-1.0, 1.0]
				val sampleShort = byteBuffer.getShort()
				floatArray[i] = sampleShort / 32768.0f
			}
			return floatArray
		}
	}



}


