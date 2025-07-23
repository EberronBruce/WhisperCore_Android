//package com.whispercpp.whisper
//
//import android.content.res.AssetManager
//import android.os.Build
//import android.util.Log
//import kotlinx.coroutines.*
//import java.io.File
//import java.io.InputStream
//import java.util.concurrent.Executors
//
//private const val LOG_TAG = "LibWhisper"
//
//class WhisperContext private constructor(private var ptr: Long) {
//    // Meet Whisper C++ constraint: Don't access from more than one thread at a time.
//    private val scope: CoroutineScope = CoroutineScope(
//        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
//    )
//
//    suspend fun transcribeData(data: FloatArray, printTimestamp: Boolean = true): String = withContext(scope.coroutineContext) {
//        require(ptr != 0L)
//        val numThreads = WhisperCpuConfig.preferredThreadCount
//        Log.d(LOG_TAG, "Selecting $numThreads threads")
//        WhisperLib.fullTranscribe(ptr, numThreads, data)
//        val textCount = WhisperLib.getTextSegmentCount(ptr)
//        return@withContext buildString {
//            for (i in 0 until textCount) {
//                if (printTimestamp) {
//                    val textTimestamp = "[${toTimestamp(WhisperLib.getTextSegmentT0(ptr, i))} --> ${toTimestamp(WhisperLib.getTextSegmentT1(ptr, i))}]"
//                    val textSegment = WhisperLib.getTextSegment(ptr, i)
//                    append("$textTimestamp: $textSegment\n")
//                } else {
//                    append(WhisperLib.getTextSegment(ptr, i))
//                }
//            }
//        }
//    }
//
//    suspend fun benchMemory(nthreads: Int): String = withContext(scope.coroutineContext) {
//        return@withContext WhisperLib.benchMemcpy(nthreads)
//    }
//
//    suspend fun benchGgmlMulMat(nthreads: Int): String = withContext(scope.coroutineContext) {
//        return@withContext WhisperLib.benchGgmlMulMat(nthreads)
//    }
//
//    suspend fun release() = withContext(scope.coroutineContext) {
//        if (ptr != 0L) {
//            WhisperLib.freeContext(ptr)
//            ptr = 0
//        }
//    }
//
//    protected fun finalize() {
//        runBlocking {
//            release()
//        }
//    }
//
//    companion object {
//        fun createContextFromFile(filePath: String): WhisperContext {
//            val ptr = WhisperLib.initContext(filePath)
//            if (ptr == 0L) {
//                throw java.lang.RuntimeException("Couldn't create context with path $filePath")
//            }
//            return WhisperContext(ptr)
//        }
//
//        fun createContextFromInputStream(stream: InputStream): WhisperContext {
//            val ptr = WhisperLib.initContextFromInputStream(stream)
//
//            if (ptr == 0L) {
//                throw java.lang.RuntimeException("Couldn't create context from input stream")
//            }
//            return WhisperContext(ptr)
//        }
//
//        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
//            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)
//
//            if (ptr == 0L) {
//                throw java.lang.RuntimeException("Couldn't create context from asset $assetPath")
//            }
//            return WhisperContext(ptr)
//        }
//
//        fun getSystemInfo(): String {
//            return WhisperLib.getSystemInfo()
//        }
//    }
//}
//
//private class WhisperLib {
//    companion object {
//        init {
//            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS[0]}")
//            var loadVfpv4 = false
//            var loadV8fp16 = false
//            if (isArmEabiV7a()) {
//                // armeabi-v7a needs runtime detection support
//                val cpuInfo = cpuInfo()
//                cpuInfo?.let {
//                    Log.d(LOG_TAG, "CPU info: $cpuInfo")
//                    if (cpuInfo.contains("vfpv4")) {
//                        Log.d(LOG_TAG, "CPU supports vfpv4")
//                        loadVfpv4 = true
//                    }
//                }
//            } else if (isArmEabiV8a()) {
//                // ARMv8.2a needs runtime detection support
//                val cpuInfo = cpuInfo()
//                cpuInfo?.let {
//                    Log.d(LOG_TAG, "CPU info: $cpuInfo")
//                    if (cpuInfo.contains("fphp")) {
//                        Log.d(LOG_TAG, "CPU supports fp16 arithmetic")
//                        loadV8fp16 = true
//                    }
//                }
//            }
//
//            if (loadVfpv4) {
//                Log.d(LOG_TAG, "Loading libwhisper_vfpv4.so")
//                System.loadLibrary("whisper_vfpv4")
//            } else if (loadV8fp16) {
//                Log.d(LOG_TAG, "Loading libwhisper_v8fp16_va.so")
//                System.loadLibrary("whisper_v8fp16_va")
//            } else {
//                Log.d(LOG_TAG, "Loading libwhisper.so")
//                System.loadLibrary("whisper")
//            }
//        }
//
//        // JNI methods
//        external fun initContextFromInputStream(inputStream: InputStream): Long
//        external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
//        external fun initContext(modelPath: String): Long
//        external fun freeContext(contextPtr: Long)
//        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray)
//        external fun getTextSegmentCount(contextPtr: Long): Int
//        external fun getTextSegment(contextPtr: Long, index: Int): String
//        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
//        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
//        external fun getSystemInfo(): String
//        external fun benchMemcpy(nthread: Int): String
//        external fun benchGgmlMulMat(nthread: Int): String
//    }
//}
//
////  500 -> 00:05.000
//// 6000 -> 01:00.000
//private fun toTimestamp(t: Long, comma: Boolean = false): String {
//    var msec = t * 10
//    val hr = msec / (1000 * 60 * 60)
//    msec -= hr * (1000 * 60 * 60)
//    val min = msec / (1000 * 60)
//    msec -= min * (1000 * 60)
//    val sec = msec / 1000
//    msec -= sec * 1000
//
//    val delimiter = if (comma) "," else "."
//    return String.format("%02d:%02d:%02d%s%03d", hr, min, sec, delimiter, msec)
//}
//
//private fun isArmEabiV7a(): Boolean {
//    return Build.SUPPORTED_ABIS[0].equals("armeabi-v7a")
//}
//
//private fun isArmEabiV8a(): Boolean {
//    return Build.SUPPORTED_ABIS[0].equals("arm64-v8a")
//}
//
//private fun cpuInfo(): String? {
//    return try {
//        File("/proc/cpuinfo").inputStream().bufferedReader().use {
//            it.readText()
//        }
//    } catch (e: Exception) {
//        Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
//        null
//    }
//}

