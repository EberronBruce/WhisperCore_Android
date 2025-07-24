package com.whispercpp

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whispercpp.whisper.WhisperCpuConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhisperCpuConfigInstrumentedTest {
	@Test
	fun preferredThreadCount_returnsAtLeastTwo() {
		val count = WhisperCpuConfig.preferredThreadCount
		assertTrue("Preferred thread count should be >= 2, but was $count", count >= 2)
		// You could add more specific assertions if you know the characteristics
		// of your test emulator/device, e.g., checking against Runtime.getRuntime().availableProcessors()
		// but remember this can be flaky across different environments.
		Log.d("WhisperCpuConfigTest", "Preferred thread count: $count")
		Log.d("WhisperCpuConfigTest", "Available processors: ${Runtime.getRuntime().availableProcessors()}")
	}
}