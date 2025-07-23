#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <sys/sysinfo.h>
#include <string.h>
#include <time.h>
#include "whisper.h"
#include "ggml.h"

#define UNUSED(x) (void)(x)
#define TAG "WhisperJNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,     TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,     TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,     TAG, __VA_ARGS__)

// Global JavaVM pointer
static JavaVM*   g_vm = NULL;

// Global cache for InputStream class and method IDs
static jclass    g_inputStreamClass = NULL;
static jmethodID g_midInputStreamAvailable = NULL;
static jmethodID g_midInputStreamRead = NULL;

static inline int min(int a, int b) {
    return (a < b) ? a : b;
}

static inline int max(int a, int b) {
    return (a > b) ? a : b;
}

struct input_stream_context {
    size_t offset;
    JNIEnv * env;
    //jobject thiz;
    jobject input_stream;

    jmethodID mid_available;
    jmethodID mid_read;
};

size_t inputStreamRead(void * ctx, void * output, size_t read_size) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;
    JNIEnv *env = is->env; // Get JNIEnv from your context struct

    if (read_size == 0) {
        return 0;
    }

    // We will attempt to read up to 'read_size' bytes.
    // The Java InputStream.read() will tell us how many were actually read.
    jbyteArray java_byte_array = (*env)->NewByteArray(env, (jint)read_size); // Try to read up to read_size
    if (java_byte_array == NULL) {
        LOGW("%s", "inputStreamRead: Failed to allocate NewByteArray for reading");
        return 0; // Indicate error or no bytes read
    }

    // Call InputStream.read(byte[] b, int off, int len)
    // We pass the full read_size as the length we want to read.
    jint actual_bytes_read = (*env)->CallIntMethod(env, is->input_stream, is->mid_read, java_byte_array, 0, (jint)read_size);

    // Check for Java exceptions after the call
    if ((*env)->ExceptionCheck(env)) {
        LOGW("%s", "inputStreamRead: Exception occurred during Java InputStream.read()");
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, java_byte_array);
        return 0; // Indicate error
    }

    size_t result_bytes = 0;
    if (actual_bytes_read > 0) {
        // If bytes were read, copy them to the output buffer
        // Instead of GetByteArrayElements & ReleaseByteArrayElements,
        // we use GetByteArrayRegion for a simple copy.
        (*env)->GetByteArrayRegion(env, java_byte_array, 0, actual_bytes_read, (jbyte*)output);
        if ((*env)->ExceptionCheck(env)) { // Check for exception after GetByteArrayRegion
            LOGW("%s", "inputStreamRead: Exception during GetByteArrayRegion");
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
            // Even if GetByteArrayRegion fails, we might have advanced the stream.
            // Returning 0 signals an error in providing the data to the caller.
            result_bytes = 0;
        } else {
            result_bytes = (size_t)actual_bytes_read;
        }
    } else if (actual_bytes_read == -1) {
        // EOF was reached by the Java InputStream.read() method.
        LOGI("%s", "inputStreamRead: EOF reached by Java InputStream.read()");
        result_bytes = 0; // Standard way to indicate EOF for this type of read callback
    } else {
        LOGI("%s", "inputStreamRead: Java InputStream.read() returned 0 bytes, but not EOF.");
        result_bytes = 0;
    }

    (*env)->DeleteLocalRef(env, java_byte_array);
    is->offset += result_bytes; // Update offset with actual bytes successfully processed
    return result_bytes;        // Return the number of bytes actually read and copied
}


bool inputStreamEof(void * ctx) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint result = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    return result <= 0;
}

