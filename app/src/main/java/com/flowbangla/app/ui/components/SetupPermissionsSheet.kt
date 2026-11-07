package com.flowbangla.app.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowbangla.app.ui.theme.*
import com.flowbangla.app.util.FlowPermissionState

/**
 * Setup and Permissions Sheet according to FLOW_PREMIUM_REDESIGN_GUIDE.md §6.9:
 * - Radius 28dp top, DarkSurface fill.
 * - Display title: "Set up Flow".
 * - Step checklist: Accessibility, Display over other apps, Restricted settings (Xiaomi / Android 13+).
 * - Quiet sage check for Done, primary ivory button only on the active step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupPermissionsSheet(
    permissionState: FlowPermissionState,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestXiaomiAutostart: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onRequestSamsungDeviceCare: () -> Unit = {}
) {
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
        val totalSteps = if (permissionState.isRestrictedSettingsPossible) 3 else 2
        val doneCount = (if (permissionState.hasAccessibilityPermission) 1 else 0) +
                (if (permissionState.hasOverlayPermission) 1 else 0) +
                (if (permissionState.isRestrictedSettingsPossible && permissionState.hasAccessibilityPermission) 1 else 0)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isBn) "Flow সেটাপ" else "Set up Flow",
                    fontFamily = FontFamily.Serif,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Normal,
                    color = DarkInk
                )
                Text(
                    text = if (isBn) "$totalSteps টির মধ্যে $doneCount টি সম্পন্ন" else "$doneCount of $totalSteps done",
                    fontSize = 13.sp,
                    color = DarkInkMuted
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Step 1: Accessibility
            PermissionRow(
                stepNumber = 1,
                title = if (isBn) "অ্যাক্সেসিবিলিটি (অটো-টাইপিং)" else "Accessibility",
                description = if (isBn)
                    "অন্য অ্যাপের লেখার বক্সে স্বয়ংক্রিয়ভাবে কথা থেকে লেখা বসায়। এটি আপনার স্ক্রিনের গোপনীয় তথ্য পড়ে না।"
                else
                    "Lets Flow place text where you are typing. It does not read other apps' content.",
                isDone = permissionState.hasAccessibilityPermission,
                actionLabel = if (isBn) "অ্যাক্সেসিবিলিটি সেটিংস খুলুন" else "Open accessibility settings",
                onAction = onRequestAccessibility,
                isBn = isBn
            )

            Spacer(modifier = Modifier.height(16.dp))
            FlowHairline()
            Spacer(modifier = Modifier.height(16.dp))

            // Step 2: Overlay
            PermissionRow(
                stepNumber = 2,
                title = if (isBn) "ডিসপ্লে ওভার অ্যাপস (ফ্লোটিং মাইক)" else "Display over other apps",
                description = if (isBn)
                    "কিবোর্ডের উপরে ফ্লোটিং মাইক বাটনটি ভাসমান রাখতে সাহায্য করে।"
                else
                    "Lets the mic float above your keyboard.",
                isDone = permissionState.hasOverlayPermission,
                actionLabel = if (isBn) "ডিসপ্লে সেটিংস খুলুন" else "Open settings",
                onAction = onRequestOverlay,
                isBn = isBn
            )

            // Step 3: Restricted Settings (Xiaomi / Android 13+)
            if (permissionState.isRestrictedSettingsPossible && !permissionState.hasAccessibilityPermission) {
                Spacer(modifier = Modifier.height(16.dp))
                FlowHairline()
                Spacer(modifier = Modifier.height(16.dp))

                val isXiaomi = Build.MANUFACTURER.contains("xiaomi", ignoreCase = true) ||
                        Build.MANUFACTURER.contains("redmi", ignoreCase = true) ||
                        Build.MANUFACTURER.contains("poco", ignoreCase = true)

                val desc = if (isXiaomi) {
                    if (isBn) "Xiaomi ফোনে অ্যাপ ইনফো-র ৩-ডট মেনু থেকে 'Allow restricted settings' অপশনটি চালু করুন।"
                    else "On Xiaomi phones, open App info, tap the top-right 3 dots and choose 'Allow restricted settings'."
                } else {
                    if (isBn) "অ্যান্ড্রয়েড ১৩-১৫ সিকিউরিটির জন্য অ্যাপ ইনফোতে গিয়ে 'Allow restricted settings' অনুমোদন করুন।"
                    else "This phone blocks new accessibility access for sideloaded apps. Allow it once in app info, then return here."
                }

                PermissionRow(
                    stepNumber = 3,
                    title = if (isBn) "রেস্ট্রিক্টেড সেটিংস অনুমোদন" else "Restricted settings",
                    description = desc,
                    isDone = permissionState.hasAccessibilityPermission,
                    actionLabel = if (isBn) "অ্যাপ ইনফো খুলুন" else "Open app info",
                    onAction = onRequestAccessibility,
                    isBn = isBn
                )
            }

            // Step 4: Samsung One UI Background Protection (Issue 3)
            if (Build.MANUFACTURER.contains("samsung", ignoreCase = true)) {
                Spacer(modifier = Modifier.height(16.dp))
                FlowHairline()
                Spacer(modifier = Modifier.height(16.dp))

                PermissionRow(
                    stepNumber = if (permissionState.isRestrictedSettingsPossible && !permissionState.hasAccessibilityPermission) 4 else 3,
                    title = if (isBn) "স্যামসাং ব্যাকগ্রাউন্ড সুরক্ষা" else "Samsung Background Protection",
                    description = if (isBn)
                        "স্যামসাং One UI ব্যাকগ্রাউন্ডে সার্ভিস বন্ধ করে দেওয়া ঠেকাতে Device Care -> Battery -> Never sleeping apps-এ Flow যুক্ত রাখুন।"
                    else
                        "Prevent Samsung One UI from killing Flow. Add Flow to 'Never sleeping apps' in Device Care -> Battery.",
                    isDone = permissionState.isIgnoringBatteryOptimizations,
                    actionLabel = if (isBn) "Device Care সেটিংস খুলুন" else "Open Device Care",
                    onAction = onRequestSamsungDeviceCare,
                    isBn = isBn
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = if (isBn) "ক্লাউড মোডের জন্য একটি ফ্রি API কী দিন (Keys অপশনে)।" else "Cloud modes need a free key. Add one under Keys.",
                fontSize = 12.sp,
                color = DarkInkMuted
            )
        }
    }
}

@Composable
private fun PermissionRow(
    stepNumber: Int,
    title: String,
    description: String,
    isDone: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    isBn: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = DarkInk
            )
            if (isDone) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Done",
                        tint = SageDark,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isBn) "সম্পন্ন" else "Done",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = SageDark
                    )
                }
            } else {
                Text(
                    text = if (isBn) "বাকি" else "To do",
                    fontSize = 12.sp,
                    color = DarkInkMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            fontSize = 13.sp,
            color = DarkInkMuted,
            lineHeight = 18.sp
        )

        if (!isDone) {
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DarkInk,
                    contentColor = DarkInkOnAccent
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = actionLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
