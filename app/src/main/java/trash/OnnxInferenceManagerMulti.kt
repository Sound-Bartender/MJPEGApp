//package trash
//
//import android.content.Context
//import android.util.Log
//import ai.onnxruntime.* // ORT 클래스 임포트
//import kr.goldenmine.mjpegapp.util.floatBufferToPcm16ByteArray
//import java.nio.FloatBuffer // 입력 데이터용
//
//// 두 개의 ONNX 모델을 순차적으로 추론하는 싱글톤 객체
//object OnnxInferenceManagerMulti {
//    private const val TAG = "OnnxInferenceMulti"
//    // <<<--- 실제 모델 파일명으로 변경하세요! --- >>>
//    private const val VIDEO_MODEL_FILE = "IIANetVideo_infer.onnx" // 비디오 특징 추출 모델
//    private const val AUDIO_MODEL_FILE = "IIANetAudio_infer_11.onnx" // 오디오 처리 모델
//
//    // --- 모델 입/출력 이름 및 형태 정의 (반드시 실제 모델에 맞게 수정!) ---
//    // 비디오 모델 (VideoModel)
//    private const val VID_INPUT_NAME = "video" // 비디오 모델의 비디오 입력 이름 (예시)
//    private val vidInputShape = longArrayOf(1L, 1L, 25L, 88L, 88L)
//    // 비디오 모델의 출력 이름이자, 오디오 모델의 입력 이름 중 하나
//    private const val VID_OUTPUT_EMB_NAME = "video_emb" // 예시, 실제 이름 확인 필수!
//    private val vidOutputEmbShape = longArrayOf(1L, 512L, 25L)
//
//    // 오디오 모델 (AudioModel)
//    private const val AUD_INPUT_WAV_NAME = "input_wav" // 오디오 모델의 오디오 입력 이름 (예시)
//    private val audInputWavShape = longArrayOf(1L, 1L, 16000L)
//    // 오디오 모델의 임베딩 입력 이름 (비디오 모델의 출력 이름과 일치해야 함)
//    private const val AUD_INPUT_EMB_NAME = "audio_emb" // 예시, 실제 이름 확인 필수!
//    // private val audInputEmbShape = longArrayOf(1L, 512L, 25L) // 위와 동일
//
//    // 오디오 모델의 최종 오디오 출력 이름
//    private const val AUD_OUTPUT_NAME = "enhanced_wav" // 예시, 실제 이름 확인 필수!
//    // private val audOutputShape = longArrayOf(1L, 1L, 16000L) // 최종 오디오 출력 형태
//    // --- --- --- --- --- --- --- --- --- --- --- --- --- --- ---
//
//    private var ortEnvironment: OrtEnvironment? = null
//    private var videoSession: OrtSession? = null // 비디오 모델용 세션
//    private var audioSession: OrtSession? = null // 오디오 모델용 세션
//
//    @Volatile
//    private var isInitialized = false
//
//    /**
//     * ONNX Runtime 환경 및 두 개의 세션(비디오, 오디오)을 초기화합니다.
//     * Application 클래스의 onCreate() 등 앱 시작 시점에 한 번만 호출해야 합니다.
//     */
//    fun initialize(context: Context) {
//        if (isInitialized) {
//            Log.d(TAG, "이미 초기화되었습니다.")
//            return
//        }
//        synchronized(this) {
//            if (isInitialized) return
//
//            try {
//                ortEnvironment = OrtEnvironment.getEnvironment()
//                val sessionOptionsAudio = OrtSession.SessionOptions().apply {
//                    setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE)
//                    // 필요 시 Execution Provider 설정 (예: NNAPI)
//                     try { addNnapi() } catch (e: Exception) { Log.w(TAG, "NNAPI 설정 실패", e) }
//                    enableProfiling("onnx_profile.json")
//                }
//
//                val sessionOptionsVideo = OrtSession.SessionOptions().apply {
//                    // 필요 시 Execution Provider 설정 (예: NNAPI)
//                     try { addNnapi() } catch (e: Exception) { Log.w(TAG, "NNAPI 설정 실패", e) }
//                }
//
//                val appContext = context.applicationContext
//                // 두 모델 파일 로드
//                val videoModelBytes = appContext.assets.open(VIDEO_MODEL_FILE).readBytes()
//                val audioModelBytes = appContext.assets.open(AUDIO_MODEL_FILE).readBytes()
//
//                // 각 모델에 대한 세션 생성
//                videoSession = ortEnvironment?.createSession(videoModelBytes, sessionOptionsVideo)
//                logModelInfo("Video Model", videoSession)
//                audioSession = ortEnvironment?.createSession(audioModelBytes, sessionOptionsAudio)
//
//                // 두 세션이 모두 성공적으로 생성되었는지 확인
//                if (videoSession != null && audioSession != null) {
//                    isInitialized = true
//                    Log.i(TAG, "ONNX Runtime 초기화 성공 (Video & Audio 모델).")
//                    // 각 모델의 입출력 정보 로깅
//                    logModelInfo("Video Model", videoSession)
//                    logModelInfo("Audio Model", audioSession)
//                } else {
//                    throw IllegalStateException("ONNX 세션 생성 실패 (Video 또는 Audio 모델)")
//                }
//
//            } catch (e: Exception) {
//                Log.e(TAG, "ONNX Runtime 초기화 실패", e)
//                close() // 실패 시 자원 정리
//            }
//        }
//    }
//
//    // 모델 정보 로깅 헬퍼 함수
//    private fun logModelInfo(modelName: String, session: OrtSession?) {
//        session?.let { sess ->
//            Log.d(TAG, "--- ONNX 모델 정보 ($modelName) ---")
//            Log.d(TAG, "[입력 정보]")
//            sess.inputNames.forEach { name ->
//                val info = sess.inputInfo[name]?.info as? TensorInfo
//                Log.d(TAG, "  - 이름: $name, 형태: ${info?.shape?.contentToString()}, 타입: ${info?.type}")
//            }
//            Log.d(TAG, "[출력 정보]")
//            sess.outputNames.forEach { name ->
//                val info = sess.outputInfo[name]?.info as? TensorInfo
//                 Log.d(TAG, "  - 이름: $name, 형태: ${info?.shape?.contentToString()}, 타입: ${info?.type}")
//            }
//            Log.d(TAG, "--------------------------")
//        } ?: Log.w(TAG, "$modelName 세션이 null입니다.")
//    }
//
//    /**
//     * 비디오 및 오디오 입력을 받아 두 모델을 순차적으로 추론하고 최종 오디오 결과를 반환합니다.
//     * @param audioInputData 오디오 입력 데이터 (FloatBuffer, 크기: 16000)
//     * @param videoInputData 비디오 입력 데이터 (FloatBuffer, 크기: 1*25*112*112 = 313600)
//     * @return 추론 결과 오디오 (FloatArray, 크기: 16000) 또는 오류 시 null
//     */
//    fun runInference(
//        audioInputData: FloatBuffer,
//        videoInputData: FloatBuffer
//    ): ByteArray? {
//
//        if (!isInitialized || ortEnvironment == null || videoSession == null || audioSession == null) {
//            Log.e(TAG, "추론 실패: 초기화되지 않았거나 세션이 유효하지 않습니다.")
//            return floatBufferToPcm16ByteArray(audioInputData)
//        }
//
//        // 입력 버퍼 크기 검증 (선택 사항)
//        // if (audioInputData.remaining() != audInputWavShape.last().toInt()) { ... }
//        // if (videoInputData.remaining() != vidInputShape.drop(2).reduce { a, b -> a * b }.toInt()) { ... }
//
//
//        // 자원 해제를 위한 변수 선언 (finally 블록에서 사용)
//        var videoInputTensor: OnnxTensor? = null
//        var videoResult: OrtSession.Result? = null
//        var mouthEmbTensor: OnnxTensor? = null // Video -> Audio 전달되는 텐서
//        var audioInputWavTensor: OnnxTensor? = null
//        var audioResult: OrtSession.Result? = null
//        var finalAudioOutputTensor: OnnxTensor? = null
//
//        try {
//            val env = ortEnvironment!! // 초기화 보장됨
//
//            videoInputData.rewind()
//            audioInputData.rewind()
//
//            // --- 1단계: 비디오 모델 추론 ---
//            Log.d(TAG, "1단계: 비디오 모델 추론 시작...")
//            val time = System.currentTimeMillis()
//            videoInputTensor = OnnxTensor.createTensor(env, videoInputData, vidInputShape)
//            val videoInputs = mapOf(VID_INPUT_NAME to videoInputTensor)
//
//            videoResult = videoSession!!.run(videoInputs) // 비디오 모델 실행
//
//            // 비디오 모델 출력 (Mouth Embedding) 텐서 가져오기
//            // videoResult에서 얻은 텐서는 videoResult가 닫힐 때 같이 닫힐 수 있으므로 주의
//            // 여기서는 우선 참조만 얻음
//            mouthEmbTensor = videoResult.get(VID_OUTPUT_EMB_NAME)?.get() as? OnnxTensor
//                ?: throw OrtException(OrtException.OrtErrorCode.ORT_RUNTIME_EXCEPTION, "비디오 모델 출력 '$VID_OUTPUT_EMB_NAME' 찾기 실패")
//            Log.d(TAG, "1단계: 비디오 모델 추론 완료. 임베딩 텐서 획득.")
//
//
//            // --- 2단계: 오디오 모델 추론 ---
//            Log.d(TAG, "2단계: 오디오 모델 추론 시작... ")
//            val time2 = System.currentTimeMillis()
//            audioInputWavTensor = OnnxTensor.createTensor(env, audioInputData, audInputWavShape)
//
//            // 오디오 모델 입력 구성 (원본 오디오 + 비디오 임베딩)
//            val audioInputs = mapOf(
//                AUD_INPUT_WAV_NAME to audioInputWavTensor,
//                AUD_INPUT_EMB_NAME to mouthEmbTensor // 비디오 모델 출력을 입력으로 사용
//            )
//
//            audioResult = audioSession!!.run(audioInputs) // 오디오 모델 실행
//
//            // 최종 오디오 출력 텐서 가져오기
//            finalAudioOutputTensor = audioResult.get(AUD_OUTPUT_NAME)?.get() as? OnnxTensor
//                ?: throw OrtException(OrtException.OrtErrorCode.ORT_RUNTIME_EXCEPTION, "오디오 모델 출력 '$AUD_OUTPUT_NAME' 찾기 실패")
//            Log.d(TAG, "2단계: 오디오 모델 추론 완료. 최종 오디오 텐서 획득.")
//
//            // --- 3단계: 결과 추출 ---
//            val outputBuffer = finalAudioOutputTensor.floatBuffer
////            outputBuffer.rewind()
////            val outputArray = FloatArray(outputBuffer.remaining())
////            outputBuffer.get(outputArray)
//            Log.d(TAG, "3단계: 최종 오디오 데이터 추출 완료. ${System.currentTimeMillis() - time} ${time2 - time}")
//
//            return floatBufferToPcm16ByteArray(outputBuffer) // 성공 시 결과 반환
//        } catch (e: OrtException) {
//            Log.e(TAG, "ONNX Runtime 추론 중 오류 발생", e)
//            return null
//        } catch (e: Exception) {
//            Log.e(TAG, "추론 중 일반 예외 발생", e)
//            return null
//        } finally {
//            // --- 4단계: 사용된 모든 자원 명시적 해제 (매우 중요!) ---
//            // AutoCloseable 인터페이스를 구현하므로 .close() 호출
//            // 생성된 순서의 역순 또는 생성/획득 시점과 무관하게 닫아도 일반적으로 문제 없음
//            Log.d(TAG, "4단계: 추론 관련 자원 해제 시작...")
////            finalAudioOutputTensor?.close()
//            audioResult?.close() // Result를 닫으면 내부 텐서(finalAudioOutputTensor 참조)도 해제될 수 있음
//            audioInputWavTensor?.close()
////            mouthEmbTensor?.close() // 비디오 모델 출력 텐서도 명시적 해제
//            videoResult?.close() // 비디오 Result 닫기 (mouthEmbTensor 참조 해제될 수 있음)
//            videoInputTensor?.close()
//            Log.d(TAG, "4단계: 추론 관련 자원 해제 완료.")
//        }
//    }
//
//    /**
//     * ONNX Runtime 관련 모든 자원(세션, 환경)을 해제합니다.
//     * 앱 종료 시 호출하는 것이 좋습니다.
//     */
//    fun close() {
//        synchronized(this) {
//            if (!isInitialized) return
//
//            try {
//                Log.d(TAG, "ONNX Runtime 자원 해제 시도...")
//                // 세션들을 먼저 닫음
//                audioSession?.close()
//                videoSession?.close()
//                // 환경은 마지막에 닫음
//                ortEnvironment?.close()
//                audioSession = null
//                videoSession = null
//                ortEnvironment = null
//                isInitialized = false
//                Log.i(TAG, "ONNX Runtime 자원 해제 완료.")
//            } catch (e: Exception) {
//                Log.e(TAG, "ONNX Runtime 자원 해제 중 오류 발생", e)
//            }
//        }
//    }
//}