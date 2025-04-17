//package kr.goldenmine.mjpegapp.ai
//
//import android.content.Context
//import android.util.Log
//import ai.onnxruntime.* // ONNX Runtime 클래스 임포트
//import kr.goldenmine.mjpegapp.util.floatBufferToPcm16ByteArray
//import kr.goldenmine.mjpegapp.util.normalizeAndConvertToPcm16ByteArray
//import java.nio.ByteBuffer
//import java.nio.ByteOrder
//import java.nio.FloatBuffer // FloatBuffer 사용 (ORT와 직접 호환)
//import kotlin.math.abs
//import kotlin.math.max
//import kotlin.math.min
//
//// ONNX 추론을 위한 싱글톤 객체
//object AIInference {
//
//    private const val TAG = "AIInference"
//    // <<<--- 실제 ONNX 모델 파일명으로 변경하세요! --- >>>
//    private const val MODEL_FILE = "davse_sim.onnx"
//
//    // <<<--- 실제 모델의 입출력 이름으로 변경하세요! (Netron 등으로 확인) --- >>>
//    private const val INPUT_AUDIO_NAME = "input.1" // 오디오 입력 이름 예시
//    private const val INPUT_VIDEO_NAME = "input.5" // 비디오 입력 이름 예시
//    private const val OUTPUT_NAME = "1346"         // 출력 이름 예시
//
//    // 모델의 입력 및 출력 형태 정의 (이전 대화 내용 기반)
//    // 형태는 Long 타입 배열로 지정
//    private val audioInputShape = longArrayOf(1L, 1L, 16000L)
//    // 비디오 입력 형태 [1, 1, 25, 112, 112] - 모델이 이 형태를 사용하는지 반드시 확인 필요!
//    private val videoInputShape = longArrayOf(1L, 1L, 25L, 112L, 112L)
//    // 예상 출력 형태
//    private val outputShape = longArrayOf(1L, 1L, 16000L)
//
//    private var ortEnvironment: OrtEnvironment? = null
//    private var ortSession: OrtSession? = null
//
//    // 초기화 완료 여부 플래그
//    @Volatile // 멀티스레드 환경에서의 가시성 보장
//    private var isInitialized = false
//
//    /**
//     * ONNX Runtime 환경 및 세션을 초기화합니다.
//     * Application 클래스의 onCreate() 등 앱 시작 시점에 한 번만 호출해야 합니다.
//     * @param context 애플리케이션 컨텍스트 (메모리 누수 방지)
//     */
//    fun initialize(context: Context) {
//        // 이미 초기화되었다면 중복 실행 방지
//        if (isInitialized) {
//            Log.d(TAG, "ONNX Runtime Manager가 이미 초기화되었습니다.")
//            return
//        }
//
//        synchronized(this) { // 동기화 블록으로 스레드 안전성 확보
//            // 동기화 블록 내에서 다시 한번 체크 (Double-checked locking 유사 패턴)
//            if (isInitialized) return
//
//            try {
//                ortEnvironment = OrtEnvironment.getEnvironment()
//                val sessionOptions = OrtSession.SessionOptions().apply {
//                    // 필요 시 Execution Provider 설정 (NNAPI, CoreML 등)
//                    // try { addNnapi() } catch (e: Exception) { Log.w(TAG, "NNAPI 설정 실패", e) }
//                    // try { addCoreML() } catch (e: Exception) { Log.w(TAG, "CoreML 설정 실패", e) }
//
//                    // 필요 시 스레드 수 등 다른 옵션 설정
//                    // setIntraOpNumThreads(4)
//                }
//
//                // assets 폴더에서 모델 파일 로드
//                val modelBytes = context.applicationContext.assets.open(MODEL_FILE).readBytes()
//
//                // ORT 세션 생성
//                ortSession = ortEnvironment?.createSession(modelBytes, sessionOptions)
//
//                isInitialized = true // 초기화 완료 플래그 설정
//                Log.i(TAG, "ONNX Runtime 초기화 성공. 세션이 생성되었습니다.")
//
//                // 생성된 세션의 입출력 정보 로깅 (디버깅에 유용)
//                logModelInfo()
//
//            } catch (e: Exception) {
//                Log.e(TAG, "ONNX Runtime 초기화 실패", e)
//                // 초기화 실패 시 자원 해제 시도
//                close()
//            }
//        }
//    }
//
//    /**
//     * 로드된 모델의 입력 및 출력 정보 로깅 (디버깅용)
//     */
//    private fun logModelInfo() {
//        ortSession?.let { session ->
//            Log.d(TAG, "--- 로드된 ONNX 모델 정보 ---")
//            Log.d(TAG, "[입력 정보]")
//            session.inputNames.forEach { name ->
//                val info = session.inputInfo[name]?.info as? TensorInfo
//                Log.d(TAG, "  - 이름: $name, 형태: ${info?.shape?.contentToString()}, 타입: ${info?.type}")
//            }
//            Log.d(TAG, "[출력 정보]")
//            session.outputNames.forEach { name ->
//                val info = session.outputInfo[name]?.info as? TensorInfo
//                Log.d(TAG, "  - 이름: $name, 형태: ${info?.shape?.contentToString()}, 타입: ${info?.type}")
//            }
//            Log.d(TAG, "--------------------------")
//        }
//    }
//
//    /**
//     * ONNX 모델 추론을 실행합니다.
//     * @param audioInputData 오디오 입력 데이터 (FloatBuffer, 크기: 16000)
//     * @param videoInputData 비디오 입력 데이터 (FloatBuffer, 크기: 1*25*112*112 = 313600)
//     * @return 추론 결과 (FloatArray, 크기: 16000) 또는 오류 발생 시 null
//     */
//    fun runInference(
//        audioInputData: FloatBuffer,
//        videoInputData: FloatBuffer
//    ): ByteArray? {
//
//        // 초기화 확인
//        if (!isInitialized || ortEnvironment == null || ortSession == null) {
//            Log.e(TAG, "추론 실패: ONNX Runtime이 초기화되지 않았습니다.")
//            return null
//        }
//
//        // 입력 버퍼 크기 기본 검증 (선택 사항이지만 안전)
//        val expectedAudioSize = audioInputShape.last().toInt() // 16000
//        if (audioInputData.limit() != expectedAudioSize) { // capacity() 대신 limit() 또는 remaining() 체크가 더 적절할 수 있음
//            Log.e(TAG, "오디오 입력 데이터 크기 불일치: 필요=${expectedAudioSize}, 제공됨=${audioInputData.limit()}")
//            // return null // 또는 에러 처리
//        }
//        val expectedVideoSize = videoInputShape.drop(2).reduce { acc, l -> acc * l }.toInt() // 25*112*112
//        if (videoInputData.limit() != expectedVideoSize) {
//            Log.e(TAG, "비디오 입력 데이터 크기 불일치: 필요=${expectedVideoSize}, 제공됨=${videoInputData.limit()}")
//            // return null // 또는 에러 처리
//        }
//
//
//        var result: OrtSession.Result? = null
//        var audioTensor: OnnxTensor? = null
//        var videoTensor: OnnxTensor? = null
//        var outputTensor: OnnxTensor? = null
//
//        try {
//            // 1. 입력 텐서 생성 (FloatBuffer 사용 시 rewind() 필수!)
//            audioInputData.rewind()
//            videoInputData.rewind()
//
////                                 mouth_emb = self.videomodel({
////                                    'input.1': video_np.astype(np.float32),
////                                })[0]
////                                # print('mouth_emb:', type(mouth_emb), len(mouth_emb))
////                                # 'input_wav', 'mouth_emb'
////                                # est_sources = self.audiomodel(audio_tensor[None], mouth_emb)
////                                est_sources = self.audiomodel({
////                                    'input_wav': audio_np[None, None].astype(np.float32),
////                                    'mouth_emb': mouth_emb,
////                                })[0]
//            audioTensor = OnnxTensor.createTensor(ortEnvironment, audioInputData, audioInputShape)
//            videoTensor = OnnxTensor.createTensor(ortEnvironment, videoInputData, videoInputShape)
//
//            // 2. 입력 맵 구성
//            val inputs = mapOf(
//                INPUT_AUDIO_NAME to audioTensor,
//                INPUT_VIDEO_NAME to videoTensor
//            )
//
//            // 3. 추론 실행 (세션은 스레드 안전함)
//            result = ortSession?.run(inputs)
//
//            // 4. 결과 텐서 추출
//            // get()의 결과는 AutoCloseable이므로 use 블록 사용 권장
//            outputTensor = result?.get(OUTPUT_NAME)?.get() as? OnnxTensor
//                ?: throw OrtException(OrtException.OrtErrorCode.ORT_RUNTIME_EXCEPTION, "출력 텐서 '$OUTPUT_NAME'를 찾을 수 없거나 타입[OnnxTensor]이 아닙니다.")
//
//            // 5. 결과 데이터 추출 (FloatBuffer -> FloatArray)
//            val outputBuffer = outputTensor.floatBuffer
//            outputBuffer.rewind() // 버퍼 포지션 초기화 중요!
//
////            val outputArray = FloatArray(outputBuffer.remaining()) // 버퍼의 남은 요소만큼 배열 생성
////            outputBuffer.get(outputArray) // 배열에 복사
////            outputBuffer.rewind()
////            if (outputArray != null) {
////                val minVal = outputArray.minOrNull() ?: Float.NaN
////                val maxVal = outputArray.maxOrNull() ?: Float.NaN
////                val avgVal = outputArray.average().toFloat()
////                Log.d(TAG, "추론 결과 FloatArray: Min=$minVal, Max=$maxVal, Avg=$avgVal, Size=${outputArray.size}")
////            }
//
//            Log.e(TAG, "ONNX Runtime 추론 완료")
//            return normalizeAndConvertToPcm16ByteArray(outputBuffer)
//        } catch (e: OrtException) {
//            Log.e(TAG, "ONNX Runtime 추론 중 오류 발생", e)
//            return null
//        } catch (e: Exception) {
//            Log.e(TAG, "추론 중 일반 예외 발생", e)
//            return null
//        } finally {
//            // 6. 사용한 모든 AutoCloseable 자원들(.use{} 블록 미사용 시) 명시적 해제 - 매우 중요!
//            audioTensor?.close()
//            videoTensor?.close()
//            outputTensor?.close() // 결과 텐서도 닫아야 함
//            result?.close()       // Result 객체도 닫아야 함
//        }
//    }
//
//    /**
//     * ONNX Runtime 관련 자원을 해제합니다. 앱 종료 시 호출하는 것이 좋습니다.
//     */
//    fun close() {
//        synchronized(this) { // 동기화
//            if (!isInitialized) return
//
//            try {
//                // 세션을 먼저 닫는 것이 좋음
//                ortSession?.close()
//                ortEnvironment?.close() // 환경은 가장 마지막에
//                ortSession = null
//                ortEnvironment = null
//                isInitialized = false // 플래그 리셋
//                Log.i(TAG, "ONNX Runtime 자원 해제 완료.")
//            } catch (e: Exception) {
//                Log.e(TAG, "ONNX Runtime 자원 해제 중 오류 발생", e)
//            }
//        }
//    }
//}