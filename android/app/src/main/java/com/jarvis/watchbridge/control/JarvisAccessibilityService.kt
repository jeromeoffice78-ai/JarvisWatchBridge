package com.jarvis.watchbridge.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

data class ActionResult(val success: Boolean, val message: String)

class JarvisAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var active: JarvisAccessibilityService? = null

        fun performGlobal(action: Int): Boolean = active?.performGlobalAction(action) ?: false
        fun tapText(text: String): ActionResult = active?.tapTextInternal(text) ?: ActionResult(false, "Accessibility service is not enabled.")
        fun typeText(text: String): ActionResult = active?.typeTextInternal(text) ?: ActionResult(false, "Accessibility service is not enabled.")
        fun scrollForward(): ActionResult = active?.scrollInternal(true) ?: ActionResult(false, "Accessibility service is not enabled.")
        fun scrollBackward(): ActionResult = active?.scrollInternal(false) ?: ActionResult(false, "Accessibility service is not enabled.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
        serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    private fun tapTextInternal(text: String): ActionResult {
        val target = text.trim()
        if (target.isEmpty()) return ActionResult(false, "Tap text is empty.")
        val root = rootInActiveWindow ?: return ActionResult(false, "No active app window.")
        val node = root.findAccessibilityNodeInfosByText(target).firstOrNull { it.isVisibleToUser }
            ?: return ActionResult(false, "No visible control containing '$target'.")
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        val ok = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        return if (ok) ActionResult(true, "Tapped '$target'.") else ActionResult(false, "Visible text found, but it was not clickable.")
    }

    private fun typeTextInternal(text: String): ActionResult {
        if (text.length > 4000) return ActionResult(false, "Text exceeds safe input length.")
        val root = rootInActiveWindow ?: return ActionResult(false, "No active app window.")
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root)
            ?: return ActionResult(false, "No editable field is focused.")
        if (focused.isPassword) {
            return ActionResult(false, "JARVIS will not remotely inject raw passwords. Use Android Credential Manager or Autofill.")
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) ActionResult(true, "Text entered.") else ActionResult(false, "Android rejected text entry.")
    }

    private fun scrollInternal(forward: Boolean): ActionResult {
        val root = rootInActiveWindow ?: return ActionResult(false, "No active app window.")
        val scrollable = findScrollable(root) ?: return ActionResult(false, "No scrollable control is visible.")
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val ok = scrollable.performAction(action)
        return ActionResult(ok, if (ok) "Scrolled." else "Android rejected scrolling.")
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { findEditable(it)?.let { found -> return found } }
        return null
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { findScrollable(it)?.let { found -> return found } }
        return null
    }
}
