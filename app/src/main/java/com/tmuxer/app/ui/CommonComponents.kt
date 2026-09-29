package com.tmuxer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmuxer.app.ui.theme.Amber
import com.tmuxer.app.ui.theme.DeepSurface
import com.tmuxer.app.ui.theme.Ink
import com.tmuxer.app.ui.theme.Mint
import com.tmuxer.app.ui.theme.Outline
import com.tmuxer.app.ui.theme.TextPrimary
import com.tmuxer.app.ui.theme.TextSecondary

@Composable
internal fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String?,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    minLines: Int = 1,
    maxLines: Int = 1,
    textStyleMonospace: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, color = TextSecondary.copy(alpha = 0.65f)) } },
        leadingIcon = leading,
        trailingIcon = trailing,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = if (textStyleMonospace) FontFamily.Monospace else FontFamily.Default
        ),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Mint,
            unfocusedBorderColor = Outline,
            focusedContainerColor = DeepSurface,
            unfocusedContainerColor = DeepSurface,
            cursorColor = Mint
        )
    )
}

@Composable
internal fun TerminalBackButton(
    connected: Boolean,
    onClick: () -> Unit
) {
    Box(Modifier.padding(start = 2.dp).size(40.dp)) {
        HighContrastBackButton(
            onClick = onClick,
            description = "返回窗口列表",
            modifier = Modifier.align(Alignment.Center).size(36.dp)
        )
        Box(
            Modifier.align(Alignment.BottomEnd)
                .size(13.dp)
                .background(DeepSurface, CircleShape)
                .padding(3.dp)
                .background(if (connected) Mint else Amber, CircleShape)
                .semantics {
                    contentDescription = if (connected) "SSH 已连接" else "SSH 正在连接"
                }
        )
    }
}

@Composable
internal fun HighContrastBackButton(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.size(40.dp),
        shape = CircleShape,
        color = Mint,
        contentColor = Ink,
        shadowElevation = 2.dp
    ) {
        IconButton(onClick = onClick) {
            Icon(Icons.Rounded.ArrowBack, description, tint = Ink)
        }
    }
}

@Composable
internal fun ConnectionDot(connected: Boolean) {
    Box(
        Modifier.size(7.dp).background(
            if (connected) Mint else Amber,
            CircleShape
        )
    )
}

@Composable
internal fun SectionLabel(title: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
internal fun LogoMark() {
    Surface(
        modifier = Modifier.size(38.dp),
        shape = RoundedCornerShape(11.dp),
        color = Mint
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                ">_",
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 14.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun topBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = Ink,
    scrolledContainerColor = DeepSurface,
    titleContentColor = TextPrimary,
    navigationIconContentColor = TextPrimary,
    actionIconContentColor = TextPrimary
)
