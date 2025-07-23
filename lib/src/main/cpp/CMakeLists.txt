# Minimum CMake version
cmake_minimum_required(VERSION 3.18.1)

# Project name (optional, but good practice)
project(WhisperAndroidLib C CXX)

# --- Add the whisper_core library ---
# This tells CMake to look into the 'whisper_core' directory
# and process its CMakeLists.txt.
# The whisper_core/CMakeLists.txt (and its sub-directories like src/)
# will build a library target named "whisper".
add_subdirectory(whisper_core)

# --- Define our JNI Wrapper Library ---
# This is the library that your Android app will load.
# It includes your jni.c file.
# The output .so file will be named "libwhisper-jni.so"
add_library(
        whisper-jni         # Name of the library target we are creating
        SHARED              # Build as a shared library (.so file)
        jni.c               # Source file(s) for this JNI library
)

# --- Link the JNI library against whisper and Android NDK libraries ---
# This connects our "whisper-jni" library to the "whisper" library
# (built by whisper_core) and to standard Android NDK libraries.
target_link_libraries(
        whisper-jni         # Our JNI library target
        PRIVATE             # Linkage type
        whisper             # The library target from whisper_core
        android             # NDK library for Android-specific APIs
        log                 # NDK library for logging (used in jni.c)
)

# --- Include directories ---
# This line ensures that when jni.c is compiled, the compiler knows
# where to find "whisper.h" (which is in whisper_core/include/).
# ${CMAKE_CURRENT_SOURCE_DIR} refers to the "cpp" directory.
target_include_directories(whisper-jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/whisper_core/include
)

# --- Add compile definition for GGML_USE_CPU ---
# This is important as we've removed other backends.
# We apply this definition to the 'whisper' target, which should
# then propagate it to ggml if they are linked correctly.
# Alternatively, if 'ggml' is a separate global target from whisper_core,
# you might need to apply it to 'ggml' directly.
# Let's assume the 'whisper' target handles this.
# If not, Gradle arguments are another place to set this.
target_compile_definitions(whisper INTERFACE GGML_USE_CPU)
# An alternative or addition if ggml is a distinct target from whisper_core's perspective:
# target_compile_definitions(ggml INTERFACE GGML_USE_CPU)
