package com.weshah.ui.common.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.weshah.ui.common.theme.WeshahStatusColors

@Composable
fun StatusIndicator(isOnline: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(10.dp)
            .background(
                color = if (isOnline) WeshahStatusColors.Online else WeshahStatusColors.Offline,
                shape = CircleShape
            )
            .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
    )
}
