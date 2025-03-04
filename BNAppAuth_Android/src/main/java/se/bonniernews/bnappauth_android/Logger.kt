package se.bonniernews.bnappauth_android

import android.util.Log

object Logger {
    fun debug(message: Any?, debuggable: Boolean = false) {
        if (debuggable) {
            Log.d("BonnierAppAuth", "$message")
        }
    }

    fun error(message: Any?, debuggable: Boolean = false) {
        if (debuggable) {
            Log.e("BonnierAppAuth", "$message")
        }
    }
}