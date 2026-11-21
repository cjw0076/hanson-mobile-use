package com.hanson.mobileuse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanson.mobileuse.ui.components.CodeBadge
import com.hanson.mobileuse.ui.components.GlassCard
import com.hanson.mobileuse.ui.theme.ObsidianColors
import com.hanson.mobileuse.workspace.WorkspaceCheckpoint
import com.hanson.mobileuse.workspace.WorkspaceDiffRecord
import com.hanson.mobileuse.workspace.WorkspaceFileEntry
import com.hanson.mobileuse.workspace.WorkspaceUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class WorkbenchTab(val label: String) {
    FILES("Files"),
    DIFFS("Diff"),
    ARTIFACTS("Artifacts"),
    CHECKPOINTS("Undo")
}

private enum class ArtifactDocKind(val filename: String) {
    PLAN("PLAN.md"),
    WALKTHROUGH("WALKTHROUGH.md")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileCodeWorkbenchSheet(
    workspaceState: WorkspaceUiState,
    initialTab: WorkbenchTab = WorkbenchTab.FILES,
    onDismiss: () -> Unit,
    onSelectFile: (String) -> Unit,
    onSaveFile: (String, String) -> Unit,
    onInitAgentsMd: () -> Unit,
    onCreateCheckpoint: (String) -> Unit,
    onRestoreCheckpoint: (String) -> Unit,
    onRunWorkspaceCheck: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var activeTab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = ObsidianColors.Surface,
        scrimColor = ObsidianColors.Background.copy(alpha = 0.72f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(ObsidianColors.Divider)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            // Workbench Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "Code Workbench",
                            tint = ObsidianColors.TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = workspaceState.workspaceName.ifBlank { "Mobile Code Workbench" },
                            color = ObsidianColors.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = workspaceState.workspacePath.ifBlank { "sandbox://agent_workspace" },
                        color = ObsidianColors.TextMuted,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!workspaceState.hasAgentsMd) {
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(ObsidianColors.Accent)
                                .clickable { onInitAgentsMd() }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "/init AGENTS.md",
                                color = ObsidianColors.OnAccent,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(ObsidianColors.SurfaceHigh)
                            .border(1.dp, ObsidianColors.GlassBorder, CircleShape)
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "닫기",
                            tint = ObsidianColors.TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 4-Tab Segmented Switcher
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(14.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                val fileCount = workspaceState.files.count { !it.isDirectory }
                WorkbenchTab.entries.forEach { tab ->
                    val selected = tab == activeTab
                    val countSuffix = when (tab) {
                        WorkbenchTab.FILES -> if (fileCount > 0) " $fileCount" else ""
                        WorkbenchTab.DIFFS -> if (workspaceState.diffs.isNotEmpty()) " ${workspaceState.diffs.size}" else ""
                        WorkbenchTab.ARTIFACTS -> ""
                        WorkbenchTab.CHECKPOINTS -> if (workspaceState.checkpoints.isNotEmpty()) " ${workspaceState.checkpoints.size}" else ""
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                            .clickable { activeTab = tab },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${tab.label}$countSuffix",
                            color = if (selected) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                            fontSize = 11.5.sp,
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = ObsidianColors.Divider)
            Spacer(modifier = Modifier.height(10.dp))

            // Tab Body
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (activeTab) {
                    WorkbenchTab.FILES -> WorkbenchFilesTab(
                        workspaceState = workspaceState,
                        onSelectFile = onSelectFile,
                        onSaveFile = onSaveFile,
                        onInitAgentsMd = onInitAgentsMd,
                        onRunWorkspaceCheck = onRunWorkspaceCheck
                    )
                    WorkbenchTab.DIFFS -> WorkbenchDiffTab(
                        diffs = workspaceState.diffs
                    )
                    WorkbenchTab.ARTIFACTS -> WorkbenchArtifactsTab(
                        planMarkdown = workspaceState.planArtifactMarkdown,
                        walkthroughMarkdown = workspaceState.walkthroughArtifactMarkdown
                    )
                    WorkbenchTab.CHECKPOINTS -> WorkbenchUndoTab(
                        checkpoints = workspaceState.checkpoints,
                        onCreateCheckpoint = onCreateCheckpoint,
                        onRestoreCheckpoint = onRestoreCheckpoint
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkbenchFilesTab(
    workspaceState: WorkspaceUiState,
    onSelectFile: (String) -> Unit,
    onSaveFile: (String, String) -> Unit,
    onInitAgentsMd: () -> Unit,
    onRunWorkspaceCheck: () -> Unit
) {
    val fileEntries = remember(workspaceState.files) {
        workspaceState.files.filter { !it.isDirectory }
    }
    var showTreeList by rememberSaveable { mutableStateOf(workspaceState.selectedFilePath == null) }
    var isEditing by remember(workspaceState.selectedFilePath) { mutableStateOf(false) }
    var editorBuffer by remember(workspaceState.selectedFilePath, workspaceState.selectedFileContent) {
        mutableStateOf(workspaceState.selectedFileContent)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Quick Actions Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!workspaceState.hasAgentsMd) {
                Row(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ObsidianColors.Accent)
                        .clickable { onInitAgentsMd() }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "/init AGENTS.md",
                        tint = ObsidianColors.OnAccent,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "/init AGENTS.md",
                        color = ObsidianColors.OnAccent,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Row(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(10.dp))
                    .clickable { showTreeList = !showTreeList }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "파일 트리",
                    tint = ObsidianColors.TextPrimary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = if (showTreeList) "트리 접기" else "파일 트리 (${fileEntries.size})",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(10.dp))
                    .clickable { onRunWorkspaceCheck() }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Run Check",
                    tint = ObsidianColors.TextPrimary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Run Check",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Scrollable File Chips Strip
        if (fileEntries.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                fileEntries.forEach { entry ->
                    val selected = entry.relativePath == workspaceState.selectedFilePath
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (selected) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                            .border(
                                width = 1.dp,
                                color = if (selected) ObsidianColors.Accent else ObsidianColors.GlassBorder,
                                shape = RoundedCornerShape(999.dp)
                            )
                            .clickable { onSelectFile(entry.relativePath) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = entry.relativePath.substringAfterLast('/'),
                            color = if (selected) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        if (entry.lineCount > 0) {
                            Text(
                                text = "${entry.lineCount}L",
                                color = if (selected) ObsidianColors.OnAccent.copy(alpha = 0.8f) else ObsidianColors.TextMuted,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // Expandable File Tree List
        if (showTreeList) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                containerColor = ObsidianColors.SurfaceHigh,
                borderColor = ObsidianColors.GlassBorder,
                contentPadding = PaddingValues(10.dp)
            ) {
                if (workspaceState.files.isEmpty()) {
                    Text(
                        text = "워크스페이스에 파일이 없습니다. '/init AGENTS.md'로 시작 파일을 생성해 보세요.",
                        color = ObsidianColors.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(6.dp)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        workspaceState.files.forEach { entry ->
                            FileTreeRow(
                                entry = entry,
                                isSelected = entry.relativePath == workspaceState.selectedFilePath,
                                onClick = {
                                    if (!entry.isDirectory) {
                                        onSelectFile(entry.relativePath)
                                        showTreeList = false
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // Code Viewer & Inline Editor
        val activePath = workspaceState.selectedFilePath
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 18.dp,
            containerColor = ObsidianColors.SurfaceHigh,
            borderColor = ObsidianColors.GlassBorder,
            contentPadding = PaddingValues(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = activePath ?: "파일을 선택하면 내용을 보고 편집할 수 있습니다",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (activePath != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isEditing) ObsidianColors.Accent else ObsidianColors.Surface)
                                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(8.dp))
                                .clickable { isEditing = !isEditing }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "편집 전환",
                                tint = if (isEditing) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = if (isEditing) "Editing" else "Edit",
                                color = if (isEditing) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (isEditing) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ObsidianColors.Accent)
                                    .clickable {
                                        onSaveFile(activePath, editorBuffer)
                                        isEditing = false
                                    }
                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = "Save",
                                    tint = ObsidianColors.OnAccent,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "Save",
                                    color = ObsidianColors.OnAccent,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }
            }

            if (activePath != null) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = ObsidianColors.Divider, thickness = 0.8.dp)
                Spacer(modifier = Modifier.height(8.dp))

                if (isEditing) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 320.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ObsidianColors.Background)
                            .border(1.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(12.dp))
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        BasicTextField(
                            value = editorBuffer,
                            onValueChange = { editorBuffer = it },
                            textStyle = TextStyle(
                                color = ObsidianColors.TextPrimary,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 17.sp
                            ),
                            cursorBrush = SolidColor(ObsidianColors.Accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    val lines = remember(workspaceState.selectedFileContent) {
                        workspaceState.selectedFileContent.lines()
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 300.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ObsidianColors.Background)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (workspaceState.selectedFileContent.isEmpty()) {
                            Text(
                                text = "(빈 파일)",
                                color = ObsidianColors.TextMuted,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        } else {
                            lines.forEachIndexed { idx, line ->
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        text = String.format(Locale.US, "%3d", idx + 1),
                                        color = ObsidianColors.TextMuted,
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = line,
                                        color = ObsidianColors.TextPrimary,
                                        fontSize = 11.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 16.5.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Run Check Output Strip
        if (workspaceState.lastExecOutput.isNotBlank()) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                containerColor = ObsidianColors.Background,
                borderColor = ObsidianColors.GlassBorder,
                contentPadding = PaddingValues(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Run Check Output",
                        tint = ObsidianColors.TextPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Workspace Check Output",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = workspaceState.lastExecOutput,
                    color = ObsidianColors.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun FileTreeRow(
    entry: WorkspaceFileEntry,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val depth = entry.relativePath.count { it == '/' }.coerceAtMost(4)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) ObsidianColors.SurfaceTop else ObsidianColors.Surface)
            .border(
                width = 1.dp,
                color = if (isSelected) ObsidianColors.Accent else ObsidianColors.GlassBorder,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(start = (10 + depth * 10).dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
            contentDescription = null,
            tint = if (isSelected) ObsidianColors.TextPrimary else ObsidianColors.TextSecondary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = entry.relativePath,
            color = ObsidianColors.TextPrimary,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!entry.isDirectory) {
            Text(
                text = "${entry.lineCount}L · ${formatByteSize(entry.sizeBytes)}",
                color = ObsidianColors.TextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun WorkbenchDiffTab(
    diffs: List<WorkspaceDiffRecord>
) {
    if (diffs.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 42.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "아직 기록된 파일 변경(Diff)이 없습니다.",
                color = ObsidianColors.TextSecondary,
                fontSize = 13.sp
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 18.dp)
    ) {
        items(diffs, key = { "${it.relativePath}_${it.timestampMs}" }) { diff ->
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
                containerColor = ObsidianColors.SurfaceHigh,
                borderColor = ObsidianColors.GlassBorder,
                contentPadding = PaddingValues(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = diff.relativePath,
                        color = ObsidianColors.TextPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(ObsidianColors.AccentDim)
                                .border(1.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(999.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "+${diff.addedLines}",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(ObsidianColors.Surface)
                                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "-${diff.removedLines}",
                                color = ObsidianColors.TextMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = ObsidianColors.Divider, thickness = 0.8.dp)
                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianColors.Background)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                        .padding(8.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    diff.unifiedDiff.lines().forEach { line ->
                        val isHunk = line.startsWith("@@")
                        val isAdd = line.startsWith("+") && !line.startsWith("+++")
                        val isDel = line.startsWith("-") && !line.startsWith("---")
                        val lineBg = when {
                            isHunk -> ObsidianColors.SurfaceTop
                            isAdd -> ObsidianColors.AccentDim
                            isDel -> ObsidianColors.SurfaceHigh
                            else -> ObsidianColors.Background
                        }
                        val lineColor = when {
                            isHunk -> ObsidianColors.TextPrimary
                            isAdd -> ObsidianColors.TextPrimary
                            isDel -> ObsidianColors.TextMuted
                            else -> ObsidianColors.TextSecondary
                        }
                        Text(
                            text = line.ifEmpty { " " },
                            color = lineColor,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isAdd || isHunk) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(lineBg)
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkbenchArtifactsTab(
    planMarkdown: String,
    walkthroughMarkdown: String
) {
    var selectedDoc by rememberSaveable { mutableStateOf(ArtifactDocKind.PLAN) }
    val activeMarkdown = when (selectedDoc) {
        ArtifactDocKind.PLAN -> planMarkdown
        ArtifactDocKind.WALKTHROUGH -> walkthroughMarkdown
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(ObsidianColors.SurfaceHigh)
                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ArtifactDocKind.entries.forEach { kind ->
                val selected = kind == selectedDoc
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                        .clickable { selectedDoc = kind },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = kind.filename,
                        color = if (selected) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            cornerRadius = 18.dp,
            containerColor = ObsidianColors.SurfaceHigh,
            borderColor = ObsidianColors.GlassBorder,
            contentPadding = PaddingValues(14.dp)
        ) {
            if (activeMarkdown.isBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${selectedDoc.filename} 아티팩트가 아직 생성되지 않았습니다.",
                        color = ObsidianColors.TextSecondary,
                        fontSize = 12.5.sp
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MarkdownBlockRenderer(markdown = activeMarkdown)
                }
            }
        }
    }
}

@Composable
private fun MarkdownBlockRenderer(markdown: String) {
    val lines = remember(markdown) { markdown.lines() }

    // Simple structural render pass over lines
    val blocks = remember(lines) {
        var inCodeBlock = false
        val codeBuffer = mutableListOf<String>()
        val out = mutableListOf<Pair<Boolean, String>>() // isCode to text
        for (line in lines) {
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    out.add(true to codeBuffer.joinToString("\n"))
                    codeBuffer.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
            } else if (inCodeBlock) {
                codeBuffer.add(line)
            } else {
                out.add(false to line)
            }
        }
        if (codeBuffer.isNotEmpty()) {
            out.add(true to codeBuffer.joinToString("\n"))
        }
        out
    }

    blocks.forEach { (isCode, text) ->
        if (isCode) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianColors.Background)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(10.dp))
                    .padding(10.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                Text(
                    text = text,
                    color = ObsidianColors.TextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp
                )
            }
        } else {
            val trimmed = text.trim()
            when {
                trimmed.isEmpty() -> Spacer(modifier = Modifier.height(3.dp))
                trimmed.startsWith("# ") -> Text(
                    text = trimmed.removePrefix("# ").trim(),
                    color = ObsidianColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                trimmed.startsWith("## ") -> Text(
                    text = trimmed.removePrefix("## ").trim(),
                    color = ObsidianColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                trimmed.startsWith("### ") -> Text(
                    text = trimmed.removePrefix("### ").trim(),
                    color = ObsidianColors.TextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
                trimmed.startsWith("- [x] ") || trimmed.startsWith("- [X] ") -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "완료",
                        tint = ObsidianColors.TextPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = trimmed.drop(6).trim(),
                        color = ObsidianColors.TextSecondary,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp
                    )
                }
                trimmed.startsWith("- [ ] ") -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .border(1.dp, ObsidianColors.GlassHighlight, CircleShape)
                    )
                    Text(
                        text = trimmed.drop(6).trim(),
                        color = ObsidianColors.TextPrimary,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp
                    )
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Text(
                    text = "• ${trimmed.drop(2).trim()}",
                    color = ObsidianColors.TextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
                else -> Text(
                    text = trimmed,
                    color = ObsidianColors.TextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun WorkbenchUndoTab(
    checkpoints: List<WorkspaceCheckpoint>,
    onCreateCheckpoint: (String) -> Unit,
    onRestoreCheckpoint: (String) -> Unit
) {
    var labelInput by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Create Checkpoint Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ObsidianColors.SurfaceHigh)
                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (labelInput.isEmpty()) {
                    Text(
                        text = "스냅샷 라벨 (예: 리팩토링 전)",
                        color = ObsidianColors.TextMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = labelInput,
                    onValueChange = { labelInput = it },
                    textStyle = TextStyle(
                        color = ObsidianColors.TextPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    cursorBrush = SolidColor(ObsidianColors.Accent),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.Accent)
                    .clickable {
                        onCreateCheckpoint(labelInput.trim().ifBlank { "수동 체크포인트" })
                        labelInput = ""
                    }
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "체크포인트 생성",
                    tint = ObsidianColors.OnAccent,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "체크포인트",
                    color = ObsidianColors.OnAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        if (checkpoints.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 36.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "저장된 체크포인트가 없습니다. '+ 체크포인트'로 현재 상태를 보관하세요.",
                    color = ObsidianColors.TextSecondary,
                    fontSize = 12.5.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 18.dp)
            ) {
                items(checkpoints, key = { it.id }) { cp ->
                    CheckpointRowCard(
                        checkpoint = cp,
                        onRestore = { onRestoreCheckpoint(cp.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckpointRowCard(
    checkpoint: WorkspaceCheckpoint,
    onRestore: () -> Unit
) {
    val timeText = remember(checkpoint.createdAtMs) {
        if (checkpoint.createdAtMs <= 0L) {
            ""
        } else {
            SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()).format(Date(checkpoint.createdAtMs))
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Default.History,
            contentDescription = "Checkpoint",
            tint = ObsidianColors.TextPrimary,
            modifier = Modifier.size(17.dp)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = checkpoint.label.ifBlank { checkpoint.id },
                color = ObsidianColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CodeBadge(text = checkpoint.id, color = ObsidianColors.TextSecondary)
                Text(
                    text = "${checkpoint.fileCount} files",
                    color = ObsidianColors.TextMuted,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (timeText.isNotBlank()) {
                    Text(
                        text = timeText,
                        color = ObsidianColors.TextMuted,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ObsidianColors.Accent)
                .clickable { onRestore() }
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "복원",
                tint = ObsidianColors.OnAccent,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = "복원",
                color = ObsidianColors.OnAccent,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

private fun formatByteSize(bytes: Long): String {
    return when {
        bytes < 1024L -> "${bytes}B"
        else -> "${(bytes / 1024.0).let { String.format(Locale.US, "%.1f", it) }}KB"
    }
}
