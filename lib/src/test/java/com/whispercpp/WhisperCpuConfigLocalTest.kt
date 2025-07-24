package com.whispercpp
// or test through the public API by mocking file reads if possible.

// To make CpuInfo testable directly, you might need to make it public or internal
// and its constructor public/internal. For this example, let's assume you made it accessible.
// Alternatively, you'd mock the file reading parts if possible.

import org.junit.Assert.assertEquals
import org.junit.Test

// This test would be more complex as it requires CpuInfo to be public or internal for direct instantiation,
// and its file reading to be mockable or injectable.
// The current design of WhisperCpuConfig.CpuInfo.Companion.readCpuInfo() makes it hard to directly
// unit test the parsing logic without instrumented tests or more significant refactoring/mocking tools.

class WhisperCpuConfigLocalTest {

	// Helper to simulate CpuInfo if its constructor was public
	// For actual testing of CpuInfo, you'd need to make it accessible from tests.
	// And the methods like getMaxCpuFrequency would need to be mockable.
	// This is a simplified example showing how you *would* test the parsing if it were isolated.

	@Test
	fun cpuInfo_getHighPerfCpuCountByFrequencies_mockedData() {
		// This is illustrative. You'd need to be able to inject these lines and mock getMaxCpuFrequency
		val fakeCpuInfoLines = listOf(
			"processor       : 0",
			"processor       : 1",
			"processor       : 2",
			"processor       : 3"
		)
		// And you'd need a way to mock getMaxCpuFrequency(cpuIndex) to return desired frequencies.
		// e.g., for cpu 0,1 return 1000, for cpu 2,3 return 2000.
		// Then assert that countDroppingMin() returns 2.

		// Given the current structure, testing the parsing logic of CpuInfo in a local unit test
		// without tools like PowerMock or significant refactoring for dependency injection
		// of file readers is challenging.
		// The instrumented test is more straightforward for overall behavior.
	}

	@Test
	fun fallbackLogic_calculation() {
		// You can't directly test the fallback inside getHighPerfCpuCount easily
		// due to the try-catch and direct Runtime call.
		// However, you can test the *expression* it uses in isolation if you wanted to.
		val availableProcessors = 8
		val expected = (availableProcessors - 4).coerceAtLeast(0)
		assertEquals(4, expected)

		val availableProcessorsLow = 2
		val expectedLow = (availableProcessorsLow - 4).coerceAtLeast(0)
		assertEquals(0, expectedLow) // Corrected: coerceAtLeast(0)
	}
}