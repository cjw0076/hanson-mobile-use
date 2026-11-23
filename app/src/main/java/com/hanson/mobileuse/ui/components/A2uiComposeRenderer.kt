package com.hanson.mobileuse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanson.mobileuse.agent.A2uiActionSpec
import com.hanson.mobileuse.agent.A2uiComponentNode
import com.hanson.mobileuse.agent.A2uiDataBindingResolver
import com.hanson.mobileuse.agent.A2uiJsonPointer
import com.hanson.mobileuse.agent.A2uiSurfaceRegistry
import com.hanson.mobileuse.agent.A2uiSurfaceState
import com.hanson.mobileuse.agent.A2uiUserAction
import com.hanson.mobileuse.ui.theme.ObsidianColors
import java.net.URI
import kotlin.math.roundToInt

private const val MAX_A2UI_RENDER_DEPTH = 24

/**
 * Renders a vertical deck of all active Google A2UI surfaces in Hanson's strictly 0%-saturation
 * `ObsidianColors` Paper Light & Carbon Dark palette.
 */
@Composable
fun A2uiSurfaceDeck(
    surfaces: Map<String, A2uiSurfaceState>,
    modifier: Modifier = Modifier,
    compactOverlayMode: Boolean = false,
    onUserAction: (A2uiUserAction) -> Unit = {},
    onDismissSurface: ((String) -> Unit)? = { surfaceId -> A2uiSurfaceRegistry.deleteSurface(surfaceId) }
) {
    val renderable = surfaces.values.filter { it.isReady && it.hasRootComponent }
    if (renderable.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compactOverlayMode) 8.dp else 12.dp)
    ) {
        renderable.forEach { surface ->
            A2uiSurfaceCard(
                surface = surface,
                compactOverlayMode = compactOverlayMode,
                onUserAction = onUserAction,
                onDismiss = onDismissSurface?.let { dismissFn -> { dismissFn(surface.surfaceId) } }
            )
        }
    }
}

/**
 * Native Jetpack Compose card rendering a single Google A2UI surface (`v1.0` / `v0.9` / `v0.8`).
 *
 * Strictly obeys Hanson's pure achromatic `ObsidianColors` palette (0% saturation) across both
 * in-app (`CommandCenterScreen`) and out-of-app (`DynamicCanvasOverlayService`) contexts.
 */
