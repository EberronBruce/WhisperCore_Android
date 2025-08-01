package com.redravencomputing.whisper.whispercore.media

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun interface AudioDecoder {
	/**
	 * Decodes an audio file.
	 * @param file The audio file to decode.
	 * @return FloatArray of PCM audio data.
	 * @throws Exception if decoding fails.
	 */
	fun decode(file: File): FloatArray
}


// A basic placeholder/example. You'll need a robust implementation.
internal class DefaultAudioDecoder() : AudioDecoder {
	companion object {
		private const val TAG = "DefaultAudioDecoder"
		private const val TARGET_SAMPLE_RATE = 16000 // Whisper typically expects 16kHz
		private const val TARGET_CHANNEL_CONFIG = 1 // Mono
	}

	override fun decode(file: File): FloatArray {
		Log.d(TAG, "Attempting to decode: ${file.absolutePath}")
		if (!file.exists()) {
			throw IOException("Input file does not exist: ${file.name}")
		}

		val extractor = MediaExtractor()
		val pcmData = mutableListOf<Float>()

		try {
			extractor.setDataSource(file.absolutePath)
			var trackIndex = -1
			var inputFormat: MediaFormat? = null

			for (i in 0 until extractor.trackCount) {
				val format = extractor.getTrackFormat(i)
				val mime = format.getString(MediaFormat.KEY_MIME)
				if (mime?.startsWith("audio/") == true) {
					trackIndex = i
					inputFormat = format
					break
				}
			}

			if (trackIndex == -1 || inputFormat == null) {
				throw IOException("No audio track found in ${file.name}")
			}

			extractor.selectTrack(trackIndex)

			// Basic resampling/re-channeling might be needed here if the source
			// is not already 16kHz mono. This example is simplified.
			// For production, use a proper audio processing library if needed.
			val actualSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
			val actualChannelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

			Log.d(TAG, "Input format: SR=$actualSampleRate, Channels=$actualChannelCount")

			if (actualSampleRate != TARGET_SAMPLE_RATE) {
				Log.w(TAG, "Sample rate mismatch. Expected $TARGET_SAMPLE_RATE, got $actualSampleRate. Results might be affected. Consider proper resampling.")
			}
			if (actualChannelCount > TARGET_CHANNEL_CONFIG) {
				Log.w(TAG, "Channel count mismatch. Expected mono, got $actualChannelCount. Taking first channel or averaging might be needed. This example might not handle it correctly.")
			}


			val buffer = ByteBuffer.allocate(1024 * 1024) // Adjust buffer size as needed
			buffer.order(ByteOrder.LITTLE_ENDIAN) // Common for PCM, ensure this matches whisper.cpp expectation

			while (true) {
				val sampleSize = extractor.readSampleData(buffer, 0)
				if (sampleSize < 0) break

				buffer.position(0)
				buffer.limit(sampleSize)

				// Assuming 16-bit PCM for this conversion logic.
				// Adjust if your MediaRecorder outputs something else or if Whisper JNI expects raw floats directly
				// from a different decoder.
				while (buffer.hasRemaining()) {
					if (buffer.remaining() >= 2) { // Ensure there are at least 2 bytes for a short
						val shortSample = buffer.short
						pcmData.add(shortSample / 32768.0f) // Normalize to -1.0 to 1.0
					} else {
						buffer.position(buffer.limit()) // Consume remaining byte if any
					}
				}
				buffer.clear()
				extractor.advance()
			}
			Log.d(TAG, "Decoded ${pcmData.size} float samples.")

		} catch (e: Exception) {
			Log.e(TAG, "Error decoding audio file ${file.name}", e)
			throw IOException("Failed to decode audio file: ${e.localizedMessage}", e)
		} finally {
			extractor.release()
		}

		if (pcmData.isEmpty()) {
			throw IOException("No audio data decoded from ${file.name}")
		}
		return pcmData.toFloatArray()
	}
}