package kr.goldenmine.mjpegapp.ai

import android.content.Context
import android.util.Log
import kr.goldenmine.mjpegapp.util.convertFloatArrayToPCM16Bytes
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

object IIANetTFLite {

    private const val TAG = "IIANetTFLite"

    private lateinit var interpreterVideo: Interpreter
    private lateinit var interpreterAudio: Interpreter

    private fun loadModelFile(context: Context, modelPath: String): ByteBuffer {
        val assetFileDescriptor = context.assets.openFd(modelPath)
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        val mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        // ByteBuffer의 byte order를 native order로 설정하는 것이 중요할 수 있습니다.
        // mappedByteBuffer.order(ByteOrder.nativeOrder()) // TFLite 라이브셔리가 내부적으로 처리하는 경우가 많아 필수는 아닐 수 있음
        return mappedByteBuffer
    }

    fun loadAudioModel(context: Context) {
        val model = loadModelFile(context, "IIANetAudio_12.tflite")
        Log.i("TFLite", "mapped length = ${model.capacity()} bytes")  // 76722476 76722476
        try {
            val gpuOptions = Interpreter.Options()
            gpuOptions.addDelegate(GpuDelegate())
            interpreterAudio = Interpreter(model, gpuOptions)
        } catch(ex: Exception) {
            Log.e(TAG, ex.message, ex)
            interpreterAudio = Interpreter(model)
            Log.w(TAG, "an error occured while initializing audio model by GPU delegate. model is run by CPU.")
        }
        Log.i(TAG, "loaded audio model")
    }

    fun loadVideoModel(context: Context) {
        val model = loadModelFile(context, "IIANetVideo.tflite")
        try {
            val gpuOptions = Interpreter.Options()
            gpuOptions.addDelegate(GpuDelegate())
            interpreterVideo = Interpreter(model, gpuOptions)
        } catch(ex: Exception) {
            Log.e(TAG, ex.message, ex)
            interpreterVideo = Interpreter(model)
            Log.w(TAG, "an error occured while initializing video model by GPU delegate. model is run by CPU.")
        }
        Log.i(TAG, "loaded video model")
    }

    fun runInference(videoInputBuffer: ByteBuffer, audioInputBuffer: ByteBuffer): ByteArray {
        // [1, 1, 25, 88, 88] -> [1 * 1 * 25 * 88 * 88]
//        val videoInputBuffer = ByteBuffer.allocateDirect(1 * 1 * 25 * 88 * 88 * 4).order(ByteOrder.nativeOrder())
//        videoInput.forEach { videoInputBuffer.putFloat(it) }
//        videoInputBuffer.rewind()

        val visualOutputBuffer = ByteBuffer.allocateDirect(1 * 512 * 25 * 4).order(ByteOrder.nativeOrder())

        // Run visual model
        interpreterVideo.run(videoInputBuffer, visualOutputBuffer)
        visualOutputBuffer.rewind()

        // Prepare inputs for audio model
//        val audioInputBuffer = ByteBuffer.allocateDirect(1 * 1 * 16000 * 4).order(ByteOrder.nativeOrder())
//        audioInput.forEach { audioInputBuffer.putFloat(it) }
//        audioInputBuffer.rewind()

        val visualFeatureBuffer = visualOutputBuffer // [1, 512, 25]
        val audioOutputBuffer = ByteBuffer.allocateDirect(1 * 1 * 16000 * 4).order(ByteOrder.nativeOrder())

        val audioInputs = arrayOf(
            audioInputBuffer,      // audio waveform
            visualFeatureBuffer    // visual features from model 1
        )

        // Run audio model
        interpreterAudio.runForMultipleInputsOutputs(audioInputs, mapOf(0 to audioOutputBuffer))
        audioOutputBuffer.rewind()

        // Convert output buffer to float array
        val output = FloatArray(16000)
        audioOutputBuffer.asFloatBuffer().get(output)

        return convertFloatArrayToPCM16Bytes(output)
    }
}