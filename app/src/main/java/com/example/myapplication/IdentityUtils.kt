package com.example.myapplication

object IdentityUtils {

    fun digitsOnly(value: String?): String {
        if (value == null) return ""
        return value
            .replace("client:", "")
            .replace("+", "")
            .filter { it.isDigit() }
    }

    fun e164FromDigits(digits: String): String {
        return "+$digits"
    }
}
