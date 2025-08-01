package com.redravencomputing.whisper.whispercore
import android.content.res.AssetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.Executors

internal interface IWhisperJNI {
	fun initContext(filePath: String): Long
	fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
	fun initContextFromInputStream(stream: InputStream): Long
	fun freeContext(ptr: Long)
	fun getSystemInfo(): String
	fun fullTranscribe(ptr: Long, numThreads: Int, data: FloatArray)
	fun getTextSegmentCount(ptr: Long): Int
	fun getTextSegment(ptr: Long, index: Int): String
	fun getTextSegmentT0(ptr: Long, index: Int): Long
	fun getTextSegmentT1(ptr: Long, index: Int): Long
	fun benchMemcpy(nthreads: Int): String // Added from your test
	fun benchGgmlMulMat(nthreads: Int): String // Added from your test
}

// Add 'jni: IWhisperJNI' to the constructor
internal class WhisperContext private constructor(
	private var ptr: Long,
	private val jni: IWhisperJNI,
) {
	private val scope: CoroutineScope = CoroutineScope(
		Executors.newSingleThreadExecutor().asCoroutineDispatcher()
	)

	private val numThreadsForTranscription: Int
		get() = WhisperCpuConfig.preferredThreadCount // This remains the same

	suspend fun transcribeData(data: FloatArray, printTimestamp: Boolean = true): String =
		withContext(scope.coroutineContext) {
		require(ptr != 0L) { "Context has been released or was not initialized." }
		// Use the injected 'jni' instance
		jni.fullTranscribe(ptr, numThreadsForTranscription, data) // << CHANGE HERE
		val textCount = jni.getTextSegmentCount(ptr)              // << CHANGE HERE
		buildString {
			for (i in 0 until textCount) {
				if (printTimestamp) {
					val t0 = jni.getTextSegmentT0(ptr, i)       // << CHANGE HERE
					val t1 = jni.getTextSegmentT1(ptr, i)       // << CHANGE HERE
					val text = jni.getTextSegment(ptr, i)       // << CHANGE HERE
					append("[${toTimestamp(t0)} --> ${toTimestamp(t1)}]: $text\n")
				} else {
					append(jni.getTextSegment(ptr, i))          // << CHANGE HERE
				}
			}
		}
	}

	suspend fun release() = withContext(scope.coroutineContext) {
		if (ptr != 0L) {
			jni.freeContext(ptr) // << CHANGE HERE
			ptr = 0L
		}
	}

	suspend fun benchMemory(nthreads: Int): String = withContext(scope.coroutineContext) {
		require(ptr != 0L)
		require(nthreads >= 1) { "Benchmark nthreads must be >= 1" }
		jni.benchMemcpy(nthreads)
	}

	suspend fun benchGgmlMulMat(nthreads: Int): String = withContext(scope.coroutineContext) {
		require(ptr != 0L)
		require(nthreads >= 1) { "Benchmark nthreads must be >= 1" }
		jni.benchGgmlMulMat(nthreads)
	}

	companion object {
		// Helper to get JNI bridge: uses real one normally, allows test one to be passed in
		private fun getJniBridge(testBridge: IWhisperJNI? = null): IWhisperJNI {
			return testBridge ?: WhisperJNIWrapper(WhisperJNIBridge) // Use wrapper with real bridge by default
		}

		// Add 'jniBridgeForTest: IWhisperJNI? = null' to factory methods
		fun createContextFromFile(filePath: String, jniBridgeForTest: IWhisperJNI? = null): WhisperContext {
			val currentJni = getJniBridge(jniBridgeForTest) // << GET JNI INSTANCE
			val ptr = currentJni.initContext(filePath)
			if (ptr == 0L) {
				throw RuntimeException("Couldn't create context with path $filePath")
			}
			return WhisperContext(ptr, currentJni) // << PASS JNI INSTANCE
		}

		fun createContextFromInputStream(stream: InputStream, jniBridgeForTest: IWhisperJNI? = null): WhisperContext {
			val currentJni = getJniBridge(jniBridgeForTest) // << GET JNI INSTANCE
			val ptr = currentJni.initContextFromInputStream(stream)
			if (ptr == 0L) {
				throw RuntimeException("Couldn't create context from input stream")
			}
			return WhisperContext(ptr, currentJni) // << PASS JNI INSTANCE
		}

		fun createContextFromAsset(assetManager: AssetManager, assetPath: String, jniBridgeForTest: IWhisperJNI? = null): WhisperContext {
			val currentJni = getJniBridge(jniBridgeForTest) // << GET JNI INSTANCE
			val ptr = currentJni.initContextFromAsset(assetManager, assetPath)
			if (ptr == 0L) {
				throw RuntimeException("Couldn't create context from asset $assetPath")
			}
			return WhisperContext(ptr, currentJni) // << PASS JNI INSTANCE
		}

		fun getSystemInfo(jniBridgeForTest: IWhisperJNI? = null): String {
			val currentJni = getJniBridge(jniBridgeForTest) // << GET JNI INSTANCE
			return currentJni.getSystemInfo()
		}
	}
}

// toTimestamp function remains the same
private fun toTimestamp(t: Long, comma: Boolean = false): String {
	var msec = t * 10
	val hr = msec / (1000 * 60 * 60)
	msec -= hr * (1000 * 60 * 60)
	val min = msec / (1000 * 60)
	msec -= min * (1000 * 60)
	val sec = msec / 1000
	msec -= sec * 1000
	val delimiter = if (comma) "," else "."
	return String.format(Locale.US,"%02d:%02d:%02d%s%03d", hr, min, sec, delimiter, msec)
}
