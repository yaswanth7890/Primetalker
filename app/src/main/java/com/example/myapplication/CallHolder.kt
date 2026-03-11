package com.example.myapplication

import com.twilio.voice.Call as VoiceCall

object CallHolder {
    @Volatile var activeCall: VoiceCall? = null
    var isOutgoing = false   // 🚀 new flag
    var callerDisplayName: String? = null

    var calleeAnswered = false

    var isMuted: Boolean = false
    var isSpeakerOn: Boolean = false

    val captionHistory = mutableListOf<LiveCaption>()

}
