package kr.goldenmine.mjpegapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.goldenmine.mjpegapp.ai.FaceProcessor
import kr.goldenmine.mjpegapp.ai.IIANetInputConverter
import kr.goldenmine.mjpegapp.ai.IIANetTFLite
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var textViewStatus: TextView
    private lateinit var buttonConnect: Button
    private lateinit var editTextIpAddress: EditText
    private lateinit var editTextPort: EditText
    private lateinit var switch: SwitchCompat

    private var clientSocket: Socket? = null
    private var dataInputStream: DataInputStream? = null
    private var dataOutputStream: DataOutputStream? = null

    private val isRunning = AtomicBoolean(false)
    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job()) // IO 작업을 위한 코루틴 스코프

    // 데이터 타입 정의 (Python 코드와 일치해야 함)
    private val TYPE_VIDEO: Byte = 0
    private val TYPE_AUDIO: Byte = 1
    private val TYPE_ENHANCED_AUDIO: Byte = 2

    // 동기화를 위한 버퍼 (간단한 예시)
    // ConcurrentLinkedQueue는 스레드 안전하지만, 엄격한 순서나 시간 동기화는 보장하지 않음
    // 실제 구현에서는 타임스탬프 기반의 더 정교한 버퍼링/매칭 로직 필요
    private val videoFrameBuffer = ConcurrentLinkedQueue<Pair<Long, ByteArray>>() // Timestamp, MJPEG data
    private val audioChunkBuffer = ConcurrentLinkedQueue<Pair<Long, ByteArray>>() // Timestamp, Raw audio data

    private var keepOn = false

    // --- Queue 관련 추가 ---
    // 전송할 오디오 데이터를 담을 데이터 클래스
    data class AudioDataToSend(val timestamp: Long, val data: ByteArray) {
        // ByteArray 비교를 위해 equals/hashCode 오버라이드 (필요 시)
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as AudioDataToSend
            if (timestamp != other.timestamp) return false
            if (!data.contentEquals(other.data)) return false
            return true
        }
        override fun hashCode(): Int {
            var result = timestamp.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    // 오디오 전송을 위한 Channel 생성 (버퍼 크기 지정, 예: 64개 아이템)
    // Channel.BUFFERED: 기본 버퍼 크기 사용
    // Channel.UNLIMITED: 메모리 부족 위험 있으므로 주의
    private val sendQueue = Channel<AudioDataToSend>(capacity = 64)

    // 오디오 전송 코루틴 Job 관리용
    private var sendingJob: Job? = null
    // --- --- --- --- --- ---

    private val TAG = "MainActivityStream"

    private val converter = IIANetInputConverter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        textViewStatus = findViewById(R.id.textViewStatus)
        buttonConnect = findViewById(R.id.buttonConnect)
        editTextIpAddress = findViewById(R.id.editTextIpAddress)
        editTextPort = findViewById(R.id.editTextPort)
        switch = findViewById(R.id.inferenceSwitch)

        textViewStatus.movementMethod = ScrollingMovementMethod() // 스크롤 가능하게
//        OnnxInferenceManagerMulti.initialize(this)
//        TFLiteInferenceManager.loadModelFile(applicationContext)
        IIANetTFLite.loadVideoModel(applicationContext)
        IIANetTFLite.loadAudioModel(applicationContext)
//        FaceProcessor.initialize(this)

        buttonConnect.setOnClickListener {
            keepOn = switch.isChecked
            converter.keepFrames = if(keepOn) 10 else 0
            IIANetTFLite.keepFrames = if(keepOn) 10 else 0

            FaceProcessor.initialize(this)
            if (!isRunning.get()) {
                val ipAddress = editTextIpAddress.text.toString().trim()
                val portStr = editTextPort.text.toString().trim()
                if (ipAddress.isNotEmpty() && portStr.isNotEmpty()) {
                    val port = portStr.toIntOrNull()
                    if (port != null) {
                        startStreaming(ipAddress, port)
                        buttonConnect.text = "연결 해제"
                        editTextIpAddress.isEnabled = false
                        editTextPort.isEnabled = false
                    } else {
                        updateStatus("유효한 포트 번호를 입력하세요.")
                    }
                } else {
                    updateStatus("IP 주소와 포트를 입력하세요.")
                }
            } else {
                stopStreaming()
                buttonConnect.text = "연결"
                editTextIpAddress.isEnabled = true
                editTextPort.isEnabled = true
            }
        }

        coroutineScope.launch {
            val videoInputDummy = ByteBuffer.allocateDirect(1 * 25 * 88 * 88 * 4).order(ByteOrder.nativeOrder())
            val audioInputDummy = ByteBuffer.allocateDirect(1 * 1 * 16000 * 4).order(ByteOrder.nativeOrder())

            IIANetTFLite.runInference(videoInputDummy, audioInputDummy)
        }
    }

    private fun updateStatus(message: String) {
//        Log.d(TAG, message)
        runOnUiThread {
            // 상태 메시지를 맨 위에 추가하고 스크롤
            textViewStatus.append("\n$message")
            //자동 스크롤 (가장 아래로)
            val scrollAmount = textViewStatus.layout.getLineTop(textViewStatus.lineCount) - textViewStatus.height
            if (scrollAmount > 0) textViewStatus.scrollTo(0, scrollAmount)
        }
    }

    private fun updateImageView(bitmap: Bitmap?) {
        runOnUiThread {
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            } else {
                Log.w(TAG, "이미지 설정 실패")
                // 오류 또는 기본 이미지 처리
//                 imageView.setImageResource(R.drawable.ic_launcher_background)
            }
        }
    }

    private fun startStreaming(ipAddress: String, port: Int) {
        if (isRunning.compareAndSet(false, true)) {
            coroutineScope.launch {
                try {
                    updateStatus("연결 시도 중... $ipAddress:$port")
                    // 타임아웃 설정 (예: 5초)
                    clientSocket = Socket()
                    clientSocket?.connect(InetSocketAddress(ipAddress, port), 5000)

                    if (clientSocket?.isConnected == true) {
                        dataInputStream = DataInputStream(BufferedInputStream(clientSocket!!.getInputStream()))
                        dataOutputStream = DataOutputStream(BufferedOutputStream(clientSocket!!.getOutputStream()))
                        updateStatus("연결 성공!")

                        // --- ★ 오디오 전송 전용 코루틴 시작 ★ ---
                        sendingJob = launch(Dispatchers.IO) { // 네트워크 작업은 IO Dispatcher 사용
                            Log.i(TAG, "[Sender] 오디오 전송 코루틴 시작됨.")
                            processSendQueue() // 큐 처리 함수 호출
                            Log.i(TAG, "[Sender] 오디오 전송 코루틴 정상 종료.")
                        }
                        sendingJob?.invokeOnCompletion { throwable ->
                            if (throwable != null && throwable !is CancellationException) {
                                Log.e(TAG, "[Sender] 전송 코루틴 비정상 종료", throwable)
                                // 필요 시 에러 처리 및 스트리밍 중단 로직 호출
                                // runBlocking 등 사용 시 주의 필요, 여기서는 로그만 남김
                                if(isRunning.get()) { // 여전히 실행 중 상태였다면 중단 처리
                                    coroutineScope.launch { stopStreamingInternal() }
                                }
                            } else if (throwable is CancellationException){
                                Log.i(TAG, "[Sender] 전송 코루틴 취소됨.")
                            }
                        }

                        // 데이터 수신 및 처리 루프 시작
                        receiveDataLoop()
                    } else {
                        updateStatus("연결 실패 (타임아웃 또는 거부됨)")
                        stopStreamingInternal() // 실패 시 정리
                    }
                } catch (e: SocketException) {
                    updateStatus("네트워크 오류: ${e.message}")
                    stopStreamingInternal()
                } catch (e: IOException) {
                    updateStatus("IO 오류: ${e.message}")
                    stopStreamingInternal()
                } catch (e: Exception) {
                    updateStatus("알 수 없는 오류: ${e.message}")
                    e.printStackTrace()
                    stopStreamingInternal()
                }
            }
        }
    }

    private suspend fun receiveDataLoop() {
        val headerBuffer = ByteArray(13) // Type(1) + Timestamp(8) + Length(4)

        while (isRunning.get() && clientSocket?.isConnected == true) {
            try {
                // 1. 헤더 읽기
                dataInputStream?.readFully(headerBuffer)

                // 리틀 엔디안으로 읽도록 설정 (Raspberry Pi는 Network Byte Order = Big Endian)
                val byteBuffer = ByteBuffer.wrap(headerBuffer).order(ByteOrder.BIG_ENDIAN)
                val dataType = byteBuffer.get()
                val timestamp = byteBuffer.long
                val payloadLength = byteBuffer.int

//                Log.d(TAG, "Received Header: Type=$dataType, TS=$timestamp, Len=$payloadLength  ${System.currentTimeMillis()}")

                if (payloadLength < 0) {
                    updateStatus("오류: 잘못된 페이로드 길이 수신 ($payloadLength). 연결을 종료합니다.")
                    stopStreamingInternal()
                    break
                }

//                updateStatus("Received: $timestamp $payloadLength bytes")

                // 2. 페이로드 읽기
                if (payloadLength > 0) {
                    val payload = ByteArray(payloadLength)
                    dataInputStream?.readFully(payload)

                    // 3. 데이터 타입에 따른 처리
                    when (dataType) {
                        TYPE_VIDEO -> {
//                            updateStatus("Received Video: ${payload.size} bytes")
                            // 비디오 버퍼에 추가 (간단히 최신 것만 유지하거나 큐 사용)
                            videoFrameBuffer.offer(Pair(timestamp, payload)) // 큐 사용 시
//                            showVideoFrame(payload, timestamp) // 직접 처리
                            while(videoFrameBuffer.size > 3) {
                                videoFrameBuffer.poll()
                                Log.w(TAG, "버리기 video C")
                            }
                        }
                        TYPE_AUDIO -> {
//                            updateStatus("Received Audio: ${payload.size} bytes")
                            // 오디오 버퍼에 추가
                            audioChunkBuffer.offer(Pair(timestamp, payload))
                            // AI 처리를 위해 동기화 로직 호출 (예시)
                            processSynchronizedData()
                        }
                        else -> {
                            updateStatus("알 수 없는 데이터 타입 수신: $dataType")
                        }
                    }
                } else if (payloadLength == 0) {
                    // 길이가 0인 데이터 (Heartbeat 등?) - 현재는 무시
                     Log.d(TAG, "Received zero-length payload for Type=$dataType, TS=$timestamp")
                }
            } catch (e: EOFException) {
                updateStatus("서버 연결 끊김 (EOF)")
                stopStreamingInternal()
                break // 루프 종료
            } catch (e: SocketException) {
                updateStatus("소켓 오류 발생: ${e.message}")
                stopStreamingInternal()
                break
            } catch (e: IOException) {
                updateStatus("데이터 수신 중 IO 오류: ${e.message}")
                // IO 오류 시 잠시 대기 후 재시도 또는 연결 종료 결정 필요
                delay(100) // 예: 잠시 대기
                // 또는 stopStreamingInternal() 호출로 종료
            } catch (e: Exception) {
                updateStatus("데이터 처리 중 알 수 없는 오류: ${e.message}")
                e.printStackTrace()
                stopStreamingInternal() // 예상치 못한 오류 시 종료
                break
            }
//            Log.d(TAG, "Received Header Complete ${System.currentTimeMillis()}")
        }
        // 루프 종료 후 정리 (stopStreamingInternal이 이미 호출되었을 수 있음)
        if (isRunning.get()) {
            stopStreamingInternal()
        }
        updateStatus("수신 루프 종료됨.")
    }

    // 비디오 프레임 처리 (MJPEG 디코딩 및 표시)
    private fun showVideoFrame(bitmap: Bitmap) {
        // 비동기 처리 또는 별도 스레드에서 디코딩 권장 (UI 스레드 차단 방지)
        // 여기서는 간단히 코루틴 내에서 처리
        try {
//            val cropped = FaceProcessor.detectCropResizeFace(frameData, this)
//            val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.size)
//            if (bitmap != null) {
                updateImageView(bitmap)
                // 디코딩된 비트맵과 타임스탬프를 AI 처리를 위해 저장할 수도 있음
                // synchronizedFrameBuffer.offer(Pair(timestamp, bitmap))
//            } else {
//                Log.w(TAG, "비트맵 디코딩 실패 (Timestamp: $timestamp)")
//            }
        } catch (e: Exception) {
            Log.e(TAG, "비트맵 디코딩 중 오류 발생", e)
        }
    }

    // 오디오/비디오 동기화 및 AI 처리 로직
    private fun processSynchronizedData() {
        // 버퍼가 너무 차면 하나씩 버림
        while(videoFrameBuffer.size > 5) {
            videoFrameBuffer.poll()
            Log.w(TAG, "버리기 video A")
        }
        while(audioChunkBuffer.size > 5) {
            audioChunkBuffer.poll()
            Log.w(TAG, "버리기 audio A")
        }

        // 둘중 하나라도 비어있으면 처리하지 않음
        if(videoFrameBuffer.isEmpty() || audioChunkBuffer.isEmpty()) return

        var bestVideo: ByteArray? = null
        var bestAudio: ByteArray? = null
        var bestVideoTimestamp: Long? = null
        var bestAudioTimestamp: Long? = null

        // 1 밀리초는 1_000_000 나노초임
        val proposedTimeDiff = 40 * 1_000_000

        while(videoFrameBuffer.isNotEmpty() && audioChunkBuffer.isNotEmpty()) {
            val peekVideo = videoFrameBuffer.peek()
            val peekAudio = audioChunkBuffer.peek()

            if(peekVideo != null && peekAudio != null) {
                // 비디오와 오디오의 시간 차가 비슷한 경우
                if(abs(peekVideo.first - peekAudio.first) <= proposedTimeDiff) {
                    bestVideo = peekVideo.second
                    bestAudio = peekAudio.second
                    bestVideoTimestamp = peekVideo.first
                    bestAudioTimestamp = peekAudio.first

                    videoFrameBuffer.poll()
                    audioChunkBuffer.poll()
                    break
                } else {
                    // 더 빠른 것 부터 버림
                    if(peekVideo.first > peekAudio.first) { // 비디오가 느림
                        Log.w(TAG, "버리기 audio B")
                        audioChunkBuffer.poll()
                    } else {
                        Log.w(TAG, "버리기 video B")
                        videoFrameBuffer.poll()
                    }
                }
            } else {
                break
            }
        }

        // 싱크가 맞춰진 비디오와 오디오 제공
        if(bestVideo != null && bestAudio != null) {
//            val originalBitmap = BitmapFactory.decodeByteArray(bestVideo, 0, bestVideo.size)
            val croppedImage = FaceProcessor.detectCropResizeFace(bestVideo, this)

            if(croppedImage != null) {
                converter.addVideoFrame(croppedImage)
                converter.addAudioChunk(bestAudio)
                showVideoFrame(croppedImage)
            }
//            showVideoFrame(originalBitmap)
        }

        // 1초 이상 쌓인 경우 AI 처리
        if(!converter.checkIfReady()) {
            return
        }
        val buffers = converter.processBuffers()
        coroutineScope.launch {
            // 잘 돌림
            val time = System.currentTimeMillis()
            val enhancedAudio = IIANetTFLite.runInference(buffers.first, buffers.second)
            if(enhancedAudio != null) {
                queueEnhancedAudio(enhancedAudio)
                Log.d(TAG, "AI 모델 처리 완료 ${System.currentTimeMillis() - time}")
            } else {
                Log.w(TAG, "휴대폰 성능 미달로 인해 추론 생략 $time")
            }
        }
    }

    // --- ★ 실제 전송 로직 (Consumer) ★ ---
    // sendingJob 코루틴 내부에서 실행될 함수
    private suspend fun processSendQueue() {
        try {
            // 채널이 닫히거나 코루틴이 취소될 때까지 계속 아이템 소비
            for (itemToSend in sendQueue) {
                // 매번 보내기 전에 소켓 상태 확인
                if (!isRunning.get() || clientSocket?.isConnected != true || dataOutputStream == null) {
                    Log.w(TAG, "[Sender] 소켓 연결 끊김 감지. 전송 중단.")
                    break // 루프 탈출
                }

                val audioData = itemToSend.data
                val timestamp = itemToSend.timestamp
                val payloadLength = audioData.size

                try {
                    // 헤더 생성
                    val headerBuffer = ByteBuffer.allocate(1 + 8 + 4)
                    headerBuffer.order(ByteOrder.BIG_ENDIAN) // 네트워크 바이트 순서
                    headerBuffer.put(TYPE_ENHANCED_AUDIO)
                    headerBuffer.putLong(timestamp)
                    headerBuffer.putInt(payloadLength)

                    // 데이터 전송 (동기화 블록 사용 - dataOutputStream 접근 보호)
                    // 이 코루틴만 dataOutputStream에 쓰지만, 만일을 위해 유지
                    synchronized(dataOutputStream!!) {
                        dataOutputStream?.write(headerBuffer.array())
                        dataOutputStream?.write(audioData)
                        dataOutputStream?.flush() // 즉시 전송
                    }
                     Log.d(TAG, "[Sender] 큐에서 데이터 전송 완료: $payloadLength bytes")

                } catch (e: IOException) {
                    updateStatus("[Sender] 데이터 전송 중 IO 오류: ${e.message}")
                    // 전송 오류 시 스트리밍 중단 및 루프 탈출
                    stopStreamingInternal() // 이 함수는 내부적으로 isRunning을 false로 만듬
                    break
                } catch (e: Exception) {
                    updateStatus("[Sender] 데이터 전송 중 예외: ${e.message}")
                    Log.e(TAG, "[Sender] 데이터 전송 중 예외", e)
                    stopStreamingInternal()
                    break
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            // 채널이 정상적으로 닫혔을 때 (예: stopStreamingInternal 호출 시)
            Log.i(TAG, "[Sender] 전송 채널 닫힘. 정상 종료.")
        } catch (e: CancellationException) {
            // 코루틴이 외부에서 취소되었을 때
            Log.i(TAG, "[Sender] 전송 코루틴 취소됨.")
            throw e // 취소는 다시 던져주는 것이 좋음
        } catch (e: Exception) {
            // 그 외 예상치 못한 예외
            updateStatus("[Sender] 전송 루프 오류: ${e.message}")
            Log.e(TAG, "[Sender] 전송 루프 오류", e)
            // 스트리밍 중단
            if(isRunning.get()) { // 이미 중단된 상태가 아니라면 중단 처리
                stopStreamingInternal()
            }
        }
    }
    // --- --- --- --- --- --- --- ---


    // --- ★ 기존 sendEnhancedAudio -> queueEnhancedAudio 로 변경 (Producer) ★ ---
    /**
     * 개선된 오디오 데이터를 전송 큐(Channel)에 추가합니다.
     * Channel 버퍼가 가득 차면 잠시 대기(suspend)할 수 있습니다.
     * @param audioData 전송할 오디오 데이터 (ByteArray)
     * @param timestamp 타임스탬프 (Long)
     */
    private suspend fun queueEnhancedAudio(audioData: ByteArray, timestamp: Long = 0L) {
        // 소켓 상태 확인은 큐에 넣기 전에 하는 것이 좋을 수 있음 (선택)
        if (!isRunning.get() || clientSocket?.isConnected != true) {
            Log.w(TAG, "[Producer] 소켓 연결 안됨. 큐 추가 건너<0xEB><0x84><0x8E>.")
            // 여기서 데이터를 버릴지, 아니면 연결 복구 시 보낼지 등 정책 결정 필요
            // 일단은 버리는 것으로 가정
            return
        }

        try {
            val dataToSend = AudioDataToSend(timestamp, audioData)
            // Log.d(TAG, "[Producer] 데이터 큐에 추가 시도: ${audioData.size} bytes")
            sendQueue.send(dataToSend) // 채널에 데이터 추가 (버퍼 꽉 차면 suspend)
            // Log.d(TAG, "[Producer] 데이터 큐에 추가 완료.")
        } catch (e: Exception) {
            // 채널이 닫혔거나 다른 예외 발생 시
            Log.e(TAG, "[Producer] 오디오 큐 추가 실패: ${e.message}", e)
            // 큐 추가 실패 시 스트리밍 중단 또는 다른 오류 처리
            if(isRunning.get()) {
                stopStreamingInternal()
            }
        }
    }
    // --- --- --- --- --- --- --- ---
//    // 개선된 오디오 데이터를 라즈베리파이로 전송
//    private fun sendEnhancedAudio(audioData: ByteArray, timestamp: Long = 0L) {
//        if (!isRunning.get() || clientSocket?.isConnected != true || dataOutputStream == null) {
//            Log.w(TAG, "소켓이 준비되지 않아 개선된 오디오를 전송할 수 없습니다.")
//            return
//        }
//
//        // 코루틴 스코프 내에서 실행 (네트워크 작업)
//        coroutineScope.launch {
//            try {
//                val payloadLength = audioData.size
//                val headerBuffer = ByteBuffer.allocate(1 + 8 + 4) // Type(1) + Timestamp(8) + Length(4)
//                headerBuffer.order(ByteOrder.BIG_ENDIAN) // 네트워크 바이트 순서
//                headerBuffer.put(TYPE_ENHANCED_AUDIO)
//                headerBuffer.putLong(timestamp)
//                headerBuffer.putInt(payloadLength)
//
//                // dataOutputStream은 스레드 안전하지 않을 수 있으므로 주의 (필요 시 synchronized 블록 사용)
//                synchronized(dataOutputStream!!) {
//                    dataOutputStream?.write(headerBuffer.array())
//                    dataOutputStream?.write(audioData)
//                    dataOutputStream?.flush() // 버퍼 비우기
//                }
//                //Log.d(TAG, "Sent Enhanced Audio: ${audioData.size} bytes")
//
//            } catch (e: SocketException) {
//                updateStatus("개선된 오디오 전송 중 소켓 오류: ${e.message}")
//                stopStreamingInternal() // 오류 시 연결 종료
//            } catch (e: IOException) {
//                updateStatus("개선된 오디오 전송 중 IO 오류: ${e.message}")
//                // 오류 처리 필요
//            } catch (e: Exception) {
//                updateStatus("개선된 오디오 전송 중 알 수 없는 오류: ${e.message}")
//                e.printStackTrace()
//            }
//        }
//    }


    private fun stopStreaming() {
        if (isRunning.compareAndSet(true, false)) {
            updateStatus("연결 해제 시도 중...")
            coroutineScope.launch {
                stopStreamingInternal()
                // UI 업데이트는 메인 스레드에서
                withContext(Dispatchers.Main) {
                    buttonConnect.text = "연결"
                    editTextIpAddress.isEnabled = true
                    editTextPort.isEnabled = true
                    imageView.setImageResource(android.R.color.black) // 기본 이미지로 초기화
                }
            }
        }
    }

    // 실제 리소스 해제 로직 (백그라운드 스레드에서 호출 가능)
    private fun stopStreamingInternal() {
        isRunning.set(false) // 상태 플래그 먼저 설정
        try {
            // 입력/출력 스트림 먼저 닫기
            dataInputStream?.close()
            dataOutputStream?.close()
            // 소켓 닫기
            clientSocket?.close()
            updateStatus("연결이 종료되었습니다.")
        } catch (e: IOException) {
            updateStatus("소켓/스트림 닫기 오류: ${e.message}")
        } finally {
            dataInputStream = null
            dataOutputStream = null
            clientSocket = null
            // 버퍼 클리어
            videoFrameBuffer.clear()
            audioChunkBuffer.clear()
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        stopStreaming() // 액티비티 종료 시 스트리밍 중지 및 리소스 해제
        coroutineScope.cancel() // 모든 코루틴 취소
    }
}