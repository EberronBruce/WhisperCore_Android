package com.whispercpp.whisper.recorder

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.whispercpp.whisper.media.encodeWaveFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean


interface IRecorder {
	suspend fun startRecording(outputFile: File, onError: (Exception) -> Unit)
	suspend fun stopRecording()
}
class Recorder : IRecorder {
	private val scope: CoroutineScope = CoroutineScope(
		Executors.newSingleThreadExecutor().asCoroutineDispatcher()
	)
	private var recorder: AudioRecordThread? = null

//	override suspend fun startRecording(outputFile: File, onError: (Exception) -> Unit) = withContext(scope.coroutineContext) {
//		withContext(scope.coroutineContext) {
//		recorder = AudioRecordThread(outputFile, onError)
//		recorder?.start()
//			}
//	}

	override suspend fun startRecording(outputFile: File, onError: (Exception) -> Unit) { // Match explicit Unit
		// It's good practice to ensure the coroutineContext is active
		if (!scope.isActive) {
			// Handle the case where the scope is no longer active, perhaps by calling onError
			onError(IllegalStateException("Recorder scope is no longer active."))
			return
		}
		withContext(scope.coroutineContext) {
			// Clean up previous recorder if any
			if (this@Recorder.recorder != null) {
				this@Recorder.recorder?.stopRecording()
				try {
					this@Recorder.recorder?.join(1000L) // Timeout for join
				} catch (e: InterruptedException) {
					Thread.currentThread().interrupt()
					Log.e("Recorder", "Interrupted while waiting for recorder to stop", e)
					// Optionally call onError or log
				}
				this@Recorder.recorder = null
			}
			// Create and start new recorder
			this@Recorder.recorder = AudioRecordThread(outputFile, onError)
			this@Recorder.recorder?.start()
			// The lambda for withContext implicitly returns Unit here
		}
	}

//	override suspend fun stopRecording() = withContext(scope.coroutineContext) {
//		recorder?.stopRecording()
//		recorder?.join()
//		recorder = null
//	}

	override suspend fun stopRecording() { // Match explicit Unit
		if (!scope.isActive) {
			// Handle inactive scope if necessary
			return
		}
		withContext(scope.coroutineContext) {
			this@Recorder.recorder?.stopRecording()
			try {
				this@Recorder.recorder?.join(5000L) // Timeout for join
			} catch (e: InterruptedException) {
				Thread.currentThread().interrupt()
				Log.e("Recorder", "Interrupted while waiting for recorder to stop", e)
				// Optionally log or handle
			}
			this@Recorder.recorder = null
		}
	}
}

private class AudioRecordThread(
	private val outputFile: File,
	private val onError: (Exception) -> Unit
) :
	Thread("AudioRecorder") {
	private var quit = AtomicBoolean(false)

	//	@SuppressLint("MissingPermission")
	@RequiresPermission(Manifest.permission.RECORD_AUDIO)
	override fun run() {
		try {
			val bufferSize = AudioRecord.getMinBufferSize(
				16000,
				AudioFormat.CHANNEL_IN_MONO,
				AudioFormat.ENCODING_PCM_16BIT
			) * 4
			val buffer = ShortArray(bufferSize / 2)

			val audioRecord = AudioRecord(
				MediaRecorder.AudioSource.MIC,
				16000,
				AudioFormat.CHANNEL_IN_MONO,
				AudioFormat.ENCODING_PCM_16BIT,
				bufferSize
			)

			try {
				audioRecord.startRecording()

				val allData = mutableListOf<Short>()

				while (!quit.get()) {
					val read = audioRecord.read(buffer, 0, buffer.size)
					if (read > 0) {
						for (i in 0 until read) {
							allData.add(buffer[i])
						}
					} else {
						throw RuntimeException("audioRecord.read returned $read")
					}
				}

				audioRecord.stop()
				encodeWaveFile(outputFile, allData.toShortArray())
			} finally {
				audioRecord.release()
			}
		} catch (e: Exception) {
			onError(e)
		}
	}

	fun stopRecording() {
		quit.set(true)
	}
}