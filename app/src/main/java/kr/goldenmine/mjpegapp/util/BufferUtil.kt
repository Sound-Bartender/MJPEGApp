package kr.goldenmine.mjpegapp.util

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * FloatArray를 ByteArray로 변환합니다.
 *
 * @param floatArray 변환할 Float 배열.
 * @param order 사용할 바이트 순서 (기본값: 시스템 네이티브 순서).
 * 네트워크 전송 등 특정 순서가 필요하면 ByteOrder.BIG_ENDIAN 또는 ByteOrder.LITTLE_ENDIAN 지정.
 * @return 변환된 Byte 배열.
 */
fun floatArrayToByteArray(floatArray: FloatArray, order: ByteOrder = ByteOrder.nativeOrder()): ByteArray {
    // 각 Float는 4바이트
    val bytesPerFloat = Float.SIZE_BYTES // Java 8+ (혹은 그냥 4 사용)
    val byteBuffer = ByteBuffer.allocate(floatArray.size * bytesPerFloat)

    // 바이트 순서 설정
    byteBuffer.order(order)

    // ByteBuffer를 FloatBuffer 뷰로 가져옴
    val floatBuffer = byteBuffer.asFloatBuffer()

    // FloatArray의 내용을 FloatBuffer 뷰에 씀
    // 이 작업은 내부적으로 ByteBuffer의 내용을 채움
    floatBuffer.put(floatArray)

    // ByteBuffer의 내부 바이트 배열을 반환
    return byteBuffer.array()
}

/**
 * FloatBuffer (normalized audio -1.0 to 1.0)를
 * 16-bit PCM 형식의 ByteArray로 변환합니다.
 * 입력 FloatBuffer의 position과 limit 사이의 유효한 데이터만 변환합니다.
 * 변환 후 입력 FloatBuffer의 position은 원래대로 복원됩니다.
 *
 * @param floatBuffer 변환할 FloatBuffer (값은 -1.0f ~ 1.0f 범위로 가정).
 * @param order 사용할 바이트 순서 (기본값: 시스템 네이티브 순서). WAV 파일 등은 보통 LITTLE_ENDIAN 사용.
 * @return 16-bit PCM 데이터가 담긴 Byte 배열.
 */
fun floatBufferToPcm16ByteArray(
    floatBuffer: FloatBuffer,
    order: ByteOrder = ByteOrder.nativeOrder() // WAV는 보통 LITTLE_ENDIAN
): ByteArray {
    // 1. 변환할 float 요소의 개수 확인 (position부터 limit까지)
    val remainingFloats = floatBuffer.remaining() // limit - position
    if (remainingFloats <= 0) {
        return ByteArray(0) // 변환할 데이터가 없으면 빈 배열 반환
    }

    // 2. 원본 FloatBuffer의 현재 position 저장 (나중에 복원하기 위함)
    val originalPosition = floatBuffer.position()

    // 3. 결과 ByteArray를 담을 ByteBuffer 생성
    val bytesPerSample = 2 // 16-bit PCM = 2 bytes per sample
    val byteBuffer = ByteBuffer.allocate(remainingFloats * bytesPerSample)
    byteBuffer.order(order) // 바이트 순서 설정

    try {
        // 4. FloatBuffer에서 값을 하나씩 읽어 변환 및 ByteBuffer에 쓰기
        for (i in 0 until remainingFloats) {
            // get() 메소드는 값을 읽고 position을 자동으로 1 증가시킴
            val floatValue = floatBuffer.get()

            // Float -> Short 변환 로직 (이전과 동일)
            val shortValueF = floatValue * 32767.0f
            val clampedValue = max(-32768.0f, min(32767.0f, shortValueF))
            val pcm16Value = clampedValue.toInt().toShort()

            // 변환된 Short 값을 ByteBuffer에 쓰기
            byteBuffer.putShort(pcm16Value)
        }
    } finally {
        // 5. try 블록 실행 후 또는 오류 발생 시에도 반드시 원본 FloatBuffer의 position 복원
        floatBuffer.position(originalPosition)
    }

    // 6. 완성된 ByteBuffer의 내부 바이트 배열 반환
    return byteBuffer.array()
}


