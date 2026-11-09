package com.meenai.mina.capsule.circle

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import kotlin.math.roundToInt

@Composable
fun CircleToAskOverlay(
    screenshot: Bitmap,
    onAsk: (cropRect: CropRect, question: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val colors = MinaTheme.colors

    var screenWidthPx by remember { mutableStateOf(screenshot.width.toFloat()) }
    var screenHeightPx by remember { mutableStateOf(screenshot.height.toFloat()) }

    var startPoint by remember { mutableStateOf<Offset?>(null) }
    var draggingRect by remember { mutableStateOf<CropRect?>(null) }
    var confirmedRect by remember { mutableStateOf<CropRect?>(null) }
    var userQuestion by remember { mutableStateOf("") }

    val activeRect = draggingRect ?: confirmedRect

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                if (it.width > 0 && it.height > 0) {
                    screenWidthPx = it.width.toFloat()
                    screenHeightPx = it.height.toFloat()
                }
            }
    ) {
        // 1. Screenshot background
        Image(
            bitmap = remember(screenshot) { screenshot.asImageBitmap() },
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )

        // 2. Dimmed scrim with transparent hole over selected rect
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.99f }
                .pointerInput(confirmedRect) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            startPoint = offset
                            draggingRect = CropRect(offset.x, offset.y, offset.x, offset.y)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val start = startPoint ?: return@detectDragGestures
                            val raw = CropRect(
                                left = minOf(start.x, change.position.x),
                                top = minOf(start.y, change.position.y),
                                right = maxOf(start.x, change.position.x),
                                bottom = maxOf(start.y, change.position.y),
                            )
                            draggingRect = CircleCropGeometry.clamp(raw, screenWidthPx, screenHeightPx)
                        },
                        onDragEnd = {
                            val candidate = draggingRect
                            draggingRect = null
                            startPoint = null
                            if (candidate != null) {
                                if (CircleCropGeometry.isValidSelection(candidate, density)) {
                                    confirmedRect = candidate
                                } else {
                                    confirmedRect = null
                                    Toast.makeText(
                                        context,
                                        tr(R.string.circle_to_ask_too_small),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        onDragCancel = {
                            draggingRect = null
                            startPoint = null
                        }
                    )
                }
        ) {
            // Dark scrim across entire canvas
            drawRect(Color(0x88000000))

            activeRect?.let { r ->
                // Clear the scrim inside the selection
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(r.left, r.top),
                    size = Size(r.width, r.height),
                    cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
                    blendMode = BlendMode.Clear,
                )
                // Draw glowing outline around selection
                drawRoundRect(
                    color = colors.accent,
                    topLeft = Offset(r.left, r.top),
                    size = Size(r.width, r.height),
                    cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }

        // 3. Top Cancel button & hint
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colors.surface.copy(alpha = 0.9f),
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = stringResource(R.string.capsule_menu_circle_to_ask),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.ink,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.surface.copy(alpha = 0.9f))
                    .clickable(onClick = onCancel)
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MinaIcons.Close,
                    contentDescription = stringResource(R.string.circle_to_ask_cancel),
                    tint = colors.ink,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // 4. 4 Resize Corner Handles (only when confirmedRect is set)
        val rect = confirmedRect
        if (rect != null) {
            val handleSizeDp = 26.dp
            val handleOffsetPx = (handleSizeDp.value * density / 2).roundToInt()

            listOf(
                ResizeHandle.TopLeft to IntOffset(rect.left.roundToInt() - handleOffsetPx, rect.top.roundToInt() - handleOffsetPx),
                ResizeHandle.TopRight to IntOffset(rect.right.roundToInt() - handleOffsetPx, rect.top.roundToInt() - handleOffsetPx),
                ResizeHandle.BottomLeft to IntOffset(rect.left.roundToInt() - handleOffsetPx, rect.bottom.roundToInt() - handleOffsetPx),
                ResizeHandle.BottomRight to IntOffset(rect.right.roundToInt() - handleOffsetPx, rect.bottom.roundToInt() - handleOffsetPx),
            ).forEach { (handle, offset) ->
                Box(
                    modifier = Modifier
                        .offset { offset }
                        .size(handleSizeDp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(2.5.dp, colors.accent, CircleShape)
                        .pointerInput(handle) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                confirmedRect = CircleCropGeometry.resizeByDelta(
                                    current = confirmedRect ?: return@detectDragGestures,
                                    handle = handle,
                                    dx = dragAmount.x,
                                    dy = dragAmount.y,
                                    screenWidth = screenWidthPx,
                                    screenHeight = screenHeightPx,
                                    density = density,
                                )
                            }
                        }
                )
            }

            // 5. Action card (Ask / Cancel + input) positioned below or above the rect
            val spaceBelow = screenHeightPx - rect.bottom
            val showAbove = spaceBelow < 140f * density
            val cardY = if (showAbove) {
                (rect.top - 120f * density).coerceAtLeast(80f * density).roundToInt()
            } else {
                (rect.bottom + 16f * density).coerceAtMost(screenHeightPx - 130f * density).roundToInt()
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, cardY) }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colors.surface,
                    shadowElevation = 8.dp,
                    modifier = Modifier.widthIn(max = 380.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                    ) {
                        // Optional custom question
                        BasicTextField(
                            value = userQuestion,
                            onValueChange = { userQuestion = it },
                            textStyle = TextStyle(
                                fontSize = 14.sp,
                                color = colors.ink,
                                fontWeight = FontWeight.Normal,
                            ),
                            cursorBrush = SolidColor(colors.accent),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(colors.fill)
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                ) {
                                    if (userQuestion.isBlank()) {
                                        Text(
                                            text = stringResource(R.string.circle_to_ask_hint),
                                            fontSize = 14.sp,
                                            color = colors.ink3,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )

                        Spacer(Modifier.height(10.dp))

                        // Action Buttons: Ask & Cancel
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.circle_to_ask_cancel),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.ink3,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(onClick = onCancel)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )

                            Spacer(Modifier.width(8.dp))

                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.accent)
                                    .clickable {
                                        onAsk(rect, userQuestion.trim())
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = MinaIcons.Spark,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.circle_to_ask_ask),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
