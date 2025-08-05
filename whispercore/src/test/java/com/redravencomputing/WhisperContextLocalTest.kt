package com.redravencomputing

import com.redravencomputing.whispercore.IWhisperJNI // << IMPORT THE INTERFACE
import com.redravencomputing.whispercore.WhisperContext
import com.redravencomputing.whispercore.WhisperCpuConfig // Keep this for now if WhisperContext uses it directly
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk // << For creating interface mocks
import io.mockk.mockkObject // For WhisperCpuConfig if still needed
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WhisperContextLocalTest {

	private val mockModelPath = "/path/to/mock/model.bin"
	private val defaultMockContextPtr = 12345L
	private val specificTestContextPtr = 67890L

	// Declare the mock for the interface
	private lateinit var mockJni: IWhisperJNI

	@Before
	fun setUp() {
		// Create a mock implementation of the IWhisperJNI interface
		mockJni = mockk<IWhisperJNI>()

		// Mock WhisperCpuConfig if WhisperContext still accesses it directly
		// If WhisperContext gets numThreads from the IWhisperJNI mock, this might not be needed.
		// For now, let's assume WhisperContext.numThreadsForTranscription still uses WhisperCpuConfig.
		mockkObject(WhisperCpuConfig)
		every { WhisperCpuConfig.preferredThreadCount } returns 4

		// --- Mocking IWhisperJNI methods ---
		// Now, 'every' rules are defined on the mockJni instance.
		// This will NOT attempt to call any native methods.
		every { mockJni.initContext(any<String>()) } returns defaultMockContextPtr
		every { mockJni.initContextFromAsset(any(), any<String>()) } returns defaultMockContextPtr
		every { mockJni.initContextFromInputStream(any()) } returns defaultMockContextPtr
		every { mockJni.freeContext(any<Long>()) } just Runs
		every { mockJni.getSystemInfo() } returns "Mocked System Info"
		every { mockJni.fullTranscribe(any<Long>(), any<Int>(), any<FloatArray>()) } just Runs
		every { mockJni.getTextSegmentCount(any<Long>()) } returns 0 // Default
		every { mockJni.getTextSegment(any<Long>(), any<Int>()) } returns "" // Default
		every { mockJni.getTextSegmentT0(any<Long>(), any<Int>()) } returns 0L // Default
		every { mockJni.getTextSegmentT1(any<Long>(), any<Int>()) } returns 0L // Default
		every { mockJni.benchMemcpy(any<Int>()) } returns "Mocked benchMemcpy"
		every { mockJni.benchGgmlMulMat(any<Int>()) } returns "Mocked benchGgmlMulMat"

		// Specific mocks for defaultMockContextPtr using the mockJni instance
		every { mockJni.getTextSegmentCount(defaultMockContextPtr) } returns 2
		every { mockJni.getTextSegment(defaultMockContextPtr, 0) } returns "Hello"
		every { mockJni.getTextSegment(defaultMockContextPtr, 1) } returns "world"
		every { mockJni.getTextSegmentT0(defaultMockContextPtr, 0) } returns 0L
		every { mockJni.getTextSegmentT1(defaultMockContextPtr, 0) } returns 100L
		every { mockJni.getTextSegmentT0(defaultMockContextPtr, 1) } returns 100L
		every { mockJni.getTextSegmentT1(defaultMockContextPtr, 1) } returns 200L
	}

	@Test
	fun `createContextFromFile success should return WhisperContext instance`() {
		// Override general mock if a more specific one for this path is needed in this test
		every { mockJni.initContext(mockModelPath) } returns defaultMockContextPtr

		// Pass the mockJni instance to the factory method
		val context = WhisperContext.createContextFromFile(mockModelPath, jniBridgeForTest = mockJni)
		assertNotNull(context)
		verify { mockJni.initContext(mockModelPath) }
	}

	@Test
	fun `createContextFromFile failure should throw RuntimeException`() {
		every { mockJni.initContext(mockModelPath) } returns 0L // Simulate JNI failure

		val exception = assertThrows(RuntimeException::class.java) {
			// Pass the mockJni instance
			WhisperContext.createContextFromFile(mockModelPath, jniBridgeForTest = mockJni)
		}
		assertTrue(exception.message?.contains("Couldn't create context with path $mockModelPath") == true)
		verify { mockJni.initContext(mockModelPath) }
	}

	@Test
	fun `release should call JNIBridge freeContext`() = runTest {
		every { mockJni.initContext(mockModelPath) } returns specificTestContextPtr
		// Pass the mockJni instance
		val whisperContext = WhisperContext.createContextFromFile(mockModelPath, jniBridgeForTest = mockJni)

		whisperContext.release()

		verify { mockJni.freeContext(specificTestContextPtr) }
	}

	@Test
	fun `getSystemInfo should call JNIBridge`() {
		// Pass the mockJni instance
		val info = WhisperContext.getSystemInfo(jniBridgeForTest = mockJni)
		assertEquals("Mocked System Info", info)
		verify { mockJni.getSystemInfo() }
	}

	@Test
	fun `transcribeData should call JNIBridge and process results`() = runTest {
		every { mockJni.initContext(mockModelPath) } returns specificTestContextPtr
		val expectedThreadCount = 4 // From WhisperCpuConfig mock
		// every { WhisperCpuConfig.preferredThreadCount } returns expectedThreadCount // Already in setUp

		// Pass the mockJni instance
		val whisperContext = WhisperContext.createContextFromFile(mockModelPath, jniBridgeForTest = mockJni)
		val audioData = FloatArray(16000)

		every { mockJni.fullTranscribe(specificTestContextPtr, expectedThreadCount, audioData) } just Runs
		every { mockJni.getTextSegmentCount(specificTestContextPtr) } returns 1
		every { mockJni.getTextSegment(specificTestContextPtr, 0) } returns "Test transcription."
		every { mockJni.getTextSegmentT0(specificTestContextPtr, 0) } returns 0L
		every { mockJni.getTextSegmentT1(specificTestContextPtr, 0) } returns 1000L // 100 * 10 ms for toTimestamp

		val result = whisperContext.transcribeData(audioData)

		assertNotNull(result)
		// Adjust expected timestamp based on your toTimestamp logic (t * 10)
		// 0L -> 00:00:00.000
		// 1000L (for t1, means 100 * 10ms in your toTimestamp if t is 100) -> 00:00:01.000 (if t=100)
		// If getTextSegmentT1 returns 1000L directly as time in 10ms units, then toTimestamp(1000L) is toTimestamp(10 seconds)
		// Let's assume getTextSegmentT1 returns time in 10ms units as per your example, so 1000L means 10 seconds.
		// toTimestamp(1000) -> 00:00:10.000
		// If getTextSegmentT1 returns 100L (meaning 1 second), then toTimestamp(100L) -> 00:00:01.000
		// Your current mock has T1=1000L.
		// toTimestamp(0L) -> "00:00:00.000"
		// toTimestamp(1000L) -> "00:00:10.000"
		assertTrue(
			"Transcription result did not match expected format. Actual: $result",
			result.contains("[00:00:00.000 --> 00:00:10.000]: Test transcription.") // Adjusted based on T1=1000L
		)

		verify { mockJni.fullTranscribe(specificTestContextPtr, expectedThreadCount, audioData) }
		verify { mockJni.getTextSegmentCount(specificTestContextPtr) }
		verify { mockJni.getTextSegment(specificTestContextPtr, 0) }
		verify { mockJni.getTextSegmentT0(specificTestContextPtr, 0) }
		verify { mockJni.getTextSegmentT1(specificTestContextPtr, 0) }
	}

	@Test
	fun `transcribeData should handle multiple segments correctly`() = runTest {
		every { mockJni.initContext(mockModelPath) } returns specificTestContextPtr
		val expectedThreadCount = 4
		// every { WhisperCpuConfig.preferredThreadCount } returns expectedThreadCount // In setUp

		// Pass the mockJni instance
		val whisperContext = WhisperContext.createContextFromFile(mockModelPath, jniBridgeForTest = mockJni)
		val audioData = FloatArray(32000)

		every { mockJni.fullTranscribe(specificTestContextPtr, expectedThreadCount, audioData) } just Runs
		every { mockJni.getTextSegmentCount(specificTestContextPtr) } returns 2
		every { mockJni.getTextSegment(specificTestContextPtr, 0) } returns "First part."
		every { mockJni.getTextSegmentT0(specificTestContextPtr, 0) } returns 0L      // 0s
		every { mockJni.getTextSegmentT1(specificTestContextPtr, 0) } returns 50L     // 0.5s (50 * 10ms)
		every { mockJni.getTextSegment(specificTestContextPtr, 1) } returns "Second part."
		every { mockJni.getTextSegmentT0(specificTestContextPtr, 1) } returns 50L     // 0.5s
		every { mockJni.getTextSegmentT1(specificTestContextPtr, 1) } returns 120L    // 1.2s (120 * 10ms)

		val result = whisperContext.transcribeData(audioData)

		assertNotNull(result)
		// Adjusted timestamps based on toTimestamp logic
		assertTrue(result.contains("[00:00:00.000 --> 00:00:00.500]: First part."))
		assertTrue(result.contains("[00:00:00.500 --> 00:00:01.200]: Second part."))

		verify { mockJni.fullTranscribe(specificTestContextPtr, expectedThreadCount, audioData) }
		verify { mockJni.getTextSegmentCount(specificTestContextPtr) }
		verify { mockJni.getTextSegment(specificTestContextPtr, 0) } // And T0, T1 for segment 0
		verify { mockJni.getTextSegment(specificTestContextPtr, 1) } // And T0, T1 for segment 1
	}
}
