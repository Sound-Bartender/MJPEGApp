package kr.goldenmine.mjpegapp.ai

import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedList // Queue로 사용하거나 List로 사용
import androidx.core.graphics.get

class IIANetInputConverter(
    private val videoFramesNeeded: Int = 25,
    private val audioSamplesPerChunk: Int = 640,
    private val audioBytesPerSample: Int = 2, // Int16 = 2 bytes
    private val targetAudioSamples: Int = 16000,
    private val frameSize: Int = 88,
) {
    private val TAG = "IIANetInputConverter"

    private val videoFrameBuffer = LinkedList<Bitmap>()
    private val audioFrameBuffer = LinkedList<ByteArray>()
    private var currentAudioBytes = 0
    private val audioChunksNeeded = targetAudioSamples / audioSamplesPerChunk

    @Synchronized
    fun addVideoFrame(frame: Bitmap) {
        if (frame.width != frameSize || frame.height != frameSize) {
            Log.w(TAG, "비디오 프레임 크기가 88x88이 아닙니다. 무시됨.")
            return
        }
        videoFrameBuffer.add(frame)
        if (videoFrameBuffer.size > videoFramesNeeded) {
            videoFrameBuffer.poll()
        }
    }

    @Synchronized
    fun addAudioChunk(chunk: ByteArray) {
        val expectedBytes = audioSamplesPerChunk * audioBytesPerSample
        if (chunk.size != expectedBytes) {
            Log.w(TAG, "오디오 청크 크기가 ${expectedBytes}bytes가 아닙니다. 무시됨.")
            return
        }
        audioFrameBuffer.add(chunk)
        currentAudioBytes += chunk.size
        if (audioFrameBuffer.size > audioChunksNeeded) {
            val removed = audioFrameBuffer.poll()
            currentAudioBytes -= removed?.size ?: 0 // Null safety for removed element
        }
    }

    fun checkIfReady(): Boolean {
        return videoFrameBuffer.size >= videoFramesNeeded && audioFrameBuffer.size >= audioChunksNeeded
    }

    @Synchronized
    fun processBuffers(): Pair<ByteBuffer, ByteBuffer> {
        require(videoFrameBuffer.size >= 25) { "Expected 25 frames video buffer, got ${videoFrameBuffer.size}" }
        require(audioFrameBuffer.size >= 25) { "Expected 25 frames audio buffer, got ${videoFrameBuffer.size}" }

        Log.d(TAG, "데이터 준비 완료. FloatBuffer 변환 시작...")
        val start = System.currentTimeMillis()

        currentAudioBytes = 0

        // Video
        val videoInputBuffer = ByteBuffer.allocateDirect(1 * 1 * 25 * 88 * 88 * 4).order(ByteOrder.nativeOrder())
        for(image in videoFrameBuffer) {
            for (y in 0 until frameSize) {
                for (x in 0 until frameSize) {
                    val pixel = image[x, y]
                    // Convert RGB to grayscale (luma transform)
                    val r = (pixel shr 16 and 0xFF)
                    val g = (pixel shr 8 and 0xFF)
                    val b = (pixel and 0xFF)
                    val gray = (0.299f * r + 0.587f * g + 0.114f * b) / 255f // normalize to [0,1]
                    videoInputBuffer.putFloat(gray)
                }
            }
        }
        videoInputBuffer.rewind()

        // Audio
        val audioInputBuffer = ByteBuffer.allocateDirect(1 * 1 * targetAudioSamples * 4).order(ByteOrder.nativeOrder())
        var samplesWritten = 0

        for (chunk in audioFrameBuffer) {
            val chunkBuffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            while (chunkBuffer.remaining() >= 2 && samplesWritten < targetAudioSamples) {
                val sample = chunkBuffer.short.toInt()
                val normalized = sample / 32768.0f  // normalize to [-1.0, 1.0]
                audioInputBuffer.putFloat(normalized)
                samplesWritten++
            }
            if (samplesWritten >= targetAudioSamples) break
        }
        audioInputBuffer.rewind()

        // Clear
        videoFrameBuffer.clear()
        audioFrameBuffer.clear()

        Log.d(TAG, "데이터 준비 완료. 변환 시간: ${System.currentTimeMillis() - start}")

        return Pair(videoInputBuffer, audioInputBuffer)
    }
}