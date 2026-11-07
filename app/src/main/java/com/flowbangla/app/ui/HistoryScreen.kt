package com.flowbangla.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowbangla.app.model.DictationRecord
import com.flowbangla.app.ui.components.FlowHairline
import com.flowbangla.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Editorial History Screen according to FLOW_PREMIUM_REDESIGN_GUIDE.md §6.7:
 * - Header: Display serif 30sp "History", Count caption, "Clear all" text button in inkMuted.
 * - Entries separated by hairlines (no cards, no borders, no fills).
 * - Meta row: timestamp on left, duration on right (Numeric caption, inkMuted, fixed 48dp width, maxLines = 1).
 *   Fixes the squeezed "28 / 2 / sec" wrap bug.
 * - Transcript: Transcript serif 17sp, lineHeight 28sp (Bangla 30sp), max 4 lines with "More" expansion.
 * - Provenance: Caption, inkMuted.
 * - Actions row: Text buttons in DarkInk: "Undo AI edit" (ochre glyph) and "Copy" (with 1.2s "Copied" confirmation).
 * - Empty state: "Your dictations appear here." in Display serif, inkMuted, centered (no mic illustration).
 */
@Composable
fun HistoryScreen(
    history: List<DictationRecord>,
    isBn: Boolean,
    onCopy: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("hh:mm a, dd MMM", Locale.getDefault()) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var copiedRecordId by remember { mutableStateOf<String?>(null) }
    var expandedRecordIds by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Header §6.7
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text = if (isBn) "হিস্ট্রি" else "History",
                    fontFamily = FontFamily.Serif,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Normal,
                    color = DarkInk
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isBn) "${history.size}টি লেখা" else "${history.size} dictations",
                    fontSize = 13.sp,
                    color = DarkInkMuted
                )
            }

            if (history.isNotEmpty()) {
                TextButton(
                    onClick = { showClearConfirmDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isBn) "সব মুছুন" else "Clear all",
                        color = DarkInkMuted,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (history.isEmpty()) {
            // Empty State §6.7: Centred Display-small serif in inkMuted, no illustration
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isBn) "আপনার লেখাগুলো এখানে দেখা যাবে।" else "Your dictations appear here.",
                    fontFamily = FontFamily.Serif,
                    fontSize = 18.sp,
                    color = DarkInkMuted,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                itemsIndexed(history, key = { _, item -> item.id }) { index, item ->
                    val isExpanded = expandedRecordIds.contains(item.id)
                    val isJustCopied = copiedRecordId == item.id
                    val hasUndo = item.rawTranscript.isNotBlank() && item.rawTranscript.trim() != item.text.trim()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    ) {
                        // Meta Row: timestamp left, duration right (fixed 48dp width, maxLines = 1)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = dateFormat.format(Date(item.timestamp)),
                                fontSize = 12.sp,
                                color = DarkInkMuted
                            )

                            if (item.durationSeconds > 0) {
                                Text(
                                    text = if (isBn) "${item.durationSeconds} সে." else "${item.durationSeconds} s",
                                    fontSize = 12.sp,
                                    color = DarkInkMuted,
                                    maxLines = 1,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.width(48.dp) // Fixed 48dp width fixes squeezed bug
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Transcript: Serif 17sp, lineHeight 28sp (Bangla 30sp), max 4 lines unless expanded
                        Text(
                            text = item.text,
                            fontFamily = FontFamily.Serif,
                            fontSize = 17.sp,
                            lineHeight = if (isBn) 30.sp else 28.sp,
                            color = DarkInk,
                            maxLines = if (isExpanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                expandedRecordIds = if (isExpanded) expandedRecordIds - item.id else expandedRecordIds + item.id
                            }
                        )

                        // "More" button if long and collapsed
                        if (!isExpanded && item.text.length > 200) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isBn) "আরও দেখুন" else "More",
                                fontSize = 13.sp,
                                color = DarkInkMuted,
                                modifier = Modifier.clickable {
                                    expandedRecordIds = expandedRecordIds + item.id
                                }
                            )
                        }

                        // Provenance line below transcript (§6.7)
                        if (item.provenance.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = item.provenance,
                                fontSize = 12.sp,
                                color = DarkInkMuted
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Actions Row: Text buttons only, DarkInk
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hasUndo) {
                                TextButton(
                                    onClick = {
                                        onCopy(item.rawTranscript)
                                        copiedRecordId = item.id
                                        scope.launch {
                                            delay(1200L)
                                            if (copiedRecordId == item.id) copiedRecordId = null
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Undo,
                                        contentDescription = "Undo",
                                        tint = OchreDark, // Glyph is ochre on dark per §6.7
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isBn) "ফিরিয়ে আনুন" else "Undo AI edit",
                                        color = DarkInk,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                            }

                            TextButton(
                                onClick = {
                                    onCopy(item.text)
                                    copiedRecordId = item.id
                                    scope.launch {
                                        delay(1200L)
                                        if (copiedRecordId == item.id) copiedRecordId = null
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = DarkInk,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isJustCopied) {
                                        if (isBn) "কপি হয়েছে" else "Copied"
                                    } else {
                                        if (isBn) "কপি" else "Copy"
                                    },
                                    color = DarkInk,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Hairline separator between entries (§6.7)
                    if (index < history.size - 1) {
                        FlowHairline()
                    }
                }
            }
        }
    }

    // Clear All Confirmation Sheet / Dialog (§6.7: Confirm button is ink, not red)
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = {
                Text(
                    text = if (isBn) "হিস্ট্রি মুছবেন?" else "Clear all dictations?",
                    fontFamily = FontFamily.Serif,
                    fontSize = 20.sp,
                    color = DarkInk
                )
            },
            text = {
                Text(
                    text = if (isBn)
                        "সকল ডিক্টেশন হিস্ট্রি স্থায়ীভাবে মুছে যাবে।"
                    else
                        "All saved dictation records will be permanently removed.",
                    color = DarkInkMuted,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClear()
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DarkInk, // Ink per §6.7 (Red is reserved for recording)
                        contentColor = DarkInkOnAccent
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (isBn) "মুছুন" else "Clear all", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text(if (isBn) "বাতিল" else "Cancel", color = DarkInkMuted)
                }
            },
            containerColor = DarkSurface
        )
    }
}
