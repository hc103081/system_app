package com.example.system

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.*
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class WebRTCManager(private val context: Context, private val serverUrl: String) {

    companion object {
        @Volatile private var factory: PeerConnectionFactory? = null
        private val eglBase: EglBase by lazy { EglBase.create() }

        @Synchronized
        fun getFactory(context: Context): PeerConnectionFactory {
            if (factory == null) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(true)
                        .createInitializationOptions()
                )
                Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO)
                
                val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
                val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
                factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(encoderFactory)
                    .setVideoDecoderFactory(decoderFactory)
                    .createPeerConnectionFactory()
            }
            return factory!!
        }
    }

    private val TAG = "WebRTC_Log"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return try {
                    val addresses = Dns.SYSTEM.lookup(hostname)
                    Log.d(TAG, "DNS 解析成功 [$hostname]: ${addresses.firstOrNull()?.hostAddress}")
                    addresses
                } catch (e: Exception) {
                    if (hostname == "hc103081.servehttp.com") {
                        listOf(InetAddress.getByName("111.241.152.112"))
                    } else throw e
                }
            }
        }).build()
    
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private inner class WhipStream(val streamPath: String) {
        var pc: PeerConnection? = null
        var videoSource: VideoSource? = null
        var videoTrack: VideoTrack? = null
        var capturer: VideoCapturer? = null
        var helper: SurfaceTextureHelper? = null
        var resourceUrl: String? = null
        var isIceSent = false
        var isNegotiating = false

        fun stop() {
            Log.d(TAG, "[$streamPath] 執行停止流程...")
            mainHandler.removeCallbacksAndMessages(this)
            
            val oldPc = pc; val oldCapturer = capturer; val oldHelper = helper
            val oldSource = videoSource; val oldTrack = videoTrack; val oldResourceUrl = resourceUrl

            pc = null; capturer = null; helper = null; videoSource = null; videoTrack = null
            isIceSent = false; isNegotiating = false

            managerScope.launch {
                oldResourceUrl?.let { url ->
                    val absUrl = if (url.startsWith("http")) url 
                    else "${serverUrl.removeSuffix("/")}/${url.removePrefix("/")}"
                    try {
                        val request = Request.Builder().url(absUrl).delete().build()
                        httpClient.newCall(request).execute().use { response ->
                            Log.d(TAG, "[$streamPath] WHIP Session 已關閉: ${response.code}")
                        }
                    } catch (e: Exception) { Log.w(TAG, "[$streamPath] DELETE 失敗: ${e.message}") }
                }

                try {
                    oldCapturer?.stopCapture()
                    oldCapturer?.dispose()
                    oldTrack?.dispose()
                    oldSource?.dispose()
                    oldPc?.close()
                    oldPc?.dispose()
                    oldHelper?.dispose()
                    Log.d(TAG, "[$streamPath] 本地資源已完全釋放")
                } catch (e: Exception) { Log.e(TAG, "Cleanup Error", e) }
            }
        }

        fun createPC(): PeerConnection? {
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:global.stun.twilio.com:3478").createIceServer()
            )
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
                iceCandidatePoolSize = 2
            }
            
            Log.d(TAG, "[$streamPath] 正在建立 PeerConnection...")
            return getFactory(context).createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(TAG, "[$streamPath] ❄️ 發現 Candidate: ${candidate.sdp.take(50)}...")
                }
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                    Log.d(TAG, "[$streamPath] 🛰️ Gathering: $state")
                    if (state == PeerConnection.IceGatheringState.COMPLETE) handleIceComplete(this@WhipStream)
                }
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) { 
                    Log.d(TAG, "[$streamPath] 🔗 ICE State: $s")
                    if (s == PeerConnection.IceConnectionState.FAILED) stop()
                }
                override fun onRenegotiationNeeded() { createOffer(this@WhipStream) }
                override fun onSignalingChange(s: PeerConnection.SignalingState) {
                    Log.d(TAG, "[$streamPath] 📡 Signaling State: $s")
                }
                override fun onIceCandidatesRemoved(a: Array<out IceCandidate>?) {}
                override fun onDataChannel(d: DataChannel?) {}
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onAddStream(s: MediaStream?) {}
                override fun onRemoveStream(s: MediaStream?) {}
                override fun onAddTrack(r: RtpReceiver?, m: Array<out MediaStream>?) {}
            })
        }
    }

    private val screenStream = WhipStream("mystream")
    private val cameraStream = WhipStream("camera")

    fun startScreenCapture(resultCode: Int, data: Intent) {
        screenStream.stop()
        managerScope.launch(Dispatchers.Main) {
            delay(1000) 
            Log.d(TAG, "🚀 [mystream] 螢幕推流初始化...")
            val stream = screenStream
            val pc = stream.createPC() ?: return@launch
            stream.pc = pc
            
            stream.videoSource = getFactory(context).createVideoSource(true)
            stream.capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                override fun onStop() { super.onStop() }
            })
            stream.helper = SurfaceTextureHelper.create("ScreenThread", eglBase.eglBaseContext)
            stream.capturer?.initialize(stream.helper, context, stream.videoSource?.capturerObserver)
            stream.capturer?.startCapture(1280, 720, 30)
            
            stream.videoTrack = getFactory(context).createVideoTrack("SCREEN_TRACK", stream.videoSource)
            pc.addTrack(stream.videoTrack, listOf("screen_stream"))
            createOffer(stream)
        }
    }

    fun startFrontCamera() {
        cameraStream.stop()
        managerScope.launch(Dispatchers.Main) {
            delay(1000)
            Log.d(TAG, "🚀 [camera] 相機推流初始化...")
            val stream = cameraStream
            val pc = stream.createPC() ?: return@launch
            stream.pc = pc

            val enumerator = Camera2Enumerator(context)
            val frontCamera = enumerator.deviceNames.find { enumerator.isFrontFacing(it) } ?: return@launch
            
            stream.capturer = enumerator.createCapturer(frontCamera, null)
            stream.videoSource = getFactory(context).createVideoSource(false)
            stream.helper = SurfaceTextureHelper.create("CameraThread", eglBase.eglBaseContext)
            stream.capturer?.initialize(stream.helper, context, stream.videoSource?.capturerObserver)
            stream.capturer?.startCapture(640, 480, 30)
            
            stream.videoTrack = getFactory(context).createVideoTrack("CAMERA_TRACK", stream.videoSource)
            pc.addTrack(stream.videoTrack, listOf("camera_stream"))
            createOffer(stream)
        }
    }

    private fun createOffer(stream: WhipStream) {
        val pc = stream.pc ?: return
        if (stream.isNegotiating || pc.signalingState() != PeerConnection.SignalingState.STABLE) return
        stream.isNegotiating = true
        
        Log.d(TAG, "[${stream.streamPath}] 建立 Offer SDP...")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                if (stream.pc != pc) return
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        managerScope.launch { 
                            delay(5000) 
                            handleIceComplete(stream) 
                        }
                    }
                    override fun onSetFailure(s: String?) { 
                        Log.e(TAG, "[${stream.streamPath}] ❌ LocalSDP 失敗: $s")
                        stream.isNegotiating = false 
                    }
                    override fun onCreateSuccess(s: SessionDescription) {}
                    override fun onCreateFailure(s: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(s: String?) { 
                Log.e(TAG, "[${stream.streamPath}] ❌ Offer 建立失敗: $s")
                stream.isNegotiating = false 
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(s: String?) {}
        }, constraints)
    }

    private fun handleIceComplete(stream: WhipStream) {
        if (stream.isIceSent || stream.pc == null) return
        stream.isIceSent = true
        val sdp = stream.pc?.localDescription?.description ?: return
        sendWhipOffer(stream, sdp)
    }

    private fun sendWhipOffer(stream: WhipStream, sdp: String) {
        val currentPc = stream.pc ?: return
        val whipUrl = "${serverUrl.removeSuffix("/")}/${stream.streamPath}/whip"
        Log.d(TAG, "[${stream.streamPath}] WHIP POST: $whipUrl")
        
        val request = Request.Builder()
            .url(whipUrl)
            .post(sdp.toRequestBody("application/sdp".toMediaType()))
            .build()
            
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { 
                Log.e(TAG, "[${stream.streamPath}] ❌ HTTP 失敗: ${e.message}")
                stream.isIceSent = false; stream.isNegotiating = false 
            }
            
            override fun onResponse(call: Call, response: Response) {
                val rawBody = response.body?.string() ?: ""
                Log.d(TAG, "[${stream.streamPath}] 回應碼: ${response.code}, Body 長度: ${rawBody.length}")
                
                // 1. 清洗 SDP: 定位 v=0 之後的內容
                val cleanSdp = if (rawBody.contains("v=0")) {
                    rawBody.substring(rawBody.indexOf("v=0")).trim()
                } else rawBody.trim()
                
                if (response.isSuccessful && cleanSdp.startsWith("v=0")) {
                    stream.resourceUrl = response.header("Location")
                    
                    // 2. 強制標準格式: 使用正則拆分行並過濾無效屬性
                    val normalizedSdp = cleanSdp.split(Regex("[\\r\\n]+"))
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .map { line ->
                            when {
                                line.contains("a=setup:actpass") -> "a=setup:passive"
                                line.contains("a=extmap-allow-mixed") -> "" 
                                else -> line
                            }
                        }
                        .filter { it.isNotBlank() }
                        .joinToString("\r\n") + "\r\n"

                    managerScope.launch(Dispatchers.Main) {
                        val pc = stream.pc
                        if (pc == null || pc != currentPc) {
                            Log.w(TAG, "[${stream.streamPath}] PC 已過期"); stream.isNegotiating = false; return@launch
                        }

                        if (pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                            Log.e(TAG, "[${stream.streamPath}] ❌ 狀態錯誤: ${pc.signalingState()}"); stream.isNegotiating = false; return@launch
                        }

                        Log.d(TAG, "[${stream.streamPath}] 正在設定 RemoteSDP (長度: ${normalizedSdp.length})")
                        pc.setRemoteDescription(object : SdpObserver {
                            override fun onSetSuccess() { 
                                stream.isNegotiating = false
                                Log.d(TAG, "🚀 [${stream.streamPath}] 推流建立成功") 
                            }
                            override fun onSetFailure(s: String?) { 
                                stream.isNegotiating = false
                                Log.e(TAG, "[${stream.streamPath}] ❌ RemoteSDP 解析失敗: $s")
                                Log.e(TAG, "----- 失敗的 SDP 內容 -----")
                                normalizedSdp.split("\r\n").forEach { line -> Log.e(TAG, "L: $line") }
                                Log.e(TAG, "-------------------------")
                            }
                            override fun onCreateSuccess(s: SessionDescription) {}
                            override fun onCreateFailure(s: String?) {}
                        }, SessionDescription(SessionDescription.Type.ANSWER, normalizedSdp))
                    }
                } else { 
                    Log.e(TAG, "[${stream.streamPath}] ❌ 無效回應: ${response.code}\n$rawBody")
                    stream.isIceSent = false; stream.isNegotiating = false
                }
                response.close()
            }
        })
    }

    fun stopCapture() {
        screenStream.stop()
        cameraStream.stop()
    }

    fun dispose() {
        stopCapture()
        managerScope.cancel()
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(s: String?) {}
        override fun onSetFailure(s: String?) {}
    }
}
