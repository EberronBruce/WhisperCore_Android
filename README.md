# WhisperCore_Android

**WhisperCore_Android** is a Kotlin-based wrapper around [`whisper.cpp`](https://github.com/ggerganov/whisper.cpp) that brings native, on-device speech-to-text capabilities to Android apps. It provides a structured, coroutine-friendly API modeled after the iOS Swift interface, making it easier to build cross-platform voice-driven features.

[![](https://jitpack.io/v/EberronBruce/WhisperCore_Android.svg)](https://jitpack.io/#EberronBruce/WhisperCore_Android)

---

## 📦 Installation

Add the following to your `build.gradle` (Project level):

```gradle
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```

Then include the library in your app module:

```gradle
dependencies {
    implementation 'com.github.EberronBruce:WhisperCore_Android:v1.0.2'
}
```

---

## 🚀 Features

- Load Whisper models from assets or file system
- Start/stop microphone recording
- Transcribe audio from mic or file
- Toggle recording
- Benchmark models
- Playback support (optional)
- Permission request handling via delegate
- Kotlin coroutine–based, lifecycle-aware design
- Mirrors Swift/iOS API for easier multiplatform development

---

## 🧠 Powered by whisper.cpp

This library uses [**whisper.cpp**](https://github.com/ggerganov/whisper.cpp) under the hood — an efficient C++ implementation of OpenAI's Whisper speech-to-text model, optimized for on-device use.

Please ⭐ the upstream project and review its [license](https://github.com/ggerganov/whisper.cpp/blob/master/LICENSE).

---

## 🧩 API Overview

```kotlin
val whisper = Whisper(context)
```

### Setup

```kotlin
suspend fun initializeModel(modelPath: String, log: Boolean = false)
suspend fun initializeModelFromAsset(assetPath: String, log: Boolean = false)
```

### Permissions

```kotlin
fun callRequestRecordPermission()
fun onRecordPermissionResult(granted: Boolean)
```

### Recording & Transcription

```kotlin
fun startRecording()
fun stopRecording()
fun toggleRecording()
fun transcribeAudioFile(audioFile: File, log: Boolean = false, timestamp: Boolean = false)
```

### Utilities

```kotlin
fun enablePlayback(enabled: Boolean)
fun getSystemInfo(log: Boolean = true): String
fun getMessageLogs(): String
fun benchmark()
fun reset()
fun cleanup()
```

---

## 🧪 Delegates

Implement the `WhisperDelegate` interface in your app to handle events:

```kotlin
interface WhisperDelegate {
    fun didTranscribe(text: String)
    fun recordingFailed(error: WhisperOperationError)
    fun failedToTranscribe(error: WhisperOperationError)
    fun permissionRequestNeeded()
    fun didStartRecording()
    fun didStopRecording()
}
```

---

## ❗ Errors

Errors are structured using sealed classes:

### Load-Time Errors

```kotlin
sealed class WhisperLoadError : Exception() {
    object PathToModelEmpty
    object CouldNotLocateModel
    class UnableToLoadModel(details: String, cause: Throwable? = null)
}
```

### Runtime Errors

```kotlin
sealed class WhisperOperationError : Exception() {
    object MissingRecordedFile
    object MicPermissionDenied
    object ModelNotLoaded
    object RecordingFailed
    class TranscriptionFailed(details: String, cause: Throwable? = null)
}
```

All errors are dispatched via delegate methods on the main thread.

---

## 📂 Example

```kotlin
val whisper = Whisper(context).apply {
    delegate = object : WhisperDelegate {
        override fun didTranscribe(text: String) {
            Log.i("Whisper", "Transcription: $text")
        }

        override fun recordingFailed(error: WhisperOperationError) { /* handle */ }
        override fun failedToTranscribe(error: WhisperOperationError) { /* handle */ }
        override fun permissionRequestNeeded() { /* prompt user */ }
        override fun didStartRecording() { /* update UI */ }
        override fun didStopRecording() { /* update UI */ }
    }
}

whisper.callRequestRecordPermission()
// After permission granted:
whisper.initializeModelFromAsset("models/ggml-base.en.bin")
whisper.startRecording()
```

---

## 📋 License

MIT — see [`LICENSE`](./LICENSE)

> Includes code that interacts with [whisper.cpp](https://github.com/ggerganov/whisper.cpp), which is licensed under the MIT License.
