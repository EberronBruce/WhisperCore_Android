package com.redravencomputing

import android.content.Context
import com.redravencomputing.whispercore.WhisperDelegate
import com.redravencomputing.whispercore.WhisperOperationError
import com.redravencomputing.whispercore.WhisperState
import com.redravencomputing.whispercore.media.AudioDecoder
import com.redravencomputing.whispercore.recorder.IRecorder
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import org.mockito.kotlin.eq as mockitoEq


// Use MockitoJUnitRunner if you prefer it over manual MockitoAnnotations.openMocks(this)
// @RunWith(MockitoJUnitRunner::class) // Alternative to MockitoAnnotations.openMocks(this) in setUp
class WhisperStateLocalTest {

	@Mock
	private lateinit var mockContext: Context // If needed for WhisperState initialization (e.g., file paths)

	@Mock
	private lateinit var mockAudioDecoder: AudioDecoder

	private lateinit var mockRecorder: IRecorder // Key mock for these tests

	@Mock
	private lateinit var mockDelegate: WhisperDelegate

	// Test Coroutine Dispatcher
	@OptIn(ExperimentalCoroutinesApi::class)
	private val testDispatcher = StandardTestDispatcher() // You can also use UnconfinedTestDispatcher for simpler tests

	private lateinit var whisperState: WhisperState


