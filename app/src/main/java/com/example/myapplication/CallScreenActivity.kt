package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.twilio.video.*
import com.twilio.voice.Call as VoiceCall
import com.twilio.voice.CallException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Context.RECEIVER_EXPORTED
import android.util.Base64
import java.io.File
import android.media.MediaPlayer




class CallScreenActivity : AppCompatActivity() {

    // UI
    private lateinit var txtCallerName: TextView
    private lateinit var txtCallTimer: TextView
    private lateinit var btnEndCall: ImageButton
    private lateinit var btnMute: ImageButton
    private lateinit var btnSpeaker: ImageButton
    private lateinit var btnUpgradeToVideo: ImageButton
    private lateinit var remoteContainer: FrameLayout
    private lateinit var localContainer: FrameLayout
    private lateinit var spinnerSourceLang: Spinner
    private lateinit var spinnerTargetLang: Spinner

    // Twilio Video Views (created at runtime)
    private lateinit var remoteVideoView: VideoView
    private lateinit var localVideoView: VideoView

    // State
    private var mode = CallMode.PSTN_AUDIO

    // Voice (Twilio Voice SDK)
    private var voiceCall: VoiceCall? = null
    private var muted = false

    // Video
    private var room: Room? = null
    private var localAudio: LocalAudioTrack? = null
    private var localVideo: LocalVideoTrack? = null
    private var videoCapturer: VideoCapturer? = null

    // Timer / notification sync
    private var timer: CountDownTimer? = null
    private var elapsed = 0L
    private val running = AtomicBoolean(false)