void inputStreamClose(void * ctx) {
    LOGI("JNI: inputStreamClose called for context %p", ctx);
    struct input_stream_context* is = (struct input_stream_context*)ctx;
    if (is == NULL) {
        LOGW("JNI: inputStreamClose called with NULL context");
        return;
    }

    JNIEnv *env = NULL;
    bool attached_here = false; // Flag to track if we attached the thread

    if (g_vm == NULL) {
        LOGE("JNI: inputStreamClose - g_vm is NULL. Cannot get JNIEnv.");
        // Cannot safely DeleteGlobalRef without an env.
        // Free the struct memory to prevent its leak, but the JNI ref will leak.
        if (is->input_stream != NULL) {
            LOGW("JNI: inputStreamClose - Leaking GlobalRef for input_stream %p because g_vm is NULL.", is->input_stream);
        }
        free(is);
        return;
    }

    // Try to get the JNIEnv for the current thread
    int getEnvStat = (*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        LOGI("JNI: inputStreamClose - Thread not attached, attempting to attach.");
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) {
            LOGE("JNI: inputStreamClose - Failed to attach current thread to JVM.");
            // Cannot safely DeleteGlobalRef. Free struct memory.
            if (is->input_stream != NULL) {
                LOGW("JNI: inputStreamClose - Leaking GlobalRef for input_stream %p due to attach failure.", is->input_stream);
            }
            free(is);
            return;
        }
        attached_here = true; // Mark that we attached it here
    } else if (getEnvStat == JNI_EVERSION) {
        LOGE("JNI: inputStreamClose - GetEnv: JNI version not supported.");
        // Cannot safely DeleteGlobalRef. Free struct memory.
        if (is->input_stream != NULL) {
            LOGW("JNI: inputStreamClose - Leaking GlobalRef for input_stream %p due to JNI version error.", is->input_stream);
        }
        free(is);
        return;
    } else if (getEnvStat == JNI_OK) {
        LOGI("JNI: inputStreamClose - Successfully got JNIEnv for current thread.");
    }
    // 'env' should now be valid if we reached here without returning.

    if (is->input_stream != NULL) {
        LOGI("JNI: Deleting global ref %p for input_stream in inputStreamClose (context %p)", is->input_stream, ctx);
        (*env)->DeleteGlobalRef(env, is->input_stream);
        is->input_stream = NULL; // Good practice
    } else {
        LOGW("JNI: inputStreamClose - input_stream in context %p was already NULL", ctx);
    }

    LOGI("JNI: Freeing input_stream_context %p in inputStreamClose", ctx);
    free(is); // Free the struct itself

    if (attached_here) {
        LOGI("JNI: inputStreamClose - Detaching current thread from JVM.");
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}


JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    UNUSED(reserved);
    LOGI("JNI_OnLoad called");

    // Store the JavaVM pointer for later use
    g_vm = vm;

    JNIEnv* env;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        LOGW("JNI_OnLoad: Failed to get JNIEnv");
        return JNI_ERR; // Initialization failed
    }

    // --- Initialize InputStream Class and Method IDs ---
    jclass localInputStreamClassRef = (*env)->FindClass(env, "java/io/InputStream");
    if (localInputStreamClassRef == NULL) {
        LOGW("JNI_OnLoad: Failed to find java/io/InputStream class.");
        // No need to check for pending exceptions here, FindClass sets one if it fails.
        return JNI_ERR;
    }

    // Create a global reference for the class. Global refs survive DetachCurrentThread.
    g_inputStreamClass = (*env)->NewGlobalRef(env, localInputStreamClassRef);
    // We are done with the local reference, release it.
    (*env)->DeleteLocalRef(env, localInputStreamClassRef);

    if (g_inputStreamClass == NULL) {
        LOGW("JNI_OnLoad: Failed to create global ref for InputStream class.");
        return JNI_ERR;
    }

    g_midInputStreamAvailable = (*env)->GetMethodID(env, g_inputStreamClass, "available", "()I");
    if (g_midInputStreamAvailable == NULL) {
        LOGW("JNI_OnLoad: Failed to get method ID for InputStream.available()");
        // Cleanup what we've created so far if this step fails
        (*env)->DeleteGlobalRef(env, g_inputStreamClass);
        g_inputStreamClass = NULL;
        return JNI_ERR;
    }

    g_midInputStreamRead = (*env)->GetMethodID(env, g_inputStreamClass, "read", "([BII)I");
    if (g_midInputStreamRead == NULL) {
        LOGW("JNI_OnLoad: Failed to get method ID for InputStream.read([BII)I");
        // Cleanup what we've created so far if this step fails
        (*env)->DeleteGlobalRef(env, g_inputStreamClass);
        g_inputStreamClass = NULL;
        g_midInputStreamAvailable = NULL; // Reset this too
        return JNI_ERR;
    }

    LOGI("JNI_OnLoad: InputStream class and method IDs cached successfully.");
    return JNI_VERSION_1_6; // Indicate success and the JNI version we support
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    UNUSED(vm); // vm is the same as g_vm, but good practice to have it as a param
    UNUSED(reserved);
    LOGI("JNI_OnUnload called");

    JNIEnv* env;
    if ((*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        LOGW("JNI_OnUnload: Failed to get JNIEnv");
        return; // Can't do much if we can't get an env
    }

    // Delete the global reference to the InputStream class
    if (g_inputStreamClass != NULL) {
        (*env)->DeleteGlobalRef(env, g_inputStreamClass);
        g_inputStreamClass = NULL;
    }

    // Method IDs don't need explicit cleanup, but good to reset them
    g_midInputStreamAvailable = NULL;
    g_midInputStreamRead = NULL;

    g_vm = NULL; // Clear the global VM pointer

    LOGI("JNI_OnUnload: Global resources released.");
}


JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_initContextFromInputStream(
        JNIEnv *env, jobject thiz, jobject input_stream_param) { // Renamed param for clarity
    UNUSED(thiz);

    if (g_inputStreamClass == NULL || g_midInputStreamAvailable == NULL || g_midInputStreamRead == NULL) {
        LOGW("JNI Error: Global InputStream class/method IDs not initialized in initContextFromInputStream. ""This usually means JNI_OnLoad() failed or was not called correctly.");
        return (jlong)NULL; // Cannot proceed
    }

    // Allocate on heap ---
    struct input_stream_context *inp_ctx_ptr =
            (struct input_stream_context*) malloc(sizeof(struct input_stream_context));

    if (inp_ctx_ptr == NULL) {
        LOGW("Failed to allocate memory for input_stream_context");
        return (jlong)NULL;
    }
    memset(inp_ctx_ptr, 0, sizeof(struct input_stream_context)); // Zero out memory

    // Now use inp_ctx_ptr instead of inp_ctx
    inp_ctx_ptr->offset = 0;
    inp_ctx_ptr->env = env; // Store env

    //Create Global Reference for input_stream ---
    inp_ctx_ptr->input_stream = (*env)->NewGlobalRef(env, input_stream_param);
    if (inp_ctx_ptr->input_stream == NULL) {
        LOGW("Failed to create global ref for input_stream_param");
        free(inp_ctx_ptr); // Clean up allocated memory
        return (jlong)NULL;
    }

    // Now, assign the cached (or newly initialized) IDs to the context structure
    inp_ctx_ptr->mid_available = g_midInputStreamAvailable;
    inp_ctx_ptr->mid_read = g_midInputStreamRead;
    LOGI("JNI: Assigned cached method IDs to input_stream_context for instance %p.", inp_ctx_ptr);

    struct whisper_context *context = NULL;
    struct whisper_model_loader loader = {};
    //Pass pointer to heap struct to loader ---
    loader.context = inp_ctx_ptr;

    loader.read = inputStreamRead;
    loader.eof = inputStreamEof;
    loader.close = inputStreamClose; // This will be responsible for freeing inp_ctx_ptr

    // This call should use the pointer: loader.context (which is inp_ctx_ptr)
    //loader.eof(loader.context);
    if (loader.eof != NULL) { // Safety check before calling
        LOGI("JNI: Calling loader.eof() before whisper_init_with_params for instance %p.", loader.context);
        loader.eof(loader.context);
    }  else { // This means loader.context is NULL, which is also bad
        LOGW("JNI WARNING: loader.eof function pointer was NULL. Cannot call eof. Instance %p", loader.context);
    }

    struct whisper_context_params params = whisper_context_default_params();
    context = whisper_init_with_params(&loader, params);
    LOGI("JNI: [REGULAR MODE] whisper_init_with_params returned context: %p.", context); // Add log

    if (context == NULL) {
        LOGW("JNI: [REGULAR MODE] whisper_init_with_params failed for input stream using loader context %p.", inp_ctx_ptr);
        // DO NOT free inp_ctx_ptr or DeleteGlobalRef here.
        // Assume whisper_init_with_params, if it used the loader,
        // should have called loader.close (inputStreamClose) to clean up inp_ctx_ptr.
        // If it didn't, inp_ctx_ptr is leaked by whisper.cpp, but freeing it here
        // risks a double-free if it *was* called.
        // The most important thing is that inp_ctx_ptr itself should not be returned
        // as if it were a valid whisper_context.
        LOGW("JNI: [REGULAR MODE] Assuming loader.close handled cleanup for inp_ctx_ptr %p if init failed.", inp_ctx_ptr);
        return (jlong)NULL; // Return NULL to indicate failure to Kotlin
    }
    // If successful, whisper_context "owns" inp_ctx_ptr via the loader.
    // It should be freed by inputStreamClose when whisper is done with it.
    return (jlong) context;
}


