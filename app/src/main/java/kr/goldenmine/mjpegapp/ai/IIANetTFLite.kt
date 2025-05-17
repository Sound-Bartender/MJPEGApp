package kr.goldenmine.mjpegapp.ai

import android.content.Context
import android.util.Log
import kr.goldenmine.mjpegapp.util.convertDirectlyToPCM16Bytes
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

    private var isRunning = false

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
        val model = loadModelFile(context, "IIANetVideo_2.tflite")
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

    fun runInference(videoInputBuffer: ByteBuffer, audioInputBuffer: ByteBuffer): ByteArray? {
        if(isRunning) return null
        synchronized(this) {
            if(isRunning) return null
            isRunning = true
        }
        // [1, 1, 25, 88, 88] -> [1 * 1 * 25 * 88 * 88]
        val time1 = System.currentTimeMillis()
        // [1, 512, 25]
        val visualOutputBuffer = ByteBuffer.allocateDirect(1 * 512 * 25 * 4).order(ByteOrder.nativeOrder())

        // Run visual model
        interpreterVideo.run(videoInputBuffer, visualOutputBuffer)
        visualOutputBuffer.rewind()
        val time2 = System.currentTimeMillis()

        // Prepare inputs for audio model
        val visualFeatureBuffer = visualOutputBuffer // [1, 512, 25]
        val audioOutputBuffer = ByteBuffer.allocateDirect(1 * 1 * 16000 * 4).order(ByteOrder.nativeOrder())
        audioOutputBuffer.rewind()

        // Run audio model
        val audioInputs = arrayOf(
            audioInputBuffer,      // audio waveform
            visualFeatureBuffer    // visual features from model 1
        )
        interpreterAudio.runForMultipleInputsOutputs(audioInputs, mapOf(0 to audioOutputBuffer))
        audioOutputBuffer.rewind()
        val time3 = System.currentTimeMillis()

        synchronized(this) {
            isRunning = false
        }

        // convert to pcm16
        val result = convertDirectlyToPCM16Bytes(audioOutputBuffer, 16000)
//        val result = convertDirectlyToPCM16Bytes(audioInputBuffer, 16000)
        val time4 = System.currentTimeMillis()

        Log.i(TAG, "time: ${time2-time1} ${time3-time2} ${time4-time3}")

        return result
    }
}