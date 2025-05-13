//package trash
//
//import android.graphics.Bitmap
//import android.graphics.Color
//import android.util.Log
//import java.nio.ByteBuffer
//import java.nio.ByteOrder
//import java.util.ArrayList // 내부 처리용
//import java.util.LinkedList // Queue로 사용하거나 List로 사용
//import androidx.core.graphics.get
//
//class TFLiteInputConverter(
//    private val videoFramesNeeded: Int = 25,
//    private val audioSamplesPerChunk: Int = 640,
//    private val audioBytesPerSample: Int = 2, // Int16 = 2 bytes
//    private val targetAudioSamples: Int = 16000,
//    private val frameSize: Int = 112
//) {
//    private val TAG = "TFLiteInputConverter"
//
//    private val videoFrameBuffer = LinkedList<Bitmap>()
//    private val audioByteBuffer = LinkedList<ByteArray>()
//    private var currentAudioBytes = 0
//    private val audioChunksNeeded = targetAudioSamples / audioSamplesPerChunk
//
//    @Synchronized
//    fun addVideoFrame(frame: Bitmap) {
//        if (frame.width != frameSize || frame.height != frameSize) {
//            Log.w(TAG, "비디오 프레임 크기가 ${frameSize}x${frameSize}이 아닙니다. 무시됨.")
//            return
//        }
//        videoFrameBuffer.add(frame)
//        if (videoFrameBuffer.size > videoFramesNeeded) {
//            videoFrameBuffer.poll()
//        }
//    }
//
//    @Synchronized
//    fun addAudioChunk(chunk: ByteArray) {
//        val expectedBytes = audioSamplesPerChunk * audioBytesPerSample
//        if (chunk.size != expectedBytes) {
//            Log.w(TAG, "오디오 청크 크기가 ${expectedBytes}bytes가 아닙니다. 무시됨.")
//            return
//        }
//        audioByteBuffer.add(chunk)
//        currentAudioBytes += chunk.size
//        if (audioByteBuffer.size > audioChunksNeeded) {
//            val removed = audioByteBuffer.poll()
//            currentAudioBytes -= removed?.size ?: 0 // Null safety for removed element
//        }
//    }
//
//    fun checkIfReady(): Boolean {
//        return videoFrameBuffer.size >= videoFramesNeeded && audioByteBuffer.size >= audioChunksNeeded
//    }
//
//    @Synchronized
//    fun processBuffersIfReady(): Triple<Array<FloatArray>, Array<Array<Array<FloatArray>>>, Array<Array<FloatArray>>>? {
//        if (checkIfReady()) {
//            Log.d(TAG, "데이터 준비 완료. FloatBuffer 변환 시작...")
//            val start = System.currentTimeMillis()
//
//            val videoFramesToProcess = ArrayList(videoFrameBuffer)
//            val audioChunksToProcess = ArrayList(audioByteBuffer)
//
//            for(i in 0 until 5) {
//                videoFrameBuffer.poll()
//                audioByteBuffer.poll()
//            }
//            currentAudioBytes = 0
//
//            val audioFloatArray = convertAudioToInputArray(audioChunksToProcess) ?: return null
//            val videoFloatArray = convertVideoToInputArray(videoFramesToProcess) ?: return null
//            val speakerDummyArray = Array(1) { Array(4) { FloatArray(128) { 0.0f } } }  // 필요시 바꾸세요
//
//            Log.d(TAG, "데이터 준비 완료. 변환 시간: ${System.currentTimeMillis() - start}")
//            return Triple(audioFloatArray, videoFloatArray, speakerDummyArray)
//        }
//        return null
//    }
//
//    private fun convertAudioToInputArray(audioChunks: List<ByteArray>): Array<FloatArray>? {
//        try {
//            val combinedBytes = ByteArray(targetAudioSamples * audioBytesPerSample)
//            var currentPos = 0
//            for (chunk in audioChunks) {
//                System.arraycopy(chunk, 0, combinedBytes, currentPos, chunk.size)
//                currentPos += chunk.size
//            }
//
//            val shortBuffer = ByteBuffer.wrap(combinedBytes)
//                .order(ByteOrder.nativeOrder())
//                .asShortBuffer()
//
//            val floatArray = FloatArray(targetAudioSamples) // 16000
//            for (i in 0 until targetAudioSamples) {
//                floatArray[i] = shortBuffer.get(i) / 32767.0f
//            }
//
//            return arrayOf(floatArray) // [1, 16000]
//        } catch (e: Exception) {
//            Log.e(TAG, "오디오 변환 실패", e)
//            return null
//        }
//    }
//
//    private fun convertVideoToInputArray(videoFrames: List<Bitmap>): Array<Array<Array<FloatArray>>>? {
//        val frameCount = videoFrames.size
//
//        if (frameCount != 25) return null
//
//        val result = Array(1) { // batch
//            Array(25) { frameIndex ->
//                Array(frameSize) { h ->
//                    FloatArray(frameSize) { w ->
//                        val pixel = videoFrames[frameIndex][w, h]
//                        val r = Color.red(pixel)
//                        val g = Color.green(pixel)
//                        val b = Color.blue(pixel)
//                        val gray = (0.299 * r + 0.587 * g + 0.114 * b).toFloat() / 255.0f
//                        gray
//                    }
//                }
//            }
//        }
//
//        return result
//    }
//}