    // Permissions
    private val REQ_PERMS = 101
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)

    // Backend
    private val BACKEND_BASE_URL = "https://nodical-earlie-unyieldingly.ngrok-free.dev"
    private val prefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }







    companion object {
        var isActive = false
    }

    // Broadcast receivers
    private val endCallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "ACTION_FORCE_END_CALL") return
            if (!isActive) {
                Log.d(TAG, "Ignoring END_CALL broadcast — UI not active")
                return
            }
            // If we are still ringing, ignore remote END_CALL (server will notify after connect usually)
            if (::txtCallTimer.isInitialized &&
                txtCallTimer.text.toString().contains("Ringing", true)
            ) {
                Log.w(TAG, "END_CALL received while ringing — ignoring")
                return
            }
            Log.d(TAG, "Received END_CALL broadcast — finishing call")
            finishCallGracefully()
        }
    }

    private val calleeAnsweredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "CALLEE_ANSWERED broadcast received — starting timer")
            beginTimerSafely()
            CallNotificationService.startOngoing(this@CallScreenActivity, txtCallerName.text.toString(), mode == CallMode.APP_VIDEO)
        }
    }


    private val translationIncomingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra("translated_text") ?: ""
            val audio64 = intent?.getStringExtra("translated_audio_base64") ?: ""

            if (text.isNotBlank()) showTranslatedText(text)
            if (audio64.isNotBlank()) playTranslatedAudio(audio64)
        }
    }

    // Twilio Video listeners
    private val roomListener = object : Room.Listener {
        override fun onConnected(room: Room) {
            runOnUiThread {
                Log.d(TAG, "Video room connected")
                txtCallTimer.text = "Ringing…"
            }
            // Wait for remote participant to start timer
            room.remoteParticipants.firstOrNull()?.let { participant ->
                attachRemoteListener(participant, remoteListener)
                runOnUiThread {
                    if (timer == null) {
                        txtCallTimer.text = "00:00"
                        startTimer()
                        CallNotificationService.startOngoing(this@CallScreenActivity, txtCallerName.text.toString(), true)
                    }
                }
            }
        }

        override fun onParticipantConnected(room: Room, participant: RemoteParticipant) {
            attachRemoteListener(participant, remoteListener)
            runOnUiThread {
                if (timer == null) {
                    txtCallTimer.text = "00:00"
                    startTimer()
                    CallNotificationService.startOngoing(this@CallScreenActivity, txtCallerName.text.toString(), true)
                }
            }
        }

        override fun onConnectFailure(room: Room, e: TwilioException) {
            runOnUiThread {
                Toast.makeText(this@CallScreenActivity, "Video connect failed: ${e.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        override fun onDisconnected(room: Room, e: TwilioException?) {
            runOnUiThread {
                stopTimer()
                finishCallGracefully()
            }
        }

        override fun onRecordingStarted(room: Room) {}
        override fun onRecordingStopped(room: Room) {}
        override fun onParticipantDisconnected(room: Room, participant: RemoteParticipant) {}
        override fun onDominantSpeakerChanged(room: Room, remoteParticipant: RemoteParticipant?) {}
        override fun onReconnecting(room: Room, e: TwilioException) {}
        override fun onReconnected(room: Room) {}
    }

    private val remoteListener = object : RemoteParticipant.Listener {
        override fun onVideoTrackSubscribed(p: RemoteParticipant, pub: RemoteVideoTrackPublication, track: RemoteVideoTrack) {
            runOnUiThread {
                track.addSink(remoteVideoView)
                remoteVideoView.visibility = View.VISIBLE
            }
        }

        override fun onVideoTrackUnsubscribed(p: RemoteParticipant, pub: RemoteVideoTrackPublication, track: RemoteVideoTrack) {
            runOnUiThread {
                track.removeSink(remoteVideoView)
                remoteVideoView.visibility = View.GONE
            }
        }

        override fun onAudioTrackSubscribed(p: RemoteParticipant, pub: RemoteAudioTrackPublication, track: RemoteAudioTrack) {}
        override fun onAudioTrackUnsubscribed(p: RemoteParticipant, pub: RemoteAudioTrackPublication, track: RemoteAudioTrack) {}
        override fun onDataTrackSubscribed(p: RemoteParticipant, pub: RemoteDataTrackPublication, track: RemoteDataTrack) {}
        override fun onDataTrackUnsubscribed(p: RemoteParticipant, pub: RemoteDataTrackPublication, track: RemoteDataTrack) {}
        override fun onVideoTrackPublished(p: RemoteParticipant, pub: RemoteVideoTrackPublication) {}
        override fun onVideoTrackUnpublished(p: RemoteParticipant, pub: RemoteVideoTrackPublication) {}
        override fun onAudioTrackPublished(p: RemoteParticipant, pub: RemoteAudioTrackPublication) {}
        override fun onAudioTrackUnpublished(p: RemoteParticipant, pub: RemoteAudioTrackPublication) {}
        override fun onDataTrackPublished(p: RemoteParticipant, pub: RemoteDataTrackPublication) {}
        override fun onDataTrackUnpublished(p: RemoteParticipant, pub: RemoteDataTrackPublication) {}
        override fun onDataTrackSubscriptionFailed(remoteParticipant: RemoteParticipant, remoteDataTrackPublication: RemoteDataTrackPublication, twilioException: TwilioException) {}
        override fun onAudioTrackSubscriptionFailed(p: RemoteParticipant, pub: RemoteAudioTrackPublication, e: TwilioException) {}
        override fun onVideoTrackSubscriptionFailed(p: RemoteParticipant, pub: RemoteVideoTrackPublication, e: TwilioException) {}
        override fun onAudioTrackEnabled(p: RemoteParticipant, pub: RemoteAudioTrackPublication) {}
        override fun onAudioTrackDisabled(p: RemoteParticipant, pub: RemoteAudioTrackPublication) {}
        override fun onVideoTrackEnabled(p: RemoteParticipant, pub: RemoteVideoTrackPublication) {}
        override fun onVideoTrackDisabled(p: RemoteParticipant, pub: RemoteVideoTrackPublication) {}
        override fun onNetworkQualityLevelChanged(remoteParticipant: RemoteParticipant, networkQualityLevel: NetworkQualityLevel) {}
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isActive = true
        setContentView(R.layout.activity_call_screen)

        bindViews()
        setupSpinners()

        // Apply incoming extras
        txtCallerName.text = intent.getStringExtra("display_name") ?: CallHolder.callerDisplayName ?: "Unknown"
        mode = CallMode.valueOf(intent.getStringExtra("call_mode") ?: CallMode.PSTN_AUDIO.name)

        btnEndCall.setOnClickListener { finishCallGracefully() }
        btnMute.setOnClickListener { toggleMute() }
        btnSpeaker.setOnClickListener { toggleSpeaker() }
        btnUpgradeToVideo.setOnClickListener { toggleVideo() }

        // Register receivers (safe registration)

            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(
                        endCallReceiver,
                        IntentFilter("ACTION_FORCE_END_CALL"),
                        RECEIVER_NOT_EXPORTED
                    )
                    registerReceiver(
                        calleeAnsweredReceiver,
                        IntentFilter("ACTION_CALLEE_ANSWERED"),
                        RECEIVER_NOT_EXPORTED
                    )
                } else {
                    registerReceiver(endCallReceiver, IntentFilter("ACTION_FORCE_END_CALL"))
                    registerReceiver(calleeAnsweredReceiver, IntentFilter("ACTION_CALLEE_ANSWERED"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Receiver registration failed: ${e.message}")
            }


        try {
            registerReceiver(
                translationIncomingReceiver,
                IntentFilter("ACTION_TRANSLATION_INCOMING")
            )
        } catch (e: Exception) {
            Log.w(TAG, "Receiver registration failed: ${e.message}")
        }


        // If the activity asked to start the timer now
        if (intent.getBooleanExtra("start_timer_now", false)) {
            Handler(Looper.getMainLooper()).postDelayed({
                beginTimerSafely()
                CallNotificationService.startOngoing(this, txtCallerName.text.toString(), mode == CallMode.APP_VIDEO)
            }, 700)
        }

        // Handle immediate force_end request (rare)
        if (intent.getBooleanExtra("force_end", false)) {
            finishCallGracefully()
            return
        }

        if (mode == CallMode.PSTN_AUDIO) {
            setupForPstnAudio()
        } else {
            setupForAppVideo()
        }
    }

    private fun bindViews() {
        txtCallerName = findViewById(R.id.txtCallerName)
        txtCallTimer = findViewById(R.id.txtCallTimer)
        btnEndCall = findViewById(R.id.btnEndCall)
        btnMute = findViewById(R.id.btnMute)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        btnUpgradeToVideo = findViewById(R.id.btnUpgradeToVideo)
        remoteContainer = findViewById(R.id.remoteVideoContainer)
        localContainer = findViewById(R.id.localVideoContainer)
        spinnerSourceLang = findViewById(R.id.spinnerSourceLang)
        spinnerTargetLang = findViewById(R.id.spinnerTargetLang)

        remoteVideoView = VideoView(this)
        localVideoView = VideoView(this)
        remoteContainer.addView(remoteVideoView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        localContainer.addView(localVideoView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun setupSpinners() {
        val langs = arrayOf("English", "Spanish", "French", "German", "Hindi")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, langs)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSourceLang.adapter = adapter
        spinnerTargetLang.adapter = adapter
        spinnerTargetLang.setSelection(1)
    }

    // PSTN (Twilio Voice)
    private fun setupForPstnAudio() {
        showVideo(false)
        txtCallTimer.text = "Ringing…"
        voiceCall = CallHolder.activeCall
        Log.d(TAG, "ActiveCall state: ${voiceCall?.state}")

        if (voiceCall == null) {
            Toast.makeText(this, "No active call", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        attachVoiceListener(voiceCall!!, object : VoiceCall.Listener {
            override fun onRinging(call: VoiceCall) {
                runOnUiThread { txtCallTimer.text = "Ringing…" }
            }

            override fun onConnected(call: VoiceCall) {
                runOnUiThread {
                    // Prefer backend push to tell us callee answered; fallback to start timer when connected long enough
                    checkIfCalleeAnswered { answered ->
                        if (answered) {
                            runOnUiThread { startTimerIfNeeded() }

                            val selectedTarget = spinnerTargetLang.selectedItem.toString()
                            val targetLang = mapLangToCode(selectedTarget)

                            sendTargetLangToServer(targetLang)


                        } else {
                            // Wait briefly for stable CONNECTED
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (CallHolder.activeCall?.state == VoiceCall.State.CONNECTED) {
                                    runOnUiThread { startTimerIfNeeded() }
                                }
                            }, 1200)
                        }
                    }
                }
            }





            override fun onDisconnected(call: VoiceCall, error: CallException?) {
                runOnUiThread {
                    stopTimer()
                    finishCallGracefully()
                }
            }

            override fun onConnectFailure(call: VoiceCall, error: CallException) {
                runOnUiThread {
                    Toast.makeText(this@CallScreenActivity, "Connect failed: ${error.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }

            override fun onReconnecting(call: VoiceCall, callException: CallException) {}
            override fun onReconnected(call: VoiceCall) {}
        })

        // Poll state as an extra fallback (non-blocking)
        val handler = Handler(Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                try {
                    val state = CallHolder.activeCall?.state
                    if (state == VoiceCall.State.CONNECTED) {
                        startTimerIfNeeded()
                        return
                    }
                } catch (e: Exception) { Log.w(TAG, "poll error: ${e.message}") }
                handler.postDelayed(this, 800)
            }

        })


    }


    private fun sendTargetLangToServer(target: String) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val from = prefs.getString("identity", "")!!.replace("[^0-9]".toRegex(), "")
        val to   = intent.getStringExtra("to_identity")?.replace("[^0-9]".toRegex(), "") ?: ""

        val json = JSONObject().apply {
            put("fromIdentity", from)
            put("toIdentity", to)
            put("targetLang", target)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val req  = Request.Builder()
            .url("$BACKEND_BASE_URL/set-lang")
            .post(body)
            .build()

        OkHttpClient().newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("LangUpdate", e.message ?: "")
            }

            override fun onResponse(call: Call, resp: Response) {
                resp.close()
            }
        })
    }

    // Video
    private fun setupForAppVideo() {
        if (!checkPermissions()) return
        val token = intent.getStringExtra("access_token")
        val roomName = intent.getStringExtra("room_name")
        if (token.isNullOrEmpty() || roomName.isNullOrEmpty()) {
            txtCallTimer.text = "Connected (audio only)"
            return
        }
        connectVideo(token, roomName)
    }

    private fun connectVideo(token: String, roomName: String) {
        showVideo(true)
        txtCallTimer.text = "Ringing…"
        setAudioMode(true)

        videoCapturer = buildCamera2Capturer()
        localVideo = LocalVideoTrack.create(this, true, videoCapturer!!)
        localAudio = LocalAudioTrack.create(this, true)
        localVideo?.addSink(localVideoView)

        val options = ConnectOptions.Builder(token)
            .roomName(roomName)
            .audioTracks(listOfNotNull(localAudio))
            .videoTracks(listOfNotNull(localVideo))
            .build()

        room = Video.connect(this, options, roomListener)
        CallNotificationService.startOngoing(this, txtCallerName.text.toString(), true)
    }

    private fun buildCamera2Capturer(): VideoCapturer {
        val cameraId = findFrontCameraId() ?: "0"
        return Camera2Capturer(this, cameraId)
    }

    private fun findFrontCameraId(): String? {
        return try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) return id
            }
            null
        } catch (e: Exception) { null }
    }

    // Helpers to attach listeners compatibly
    private fun attachRemoteListener(participant: RemoteParticipant, listener: RemoteParticipant.Listener) {
        try {
            val m = participant.javaClass.getMethod("setListener", RemoteParticipant.Listener::class.java)
            m.invoke(participant, listener); return
        } catch (_: Exception) { }
        try {
            val m = participant.javaClass.getMethod("addListener", RemoteParticipant.Listener::class.java)
            m.invoke(participant, listener)
        } catch (_: Exception) { }
    }

    private fun attachVoiceListener(call: VoiceCall, listener: VoiceCall.Listener) {
        try {
            val m = call.javaClass.getMethod("setListener", VoiceCall.Listener::class.java)
            m.invoke(call, listener); return
        } catch (_: Exception) { }
        try {
            val m = call.javaClass.getMethod("addListener", VoiceCall.Listener::class.java)
            m.invoke(call, listener)
        } catch (_: Exception) { }
    }

    // Timer & notification sync
    private fun startTimerIfNeeded() {
        if (timer != null) return
        txtCallTimer.text = "00:00"
        startTimer()
        setAudioMode(true)
        CallNotificationService.startOngoing(this, txtCallerName.text.toString(), mode == CallMode.APP_VIDEO)
    }

    private fun startTimer() {
        if (timer != null) return
        elapsed = 0L
        timer?.cancel()
        timer = object : CountDownTimer(Long.MAX_VALUE, 1000) {
            override fun onTick(ms: Long) {
                elapsed++
                txtCallTimer.text = String.format("%02d:%02d", elapsed / 60, elapsed % 60)

                // Sync every 2-3 seconds to keep service in sync (less frequent reduces overhead)
                if (elapsed % 3L == 0L) {
                    val syncIntent = Intent(this@CallScreenActivity, CallNotificationService::class.java).apply {
                        action = "com.example.myapplication.ACTION_SYNC_SECONDS"
                        putExtra("elapsed", elapsed)
                        putExtra("caller", txtCallerName.text.toString())
                        putExtra("isVideo", mode == CallMode.APP_VIDEO)
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(syncIntent)
                    } else {
                        startService(syncIntent)
                    }
                }
            }

            override fun onFinish() {}
        }.start()

        // Ensure the notification service starts as foreground
        try {
            val i = Intent(this, CallNotificationService::class.java).apply {
                action = "com.example.myapplication.ACTION_SYNC_SECONDS"
                putExtra("elapsed", elapsed)
                putExtra("caller", txtCallerName.text.toString())
                putExtra("isVideo", mode == CallMode.APP_VIDEO)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start/sync CallNotificationService: ${e.message}")
        }
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    private fun beginTimerSafely() {
        runOnUiThread {
            if (!::txtCallTimer.isInitialized) return@runOnUiThread
            if (timer == null) {
                txtCallTimer.text = "00:00"
                startTimer()
            }
        }
    }

    // UI controls
    private fun toggleMute() {
        if (mode == CallMode.PSTN_AUDIO) {
            CallHolder.activeCall?.let {
                muted = !muted
                try { it.mute(muted) } catch (e: Exception) { Log.w(TAG, "mute error: ${e.message}") }
            }
        } else {
            localAudio?.let { muted = !muted; it.enable(!muted) }
        }
        Toast.makeText(this, if (muted) "Muted" else "Unmuted", Toast.LENGTH_SHORT).show()
        CallNotificationService.startOngoing(this, txtCallerName.text.toString(), mode == CallMode.APP_VIDEO) // refresh notif
    }

    private fun toggleSpeaker() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = !am.isSpeakerphoneOn
    }

    private fun toggleVideo() {
        // If no video tracks yet -> enable camera and publish
        if (localVideo == null) {
            if (!ensureCameraPermission()) {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                videoCapturer = buildCamera2Capturer()
                localVideo = LocalVideoTrack.create(this, true, videoCapturer!!)
                localVideo?.addSink(localVideoView)
                showVideo(true)
                room?.localParticipant?.publishTrack(localVideo!!)
                btnUpgradeToVideo.setImageResource(android.R.drawable.presence_video_online)
                Toast.makeText(this, "Video On", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Enable camera failed: ${e.message}")
            }
            return
        }

        // Turn off camera
        try {
            localVideo?.removeSink(localVideoView)
            room?.localParticipant?.unpublishTrack(localVideo!!)
            localVideo?.release()
            localVideo = null
            showVideo(false)
            btnUpgradeToVideo.setImageResource(android.R.drawable.ic_menu_camera)
            Toast.makeText(this, "Video Off", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Disable camera failed: ${e.message}")
        }
    }

    private fun showVideo(show: Boolean) {
        localContainer.visibility = if (show) View.VISIBLE else View.GONE
        remoteContainer.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) remoteVideoView.visibility = View.GONE
    }

    private fun setAudioMode(inCall: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = if (inCall) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        if (!inCall) am.isSpeakerphoneOn = false
    }

    private fun checkPermissions(): Boolean {
        val missing = REQUIRED_PERMISSIONS.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        return if (missing.isEmpty()) true else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
            false
        }
    }

    private fun ensureCameraPermission(): Boolean {
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!ok) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_PERMS)
        return ok
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (ok) {
                if (mode == CallMode.APP_VIDEO) setupForAppVideo()
                else toggleVideo()
            } else {
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun mapLangToCode(lang: String): String {
        return when (lang) {
            "English" -> "en"
            "Spanish" -> "es"
            "French"  -> "fr"
            "German"  -> "de"
            "Hindi"   -> "hi"
            else -> "en"
        }
    }


    // finish call - safe & graceful
    private fun finishCallGracefully() {
        try {
            // Avoid ending during ringing (server side may want to continue)
            if (::txtCallTimer.isInitialized && txtCallTimer.text.toString().contains("Ringing", true)) {
                Log.w(TAG, "Ignoring finishCall while ringing")
                return
            }


            CallHolder.activeCall?.let {

                try { it.disconnect() } catch (e: Exception) { Log.w(TAG, "disconnect error: ${e.message}") }
            }
            CallHolder.activeCall = null

            room?.let { try { it.disconnect() } catch (_: Exception) {} }
            room = null

            try { localVideo?.release() } catch (_: Exception) {}
            try { localAudio?.release() } catch (_: Exception) {}
            localVideo = null; localAudio = null

            CallHolder.isOutgoing = false
            isActive = false

            // Notify backend END_CALL
            notifyBackendEndCallSafely()

        } catch (e: Exception) {
            Log.e(TAG, "finishCallGracefully error: ${e.message}")
        } finally {
            runOnUiThread {
                CallNotificationService.stopService(this@CallScreenActivity)
                stopTimer()
                setAudioMode(false)
                finish()
            }
        }
    }

    private fun notifyBackendEndCallSafely() {
        try {
            val stored = prefs.getString("identity", "") ?: ""
            val fromDigits = stored.replace("[^0-9]".toRegex(), "")
            val toCandidate = intent.getStringExtra("to_identity")
                ?: intent.getStringExtra("callee_number")
                ?: intent.getStringExtra("display_name")
                ?: CallHolder.callerDisplayName
                ?: ""
            val toE164 = normalizeE164(toCandidate)
            val toDigits = toE164.replace("[^0-9]".toRegex(), "")

            val payload = JSONObject().apply {
                put("fromIdentity", fromDigits)
                put("toIdentity", toDigits)
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$BACKEND_BASE_URL/end-call").post(body).build()
            OkHttpClient().newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { Log.w(TAG, "END_CALL notify failed: ${e.message}") }
                override fun onResponse(call: Call, response: Response) { response.close(); Log.d(TAG, "END_CALL notified: ${response.code}") }
            })
        } catch (e: Exception) {
            Log.w(TAG, "notifyBackendEndCallSafely exception: ${e.message}")
        }
    }

    // helper normalizer
    private fun normalizeE164(num: String?): String {
        if (num.isNullOrBlank()) return ""
        var cleaned = num.trim().replace("client:", "")
        if (!cleaned.startsWith("+")) cleaned = "+${cleaned.filter { it.isDigit() }}"
        return cleaned
    }

    // backend probe for whether callee answered (fast fallback)
    private fun checkIfCalleeAnswered(callback: (Boolean) -> Unit) {
        try {
            val myId = prefs.getString("identity", "") ?: ""
            val url = "$BACKEND_BASE_URL/check-answer?caller=${myId.replace("[^0-9]".toRegex(), "")}"
            OkHttpClient().newCall(Request.Builder().url(url).get().build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "checkIfCalleeAnswered failed: ${e.message}")
                    callback(false)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string()?.trim().orEmpty()
                    val answered = body.contains("true", true)
                    Log.d(TAG, "checkIfCalleeAnswered -> $answered")
                    callback(answered)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "checkIfCalleeAnswered exception: ${e.message}")
            callback(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(endCallReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(calleeAnsweredReceiver) } catch (_: Exception) {}
        isActive = false
        CallHolder.isOutgoing = false
        try { CallHolder.activeCall?.disconnect() } catch (_: Exception) {}
        try { room?.disconnect() } catch (_: Exception) {}
        stopTimer()
        setAudioMode(false)
        try { unregisterReceiver(translationIncomingReceiver) } catch (_: Exception) {}

    }

    private fun beginTimerSafelyForTest() { beginTimerSafely() } // small helper if you want to call it externally

    // small constant(s)
    private val TAG = "CallScreen"



    private fun showTranslatedText(text: String) {
        try { findViewById<TextView>(R.id.txtCaptions)?.text = text }
        catch (_: Exception) {}
    }

    // ⭐ SMART SPEAKER/EARPIECE ROUTING + MUTE ORIGINAL CALL AUDIO ⭐
    // AUDIO QUEUE (ADD THESE ABOVE playTranslatedAudio)
    private val audioQueue = ArrayDeque<String>()
    private var isPlayingAudio = false

    // SAFE NON-FREEZING AUDIO PLAYER
    private fun playTranslatedAudio(b64: String) {
        audioQueue.addLast(b64)
        if (!isPlayingAudio) playNextAudio()
    }

    private fun playNextAudio() {
        if (audioQueue.isEmpty()) {
            isPlayingAudio = false
            return
        }

        isPlayingAudio = true
        val b64 = audioQueue.removeFirst()

        Thread {
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val file = File("${externalCacheDir?.absolutePath}/translated.mp3")
                file.writeBytes(bytes)

                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val wasSpeaker = am.isSpeakerphoneOn

                val call = CallHolder.activeCall
                call?.mute(true)

                val player = MediaPlayer()
                player.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                player.setDataSource(file.absolutePath)

                player.setOnCompletionListener {
                    try { call?.mute(false) } catch (_:Exception){}
                    am.isSpeakerphoneOn = wasSpeaker
                    it.release()
                    playNextAudio()
                }

                player.prepare()  // NOT async → runs safely because in background thread

            } catch (e: Exception) {
                Log.e("TranslatePlay", "Playback error: ${e.message}")
                playNextAudio()
            }
        }.start()
    }


}
