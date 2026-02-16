package com.raph563.cheatupdater.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class CheatAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // V1: service present to let user grant accessibility access from settings.
    }

    override fun onInterrupt() = Unit
}
