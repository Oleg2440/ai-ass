package com.example.aiassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AssistantAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AssistantAccessibilityService? = null

        fun screenText(): String {
            val root = instance?.rootInActiveWindow ?: return ""
            val sb = StringBuilder()
            fun walk(node: AccessibilityNodeInfo) {
                node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
                    sb.append("· ").append(it).append('\n')
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it) }
            }
            walk(root)
            return sb.toString().take(4000)
        }

        fun clickByText(target: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(target) ?: return false
            for (n in nodes) {
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                var p: AccessibilityNodeInfo? = n.parent
                while (p != null) {
                    if (p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    p = p.parent
                }
            }
            return false
        }

        fun swipe(direction: String): Boolean {
            val svc = instance ?: return false
            val dm = svc.resources.displayMetrics
            val cx = dm.widthPixels / 2f
            val cy = dm.heightPixels / 2f
            val d = dm.heightPixels / 5f
            val path = Path()
            when (direction.lowercase()) {
                "up"    -> { path.moveTo(cx, cy + d); path.lineTo(cx, cy - d) }
                "down"  -> { path.moveTo(cx, cy - d); path.lineTo(cx, cy + d) }
                "left"  -> { path.moveTo(cx + d, cy); path.lineTo(cx - d, cy) }
                "right" -> { path.moveTo(cx - d, cy); path.lineTo(cx + d, cy) }
                else -> return false
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
                .build()
            return svc.dispatchGesture(gesture, null, null)
        }

        fun typeText(text: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            var target: AccessibilityNodeInfo? = null
            fun walk(n: AccessibilityNodeInfo) {
                if (target == null && n.className?.contains("EditText") == true) target = n
                for (i in 0 until n.childCount) {
                    if (target == null) n.getChild(i)?.let { walk(it) }
                }
            }
            walk(root)
            val t = target ?: return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return t.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}