package kr.goldenmine.mjpegapp.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.facedetector.FaceDetectorResult

object FaceProcessor {

    private const val TAG = "FaceProcessor"
    private const val FACE_MODEL = "blaze_face_short_range.tflite" // assets 폴더에 있는 모델 파일명
    private const val TARGET_WIDTH = 88
    private const val TARGET_HEIGHT = 88
    private var faceDetector: FaceDetector? = null

    fun initialize(context: Context) {
//        // 2. MediaPipe FaceDetector 설정 및 생성
        val optionsBuilder = FaceDetector.FaceDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(FACE_MODEL)
//                    .setDelegate(Delegate.GPU)
                    .build()
            )
            .setRunningMode(RunningMode.IMAGE) // 단일 이미지 처리 모드
            .setMinDetectionConfidence(0.5f) // 최소 감지 신뢰도 (0.0 ~ 1.0)

        val options = optionsBuilder.build()

        try {
            faceDetector = FaceDetector.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e(TAG, "FaceDetector 생성 실패: ${e.message}", e)
        }
        Log.d(TAG, "FaceDetector 초기화 완료")
    }
    /**
     * ByteArray 이미지를 받아 얼굴 감지, 크롭, 리사이징을 수행합니다.
     *
     * @param imageData 이미지 데이터 (JPEG, PNG 등 Bitmap으로 디코딩 가능한 형식)
     * @param context Android Context
     * @return 112x112 크기로 크롭 및 리사이징된 얼굴 Bitmap. 얼굴 미감지 또는 오류 시 null 반환.
     */
    fun detectCropResizeFace(imageData: ByteArray, context: Context): Bitmap? {

        // 1. ByteArray -> Bitmap 변환
        val originalBitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            ?: run {
                Log.e(TAG, "이미지 데이터(ByteArray)를 Bitmap으로 디코딩 실패")
                return null
            }
        initialize(context)
        if(faceDetector == null) {
            return Bitmap.createScaledBitmap(
                originalBitmap,
                TARGET_WIDTH,
                TARGET_HEIGHT,
                true // true: filter 적용 (부드러운 보간)
            )
        }

        Log.d(TAG, "원본 Bitmap 크기: ${originalBitmap.width}x${originalBitmap.height}")
        // 입력 이미지 크기가 480x360이라고 가정. 필요 시 크기 검증 로직 추가 가능

        var croppedResizedBitmap: Bitmap? = null

        faceDetector.use { detector -> // 'use' 블록으로 자원 자동 해제
            // 3. MediaPipe 입력 형식으로 변환 (MPImage)
            val mpImage = BitmapImageBuilder(originalBitmap).build()

            // 4. 얼굴 감지 실행
            val results: FaceDetectorResult? = try {
                detector?.detect(mpImage)
            } catch (e: Exception) {
                Log.e(TAG, "얼굴 감지 중 오류 발생: ${e.message}", e)
                null // 오류 발생 시 null 반환
            }


            // 5. 결과 처리: 첫 번째 감지된 얼굴 찾기
            val firstDetection = results?.detections()?.firstOrNull()

            if (firstDetection != null) {
                // 6. 감지된 얼굴의 Bounding Box 가져오기
                val boundingBox = firstDetection.boundingBox()
                Log.d(TAG, "Raw BoundingBox: left=${boundingBox.left}, top=${boundingBox.top}, width=${boundingBox.width()}, height=${boundingBox.height()}")

                // 계산된 너비/높이가 유효한지 확인
                if (boundingBox.width() > 0 && boundingBox.height() > 0) {
                    try {
                        // 8. 원본 Bitmap에서 얼굴 영역 크롭
                        val croppedBitmap = Bitmap.createBitmap(
                            originalBitmap,
                            boundingBox.left.toInt(),
                            boundingBox.top.toInt(),
                            boundingBox.width().toInt(),
                            boundingBox.height().toInt(),
                        )

                        // Log.d(TAG, "크롭된 Bitmap 크기: ${croppedBitmap.width}x${croppedBitmap.height}")

                        // 9. 크롭된 이미지를 112x112 크기로 리사이징 (Bi-linear interpolation 사용)
                        croppedResizedBitmap = Bitmap.createScaledBitmap(
                            croppedBitmap,
                            TARGET_WIDTH,
                            TARGET_HEIGHT,
                            true // true: filter 적용 (부드러운 보간)
                        )

                        // 메모리 관리를 위해 중간 단계의 비트맵은 필요 시 recycle (선택 사항)
                        // if (croppedBitmap != originalBitmap && !croppedBitmap.isRecycled) {
                        //    croppedBitmap.recycle()
                        // }

                        // Log.d(TAG, "최종 리사이즈된 Bitmap 크기: ${croppedResizedBitmap.width}x${croppedResizedBitmap.height}")

                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Bitmap 크롭 또는 리사이징 실패: ${e.message}", e)
                        // croppedResizedBitmap은 null로 유지됨
                    } catch (e: Exception) {
                        Log.e(TAG, "Bitmap 처리 중 예외 발생: ${e.message}", e)
                        // croppedResizedBitmap은 null로 유지됨
                    }
                } else {
                    Log.w(TAG, "계산된 크롭 영역이 유효하지 않음")
                }

            } else {
                Log.w(TAG, "이미지에서 얼굴을 감지하지 못했습니다.")
            }
        } // faceDetector.use 블록 끝 (자동 close 호출)

        // 원본 비트맵은 여기서 recycle하지 않습니다. 함수 외부에서 관리해야 할 수 있습니다.
        // originalBitmap.recycle() // 필요 시 호출

        return croppedResizedBitmap
    }
}