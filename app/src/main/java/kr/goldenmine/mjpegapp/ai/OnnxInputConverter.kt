package kr.goldenmine.mjpegapp.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.ArrayList // 내부 처리용
import java.util.LinkedList // Queue로 사용하거나 List로 사용

class OnnxInputConverter(
    private val videoFramesNeeded: Int = 25,
    private val audioSamplesPerChunk: Int = 640,
    private val audioBytesPerSample: Int = 2, // Int16 = 2 bytes
    private val targetAudioSamples: Int = 16000
) {
    private val TAG = "OnnxInputConverter"

    private val videoFrameBuffer = LinkedList<Bitmap>()
    private val audioByteBuffer = LinkedList<ByteArray>()
    private var currentAudioBytes = 0
    private val audioChunksNeeded = targetAudioSamples / audioSamplesPerChunk

    @Synchronized
    fun addVideoFrame(frame: Bitmap) {
        if (frame.width != 88 || frame.height != 88) {
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
        audioByteBuffer.add(chunk)
        currentAudioBytes += chunk.size
        if (audioByteBuffer.size > audioChunksNeeded) {
            val removed = audioByteBuffer.poll()
            currentAudioBytes -= removed?.size ?: 0 // Null safety for removed element
        }
    }

    @Synchronized
    fun processBuffersIfReady(): Pair<FloatBuffer, FloatBuffer>? {
        if (videoFrameBuffer.size == videoFramesNeeded && audioByteBuffer.size == audioChunksNeeded) {
            Log.d(TAG, "데이터 준비 완료. FloatBuffer 변환 시작...")
            val start = System.currentTimeMillis()

            val videoFramesToProcess = ArrayList(videoFrameBuffer)
            val audioChunksToProcess = ArrayList(audioByteBuffer)

            videoFrameBuffer.clear()
            audioByteBuffer.clear()
            currentAudioBytes = 0

            // --- 변환 작업 ---
            // 두 변환 중 하나라도 실패하면 null 반환
            val audioFloatBuffer = convertAudioToFloatBuffer(audioChunksToProcess) ?: return null
            val videoFloatBuffer = convertVideoToFloatBuffer(videoFramesToProcess) ?: return null
            Log.d(TAG, "데이터 준비 완료. FloatBuffer 변환 완료... ${System.currentTimeMillis() - start}")
            return Pair(audioFloatBuffer, videoFloatBuffer)

        }
        return null
    }

    // --- 오디오 변환 함수 ---
    private fun convertAudioToFloatBuffer(audioChunks: List<ByteArray>): FloatBuffer? {
        try {
            val combinedBytes = ByteArray(targetAudioSamples * audioBytesPerSample)
            var currentPos = 0
            for (chunk in audioChunks) {
                System.arraycopy(chunk, 0, combinedBytes, currentPos, chunk.size)
                currentPos += chunk.size
            }

            val shortBuffer = ByteBuffer.wrap(combinedBytes)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer()

            val floatBuffer = FloatBuffer.allocate(targetAudioSamples) // 16000
            for (i in 0 until targetAudioSamples) {
                val pcmValue = shortBuffer.get(i).toFloat()
                // *** 오디오 정규화: [-1.0f, 1.0f] 범위 ***
                val normalizedValue = pcmValue / 32767.0f
                floatBuffer.put(i, normalizedValue)
            }
            val outputArray = FloatArray(floatBuffer.remaining()) // 버퍼의 남은 요소만큼 배열 생성
            floatBuffer.get(outputArray) // 배열에 복사
            floatBuffer.rewind()
            val minVal = outputArray.minOrNull() ?: Float.NaN
            val maxVal = outputArray.maxOrNull() ?: Float.NaN
            val avgVal = outputArray.average().toFloat()
            Log.d(TAG, "FloatArray: Min=$minVal, Max=$maxVal, Avg=$avgVal, Size=${outputArray.size}")

            floatBuffer.rewind()
            Log.d(TAG, "오디오 FloatBuffer 변환 완료.")
            return floatBuffer
        } catch (e: Exception) {
            Log.e(TAG, "오디오 변환 실패", e)
            return null
        }
    }

    // --- 비디오 변환 함수 ---
    private fun convertVideoToFloatBuffer(videoFrames: List<Bitmap>): FloatBuffer? {
        val frameCount = videoFrames.size
        if (frameCount != videoFramesNeeded) return null

        val height = 88
        val width = 88
        val numPixelsPerFrame = height * width
        val totalFloats = frameCount * numPixelsPerFrame // 25 * 112 * 112 = 313,600

        try {
            // Direct ByteBuffer를 사용하면 성능상 이점이 있을 수 있습니다.
            // 예: val byteBuffer = ByteBuffer.allocateDirect(totalFloats * 4).order(ByteOrder.nativeOrder())
            //     val floatBuffer = byteBuffer.asFloatBuffer()
            val floatBuffer = FloatBuffer.allocate(totalFloats)

            for (t in 0 until frameCount) { // 시간 축 (프레임)
                val bitmap = videoFrames[t]
                for (h in 0 until height) { // 높이 축
                    for (w in 0 until width) { // 너비 축
                        val pixel = bitmap.getPixel(w, h)

                        // ARGB -> Grayscale (Luminance)
                        val r = Color.red(pixel)
                        val g = Color.green(pixel)
                        val b = Color.blue(pixel)
                        val gray = (0.299 * r + 0.587 * g + 0.114 * b).toFloat()

                        // *** 비디오 정규화: [0.0f, 1.0f] 범위 ***
                        val normalizedGray = gray / 255.0f

                        // FloatBuffer에 [T, H, W] 순서로 값 저장
                        val index = t * numPixelsPerFrame + h * width + w
                        floatBuffer.put(index, normalizedGray)
                    }
                }
            }

            floatBuffer.rewind()
            Log.d(TAG, "비디오 FloatBuffer 변환 완료.")
            return floatBuffer

        } catch (e: Exception) {
            Log.e(TAG, "비디오 변환 실패", e)
            return null
        }
    }
}