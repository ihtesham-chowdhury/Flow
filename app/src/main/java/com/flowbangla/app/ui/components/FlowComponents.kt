package com.flowbangla.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowbangla.app.ui.theme.*

/**
 * Two-tone signature brand wordmark inspired by Dieter Rams / Widgets app ("Widg" + "ets"):
 * "Fl" in inkPrimary (white in dark, black in light) + "ow" in vibrant Signal Red (#EA3829).
 */
@Composable
fun FlowBrandWordmark(modifier: Modifier = Modifier) {
    val colors = LocalFlowColors.current
    Text(
        text = buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = colors.inkPrimary,
                    fontWeight = FontWeight.ExtraBold
                )
            ) {
                append("Fl")
            }
            withStyle(
                SpanStyle(
                    color = colors.signalRed,
                    fontWeight = FontWeight.ExtraBold
                )
            ) {
                append("ow")
            }
        },
        fontFamily = FontFamily.SansSerif,
        fontSize = 26.sp,
        letterSpacing = (-0.8).sp,
        modifier = modifier
    )
}

/**
 * Premium Segmented Control (Widgets App Inspired Full Capsule):
 * - Track: surfaceSunken, full capsule (999dp), 1dp hairline border, 3dp padding.
 * - Active indicator: signalRed pill with onSignalRed pure white text.
 * - Inactive text: inkSecondary.
 * - Non-wrapping 13sp medium text to prevent multi-line clipping on "Tap to start".
 */
@Composable
fun FlowSegmentedControl(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Int = 42
) {
    val colors = LocalFlowColors.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.hairline.copy(alpha = 0.6f), RoundedCornerShape(999.dp))
            .padding(3.dp)
    ) {
        val count = items.size.coerceAtLeast(1)
        val segmentWidth = maxWidth / count
        val animatedOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = tween(durationMillis = 180),
            label = "segmented_indicator"
        )

        // Animated sliding indicator pill
        Box(
            modifier = Modifier
                .offset(x = animatedOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(999.dp))
                .background(colors.signalRed)
        )

        // Segment labels
        Row(modifier = Modifier.fillMaxSize()) {
            items.forEachIndexed { index, title ->
                val isSelected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onSelect(index)
                        }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) colors.onSignalRed else colors.inkSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Compact Segmented Control for the TopAppBar (e.g. [ EN | বাংলা ]).
 */
@Composable
fun FlowCompactSegmentedControl(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalFlowColors.current
    BoxWithConstraints(
        modifier = modifier
            .width(136.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.hairline.copy(alpha = 0.6f), RoundedCornerShape(999.dp))
            .padding(2.5.dp)
    ) {
        val count = items.size.coerceAtLeast(1)
        val segmentWidth = maxWidth / count
        val animatedOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = tween(durationMillis = 180),
            label = "compact_indicator"
        )

        Box(
            modifier = Modifier
                .offset(x = animatedOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(999.dp))
                .background(colors.signalRed)
        )

        Row(modifier = Modifier.fillMaxSize()) {
            items.forEachIndexed { index, title ->
                val isSelected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onSelect(index)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) colors.onSignalRed else colors.inkSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/**
 * Editorial Group Surface Container:
 * Rounded 22dp, surface fill, and crisp 1dp hairline border.
 */
@Composable
fun FlowGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalFlowColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.hairline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            content = content
        )
    }
}

/**
 * Hairline separator: 1dp hairline color.
 */
@Composable
fun FlowHairline(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        thickness = 1.dp,
        color = LocalFlowColors.current.hairline
    )
}

/**
 * Editorial primary action button with signature Signal Red styling.
 */
@Composable
fun FlowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    containerColor: Color = LocalFlowColors.current.signalRed,
    contentColor: Color = LocalFlowColors.current.onSignalRed
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Tactical hardware switch colors inspired by Widgets app:
 * Bold Signal Red track with pure white thumb when checked!
 */
@Composable
fun flowSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = LocalFlowColors.current.onSignalRed,
    checkedTrackColor = LocalFlowColors.current.signalRed,
    uncheckedThumbColor = LocalFlowColors.current.inkSecondary,
    uncheckedTrackColor = LocalFlowColors.current.surfaceSunken,
    uncheckedBorderColor = LocalFlowColors.current.hairline
)