@Composable
fun A2uiSurfaceCard(
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    compactOverlayMode: Boolean = false,
    onUserAction: (A2uiUserAction) -> Unit = {},
    onDataModelMutation: (surfaceId: String, pointer: String, newValue: Any?) -> Unit = { sid, ptr, value ->
        A2uiSurfaceRegistry.updateDataModelAtPath(sid, ptr, value)
    },
    onDismiss: (() -> Unit)? = null
) {
    if (!surface.isReady || !surface.hasRootComponent) return

    val cardRadius = if (compactOverlayMode) 16.dp else 20.dp
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cardRadius))
            .background(ObsidianColors.Surface)
            .border(1.2.dp, ObsidianColors.Accent.copy(alpha = 0.34f), RoundedCornerShape(cardRadius))
            .padding(if (compactOverlayMode) 12.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (compactOverlayMode) 8.dp else 10.dp)
    ) {
        // Monochrome A2UI Surface Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(ObsidianColors.Accent)
                )
                Text(
                    text = "A2UI ${surface.protocolVersion} · ${surface.surfaceId}",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${surface.components.size} nodes",
                        color = ObsidianColors.TextSecondary,
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (onDismiss != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.SurfaceHigh)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                            .clickable { onDismiss() }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "✕",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = ObsidianColors.Divider, thickness = 0.8.dp)

        // Root A2UI Component Tree
        val bodyModifier = if (compactOverlayMode) {
            Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
        } else {
            Modifier.fillMaxWidth()
        }

        Box(modifier = bodyModifier) {
            A2uiNodeRenderer(
                surface = surface,
                componentId = surface.rootComponentId,
                scopePath = null,
                depth = 0,
                visitedIds = emptySet(),
                invertedPrimary = false,
                onUserAction = onUserAction,
                onDataModelMutation = onDataModelMutation,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun A2uiNodeRenderer(
    surface: A2uiSurfaceState,
    componentId: String,
    scopePath: String?,
    depth: Int,
    visitedIds: Set<String>,
    invertedPrimary: Boolean,
    onUserAction: (A2uiUserAction) -> Unit,
    onDataModelMutation: (surfaceId: String, pointer: String, newValue: Any?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (depth > MAX_A2UI_RENDER_DEPTH) return
    val visitKey = if (scopePath == null) componentId else "$componentId@$scopePath"
    if (visitKey in visitedIds) return
    val node = surface.components[componentId] ?: return
    val nextVisited = visitedIds + visitKey

    when (node.type) {
        "Column" -> A2uiColumnNode(
            surface = surface,
            node = node,
            scopePath = scopePath,
            depth = depth,
            visitedIds = nextVisited,
            invertedPrimary = invertedPrimary,
            onUserAction = onUserAction,
            onDataModelMutation = onDataModelMutation,
            modifier = modifier
        )
        "Row" -> A2uiRowNode(
            surface = surface,
            node = node,
            scopePath = scopePath,
            depth = depth,
            visitedIds = nextVisited,
            invertedPrimary = invertedPrimary,
            onUserAction = onUserAction,
            onDataModelMutation = onDataModelMutation,
            modifier = modifier
        )
        "List" -> A2uiListNode(
            surface = surface,
            node = node,
            scopePath = scopePath,
            depth = depth,
            visitedIds = nextVisited,
            invertedPrimary = invertedPrimary,
            onUserAction = onUserAction,
            onDataModelMutation = onDataModelMutation,
            modifier = modifier
        )
        "Card" -> A2uiCardNode(
            surface = surface,
            node = node,
            scopePath = scopePath,
            depth = depth,
            visitedIds = nextVisited,
            onUserAction = onUserAction,
            onDataModelMutation = onDataModelMutation,
            modifier = modifier
        )
        "Box" -> A2uiBoxNode(
            surface = surface,
            node = node,
            scopePath = scopePath,
            depth = depth,
            visitedIds = nextVisited,
            invertedPrimary = invertedPrimary,
            onUserAction = onUserAction,
            onDataModelMutation = onDataModelMutation,
            modifier = modifier
        )
        "Text" -> A2uiTextNode(
            surface = surface,
            node = node,
            scopePath = scopePath,
            invertedPrimary = invertedPrimary,
            modifier = modifier
        )
        "Button" -> A2uiButtonNode(
            surface = surface,
            node = node,
            scopePath = scopePath,
            depth = depth,
            visitedIds = nextVisited,
            onUserAction = onUserAction,
            onDataModelMutation = onDataModelMutation,
            modifier = modifier
        )
        "TextField" -> A2uiTextFieldNode(
            surface = surface,
            node = node,
            scopePath = scopePath,
            onDataModelMutation = onDataModelMutation,
            modifier = modifier
        )
        "CheckBox" -> A2uiCheckBoxNode(
            surface = surface,
            node = node,
            scopePath = scopePath,
            onDataModelMutation = onDataModelMutation,
            modifier = modifier
        )
        "MultipleChoice", "ChoicePicker" -> A2uiMultipleChoiceNode(
            surface = surface,
            node = node,
            scopePath = scopePath,
            onDataModelMutation = onDataModelMutation,
            modifier = modifier
        )
        "Slider" -> A2uiSliderNode(
            surface = surface,
            node = node,
            scopePath = scopePath,
            onDataModelMutation = onDataModelMutation,
            modifier = modifier
        )
        "Divider" -> A2uiDividerNode(
            node = node,
            modifier = modifier
        )
        "Icon" -> A2uiIconNode(
            surface = surface,
            node = node,
            scopePath = scopePath,
            invertedPrimary = invertedPrimary,
            modifier = modifier
        )
        "Image" -> A2uiImageNode(
            surface = surface,
            node = node,
            scopePath = scopePath,
            modifier = modifier
        )
        else -> A2uiUnknownFallbackNode(
            surface = surface,
            node = node,
            scopePath = scopePath,
            modifier = modifier
        )
    }
}

private fun expandChildInstances(
    surface: A2uiSurfaceState,
    node: A2uiComponentNode,
    parentScopePath: String?
): List<Pair<String, String?>> {
    val spec = A2uiDataBindingResolver.resolveChildrenSpec(node)
    if (spec.explicitIds.isNotEmpty()) {
        return spec.explicitIds.map { it to parentScopePath }
    }
    val tpl = spec.template ?: return emptyList()
    val resolvedBase = A2uiJsonPointer.resolvePointerPath(tpl.path, parentScopePath)
    val boundValue = A2uiJsonPointer.resolve(surface.dataModel, resolvedBase)
    return when (boundValue) {
        is List<*> -> boundValue.indices.map { idx ->
            val itemScope = if (resolvedBase == "/") "/$idx" else "$resolvedBase/$idx"
            tpl.componentId to itemScope
        }
        is Map<*, *> -> boundValue.keys.mapNotNull { k ->
            val keyStr = k?.toString() ?: return@mapNotNull null
            val escaped = A2uiJsonPointer.escapeToken(keyStr)
            val itemScope = if (resolvedBase == "/") "/$escaped" else "$resolvedBase/$escaped"
            tpl.componentId to itemScope
        }
        else -> emptyList()
    }
}

@Composable
private fun A2uiColumnNode(
    surface: A2uiSurfaceState,
    node: A2uiComponentNode,
    scopePath: String?,
    depth: Int,
    visitedIds: Set<String>,
    invertedPrimary: Boolean,
    onUserAction: (A2uiUserAction) -> Unit,
    onDataModelMutation: (String, String, Any?) -> Unit,
    modifier: Modifier
) {
    val alignProp = (node.properties["align"] ?: node.properties["alignment"])?.toString()?.lowercase()
    val horizontalAlign = when (alignProp) {
        "center" -> Alignment.CenterHorizontally
        "end" -> Alignment.End
        else -> Alignment.Start
    }
    val justifyProp = (node.properties["justify"] ?: node.properties["distribution"])?.toString()?.lowercase()
    val verticalArr = when (justifyProp) {
        "center" -> Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
        "end" -> Arrangement.spacedBy(8.dp, Alignment.Bottom)
        "spacebetween" -> Arrangement.SpaceBetween
        "spacearound" -> Arrangement.SpaceAround
        "spaceevenly" -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(8.dp)
    }

    val children = expandChildInstances(surface, node, scopePath)
    Column(
        modifier = modifier,
        verticalArrangement = verticalArr,
        horizontalAlignment = horizontalAlign
    ) {
        children.forEach { (childId, childScope) ->
            val childNode = surface.components[childId]
            val childMod = if (alignProp == "stretch" || alignProp == null || childNode?.type in setOf("Card", "Row", "Column", "TextField", "Divider", "Slider", "MultipleChoice", "ChoicePicker", "CheckBox")) {
                Modifier.fillMaxWidth()
            } else {
                Modifier
            }
            A2uiNodeRenderer(
                surface = surface,
                componentId = childId,
                scopePath = childScope,
                depth = depth + 1,
                visitedIds = visitedIds,
                invertedPrimary = invertedPrimary,
                onUserAction = onUserAction,
                onDataModelMutation = onDataModelMutation,
                modifier = childMod
            )
        }
    }
}

@Composable
private fun A2uiRowNode(
    surface: A2uiSurfaceState,
    node: A2uiComponentNode,
    scopePath: String?,
    depth: Int,
    visitedIds: Set<String>,
    invertedPrimary: Boolean,
    onUserAction: (A2uiUserAction) -> Unit,
    onDataModelMutation: (String, String, Any?) -> Unit,
    modifier: Modifier
) {
    val alignProp = (node.properties["align"] ?: node.properties["alignment"])?.toString()?.lowercase()
    val verticalAlign = when (alignProp) {
        "start" -> Alignment.Top
        "end" -> Alignment.Bottom
        else -> Alignment.CenterVertically
    }
    val justifyProp = (node.properties["justify"] ?: node.properties["distribution"])?.toString()?.lowercase()
    val horizontalArr = when (justifyProp) {
        "center" -> Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        "end" -> Arrangement.spacedBy(8.dp, Alignment.End)
        "spacebetween" -> Arrangement.spacedBy(8.dp)
        "spacearound" -> Arrangement.SpaceAround
        "spaceevenly" -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(8.dp)
    }

    val children = expandChildInstances(surface, node, scopePath)
    val hasWeightedChild = children.any { (cid, _) -> (surface.components[cid]?.weight ?: 0f) > 0f }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (justifyProp == "spacebetween" && !hasWeightedChild) {
            Arrangement.SpaceBetween
        } else {
            horizontalArr
        },
        verticalAlignment = verticalAlign
    ) {
        children.forEach { (childId, childScope) ->
            val weight = surface.components[childId]?.weight
            val childMod = if (weight != null && weight > 0f) {
                Modifier.weight(weight)
            } else {
                Modifier
            }
            A2uiNodeRenderer(
                surface = surface,
                componentId = childId,
                scopePath = childScope,
                depth = depth + 1,
                visitedIds = visitedIds,
                invertedPrimary = invertedPrimary,
                onUserAction = onUserAction,
                onDataModelMutation = onDataModelMutation,
                modifier = childMod
            )
        }
    }
}

@Composable
private fun A2uiListNode(
    surface: A2uiSurfaceState,
    node: A2uiComponentNode,
    scopePath: String?,
    depth: Int,
    visitedIds: Set<String>,
    invertedPrimary: Boolean,
    onUserAction: (A2uiUserAction) -> Unit,
    onDataModelMutation: (String, String, Any?) -> Unit,
    modifier: Modifier
) {
    val direction = node.properties["direction"]?.toString()?.lowercase() ?: "vertical"
    val children = expandChildInstances(surface, node, scopePath)
    if (direction == "horizontal") {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            children.forEach { (childId, childScope) ->
                A2uiNodeRenderer(
                    surface = surface,
                    componentId = childId,
                    scopePath = childScope,
                    depth = depth + 1,
                    visitedIds = visitedIds,
                    invertedPrimary = invertedPrimary,
                    onUserAction = onUserAction,
                    onDataModelMutation = onDataModelMutation
                )
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            children.forEach { (childId, childScope) ->
                A2uiNodeRenderer(
                    surface = surface,
                    componentId = childId,
                    scopePath = childScope,
                    depth = depth + 1,
                    visitedIds = visitedIds,
                    invertedPrimary = invertedPrimary,
                    onUserAction = onUserAction,
                    onDataModelMutation = onDataModelMutation,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun A2uiCardNode(
    surface: A2uiSurfaceState,
    node: A2uiComponentNode,
    scopePath: String?,
    depth: Int,
    visitedIds: Set<String>,
    onUserAction: (A2uiUserAction) -> Unit,
    onDataModelMutation: (String, String, Any?) -> Unit,
    modifier: Modifier
) {
    val children = expandChildInstances(surface, node, scopePath)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        children.forEach { (childId, childScope) ->
            A2uiNodeRenderer(
                surface = surface,
                componentId = childId,
                scopePath = childScope,
                depth = depth + 1,
                visitedIds = visitedIds,
                invertedPrimary = false,
                onUserAction = onUserAction,
                onDataModelMutation = onDataModelMutation,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun A2uiBoxNode(
    surface: A2uiSurfaceState,
    node: A2uiComponentNode,
    scopePath: String?,
    depth: Int,
    visitedIds: Set<String>,
    invertedPrimary: Boolean,
    onUserAction: (A2uiUserAction) -> Unit,
    onDataModelMutation: (String, String, Any?) -> Unit,
    modifier: Modifier
) {
    val alignProp = (node.properties["align"] ?: node.properties["contentAlignment"])?.toString()?.lowercase()
    val contentAlignment = when (alignProp) {
        "center" -> Alignment.Center
        "end", "bottomend" -> Alignment.BottomEnd
        "topend" -> Alignment.TopEnd
        "bottomstart" -> Alignment.BottomStart
        "bottomcenter" -> Alignment.BottomCenter
        "topcenter" -> Alignment.TopCenter
        else -> Alignment.TopStart
    }
    val children = expandChildInstances(surface, node, scopePath)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ObsidianColors.SurfaceHigh.copy(alpha = 0.55f))
            .padding(8.dp),
        contentAlignment = contentAlignment
    ) {
        children.forEach { (childId, childScope) ->
            A2uiNodeRenderer(
                surface = surface,
                componentId = childId,
                scopePath = childScope,
                depth = depth + 1,
                visitedIds = visitedIds,
                invertedPrimary = invertedPrimary,
                onUserAction = onUserAction,
                onDataModelMutation = onDataModelMutation
            )
        }
    }
}

@Composable
private fun A2uiTextNode(
    surface: A2uiSurfaceState,
    node: A2uiComponentNode,
    scopePath: String?,
    invertedPrimary: Boolean,
    modifier: Modifier
) {
    val resolvedText = A2uiDataBindingResolver.resolveString(
        raw = node.properties["text"],
        dataModel = surface.dataModel,
        scopePath = scopePath
    )
    val variant = (node.properties["variant"] ?: node.properties["usageHint"])
        ?.toString()
        ?.trim()
        ?.lowercase()
        ?: "body"

    val (fontSize, lineHeight, fontWeight, defaultColor) = when (variant) {
        "h1" -> Quad(19.sp, 25.sp, FontWeight.ExtraBold, ObsidianColors.TextPrimary)
        "h2" -> Quad(16.sp, 22.sp, FontWeight.ExtraBold, ObsidianColors.TextPrimary)
        "h3" -> Quad(14.5.sp, 20.sp, FontWeight.Bold, ObsidianColors.TextPrimary)
        "h4" -> Quad(13.sp, 18.sp, FontWeight.Bold, ObsidianColors.TextPrimary)
        "h5" -> Quad(12.sp, 17.sp, FontWeight.SemiBold, ObsidianColors.TextPrimary)
        "caption" -> Quad(11.sp, 15.sp, FontWeight.SemiBold, ObsidianColors.TextSecondary)
        else -> Quad(12.5.sp, 18.sp, FontWeight.Normal, ObsidianColors.TextPrimary)
    }

    Text(
        text = resolvedText,
        color = if (invertedPrimary) ObsidianColors.OnAccent else defaultColor,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        modifier = modifier
    )
}

@Composable
private fun A2uiButtonNode(
    surface: A2uiSurfaceState,
    node: A2uiComponentNode,
    scopePath: String?,
    depth: Int,
    visitedIds: Set<String>,
    onUserAction: (A2uiUserAction) -> Unit,
    onDataModelMutation: (String, String, Any?) -> Unit,
    modifier: Modifier
) {
    val variant = node.properties["variant"]?.toString()?.trim()?.lowercase()
        ?: if (node.properties["primary"] == true) "primary" else "default"
    val isPrimary = variant == "primary"
    val isBorderless = variant == "borderless"

    val bgColor = when {
        isPrimary -> ObsidianColors.Accent
        isBorderless -> Color.Transparent
        else -> ObsidianColors.SurfaceHigh
    }
    val borderColor = when {
        isPrimary -> ObsidianColors.Accent
        isBorderless -> Color.Transparent
        else -> ObsidianColors.GlassHighlight
    }
    val contentColor = if (isPrimary) ObsidianColors.OnAccent else ObsidianColors.TextPrimary

    val actionSpec = A2uiDataBindingResolver.parseActionSpec(node.properties["action"])
    val childId = node.properties["child"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    val fallbackLabel = A2uiDataBindingResolver.resolveString(
        raw = node.properties["label"] ?: node.properties["text"],
        dataModel = surface.dataModel,
        scopePath = scopePath,
        default = actionSpec?.name ?: "Action"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bgColor)
            .then(
                if (!isBorderless) {
                    Modifier.border(1.dp, borderColor, RoundedCornerShape(999.dp))
                } else {
                    Modifier
                }
            )
            .clickable {
                val spec = actionSpec ?: A2uiActionSpec(name = node.id)
                val dispatched = A2uiSurfaceRegistry.dispatchUserAction(
                    surfaceId = surface.surfaceId,
                    sourceComponentId = node.id,
                    actionSpec = spec,
                    scopePath = scopePath
                )
                if (dispatched != null) {
                    onUserAction(dispatched)
                }
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            if (childId != null && surface.components.containsKey(childId)) {
                A2uiNodeRenderer(
                    surface = surface,
                    componentId = childId,
                    scopePath = scopePath,
                    depth = depth + 1,
                    visitedIds = visitedIds,
                    invertedPrimary = isPrimary,
                    onUserAction = onUserAction,
                    onDataModelMutation = onDataModelMutation
                )
            } else {
                Text(
                    text = fallbackLabel,
                    color = contentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun A2uiTextFieldNode(
    surface: A2uiSurfaceState,
    node: A2uiComponentNode,
    scopePath: String?,
    onDataModelMutation: (String, String, Any?) -> Unit,
    modifier: Modifier
) {
    val label = A2uiDataBindingResolver.resolveString(
        raw = node.properties["label"],
        dataModel = surface.dataModel,
        scopePath = scopePath
    )
    val placeholder = A2uiDataBindingResolver.resolveString(
        raw = node.properties["placeholder"],
        dataModel = surface.dataModel,
        scopePath = scopePath
    )
    val rawValueProp = node.properties["value"] ?: node.properties["text"]
    val bindingPath = A2uiDataBindingResolver.extractBindingPath(rawValueProp)
    val boundValue = A2uiDataBindingResolver.resolveString(
        raw = rawValueProp,
        dataModel = surface.dataModel,
        scopePath = scopePath
    )
    var localText by remember(surface.surfaceId, node.id, scopePath) { mutableStateOf(boundValue) }
    val currentText = if (bindingPath != null) boundValue else localText

    val variant = (node.properties["variant"] ?: node.properties["textFieldType"])
        ?.toString()
        ?.trim()
        ?.lowercase()
        ?: "shorttext"
    val isObscured = variant == "obscured"
    val isNumber = variant == "number"
    val isLongText = variant == "longtext"

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                color = ObsidianColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        OutlinedTextField(
            value = currentText,
            onValueChange = { updated ->
                if (bindingPath != null) {
                    val resolvedPtr = A2uiJsonPointer.resolvePointerPath(bindingPath, scopePath)
                    onDataModelMutation(surface.surfaceId, resolvedPtr, updated)
                } else {
                    localText = updated
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = !isLongText,
            minLines = if (isLongText) 2 else 1,
            maxLines = if (isLongText) 4 else 1,
            placeholder = if (placeholder.isNotBlank()) {
                {
                    Text(
                        text = placeholder,
                        color = ObsidianColors.TextMuted,
                        fontSize = 12.sp
                    )
                }
            } else {
                null
            },
            visualTransformation = if (isObscured) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isNumber) KeyboardType.Number else KeyboardType.Text
            ),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ObsidianColors.Accent,
                unfocusedBorderColor = ObsidianColors.GlassBorder,
                focusedTextColor = ObsidianColors.TextPrimary,
                unfocusedTextColor = ObsidianColors.TextPrimary,
                cursorColor = ObsidianColors.Accent
            )
        )
    }
}

@Composable
private fun A2uiCheckBoxNode(
    surface: A2uiSurfaceState,
    node: A2uiComponentNode,
    scopePath: String?,
    onDataModelMutation: (String, String, Any?) -> Unit,
    modifier: Modifier
) {
    val label = A2uiDataBindingResolver.resolveString(
        raw = node.properties["label"],
        dataModel = surface.dataModel,
        scopePath = scopePath
    )
    val rawValueProp = node.properties["value"]
    val bindingPath = A2uiDataBindingResolver.extractBindingPath(rawValueProp)
    val boundChecked = A2uiDataBindingResolver.resolveBoolean(
        raw = rawValueProp,
        dataModel = surface.dataModel,
        scopePath = scopePath,
        default = false
    )
    var localChecked by remember(surface.surfaceId, node.id, scopePath) { mutableStateOf(boundChecked) }
    val checked = if (bindingPath != null) boundChecked else localChecked

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, if (checked) ObsidianColors.Accent else ObsidianColors.GlassBorder, RoundedCornerShape(10.dp))
            .clickable {
                val next = !checked
                if (bindingPath != null) {
                    val resolvedPtr = A2uiJsonPointer.resolvePointerPath(bindingPath, scopePath)
                    onDataModelMutation(surface.surfaceId, resolvedPtr, next)
                } else {
                    localChecked = next
                }
            }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (checked) ObsidianColors.Accent else ObsidianColors.Surface)
                .border(1.2.dp, ObsidianColors.Accent, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "선택됨",
                    tint = ObsidianColors.OnAccent,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
        Text(
            text = label,
            color = ObsidianColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun A2uiMultipleChoiceNode(
    surface: A2uiSurfaceState,
    node: A2uiComponentNode,
    scopePath: String?,
    onDataModelMutation: (String, String, Any?) -> Unit,
    modifier: Modifier
) {
    val label = A2uiDataBindingResolver.resolveString(
        raw = node.properties["label"],
        dataModel = surface.dataModel,
        scopePath = scopePath
    )
    val rawValueProp = node.properties["value"] ?: node.properties["selections"]
    val bindingPath = A2uiDataBindingResolver.extractBindingPath(rawValueProp)
    val boundSelections = A2uiDataBindingResolver.resolveStringList(
        raw = rawValueProp,
        dataModel = surface.dataModel,
        scopePath = scopePath
    )
    var localSelections by remember(surface.surfaceId, node.id, scopePath) { mutableStateOf(boundSelections) }
    val selectedList = if (bindingPath != null) boundSelections else localSelections

    val variant = node.properties["variant"]?.toString()?.trim()?.lowercase()
    val maxAllowed = (node.properties["maxAllowedSelections"] as? Number)?.toInt()
    val isSingleSelect = when {
        variant == "mutuallyexclusive" -> true
        variant == "multipleselection" -> false
        maxAllowed != null -> maxAllowed <= 1
        else -> true
    }
    val displayStyle = node.properties["displayStyle"]?.toString()?.trim()?.lowercase() ?: "chips"

    val rawOptions = (node.properties["options"] as? List<*>) ?: emptyList<Any?>()
    val options: List<Pair<String, String>> = rawOptions.mapNotNull { entry ->
        when (entry) {
            is Map<*, *> -> {
                val optVal = entry["value"]?.toString()?.trim().orEmpty()
                val optLbl = A2uiDataBindingResolver.resolveString(
                    raw = entry["label"],
                    dataModel = surface.dataModel,
                    scopePath = scopePath,
                    default = optVal
                )
                if (optVal.isEmpty() && optLbl.isEmpty()) null else (optVal.ifEmpty { optLbl } to optLbl.ifEmpty { optVal })
            }
            is String -> entry.trim().takeIf { it.isNotEmpty() }?.let { it to it }
            else -> null
        }
    }

    fun onToggleOption(optValue: String) {
        val next = if (isSingleSelect) {
            listOf(optValue)
        } else if (optValue in selectedList) {
            selectedList.filterNot { it == optValue }
        } else {
            val added = selectedList + optValue
            if (maxAllowed != null && maxAllowed > 0 && added.size > maxAllowed) {
                added.takeLast(maxAllowed)
            } else {
                added
            }
        }
        if (bindingPath != null) {
            val resolvedPtr = A2uiJsonPointer.resolvePointerPath(bindingPath, scopePath)
            onDataModelMutation(surface.surfaceId, resolvedPtr, next)
        } else {
            localSelections = next
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                color = ObsidianColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (displayStyle == "checkbox") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { (optVal, optLbl) ->
                    val isSelected = optVal in selectedList
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                            .border(
                                1.dp,
                                if (isSelected) ObsidianColors.Accent else ObsidianColors.GlassBorder,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onToggleOption(optVal) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (isSelected) "●" else "○",
                            color = if (isSelected) ObsidianColors.OnAccent else ObsidianColors.TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = optLbl,
                            color = if (isSelected) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                options.forEach { (optVal, optLbl) ->
                    val isSelected = optVal in selectedList
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (isSelected) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                            .border(
                                1.dp,
                                if (isSelected) ObsidianColors.Accent else ObsidianColors.GlassBorder,
                                RoundedCornerShape(999.dp)
                            )
                            .clickable { onToggleOption(optVal) }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = optLbl,
                            color = if (isSelected) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun A2uiSliderNode(
    surface: A2uiSurfaceState,
    node: A2uiComponentNode,
    scopePath: String?,
    onDataModelMutation: (String, String, Any?) -> Unit,
    modifier: Modifier
) {
    val label = A2uiDataBindingResolver.resolveString(
        raw = node.properties["label"],
        dataModel = surface.dataModel,
        scopePath = scopePath
    )
    val minVal = A2uiDataBindingResolver.resolveDouble(
        raw = node.properties["min"] ?: node.properties["minValue"],
        dataModel = surface.dataModel,
        scopePath = scopePath,
        default = 0.0
    ).toFloat()
    val maxRaw = A2uiDataBindingResolver.resolveDouble(
        raw = node.properties["max"] ?: node.properties["maxValue"],
        dataModel = surface.dataModel,
        scopePath = scopePath,
        default = 100.0
    ).toFloat()
    val safeMax = if (maxRaw <= minVal) minVal + 1f else maxRaw

    val rawValueProp = node.properties["value"]
    val bindingPath = A2uiDataBindingResolver.extractBindingPath(rawValueProp)
    val boundVal = A2uiDataBindingResolver.resolveDouble(
        raw = rawValueProp,
        dataModel = surface.dataModel,
        scopePath = scopePath,
        default = minVal.toDouble()
    ).toFloat().coerceIn(minVal, safeMax)
    var localVal by remember(surface.surfaceId, node.id, scopePath) { mutableFloatStateOf(boundVal) }
    val currentVal = (if (bindingPath != null) boundVal else localVal).coerceIn(minVal, safeMax)

    val discreteSteps = ((node.properties["steps"] as? Number)?.toInt()?.minus(1) ?: 0).coerceAtLeast(0)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label.ifBlank { "값 조절" },
                color = ObsidianColors.TextSecondary,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (currentVal % 1f == 0f) currentVal.roundToInt().toString() else "%.1f".format(currentVal),
                color = ObsidianColors.TextPrimary,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold
            )
        }
        Slider(
            value = currentVal,
            onValueChange = { updated ->
                val clamped = updated.coerceIn(minVal, safeMax)
                if (bindingPath != null) {
                    val resolvedPtr = A2uiJsonPointer.resolvePointerPath(bindingPath, scopePath)
                    onDataModelMutation(surface.surfaceId, resolvedPtr, clamped.toDouble())
                } else {
                    localVal = clamped
                }
            },
            valueRange = minVal..safeMax,
            steps = discreteSteps,
            colors = SliderDefaults.colors(
                thumbColor = ObsidianColors.Accent,
                activeTrackColor = ObsidianColors.Accent,
                inactiveTrackColor = ObsidianColors.SurfaceTop
            )
        )
    }
}

@Composable
private fun A2uiDividerNode(
    node: A2uiComponentNode,
    modifier: Modifier
) {
    val axis = node.properties["axis"]?.toString()?.trim()?.lowercase() ?: "horizontal"
    if (axis == "vertical") {
        Box(
            modifier = modifier
                .width(1.dp)
                .height(22.dp)
                .background(ObsidianColors.Divider)
        )
    } else {
        HorizontalDivider(
            modifier = modifier.fillMaxWidth(),
            color = ObsidianColors.Divider,
            thickness = 1.dp
        )
    }
}

@Composable
private fun A2uiIconNode(
    surface: A2uiSurfaceState,
    node: A2uiComponentNode,
    scopePath: String?,
    invertedPrimary: Boolean,
    modifier: Modifier
) {
    val iconName = A2uiDataBindingResolver.resolveString(
        raw = node.properties["name"],
        dataModel = surface.dataModel,
        scopePath = scopePath,
        default = "info"
    )
    val vector = resolveBasicCatalogIcon(iconName)
    Icon(
        imageVector = vector,
        contentDescription = iconName,
        tint = if (invertedPrimary) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
        modifier = modifier.size(18.dp)
    )
}

@Composable
private fun A2uiImageNode(
    surface: A2uiSurfaceState,
    node: A2uiComponentNode,
    scopePath: String?,
    modifier: Modifier
) {
    val url = A2uiDataBindingResolver.resolveString(
        raw = node.properties["url"],
        dataModel = surface.dataModel,
        scopePath = scopePath
    )
    val description = A2uiDataBindingResolver.resolveString(
        raw = node.properties["description"],
        dataModel = surface.dataModel,
        scopePath = scopePath,
        default = "A2UI 이미지"
    )
    val variant = node.properties["variant"]?.toString()?.trim()?.lowercase() ?: "mediumfeature"
    val frameHeight = when (variant) {
        "icon" -> 40.dp
        "avatar" -> 52.dp
        "smallfeature" -> 84.dp
        "largefeature", "header" -> 148.dp
        else -> 112.dp
    }
    val safeHost = sanitizeImageHost(url)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(frameHeight)
            .clip(RoundedCornerShape(if (variant == "avatar") 26.dp else 12.dp))
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(if (variant == "avatar") 26.dp else 12.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Photo,
                contentDescription = description,
                tint = ObsidianColors.TextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = description,
                color = ObsidianColors.TextPrimary,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (safeHost.isNotBlank()) {
                Text(
                    text = safeHost,
                    color = ObsidianColors.TextMuted,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun A2uiUnknownFallbackNode(
    surface: A2uiSurfaceState,
    node: A2uiComponentNode,
    scopePath: String?,
    modifier: Modifier
) {
    val preview = A2uiDataBindingResolver.resolveString(
        raw = node.properties["text"] ?: node.properties["label"],
        dataModel = surface.dataModel,
        scopePath = scopePath
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = node.type,
            color = ObsidianColors.TextSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        if (preview.isNotBlank()) {
            Text(
                text = preview,
                color = ObsidianColors.TextPrimary,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun resolveBasicCatalogIcon(rawName: String): ImageVector {
    return when (rawName.trim().lowercase()) {
        "accountcircle" -> Icons.Default.AccountCircle
        "add", "plus" -> Icons.Default.Add
        "arrowback", "back" -> Icons.AutoMirrored.Filled.ArrowBack
        "arrowforward", "forward" -> Icons.AutoMirrored.Filled.ArrowForward
        "calendartoday", "calendar" -> Icons.Default.CalendarToday
        "call" -> Icons.Default.Call
        "check", "done" -> Icons.Default.Check
        "close", "cancel" -> Icons.Default.Close
        "delete", "trash" -> Icons.Default.DeleteOutline
        "download" -> Icons.Default.Download
        "edit" -> Icons.Default.Edit
        "error" -> Icons.Default.ErrorOutline
        "event" -> Icons.Default.Event
        "favorite", "heart" -> Icons.Default.Favorite
        "folder" -> Icons.Default.Folder
        "help" -> Icons.AutoMirrored.Filled.HelpOutline
        "home" -> Icons.Default.Home
        "info" -> Icons.Default.Info
        "locationon", "location" -> Icons.Default.LocationOn
        "lock" -> Icons.Default.Lock
        "lockopen", "unlock" -> Icons.Default.LockOpen
        "mail", "email" -> Icons.Default.Email
        "menu" -> Icons.Default.Menu
        "mic", "microphone" -> Icons.Default.Mic
        "morehoriz" -> Icons.Default.MoreHoriz
        "morevert" -> Icons.Default.MoreVert
        "notifications", "notification" -> Icons.Default.Notifications
        "pause" -> Icons.Default.Pause
        "person", "user" -> Icons.Default.Person
        "phone" -> Icons.Default.Phone
        "photo", "image" -> Icons.Default.Photo
        "play", "playarrow" -> Icons.Default.PlayArrow
        "refresh" -> Icons.Default.Refresh
        "search" -> Icons.Default.Search
        "send" -> Icons.AutoMirrored.Filled.Send
        "settings" -> Icons.Default.Settings
        "share" -> Icons.Default.Share
        "shield", "security" -> Icons.Default.Shield
        "shoppingcart", "cart" -> Icons.Default.ShoppingCart
        "star" -> Icons.Default.Star
        "stop" -> Icons.Default.Stop
        "upload" -> Icons.Default.Upload
        "visibility" -> Icons.Default.Visibility
        "visibilityoff" -> Icons.Default.VisibilityOff
        "volumeoff", "volumemute" -> Icons.AutoMirrored.Filled.VolumeOff
        "volumeup", "volumedown" -> Icons.AutoMirrored.Filled.VolumeUp
        "warning" -> Icons.Default.Warning
        else -> Icons.Default.Info
    }
}

private fun sanitizeImageHost(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return ""
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return ""
    val scheme = uri.scheme?.lowercase() ?: return ""
    val host = uri.host?.lowercase() ?: return ""
    return when {
        scheme == "https" -> host
        scheme == "http" && (host == "127.0.0.1" || host == "localhost") -> host
        else -> "blocked-scheme"
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