static size_t asset_read(void *ctx, void *output, size_t read_size) {
    return AAsset_read((AAsset *) ctx, output, read_size);
}

static bool asset_is_eof(void *ctx) {
    return AAsset_getRemainingLength64((AAsset *) ctx) <= 0;
}

static void asset_close(void *ctx) {
    AAsset_close((AAsset *) ctx);
}

static struct whisper_context *whisper_init_from_asset(
        JNIEnv *env,
        jobject assetManager,
        const char *asset_path
) {
    LOGI("Loading model from asset '%s'\n", asset_path);
    AAssetManager *asset_manager = AAssetManager_fromJava(env, assetManager);
    AAsset *asset = AAssetManager_open(asset_manager, asset_path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGW("Failed to open '%s'\n", asset_path);
        return NULL;
    }

    whisper_model_loader loader = {
            .context = asset,
            .read = &asset_read,
            .eof = &asset_is_eof,
            .close = &asset_close
    };

    return whisper_init_with_params(&loader, whisper_context_default_params());
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_initContextFromAsset(
        JNIEnv *env, jobject thiz, jobject assetManager, jstring asset_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *asset_path_chars = (*env)->GetStringUTFChars(env, asset_path_str, NULL);
    context = whisper_init_from_asset(env, assetManager, asset_path_chars);
    (*env)->ReleaseStringUTFChars(env, asset_path_str, asset_path_chars);
    return (jlong) context;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    context = whisper_init_from_file_with_params(model_path_chars, whisper_context_default_params());
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr_long) {
    UNUSED(env);
    UNUSED(thiz);

    if (context_ptr_long == 0L) {
        LOGW("JNI: freeContext called with NULL pointer, doing nothing.");
        return;
    }

    // This is your original code for when you're NOT testing JNI in isolation
    struct whisper_context *context = (struct whisper_context *) context_ptr_long;
    LOGI("JNI: [REGULAR MODE] Calling whisper_free on context: %p", context);
    whisper_free(context); // This will call your inputStreamClose
    LOGI("JNI: [REGULAR MODE] whisper_free completed for context: %p", context);
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads, jfloatArray audio_data) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = true;
    params.print_special = false;
    params.translate = false;
    params.language = "en";
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;

    LOGI("WhisperJNI: fullTranscribe called with: n_threads=%d, audio_length=%d, print_realtime=%d",params.n_threads, (int)audio_data_length, params.print_realtime);

    if (context == NULL) {
        LOGE("WhisperJNI: whisper_context is NULL. Aborting fullTranscribe.");
        if (audio_data_arr != NULL) {
            (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
        }
        return;
    }

    if (audio_data_arr == NULL) {
        LOGE("WhisperJNI: GetFloatArrayElements failed to get audio data. Aborting.");
        return;
    }

    whisper_reset_timings(context); // Resetting timing before the call

    LOGI("WhisperJNI: Preparing to call whisper_full C function. Context: %p", (void*)context);
    struct timespec ts_start, ts_end;
    clock_gettime(CLOCK_MONOTONIC, &ts_start);

    int result = whisper_full(context, params, audio_data_arr, audio_data_length);

    clock_gettime(CLOCK_MONOTONIC, &ts_end);
    double sec_diff = (double)(ts_end.tv_sec - ts_start.tv_sec);
    double nsec_diff = (double)(ts_end.tv_nsec - ts_start.tv_nsec) / 1000000000.0;
    double elapsed_s = sec_diff + nsec_diff;

    LOGI("WhisperJNI: whisper_full C function returned: %d. Time taken: %.3f seconds.", result, elapsed_s);

    // Check the result and print timings
    if (result == 0) {
        LOGI("WhisperJNI: whisper_full C function successfully returned: %d. Time taken: %.3f seconds.", result, elapsed_s);
        struct whisper_timings * timings = whisper_get_timings(context);
        if (timings) {
            LOGI("WhisperJNI Detailed Timings:");
            LOGI("  Sample ms: %.2f", timings->sample_ms);
            LOGI("  Encode ms: %.2f", timings->encode_ms);
            LOGI("  Decode ms: %.2f", timings->decode_ms);
            LOGI("  BatchD ms: %.2f", timings->batchd_ms);
            LOGI("  Prompt ms: %.2f", timings->prompt_ms);
            free(timings); // IMPORTANT: Free the allocated struct
        } else {
            LOGW("WhisperJNI: Failed to get detailed timings from whisper_get_timings.");
        }
    } else {
        LOGE("WhisperJNI: whisper_full C function FAILED with code: %d. Time taken: %.3f seconds.", result, elapsed_s);
        struct whisper_timings * timings = whisper_get_timings(context);
        if (timings) {
            LOGI("WhisperJNI Detailed Timings (on failure):");
            LOGI("  Sample ms: %.2f", timings->sample_ms);
            LOGI("  Encode ms: %.2f", timings->encode_ms);
            LOGI("  Decode ms: %.2f", timings->decode_ms);
            LOGI("  BatchD ms: %.2f", timings->batchd_ms);
            LOGI("  Prompt ms: %.2f", timings->prompt_ms);
            free(timings);
        }
    }

    // Release the Java array elements
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
}


JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_n_segments(context);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *text = whisper_full_get_segment_text(context, index);
    jstring string = (*env)->NewStringUTF(env, text);
    return string;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegmentT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t0(context, index);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegmentT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t1(context, index);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_getSystemInfo(
        JNIEnv *env,
        jobject thiz) { // 'thiz' will be the instance of the WhisperLib singleton
    // It's good practice to add logging inside your JNI functions for debugging
    __android_log_print(ANDROID_LOG_INFO, "WhisperJNI", "Java_com_whispercpp_whisper_WhisperLib_getSystemInfo CALLED");

    UNUSED(thiz); // You can keep this if 'thiz' is truly not used.
    // For a Kotlin 'object', 'thiz' is the object instance.

    const char *sysinfo = whisper_print_system_info();
    if (sysinfo == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "WhisperJNI", "whisper_print_system_info() returned NULL");
        // Return a meaningful error string or handle as appropriate
        return (*env)->NewStringUTF(env, "Error: System info was NULL.");
    }
    __android_log_print(ANDROID_LOG_INFO, "WhisperJNI", "System Info from C: %s", sysinfo);
    jstring string = (*env)->NewStringUTF(env, sysinfo);
    return string;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_benchMemcpy(JNIEnv *env, jobject thiz, jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_memcpy = whisper_bench_memcpy_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_memcpy);
    return string;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_benchGgmlMulMat(JNIEnv *env, jobject thiz, jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_mul_mat = whisper_bench_ggml_mul_mat_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_mul_mat);
    return string;
}
