package com.example.myapplication

import android.content.Context
import android.provider.ContactsContract
import com.google.i18n.phonenumbers.PhoneNumberUtil

object PhoneUtils {

    private fun digitsOnly(num: String): String {
        return num.replace("[^0-9]".toRegex(), "")
    }

    fun normalizeIdentity(number: String): String {

        val digits = digitsOnly(number)

        // If number already has country code (11-15 digits)
        if (digits.length > 10) return digits

        // If local 10 digit number → assume India
        if (digits.length == 10) return "91$digits"

        return digits
    }

    fun formatInternational(number: String): String {
        return try {
            val util = PhoneNumberUtil.getInstance()
            val parsed = util.parse(number, null)
            util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
        } catch (e: Exception) {
            number
        }
    }

    fun getContactName(context: Context, number: String): String? {

        val digits = digitsOnly(number)

        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            null
        )

        cursor?.use {

            while (it.moveToNext()) {

                val name = it.getString(0)
                val contactNumber = digitsOnly(it.getString(1))

                if (contactNumber.endsWith(digits) || digits.endsWith(contactNumber)) {
                    return name
                }
            }
        }

        return null
    }

    fun getDisplayName(context: Context, number: String): String {

        val contactName = getContactName(context, number)

        return if (contactName != null) {
            contactName
        } else {
            formatInternational(number)
        }
    }

}