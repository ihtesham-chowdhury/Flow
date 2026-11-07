package com.flowbangla.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowbangla.app.ui.theme.*

/**
 * Key Entry Sheet according to FLOW_PREMIUM_REDESIGN_GUIDE.md §6.3:
 * - Radius 28dp top, DarkSurface fill.
 * - Title in Title style: "${providerName} key".
 * - Sunken text field (DarkSurfaceSunken), 16dp radius, no outline, no notched label.
 * - Eye and Paste trailing icons (48dp touch targets).
 * - Equal-width Paste and Save action buttons at bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyBottomSheet(
    providerName: String,
    initialKey: String,
    onSave: (String) -> Unit,
    onPaste: () -> String,
    onDismiss: () -> Unit,
    isBn: Boolean
) {
    var keyText by remember { mutableStateOf(initialKey) }
    var showKey by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(DarkHairline)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
        ) {
            Text(
                text = "$providerName key",
                fontFamily = FontFamily.Serif,
                fontSize = 22.sp,
                fontWeight = FontWeight.Normal,
                color = DarkInk
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isBn)
                    "প্রোভাইডারের ওয়েবসাইট থেকে ফ্রি API কী তৈরি করুন। এটি কেবল আপনার ডিভাইসে সুরক্ষিত থাকবে।"
                else
                    "Create a free key at the provider's site. Stored on this phone.",
                fontSize = 13.sp,
                color = DarkInkMuted,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Sunken Text Field (§6.3)
            TextField(
                value = keyText,
                onValueChange = { keyText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurfaceSunken),
                placeholder = {
                    Text(
                        text = if (isBn) "API কী পেস্ট করুন..." else "Paste API key...",
                        color = DarkInkMuted,
                        fontSize = 14.sp
                    )
                },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle visibility",
                                tint = DarkInkMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = {
                            val pasted = onPaste()
                            if (pasted.isNotBlank()) keyText = pasted
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "Paste",
                                tint = DarkInk,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = DarkSurfaceSunken,
                    unfocusedContainerColor = DarkSurfaceSunken,
                    disabledContainerColor = DarkSurfaceSunken,
                    focusedTextColor = DarkInk,
                    unfocusedTextColor = DarkInk,
                    cursorColor = DarkInk,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val pasted = onPaste()
                        if (pasted.isNotBlank()) keyText = pasted
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DarkInk),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(DarkHairline)),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Text(
                        text = if (isBn) "পেস্ট" else "Paste",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Button(
                    onClick = {
                        onSave(keyText)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DarkInk,
                        contentColor = DarkInkOnAccent
                    ),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Text(
                        text = if (isBn) "সেভ করুন" else "Save",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
