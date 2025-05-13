//package trash
//
//import android.content.Context
//import android.util.Log
//import org.tensorflow.lite.Interpreter
//import org.tensorflow.lite.gpu.GpuDelegate
//import java.nio.channels.FileChannel
//
//// 두 개의 ONNX 모델을 순차적으로 추론하는 싱글톤 객체
//object TFLiteInferenceManager {
//    private const val TAG = "TFLiteInferenceManager"
//
//    private lateinit var interpreter: Interpreter
//
//    fun loadModelFile(context: Context) {
////        val modelBuffer = loadModelFile(context)
//        val fileDescriptor = context.assets.openFd("IIANetAudio.tflite")
//        val inputStream = fileDescriptor.createInputStream()
//        val fileChannel = inputStream.channel
//        val startOffset = fileDescriptor.startOffset
//        val declaredLength = fileDescriptor.declaredLength
//
//
//// 1. GPU delegate (우선 시도)
////        try {
////            val gpuDelegate = GpuDelegate()
////            options.addDelegate(gpuDelegate)
////            Log.d("TFLite", "GPU Delegate 사용 설정 완료")
////        } catch (e: Exception) {
////            Log.w("TFLite", "GPU Delegate 사용 실패, NNAPI 시도 중", e)
////
//////            // 2. NNAPI delegate fallback
//////            try {
//////                val nnapiDelegate = NnApiDelegate()
//////                options.addDelegate(nnapiDelegate)
//////                Log.d("TFLite", "NNAPI Delegate 사용 설정 완료")
//////            } catch (ee: Exception) {
//////                Log.w("TFLite", "NNAPI Delegate도 실패. CPU fallback", ee)
//////            }
////        }
//        try {
//            val options = Interpreter.Options().apply {
//                addDelegate(GpuDelegate())
//            }
//
//
//            interpreter = Interpreter(
//                fileChannel.map(
//                    FileChannel.MapMode.READ_ONLY,
//                    startOffset,
//                    declaredLength
//                ), options
//            )
//        } catch(ex: Exception) {
//            Log.e(TAG, ex.message, ex)
//        }
//    }
//
//    fun runInference(
//        audioInput: Array<FloatArray>, // [1, 16000]
//        videoInput: Array<Array<Array<FloatArray>>>, // [1, 25, 112, 112]
//        speakerInput: Array<Array<FloatArray>> // [1, 4, 128]
//    ): Triple<Array<Array<FloatArray>>, FloatArray, Array<Array<FloatArray>>> {
//        // 출력 준비
//        val estAEmb = Array(1) { Array(4) { FloatArray(800) } }
//        val estSource = FloatArray(16000)
//        val newSpkEmb = Array(1) { Array(4) { FloatArray(128) } }
//
//        val inputs = mapOf(
//            "audio" to audioInput,
//            "video" to videoInput,
//            "speaker_in" to speakerInput
//        )
//
//        val outputs = mapOf(
//            0 to estAEmb,
//            1 to estSource,
//            2 to newSpkEmb
//        )
//
//        interpreter.runForMultipleInputsOutputs(inputs.values.toTypedArray(), outputs)
//
//        return Triple(estAEmb, estSource, newSpkEmb)
//    }
//}