package com.redravencomputing.whispercore // Matching the JNI function name prefix

import android.content.res.AssetManager
import android.util.Log
import java.io.InputStream

// Wrapper implementation that delegates to the actual WhisperJNIBridge
internal class WhisperJNIWrapper(private val realJni: WhisperJNIBridge) : IWhisperJNI {
    override fun initContext(filePath: String): Long =
        realJni.initContext(filePath)

    override fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long =
        realJni.initContextFromAsset(assetManager, assetPath)

    override fun initContextFromInputStream(stream: InputStream): Long =
        realJni.initContextFromInputStream(stream)

    override fun freeContext(ptr: Long): Unit =
        realJni.freeContext(ptr)

    override fun getSystemInfo(): String =
        realJni.getSystemInfo()

    override fun fullTranscribe(ptr: Long, numThreads: Int, data: FloatArray): Unit =
        realJni.fullTranscribe(ptr, numThreads, data)

    override fun getTextSegmentCount(ptr: Long): Int =
        realJni.getTextSegmentCount(ptr)

    override fun getTextSegment(ptr: Long, index: Int): String =
        realJni.getTextSegment(ptr, index)

    override fun getTextSegmentT0(ptr: Long, index: Int): Long =
        realJni.getTextSegmentT0(ptr, index)

    override fun getTextSegmentT1(ptr: Long, index: Int): Long =
        realJni.getTextSegmentT1(ptr, index)

    override fun benchMemcpy(nthreads: Int): String =
        realJni.benchMemcpy(nthreads)

    override fun benchGgmlMulMat(nthreads: Int): String =
        realJni.benchGgmlMulMat(nthreads)
}


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
internal object WhisperJNIBridge {

    private const val TAG = "SimpleJNI"
    // Define the library name outside the try-catch so it's accessible in the catch block
    private const val LIBRARY_NAME_TO_LOAD = "whisper-jni" // Use const for clarity if it's fixed
    private var isLibraryLoaded = false

    init {
        loadNativeLibrary()
    }


    @Synchronized // Ensure thread-safe loading
    internal fun loadNativeLibrary() {
        if (isLibraryLoaded) {
            // Log.i(TAG, "Native library '$LIBRARY_NAME_TO_LOAD' already loaded.") // Optional: less verbose
            return
        }
        try {
            Log.i(TAG, "Attempting to load native library: '$LIBRARY_NAME_TO_LOAD'")
            System.loadLibrary(LIBRARY_NAME_TO_LOAD)
            isLibraryLoaded = true
            Log.i(TAG, "Native library '$LIBRARY_NAME_TO_LOAD' loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(
                TAG, "Failed to load native library '$LIBRARY_NAME_TO_LOAD'. " +
                        "Ensure your CMakeLists.txt's add_library() target name matches this. " +
                        "Also check for ABI mismatches or if the .so file is missing from the APK.", e
            )
            throw e // Re-throw to make loading failures explicit
        }
    }

    // --- Native Methods ---
    // For these methods to work, loadNativeLibrary() must have been successfully called
    // by the application or instrumentation test.
    external fun getSystemInfo(): String
    external fun initContextFromInputStream(inputStream: InputStream): Long
    external fun freeContext(contextPtr: Long)
    external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
    external fun initContext(modelPath: String): Long // For file path loading
    external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray)
    external fun getTextSegmentCount(contextPtr: Long): Int
    external fun getTextSegment(contextPtr: Long, index: Int): String
    external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
    external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
    external fun benchMemcpy(nThreads: Int): String
    external fun benchGgmlMulMat(nThreads: Int): String

}
