package kr.goldenmine.mjpegapp.ai

import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.core.graphics.get

class IIANetInputConverter(
    private val videoFramesNeeded: Int = 25,
    private val audioSamplesPerChunk: Int = 640,
    private val audioBytesPerSample: Int = 2, // Int16 = 2 bytes
    private val targetAudioSamples: Int = 16000,
    private val frameSize: Int = 88,
) {
    private val TAG = "IIANetInputConverter"

    private val videoInputBufferA = ByteBuffer.allocateDirect(1 * 1 * videoFramesNeeded * frameSize * frameSize * 4).order(ByteOrder.nativeOrder())
    private val audioInputBufferA = ByteBuffer.allocateDirect(1 * 1 * targetAudioSamples * 4).order(ByteOrder.nativeOrder())

    private val videoInputBufferB = ByteBuffer.allocateDirect(1 * 1 * videoFramesNeeded * frameSize * frameSize * 4).order(ByteOrder.nativeOrder())
    private val audioInputBufferB = ByteBuffer.allocateDirect(1 * 1 * targetAudioSamples * 4).order(ByteOrder.nativeOrder())

    private var status = 'A'

    var keepFrames = 0

    fun addVideoFrame(frame: Bitmap) {
        if (frame.width != frameSize || frame.height != frameSize) {
            Log.w(TAG, "비디오 프레임 크기가 88x88이 아닙니다. 무시됨.")
            return
        }

        val videoInputBuffer = if(status == 'A') videoInputBufferA else videoInputBufferB

//        videoFrameBuffer.add(frame)
        if(videoInputBuffer.remaining() <= 0) {
            Log.w(TAG, "비디오 버퍼 꽉 참")
            return
        }

        // 들어가는 float의 개수: frameSize^2 * 4 bytes
        for (y in 0 until frameSize) {
            for (x in 0 until frameSize) {
                val pixel = frame[y, frameSize - 1 - x]  // 회전된 좌표계
//                val pixel = frame[x, y]
                // Convert RGB to grayscale (luma transform)
                val r = (pixel shr 16 and 0xFF)
                val g = (pixel shr 8 and 0xFF)
                val b = (pixel and 0xFF)
                val gray = (0.299f * r + 0.587f * g + 0.114f * b) / 255f // normalize to [0,1]
                videoInputBuffer.putFloat(gray)
            }
        }
//        if (videoFrameBuffer.size > videoFramesNeeded) {
//            videoFrameBuffer.poll()
//        }
    }

    fun addAudioChunk(chunk: ByteArray) {
        val expectedBytes = audioSamplesPerChunk * audioBytesPerSample
        if (chunk.size != expectedBytes) {
            Log.w(TAG, "오디오 청크 크기가 ${expectedBytes}bytes가 아닙니다. 무시됨.")
            return
        }
        val audioInputBuffer = if(status == 'A') audioInputBufferA else audioInputBufferB

        if(audioInputBuffer.remaining() <= 0) {
            Log.w(TAG, "오디오 버퍼 꽉 참")
            return
        }
        val chunkBuffer = ByteBuffer.wrap(chunk).order(ByteOrder.nativeOrder()).asShortBuffer()

        chunkBuffer.rewind()
        var samplesWritten = 0
//            Log.d(TAG, "chunk size: ${chunk.size} ${chunkBuffer.remaining()}")
        while (samplesWritten < targetAudioSamples / videoFramesNeeded) {
            val sample = chunkBuffer.get(samplesWritten)
            val normalized = sample / 32768.0f  // normalize to [-1.0, 1.0]
            audioInputBuffer.putFloat(normalized)
            samplesWritten++
        }
    }

    fun checkIfReady(): Boolean {
//        synchronized(this) {
            val videoInputBuffer = if (status == 'A') videoInputBufferA else videoInputBufferB
            val audioInputBuffer = if (status == 'A') audioInputBufferA else audioInputBufferB
            return videoInputBuffer.remaining() <= 0 && audioInputBuffer.remaining() <= 0
//        }
    }

    fun processBuffers(): Pair<ByteBuffer, ByteBuffer> {
//        synchronized(this) {
        // synchronized가 필요 없는 게 해당 함수들은 모두 단일 스레드에서 호출 됨
        val videoInputBuffer = if (status == 'A') videoInputBufferA else videoInputBufferB
        val audioInputBuffer = if (status == 'A') audioInputBufferA else audioInputBufferB
        status = if (status == 'A') 'B' else 'A'

        val start = System.currentTimeMillis()
        videoInputBuffer.rewind()
        audioInputBuffer.rewind()

        // 비디오 복사
        val newVideoBuffer = ByteBuffer
            .allocateDirect(videoInputBuffer.capacity())
            .order(videoInputBuffer.order())

        newVideoBuffer.put(videoInputBuffer)  // 데이터 복사

        newVideoBuffer.rewind()
        videoInputBuffer.rewind()

        // 오디오 복사
        val newAudioBuffer = ByteBuffer
            .allocateDirect(audioInputBuffer.capacity())
            .order(audioInputBuffer.order())

        newAudioBuffer.put(audioInputBuffer)  // 데이터 복사

        newAudioBuffer.rewind()
        audioInputBuffer.rewind()

        if(keepFrames > 0) {
            val videoInputBufferNext = if (status == 'A') videoInputBufferA else videoInputBufferB
            val audioInputBufferNext = if (status == 'A') audioInputBufferA else audioInputBufferB

            videoInputBufferNext.rewind()
            audioInputBufferNext.rewind()

            val videoCopyCount = frameSize * frameSize * keepFrames * 4
            val audioCopyCount = audioSamplesPerChunk * keepFrames * 4

            videoInputBuffer.position(0).limit(videoCopyCount)
            videoInputBufferNext.put(videoInputBuffer)

            audioInputBuffer.position(0).limit(audioCopyCount)
            audioInputBufferNext.put(audioInputBuffer)

            // pos = 0, lim = cap, rewind
            videoInputBuffer.clear()
            audioInputBuffer.clear()
        }

        Log.d(TAG, "데이터 준비 완료 $status ${System.currentTimeMillis() - start}")

        return Pair(newVideoBuffer, newAudioBuffer)
    }
}