package com.hanson.mobileuse.accessibility

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject

/**
 * Unified UI node representation combining Google Artemis's `A11yNode` schema
 * with 1-based/0-based interactive element indexing and 0..1000 normalized coordinates.
 */
data class A11yNode(
    val index: Int = 0,
    val className: String = "",
    val packageName: String = "",
    val text: String = "",
    val contentDesc: String = "",
    val resourceId: String = "",
    val boundsInScreen: Rect = Rect(),
    val isClickable: Boolean = false,
    val isLongClickable: Boolean = false,
    val isFocusable: Boolean = false,
    val isFocused: Boolean = false,
    val isEnabled: Boolean = true,
    val isScrollable: Boolean = false,
    val isEditable: Boolean = false,
    val isPassword: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isSelected: Boolean = false,
    val isVisibleToUser: Boolean = true,
    val drawingOrder: Int = 0,
    val windowId: Int = -1,
    val windowLayer: Int = 0,
    val windowType: String = "APPLICATION",
    val windowTitle: String = "",
    val isImportantForAccessibility: Boolean = true,
    val actionList: List<String> = emptyList(),
    val children: MutableList<A11yNode> = mutableListOf()
) {
    val centerX: Int
        get() = (boundsInScreen.left + boundsInScreen.right) / 2

    val centerY: Int
        get() = (boundsInScreen.top + boundsInScreen.bottom) / 2

    val shortClassName: String
        get() = className.substringAfterLast('.').ifBlank { "View" }

    val displayLabel: String
        get() = when {
            text.isNotBlank() -> text.trim()
            contentDesc.isNotBlank() -> contentDesc.trim()
            resourceId.isNotBlank() -> resourceId.substringAfterLast('/')
            else -> shortClassName
        }

    fun isInteractiveOrInformative(): Boolean {
        if (!isVisibleToUser) return false
        if (boundsInScreen.width() <= 2 || boundsInScreen.height() <= 2) return false
        return isClickable ||
            isLongClickable ||
            isScrollable ||
            isEditable ||
            isCheckable ||
            text.isNotBlank() ||
            contentDesc.isNotBlank()
    }

    fun toJson(screenWidth: Int = 1080, screenHeight: Int = 2400): JSONObject {
        val norm = CoordinateNormalizer.pixelToNormalized(centerX, centerY, screenWidth, screenHeight)
        return JSONObject().apply {
            put("index", index)
            put("class", className)
            put("shortClass", shortClassName)
            put("package", packageName)
            put("text", text)
            put("contentDesc", contentDesc)
            put("resourceId", resourceId)
            put("clickable", isClickable)
            put("longClickable", isLongClickable)
            put("focusable", isFocusable)
            put("focused", isFocused)
            put("enabled", isEnabled)
            put("scrollable", isScrollable)
            put("editable", isEditable)
            put("password", isPassword)
            put("checkable", isCheckable)
            put("checked", isChecked)
            put("selected", isSelected)
            put("visible", isVisibleToUser)
            put("drawingOrder", drawingOrder)
            put("windowId", windowId)
            put("windowLayer", windowLayer)
            put("windowType", windowType)
            put("windowTitle", windowTitle)
            put("important", isImportantForAccessibility)
            put(
                "bounds",
                JSONObject().apply {
                    put("left", boundsInScreen.left)
                    put("top", boundsInScreen.top)
                    put("right", boundsInScreen.right)
                    put("bottom", boundsInScreen.bottom)
                    put("centerX", centerX)
                    put("centerY", centerY)
                    put("normX", norm.first)
                    put("normY", norm.second)
                }
            )
            put("actions", JSONArray(actionList))
            val childArray = JSONArray()
            for (child in children) {
                childArray.put(child.toJson(screenWidth, screenHeight))
            }
            put("children", childArray)
        }
    }
}

/**
 * Flattened, indexed UI element used in the multimodal prompt ("Visible UI Elements")
 * and the Live UI Tree Inspector.
 */
data class IndexedUiElement(
    val index: Int,
    val shortClass: String,
    val className: String,
    val packageName: String,
    val text: String,
    val contentDesc: String,
    val resourceId: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val centerX: Int,
    val centerY: Int,
    val normX: Int,
    val normY: Int,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val focused: Boolean,
    val enabled: Boolean,
    val windowType: String,
    val windowLayer: Int
) {
    fun toPromptLine(): String {
        val flags = buildList {
            if (clickable) add("clickable")
            if (longClickable) add("longClickable")
            if (scrollable) add("scrollable")
            if (editable) add("editable")
            if (checkable) add(if (checked) "checked" else "unchecked")
            if (focused) add("focused")
            if (!enabled) add("disabled")
        }.joinToString(",")

        val parts = mutableListOf<String>()
        parts.add("[$index] $shortClass")
        if (text.isNotBlank()) parts.add("text=\"${text.replace("\n", " ").take(80)}\"")
        if (contentDesc.isNotBlank()) parts.add("desc=\"${contentDesc.replace("\n", " ").take(60)}\"")
        if (resourceId.isNotBlank()) parts.add("id=\"${resourceId.substringAfterLast('/')}\"")
        parts.add("norm=[$normX,$normY]")
        parts.add("px=($centerX,$centerY)")
        parts.add("bounds=[$left,$top][$right,$bottom]")
        if (flags.isNotEmpty()) parts.add("flags={$flags}")
        return parts.joinToString(" ")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("index", index)
        put("shortClass", shortClass)
        put("className", className)
        put("packageName", packageName)
        put("text", text)
        put("contentDesc", contentDesc)
        put("resourceId", resourceId)
        put("left", left)
        put("top", top)
        put("right", right)
        put("bottom", bottom)
        put("centerX", centerX)
        put("centerY", centerY)
        put("normX", normX)
        put("normY", normY)
        put("clickable", clickable)
        put("longClickable", longClickable)
        put("scrollable", scrollable)
        put("editable", editable)
        put("checkable", checkable)
        put("checked", checked)
        put("focused", focused)
        put("enabled", enabled)
        put("windowType", windowType)
        put("windowLayer", windowLayer)
    }
}
