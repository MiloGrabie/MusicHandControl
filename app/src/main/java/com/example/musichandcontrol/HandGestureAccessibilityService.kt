package com.example.musichandcontrol

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.util.Log

class HandGestureAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "HandGestureAccessibility"
    }

    override fun onServiceConnected() {
        Log.d(TAG, "HandGestureAccessibilityService connected")
        // Start the MainActivity in the background
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to handle any accessibility events
    }

    override fun onInterrupt() {
        // Handle interruption if needed
    }
}