// File: lib/src/main/kotlin/com/whispercpp/whisper/SimpleJniTestInterface.kt
package com.whispercpp.whisper // Matching the JNI function name prefix

import android.content.res.AssetManager
import android.util.Log
import java.io.InputStream

/**
 * A VERY simplified interface for testing core JNI functions directly.
 * This bypasses the more complex LibWhisper.kt from the example for initial JNI validation.
 * The JNI function names in jni.c MUST match this class and package structure.
 * For example, getSystemInfo() here implies Java_com_whispercpp_whisper_SimpleJniTestInterface_00024Companion_getSystemInfo
 *
 * **ADJUST THE CLASS NAME AND PACKAGE IN THIS FILE (and the corresponding JNI function names in C)
 * TO MATCH YOUR ACTUAL JNI C FUNCTION DEFINITIONS IF THEY ARE NOT ALIGNED WITH "SimpleJniTestInterface".**
 *
 * If your C functions are truly named for `com.whispercpp.whisper.WhisperLib.Companion`,
 * then you should name this object `WhisperLib` and keep it in the `com.whispercpp.whisper` package.
 */
object WhisperLib { // RENAMED to WhisperLib to match your C JNI function names

    private const val TAG = "SimpleJNI"
    // Define the library name outside the try-catch so it's accessible in the catch block
    private const val LIBRARY_NAME_TO_LOAD = "whisper-jni" // Use const for clarity if it's fixed


    init {
        try {
            // Now LIBRARY_NAME_TO_LOAD is accessible here
            Log.i(TAG, "Attempting to load native library: '$LIBRARY_NAME_TO_LOAD'")
            System.loadLibrary(LIBRARY_NAME_TO_LOAD)
            Log.i(TAG, "Native library '$LIBRARY_NAME_TO_LOAD' loaded successfully.")

        } catch (e: UnsatisfiedLinkError) {
            // And also accessible here for better error logging
            Log.e(TAG, "Failed to load native library '$LIBRARY_NAME_TO_LOAD'. " +
                    "Ensure your CMakeLists.txt's add_library() target name matches this. " +
                    "Also check for ABI mismatches or if the .so file is missing from the APK.", e)
            throw e // Critical for tests to fail clearly if this doesn't work
        }
    }

    // --- Declare only the JNI functions you are actively testing from jni.c ---
    // Make sure the C function names in jni.c match this package and class:
    // e.g., Java_com_whispercpp_whisper_WhisperLib_00024Companion_getSystemInfo

    external fun getSystemInfo(): String

    external fun initContextFromInputStream(inputStream: InputStream): Long

    // Add this if your jni.c has initContextFromAsset and you want to test it
    // external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long

    external fun freeContext(contextPtr: Long)
    external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long

    // In your com.whispercpp.whisper.WhisperLib object (for testing)
    // ... (existing external funs) ...

    external fun initContext(modelPath: String): Long // For file path loading

    external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray)

    external fun getTextSegmentCount(contextPtr: Long): Int
    external fun getTextSegment(contextPtr: Long, index: Int): String
    external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
    external fun getTextSegmentT1(contextPtr: Long, index: Int): Long

    external fun benchMemcpy(nThreads: Int): String
    external fun benchGgmlMulMat(nThreads: Int): String

}
