package com.tmuxer.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tmuxer.app.ui.theme.DeepSurface
import com.tmuxer.app.ui.theme.Ink
import com.tmuxer.app.ui.theme.Mint
import com.tmuxer.app.ui.theme.Outline
import com.tmuxer.app.ui.theme.RaisedSurface
import com.tmuxer.app.ui.theme.TextPrimary
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val DIRECTION_KEY_REPEAT_INTERVAL_MS = 65L

@Composable
internal fun SpecialKeyBar(
    ctrlActive: State<Boolean>,
    shiftActive: State<Boolean>,
    altActive: State<Boolean>,
    onControl: () -> Unit,
    onShift: () -> Unit,
    onAlt: () -> Unit,
    onKey: (String) -> Unit
) {
    val firstRowScroll = rememberScrollState()
    val secondRowScroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxWidth().background(DeepSurface)
            // Keep the horizontally scrollable second row above Android's mandatory
            // bottom app-switch gesture region; that system gesture cannot be excluded.
            .padding(top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(firstRowScroll)
                .padding(horizontal = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            KeyButton("Esc", description = "Escape", compact = true) { onKey("\u001B") }
            KeyButton("/", description = "输入斜杠", compact = true) { onKey("/") }
            KeyButton("^X", description = "Ctrl+X", compact = true) { onKey("\u0018") }
            KeyButton("^U", description = "Ctrl+U", compact = true) { onKey("\u0015") }
            KeyButton("^J", description = "Ctrl+J", compact = true) { onKey("\u000A") }
            KeyButton("^T", description = "Ctrl+T", compact = true) { onKey("\u0014") }
            KeyButton("^O", description = "Ctrl+O", compact = true) { onKey("\u000F") }
            KeyButton("^P", description = "Ctrl+P", compact = true) { onKey("\u0010") }
            ShiftTabButton { onKey("\u001B[Z") }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(secondRowScroll)
                .padding(horizontal = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            KeyButton("Ctrl", description = "Control", active = ctrlActive.value, onClick = onControl)
            KeyButton("Shift", active = shiftActive.value, onClick = onShift)
            KeyButton("Alt", active = altActive.value, onClick = onAlt)
            KeyButton("Tab") { onKey("\t") }
            KeyButton(
                icon = Icons.Rounded.KeyboardArrowLeft,
                description = "左方向键",
                repeatOnLongPress = true
            ) { onKey("\u001B[D") }
            KeyButton(
                icon = Icons.Rounded.KeyboardArrowDown,
                description = "下方向键",
                repeatOnLongPress = true
            ) { onKey("\u001B[B") }
            KeyButton(
                icon = Icons.Rounded.KeyboardArrowUp,
                description = "上方向键",
                repeatOnLongPress = true
            ) { onKey("\u001B[A") }
            KeyButton(
                icon = Icons.Rounded.KeyboardArrowRight,
                description = "右方向键",
                repeatOnLongPress = true
            ) { onKey("\u001B[C") }
            KeyButton(icon = Icons.Rounded.KeyboardDoubleArrowUp, description = "向上翻页") {
                onKey("\u001B[5~")
            }
            KeyButton(icon = Icons.Rounded.KeyboardDoubleArrowDown, description = "向下翻页") {
                onKey("\u001B[6~")
            }
            KeyButton("-") { onKey("-") }
            KeyButton("|") { onKey("|") }
        }
    }
}

@Composable
private fun ShiftTabButton(onClick: () -> Unit) {
    KeyButton(
        description = "Shift+Tab",
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ShiftFilledIcon, contentDescription = null, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(3.dp))
                Text(
                    "Tab",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        onClick = onClick
    )
}

@Composable
internal fun PiPageNavigationOverlay(
    modifier: Modifier = Modifier,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = modifier.graphicsLayer { alpha = 0.58f },
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CtrlShiftDirectionButton(
            direction = Icons.Rounded.KeyboardArrowUp,
            description = "Pi 上一页",
            onClick = onPrevious
        )
        CtrlShiftDirectionButton(
            direction = Icons.Rounded.KeyboardArrowDown,
            description = "Pi 下一页",
            onClick = onNext
        )
    }
}

@Composable
private fun CtrlShiftDirectionButton(
    direction: ImageVector,
    description: String,
    onClick: () -> Unit
) {
    KeyButton(
        description = description,
        compact = true,
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "^",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall
                )
                Icon(ShiftFilledIcon, contentDescription = null, modifier = Modifier.size(11.dp))
                Icon(direction, contentDescription = null, modifier = Modifier.size(14.dp))
            }
        },
        onClick = onClick
    )
}

@Composable
private fun KeyButton(
    label: String? = null,
    icon: ImageVector? = null,
    description: String = label.orEmpty(),
    active: Boolean = false,
    compact: Boolean = false,
    repeatOnLongPress: Boolean = false,
    content: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    require(label != null || icon != null || content != null) { "按键必须提供文字、图标或内容" }
    val currentOnClick by rememberUpdatedState(onClick)
    val interactionSource = remember { MutableInteractionSource() }
    val inputModifier = if (repeatOnLongPress) {
        Modifier
            .semantics {
                role = Role.Button
                onClick {
                    currentOnClick()
                    true
                }
            }
            .indication(interactionSource, LocalIndication.current)
            .pointerInput(interactionSource) {
                detectTapGestures(
                    onPress = { position ->
                        val press = PressInteraction.Press(position)
                        interactionSource.emit(press)
                        var repeated = false
                        val released = coroutineScope {
                            val repeatJob = launch {
                                delay(viewConfiguration.longPressTimeoutMillis)
                                while (isActive) {
                                    repeated = true
                                    currentOnClick()
                                    delay(DIRECTION_KEY_REPEAT_INTERVAL_MS)
                                }
                            }
                            val didRelease = tryAwaitRelease()
                            repeatJob.cancelAndJoin()
                            didRelease
                        }
                        interactionSource.emit(
                            if (released) PressInteraction.Release(press)
                            else PressInteraction.Cancel(press)
                        )
                        if (released && !repeated) currentOnClick()
                    }
                )
            }
    } else {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            onClick = currentOnClick
        )
    }
    Surface(
        modifier = Modifier.height(30.dp).widthIn(min = if (compact) 34.dp else 40.dp)
            .semantics { contentDescription = description }
            .then(inputModifier),
        shape = RoundedCornerShape(8.dp),
        color = if (active) Mint else RaisedSurface,
        contentColor = if (active) Ink else TextPrimary,
        border = BorderStroke(1.dp, if (active) Mint else Outline)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = if (compact) 4.dp else 6.dp)
        ) {
            when {
                content != null -> content()
                icon != null -> Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                else -> Text(
                    label.orEmpty(),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