/**
 * FloatBuffer (값이 [-1.0, 1.0] 범위를 벗어날 수 있음)를 읽어,
 * [-1.0, 1.0] 범위로 재정규화한 후,
 * 16-bit PCM 형식의 ByteArray로 변환합니다.
 * 입력 FloatBuffer의 position과 limit 사이의 유효한 데이터만 처리합니다.
 * 변환 후 입력 FloatBuffer의 position은 원래대로 복원됩니다.
 *
 * @param floatBuffer 변환할 FloatBuffer. (ONNX 추론 결과 등)
 * @param order 결과 ByteArray에 사용할 바이트 순서 (기본값: 시스템 네이티브 순서). WAV는 보통 LITTLE_ENDIAN.
 * @return 16-bit PCM 데이터가 담긴 Byte 배열.
 */
fun normalizeAndConvertToPcm16ByteArray(
    floatBuffer: FloatBuffer,
    order: ByteOrder = ByteOrder.nativeOrder() // WAV는 보통 LITTLE_ENDIAN
): ByteArray {
    // 1. 처리할 float 개수 확인
    val remainingFloats = floatBuffer.remaining() // limit - position
    if (remainingFloats <= 0) {
        Log.w("NormalizeConvert", "변환할 데이터가 없습니다 (remaining=0).")
        return ByteArray(0) // 빈 배열 반환
    }

    // 2. 원본 버퍼의 position 저장 (복원용)
    val originalPosition = floatBuffer.position()
    var maxAbsValue = 0.0f // 최대 절대값 저장 변수

    Log.d("NormalizeConvert", "처리 시작. Floats 개수: $remainingFloats, 현재 Position: $originalPosition")

    try {
        // --- 1단계: 최대 절대값 찾기 ---
        // 이 단계에서는 position을 변경하지 않고 값을 읽기 위해 get(index) 사용
        for (i in 0 until remainingFloats) {
            val value = floatBuffer.get(originalPosition + i) // 현재 position 기준 상대 인덱스
            maxAbsValue = max(maxAbsValue, abs(value))
        }
        Log.d("NormalizeConvert", "1단계 완료: 검색된 최대 절대값 = $maxAbsValue")

        // --- 2단계: 재정규화, PCM16 변환 및 ByteBuffer에 쓰기 ---
        // 최대 절대값이 0 또는 매우 작은 경우 처리 (0으로 나누기 방지)
        val scaleFactor: Float = 1F
//        if (maxAbsValue < 1e-7f) { // 0에 매우 가까우면
//            Log.w("NormalizeConvert", "최대 절대값이 0에 가까워 스케일링 인자를 0으로 설정합니다 (결과는 0이 됨).")
//            scaleFactor = 0.0f // 결과적으로 모든 PCM 값은 0이 됨
//        } else {
//            scaleFactor = 1.0f / maxAbsValue // 재정규화를 위한 스케일 인자 (역수 곱셈)
//        }

        // 결과 PCM 데이터를 담을 ByteBuffer 생성
        val bytesPerSample = 2 // 16-bit PCM
        val outputByteBuffer = ByteBuffer.allocate(remainingFloats * bytesPerSample)
        outputByteBuffer.order(order) // 바이트 순서 설정

        // 이제 실제로 값을 읽으면서 변환 (get() 사용, position 자동 증가)
        for (i in 0 until remainingFloats) {
            val originalValue = floatBuffer.get() // 값 읽기 (position 증가)

            // 2a. 재정규화 ([-1.0, 1.0] 범위로)
            val normalizedValue = originalValue * scaleFactor

            // 2b. Float -> Short 변환 (Denormalize, Clamp, Cast)
            val shortValueF = normalizedValue * 32767.0f
            val clampedValue = max(-32768.0f, min(32767.0f, shortValueF))
            val pcm16Value = clampedValue.toInt().toShort()

            // 2c. 변환된 Short 값을 출력 ByteBuffer에 쓰기
            outputByteBuffer.putShort(pcm16Value)
        }
        Log.d("NormalizeConvert", "2단계 완료: 재정규화 및 PCM 변환 완료. $scaleFactor")

        // 완성된 ByteBuffer의 내부 바이트 배열 반환
        return outputByteBuffer.array()

    } finally {
        // --- 3단계: 원본 FloatBuffer의 position 복원 ---
        // try 블록이 정상 종료되든, 예외가 발생하든 항상 실행됨
        floatBuffer.position(originalPosition)
        Log.d("NormalizeConvert", "처리 완료. 원본 버퍼 position 복원됨: $originalPosition")
    }
}

