package com.redravencomputing.whispercore.media

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder


internal fun encodeWaveFile(file: File, data: ShortArray) {
	val audioDataSizeBytes = data.size * 2
	val totalFileLength = 44 + audioDataSizeBytes // Calculate actual total file length

	file.outputStream().use { outputStream ->
		outputStream.write(headerBytes(totalFileLength)) // Pass totalFileLength
		// ... rest of the code to write audio data ...
		val buffer = ByteBuffer.allocate(audioDataSizeBytes)
		buffer.order(ByteOrder.LITTLE_ENDIAN)
		buffer.asShortBuffer().put(data)
		val audioBytes = ByteArray(buffer.limit())
		buffer.get(audioBytes)
		outputStream.write(audioBytes)
	}
}

private fun headerBytes(totalFileLength: Int): ByteArray { // Parameter is now clearly totalFileLength
	require(totalFileLength >= 44) { "Total file length must be at least 44 bytes for the header." }

	val dataSubChunkSize = totalFileLength - 44 // Correct: This is the audioDataSizeBytes
	val riffChunkSize = totalFileLength - 8    // Correct: This is audioDataSizeBytes + 36

	return ByteBuffer.allocate(44).apply {
		order(ByteOrder.LITTLE_ENDIAN)

		// RIFF chunk descriptor
		put('R'.code.toByte())
		put('I'.code.toByte())
		put('F'.code.toByte())
		put('F'.code.toByte())
		putInt(riffChunkSize) // Correctly uses (totalFileLength - 8)
		put('W'.code.toByte())
		// ... rest of WAVE format ...
		put('A'.code.toByte())
		put('V'.code.toByte())
		put('E'.code.toByte())

		// "fmt " sub-chunk
		put('f'.code.toByte())
		put('m'.code.toByte())
		put('t'.code.toByte())
		put(' '.code.toByte())
		putInt(16)
		putShort(1.toShort())
		putShort(1.toShort())
		putInt(16000)
		putInt(16000 * 1 * 2) // ByteRate for 16kHz, mono, 16-bit
		putShort((1 * 2).toShort()) // BlockAlign for mono, 16-bit
		putShort(16.toShort())

		// "data" sub-chunk
		put('d'.code.toByte())
		put('a'.code.toByte())
		put('t'.code.toByte())
		put('a'.code.toByte())
		putInt(dataSubChunkSize) // Correctly uses (totalFileLength - 44)

		position(0)
	}.array()
}
