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
import androidx.core.graphics.scale
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

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
        if(faceDetector == null) {
            return originalBitmap.scale(TARGET_WIDTH, TARGET_HEIGHT)
        }
        Log.d(TAG, "원본 Bitmap 크기: ${originalBitmap.width}x${originalBitmap.height}")

        // 입력 이미지 크기가 480x360이라고 가정. 필요 시 크기 검증 로직 추가 가능
        var croppedResizedBitmap: Bitmap? = null

        // 3. MediaPipe 입력 형식으로 변환 (MPImage)
        val mpImage = BitmapImageBuilder(originalBitmap).build()

        // 4. 얼굴 감지 실행
        val results: FaceDetectorResult? = try {
            faceDetector?.detect(mpImage)
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
                    croppedResizedBitmap = cropPadAndResize(
                        originalBitmap,
                        boundingBox,
                    )
                    Log.d(TAG, "최종 리사이즈된 Bitmap 크기: ${croppedResizedBitmap.width}x${croppedResizedBitmap.height}")
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "Bitmap 크롭 또는 리사이징 실패: ${e.message}", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Bitmap 처리 중 예외 발생: ${e.message}", e)
                }
            } else {
                Log.w(TAG, "계산된 크롭 영역이 유효하지 않음")
            }

        } else {
            Log.w(TAG, "이미지에서 얼굴을 감지하지 못했습니다.")
        }

        if(croppedResizedBitmap != null) {
            return croppedResizedBitmap
        } else {
            return originalBitmap.scale(TARGET_WIDTH, TARGET_HEIGHT)
        }
    }
}

//fun cropAndPadBitmap(
//    bitmap: Bitmap,
//    left: Int,
//    top: Int,
//    width: Int,
//    height: Int,
//    targetSize: Int = 88
//): Bitmap {
//    // 결과 비트맵 (검정색으로 초기화)
//    val result = createBitmap(targetSize, targetSize)
//    val canvas = Canvas(result)
//    canvas.drawColor(Color.BLACK) // 배경을 검정색으로 채움
//
//    // 실제 크롭 가능한 원본 이미지 영역
//    val srcLeft = left.coerceIn(0, bitmap.width)
//    val srcTop = top.coerceIn(0, bitmap.height)
//    val srcRight = (left + width).coerceAtMost(bitmap.width)
//    val srcBottom = (top + height).coerceAtMost(bitmap.height)
//
//    val srcRect = Rect(srcLeft, srcTop, srcRight, srcBottom)
//
//    // 잘린 만큼 결과 비트맵 안의 위치를 계산 (왼쪽, 위쪽이 음수였다면 패딩되는 위치)
//    val dstLeft = (srcLeft - left).coerceAtLeast(0)
//    val dstTop = (srcTop - top).coerceAtLeast(0)
//    val dstRight = dstLeft + (srcRight - srcLeft)
//    val dstBottom = dstTop + (srcBottom - srcTop)
//
//    val dstRect = Rect(dstLeft, dstTop, dstRight, dstBottom)
//
//    // 비트맵 그리기
//    canvas.drawBitmap(bitmap, srcRect, dstRect, null)
//
//    return result
//}

/**
 * 원본 이미지에서 바운딩 박스 영역을 잘라내고, 경계를 벗어나는 부분은 검은색으로 채운 후,
 * 목표 크기로 리사이즈하는 함수.
 *
 * @param originalBitmap 원본 비트맵.
 * @param boundingBox 감지된 바운딩 박스 (RectF).
 * @param targetWidth 최종 결과 비트맵의 너비 (예: 88).
 * @param targetHeight 최종 결과 비트맵의 높이 (예: 88).
 * @return 처리된 최종 비트맵.
 */