fun convertFloatArrayToPCM16Bytes(floatArray: FloatArray): ByteArray {
    val byteBuffer = ByteBuffer.allocate(floatArray.size * 2) // int16 = 2 bytes
    byteBuffer.order(ByteOrder.LITTLE_ENDIAN)

    for (f in floatArray) {
        // [-1.0, 1.0] → [-32768, 32767]
        val clamped = f.coerceIn(-1.0f, 1.0f)
        val intVal = (clamped * 32767).toInt()
        byteBuffer.putShort(intVal.toShort())
    }

    return byteBuffer.array()
}

fun convertFloat32ToPCM16(floatBuffer: ByteBuffer): ByteArray {
    // Float32는 4바이트이므로 전체 샘플 수는 floatBuffer.limit() / 4
    floatBuffer.order(ByteOrder.LITTLE_ENDIAN)
    val sampleCount = floatBuffer.limit() / 4
    val pcm16Bytes = ByteArray(sampleCount * 2) // 2바이트 per PCM16 샘플

    for (i in 0 until sampleCount) {
        val floatSample = floatBuffer.getFloat(i * 4)
        // [-1.0, 1.0] 범위의 float -> [-32768, 32767] 범위의 PCM16으로 변환
        val clamped = floatSample.coerceIn(-1.0f, 1.0f)
        val intSample = (clamped * Short.MAX_VALUE).toInt()

        // Little endian으로 저장
        pcm16Bytes[i * 2] = (intSample and 0xFF).toByte()
        pcm16Bytes[i * 2 + 1] = ((intSample shr 8) and 0xFF).toByte()
    }

    return pcm16Bytes
}

fun convertDirectlyToPCM16Bytes(buffer: ByteBuffer, sampleCount: Int): ByteArray {
    val floatBuffer = buffer.asFloatBuffer()
    val pcmBytes = ByteArray(sampleCount * 2) // 2 bytes per sample (16-bit PCM)

    var byteIndex = 0
    for (i in 0 until sampleCount) {
        val floatSample = floatBuffer.get()
        val clamped = (floatSample.coerceIn(-1.0f, 1.0f) * Short.MAX_VALUE).toInt()
        val shortSample = clamped.toShort()
        pcmBytes[byteIndex++] = (shortSample.toInt() and 0xFF).toByte()
        pcmBytes[byteIndex++] = ((shortSample.toInt() shr 8) and 0xFF).toByte()
    }

    return pcmBytes
}

fun convertPartialFloatToPCM16Bytes(buffer: ByteBuffer, startIndex: Int, endIndex: Int): ByteArray {
    val floatBuffer = buffer.asFloatBuffer()
    val sampleCount = endIndex - startIndex
    val pcmBytes = ByteArray(sampleCount * 2) // 2 bytes per sample

    var byteIndex = 0
    for (i in startIndex until endIndex) {
        val floatSample = floatBuffer.get(i) * 4
        val clamped = (floatSample.coerceIn(-1.0f, 1.0f) * Short.MAX_VALUE).toInt()
        val shortSample = clamped.toShort()
        pcmBytes[byteIndex++] = (shortSample.toInt() and 0xFF).toByte()
        pcmBytes[byteIndex++] = ((shortSample.toInt() shr 8) and 0xFF).toByte()
    }

    return pcmBytes
}