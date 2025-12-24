package com.example.myapplication

import com.twilio.voice.CallInvite

object IncomingCallHolder {
    @Volatile var invite: CallInvite? = null
}