fun cropPadAndResize(
    originalBitmap: Bitmap,
    boundingBox: RectF,
    targetWidth: Int = 88,
    targetHeight: Int = 88
): Bitmap {
    val paint = Paint().apply {
        isAntiAlias = true // 부드러운 렌더링
        isFilterBitmap = true // 리사이징 시 필터링 적용
    }

    // 0. 바운딩 박스의 너비와 높이가 유효한지 확인
    if (boundingBox.width() <= 0 || boundingBox.height() <= 0) {
        // 유효하지 않은 바운딩 박스이면 검은색의 타겟 크기 비트맵 반환
        val blackBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(blackBitmap)
        canvas.drawColor(Color.BLACK)
        return blackBitmap
    }

    // 1. 바운딩 박스 크기의 비트맵을 생성하고 검은색으로 채웁니다.
    // 이 비트맵이 크롭 및 패딩의 중간 결과물이 됩니다.
    val intermediateWidth = boundingBox.width().toInt()
    val intermediateHeight = boundingBox.height().toInt()

    // 중간 비트맵 크기가 0 이하이면 문제 발생 방지
    if (intermediateWidth <= 0 || intermediateHeight <= 0) {
        Log.w("CropPadResize", "Intermediate bitmap size is invalid. Returning black bitmap.")
        val blackBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(blackBitmap)
        canvas.drawColor(Color.BLACK)
        return blackBitmap
    }

    val croppedPaddedBitmap = Bitmap.createBitmap(
        intermediateWidth,
        intermediateHeight,
        originalBitmap.config ?: Bitmap.Config.ARGB_8888 // 원본 비트맵 설정을 따르거나 기본값 사용
    )
    val canvas = Canvas(croppedPaddedBitmap)
    canvas.drawColor(Color.BLACK) // 배경을 검은색으로 칠함

    // 2. 원본 이미지에서 실제 복사할 영역(sourceRect) 계산
    // 바운딩 박스가 원본 이미지 경계를 벗어날 수 있으므로, 실제 겹치는 부분만 가져옴
    val actualSrcRect = RectF()
    actualSrcRect.left = max(0f, boundingBox.left)
    actualSrcRect.top = max(0f, boundingBox.top)
    actualSrcRect.right = min(originalBitmap.width.toFloat(), boundingBox.right)
    actualSrcRect.bottom = min(originalBitmap.height.toFloat(), boundingBox.bottom)

    // 3. 검은색 배경 비트맵(croppedPaddedBitmap)에 복사될 위치(destinationRect) 계산
    // 바운딩 박스의 왼쪽 상단이 (0,0)이 되도록 sourceRect를 이동시킨 것과 같음
    val actualDstRect = RectF()
    actualDstRect.left = actualSrcRect.left - boundingBox.left
    actualDstRect.top = actualSrcRect.top - boundingBox.top
    actualDstRect.right = actualDstRect.left + actualSrcRect.width() // 실제 복사될 너비
    actualDstRect.bottom = actualDstRect.top + actualSrcRect.height() // 실제 복사될 높이

    // 4. 원본 이미지의 계산된 부분(actualSrcRect)을
    //    검은색 배경 비트맵의 계산된 위치(actualDstRect)에 그립니다.
    // actualSrcRect의 너비나 높이가 0 이하면 그릴 내용이 없음
    if (actualSrcRect.width() > 0 && actualSrcRect.height() > 0) {
        canvas.drawBitmap(
            originalBitmap,
            Rect( // drawBitmap의 src 인자는 Rect여야 함
                actualSrcRect.left.toInt(),
                actualSrcRect.top.toInt(),
                actualSrcRect.right.toInt(),
                actualSrcRect.bottom.toInt()
            ),
            actualDstRect, // dst 인자는 RectF 가능
            paint
        )
    }

    // 5. 최종적으로 croppedPaddedBitmap을 목표 크기(targetWidth x targetHeight)로 리사이즈합니다.
    return Bitmap.createScaledBitmap(croppedPaddedBitmap, targetWidth, targetHeight, true)
}