	@OptIn(ExperimentalCoroutinesApi::class)
	@Before
	fun setUp() {
		// Initialize mocks created with @Mock annotation
		// If not using @RunWith(MockitoJUnitRunner::class), use this:
		org.mockito.MockitoAnnotations.openMocks(this)

		Dispatchers.setMain(testDispatcher) // Set main dispatcher for tests
		mockRecorder = mockk<IRecorder>()
		whisperState = WhisperState(
			applicationContext = mockContext,
			audioDecoder = mockAudioDecoder,
			recorder = mockRecorder,
			mainDispatcher = testDispatcher,
			defaultDispatcher = testDispatcher
		)
		whisperState.delegate = mockDelegate

		val mockCacheDir = File("build/tmp/test-cache") // Or any valid temp path for tests
		mockCacheDir.mkdirs() // Ensure the directory exists
		whenever(mockContext.cacheDir).thenReturn(mockCacheDir)
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	@After
	fun tearDown() {
		Dispatchers.resetMain() // Reset main dispatcher
	}

	// --- Helper to simulate loading model and granting permission ---
	private fun setReadyToRecordState() {
		whisperState.javaClass.getDeclaredField("isModelLoaded").apply {
			isAccessible = true
			setBoolean(whisperState, true)
		}
		whisperState.javaClass.getDeclaredField("isMicPermissionGranted").apply {
			isAccessible = true
			setBoolean(whisperState, true)
		}
		// If canTranscribe is set based on isModelLoaded, no need to set it separately here
		// unless some other logic influences it before recording.
	}

	private fun setAlreadyRecordingState() {
		whisperState.javaClass.getDeclaredField("isRecording").apply {
			isAccessible = true
			setBoolean(whisperState, true)
		}
	}


	// --- startRecording Tests ---

	@OptIn(ExperimentalCoroutinesApi::class)
	@Test
	fun `startRecording WHEN mic permission not granted EXPECTS recordingFailed and returns`() = runTest(testDispatcher) {
		// Arrange
		// isMicPermissionGranted is false by default or set explicitly
		whisperState.javaClass.getDeclaredField("isMicPermissionGranted").apply {
			isAccessible = true
			setBoolean(whisperState, false)
		}

		// Action
		whisperState.startRecording()
		advanceUntilIdle() // Process any immediate coroutine dispatches

		// Assert
		// This is correct because mockDelegate IS a Mockito mock
		verify(mockDelegate).recordingFailed(mockitoEq(WhisperOperationError.MicPermissionDenied))

		// Use MockK's verification for the MockK mock (mockRecorder)
		coVerify(exactly = 0) {
			mockRecorder.startRecording(any<File>(), any())
		}
		assertFalse(whisperState.isRecording)
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	@Test
	fun `startRecording WHEN already recording EXPECTS returns without action`() = runTest(testDispatcher) {
		// Arrange
		setReadyToRecordState() // Ensure other preconditions are met
		setAlreadyRecordingState()

		// Action
		whisperState.startRecording()
		advanceUntilIdle()

		// Assert
		// Delegate shouldn't be called again for didStartRecording, nor should recorder
		verify(mockDelegate, never()).didStartRecording() // Correct for Mockito mock

		// Correct for MockK mock
		coVerify(exactly = 0) {
			mockRecorder.startRecording(any<File>(), any())
		}
	}


	@OptIn(ExperimentalCoroutinesApi::class)
	@Test
	fun `startRecording WHEN model not loaded EXPECTS returns without action`() = runTest(testDispatcher) {
		// Arrange
		// isModelLoaded is false by default or set explicitly
		whisperState.javaClass.getDeclaredField("isModelLoaded").apply {
			isAccessible = true
			setBoolean(whisperState, false)
		}
		whisperState.javaClass.getDeclaredField("isMicPermissionGranted").apply { // Grant permission
			isAccessible = true
			setBoolean(whisperState, true)
		}


		// Action
		whisperState.startRecording()
		advanceUntilIdle()

		// Assert
		verify(mockDelegate, never()).didStartRecording() // Or a specific delegate call if you have one for this case
		coVerify(exactly = 0) { mockRecorder.startRecording(any(), any()) }
		assertFalse(whisperState.isRecording)
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	@Test
	fun `startRecording WHEN ready and recorder starts successfully EXPECTS didStartRecording and state update`() = runTest(testDispatcher) {
		// Arrange
		setReadyToRecordState()
		val fileSlot = slot<File>() // Use MockK's slot for capturing arguments
		val lambdaSlot = slot<((Exception?) -> Unit)>() // MockK slot for the lambda

		// ---- Use MockK to define behavior for the MockK mock ----
		coEvery {
			mockRecorder.startRecording(capture(fileSlot), capture(lambdaSlot))
		} coAnswers {
			// In a success scenario, the lambda is captured but NOT invoked with an error.
			// If the startRecording itself does something and then calls the lambda on success (with null),
			// you would add: lambdaSlot.captured.invoke(null)
			// However, your comment says "It does NOT imply the error lambda is called with null."
			// So, we just return Unit to signify the suspend function call itself completed.

		}
		// ---- END OF MockK MOCKING ----

		// Action
		whisperState.startRecording()
		advanceUntilIdle() // Allow coroutines to complete

		// Assert
		// In a successful start, recordingFailed should NOT be called.
		verify(mockDelegate, never()).recordingFailed(any()) // Correct for Mockito mock

		// Verify mockRecorder.startRecording was called using MockK
		coVerify(exactly = 1) {
			mockRecorder.startRecording(any<File>(), any<((Exception?) -> Unit)>())
			// You can access captured values if needed:
			// val capturedFile = fileSlot.captured
			// val capturedLambda = lambdaSlot.captured
		}

		// This should now pass if the NPE in WhisperState is fixed and this mocking strategy is correct.
		verify(mockDelegate).didStartRecording() // Correct for Mockito mock

		assertTrue(whisperState.isRecording)
		assertFalse(whisperState.canTranscribe)
	}




	// --- stopRecording Tests ---

	@OptIn(ExperimentalCoroutinesApi::class)
	@Test
	fun `stopRecording WHEN not recording EXPECTS no action`() = runTest(testDispatcher) {
		// Arrange
		// isRecording is false by default. You can also explicitly set it if needed for clarity
		// or if other tests might change the default initial state.
		whisperState.javaClass.getDeclaredField("isRecording").apply {
			isAccessible = true
			setBoolean(whisperState, false)
		}

		// Action
		whisperState.stopRecording()
		advanceUntilIdle() // Ensure any launched coroutines from stopRecording complete

		// Assert
		// For MockK mock (mockRecorder)
		coVerify(exactly = 0) { mockRecorder.stopRecording() }

		// For Mockito mock (mockDelegate)
		verify(mockDelegate, never()).didStopRecording()
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	@Test
	fun `stopRecording WHEN recording and recorder stops successfully EXPECTS didStopRecording and state update`() = runTest(testDispatcher) {
		// Arrange
		setReadyToRecordState() // Model loaded, permission granted
		setAlreadyRecordingState() // isRecording = true
		val dummyRecordedFile = File("test.wav") // Ensure this file actually exists if your code checks for it
		// Create the dummy file if it's checked by the code under test before transcription logic
		dummyRecordedFile.parentFile?.mkdirs()
		dummyRecordedFile.createNewFile()


		whisperState.javaClass.getDeclaredField("recordedFile").apply {
			isAccessible = true
			set(whisperState, dummyRecordedFile)
		}

		coJustRun { mockRecorder.stopRecording() } // MockK: Mock successful stop

		// Action
		whisperState.stopRecording()
		advanceUntilIdle()

		// Assert
		coVerify(exactly = 1) { mockRecorder.stopRecording() } // MockK verification
		verify(mockDelegate).didStopRecording() // Mockito verification (this is correct for mockDelegate)

		assertFalse(whisperState.isRecording)
		// canTranscribe logic depends on isModelLoaded AND recordedFile != null
		// If isModelLoaded is true (from setReadyToRecordState) and recordedFile is set,
		// then canTranscribe should become true.
		assertTrue(whisperState.canTranscribe)


		// Clean up the dummy file
		dummyRecordedFile.delete()
	}


	@OptIn(ExperimentalCoroutinesApi::class)
	@Test
	fun `stopRecording WHEN recorder stop throws exception EXPECTS didStopRecording and handles error`() = runTest(testDispatcher) {
		// Arrange
		setReadyToRecordState()
		setAlreadyRecordingState()
		val testException = RuntimeException("Recorder stop failed")
		val dummyRecordedFile = File("test-error.wav")
		dummyRecordedFile.parentFile?.mkdirs()
		dummyRecordedFile.createNewFile()

		whisperState.javaClass.getDeclaredField("recordedFile").apply {
			isAccessible = true
			set(whisperState, dummyRecordedFile)
		}

		coEvery { mockRecorder.stopRecording() } throws testException

		// Action
		whisperState.stopRecording()
		advanceUntilIdle()

		// Assert
		coVerify(exactly = 1) { mockRecorder.stopRecording() }
		verify(mockDelegate).didStopRecording()
		assertFalse(whisperState.isRecording)
		assertTrue(whisperState.canTranscribe)

		// Correct way to check for substring in StringBuilder's content
		val logContent = whisperState.messageLog.toString()
		val expectedLogMessage = "Error stopping AudioRecord-based recorder: ${testException.message}"
		assertTrue(
			"Log should contain '$expectedLogMessage'. Actual log: '$logContent'",
			logContent.contains(expectedLogMessage)
		)

		dummyRecordedFile.delete()
	}


	@OptIn(ExperimentalCoroutinesApi::class)
	@Test
	fun `startRecording - SUCCESS - delegate is notified and state updates`() = runTest(testDispatcher) {
		// --- 1. ARRANGE ---
		setReadyToRecordState()
		Assert.assertNotNull("Pre-condition: delegate should be set", whisperState.delegate)
		Assert.assertSame("Pre-condition: delegate is mockDelegate", mockDelegate, whisperState.delegate)
		assertFalse("Pre-condition: isRecording should be false", whisperState.isRecording)

		// Mock successful recorder start
		coEvery { mockRecorder.startRecording(any<File>(), any()) } returns Unit

		// --- 2. ACTION ---
		whisperState.startRecording()
		this.testScheduler.advanceUntilIdle()

		// --- 3. ASSERT ---

		// Debug: Check the value of recordedFile via reflection immediately after action
		val internalRecordedFileField = whisperState.javaClass.getDeclaredField("recordedFile").apply { isAccessible = true }
		val actualRecordedFile = internalRecordedFileField.get(whisperState) as? File
		println("DEBUG: Test - actualRecordedFile after startRecording and advanceUntilIdle: ${actualRecordedFile?.absolutePath}")

		// Verify mockRecorder.startRecording was called with ANY file.
		// If this fails, it means we didn't even get to that point.
		val fileCaptor = slot<File>()
		try {
			coVerify(exactly = 1) {
				mockRecorder.startRecording(capture(fileCaptor), any())
			}
			println("DEBUG: Test - mockRecorder.startRecording was called with file: ${fileCaptor.captured.absolutePath}")
		} catch (e: Exception) {
			fail("mockRecorder.startRecording(...) was NOT called. Error: ${e.message}. This means the coroutine likely failed before this point or conditions were not met.")
		}

		// Now, the primary assertions
		try {
			org.mockito.Mockito.verify(mockDelegate).didStartRecording()
		} catch (e: org.mockito.exceptions.verification.WantedButNotInvoked) {
			val isRecordingField = whisperState.javaClass.getDeclaredField("isRecording").apply { isAccessible = true }
			System.err.println("DEBUG: Test FAILED. whisperState.delegate after action = ${whisperState.delegate}")
			System.err.println("DEBUG: Is it still mockDelegate? ${whisperState.delegate === mockDelegate}")
			System.err.println("DEBUG: whisperState.recordedFile value via reflection = $actualRecordedFile")
			System.err.println("DEBUG: whisperState.isRecording value via reflection = ${isRecordingField.get(whisperState)}")
			fail("Assertion FAILED: mockDelegate.didStartRecording() was NOT called. Error: ${e.message}")
		}

		assertTrue("isRecording should be true after successful start", whisperState.isRecording)
		assertFalse("canTranscribe should be false while recording", whisperState.canTranscribe)

		org.mockito.Mockito.verify(mockDelegate, org.mockito.Mockito.never())
			.recordingFailed(any())
	}


	@Test
	fun `startRecording WHEN recorder error callback invoked EXPECTS correct state changes`() = runTest(testDispatcher) {
		// --- 1. ARRANGE ---
		val recorderErrorException = RuntimeException("Simulated Recorder Error")
		val errorCallbackSlot = slot<((Exception?) -> Unit)>()

		coEvery {
			mockRecorder.startRecording(
				any<File>(),
				capture(errorCallbackSlot)
			)
		} coAnswers {
			println("TEST ARRANGE: mockRecorder.startRecording is called by WhisperState. Simulating error callback.")
			errorCallbackSlot.captured.invoke(recorderErrorException)
		}

		// --- Initial state check ---
		// Ensure isRecording is false and recordedFile is null BEFORE the action
		// This is important because startRecording might set them temporarily even if it fails.
		// The check here is for the state *before* the call that triggers the error handling.
		// If startRecording itself sets these *before* recorder.startRecording is called and fails,
		// then the "pre-condition" check would be for a different state.
		// For this test, we assume they are null/false initially if no recording is active.
		val preRecordedFileField = whisperState.javaClass.getDeclaredField("recordedFile").apply { isAccessible = true }
		preRecordedFileField.set(whisperState, null) // Explicitly nullify for clean pre-condition
		val preIsRecordingField = whisperState.javaClass.getDeclaredField("isRecording").apply { isAccessible = true }
		preIsRecordingField.set(whisperState, false) // Explicitly set for clean pre-condition

		println("TEST PRE-ACTION: isRecording=${whisperState.isRecording}, recordedFile=${preRecordedFileField.get(whisperState)}")

		// --- 2. ACTION ---
		println("TEST ACTION: Calling whisperState.startRecording()...")
		whisperState.startRecording()

		testScheduler.advanceUntilIdle() // Ensure all coroutines complete
		println("TEST ACTION: Coroutines advanced, error callback should have executed.")

		// --- 3. ASSERT ---
		println("TEST ASSERT: Verifying state changes...")

		// Assert that isRecording is now false
		assertFalse(
			"isRecording should be false after recorder error",
			whisperState.isRecording
		)
		println("TEST ASSERT: isRecording check passed (is false).")

		// Assert that recordedFile is now null
		val recordedFileField = whisperState.javaClass.getDeclaredField("recordedFile").apply { isAccessible = true }
		assertNull(
			"recordedFile should be null after recorder error",
			recordedFileField.get(whisperState)
		)
		println("TEST ASSERT: recordedFile check passed (is null).")

		println("TEST: State assertions passed.")
	}

}

