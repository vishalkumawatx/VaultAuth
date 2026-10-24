package com.example.authanticator.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import java.util.UUID

class ClipboardGuard(context: Context) {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var label: String? = null
    private val clearTask = Runnable { clear() }

    fun copy(code: String) {
        clear()
        label = "Vault Auth ${UUID.randomUUID()}"
        val clip = ClipData.newPlainText(label, code)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        clipboard.setPrimaryClip(clip)
        handler.postDelayed(clearTask, 30_000)
    }

    fun clear() {
        handler.removeCallbacks(clearTask)
        if (label != null && clipboard.primaryClipDescription?.label?.toString() == label) clipboard.clearPrimaryClip()
        label = null
    }
}