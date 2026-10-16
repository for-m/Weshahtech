package com.weshah.ui.common.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ─── WESHAH Brand Colors ──────────────────────────────────────────────────────
// Primary: Deep teal — professional, tech, trustworthy
// Secondary: Electric blue accent
// Error: Vivid red for blocks/alerts

private val WeshahTeal = Color(0xFF00B4D8)
private val WeshahDeepTeal = Color(0xFF0077B6)
private val WeshahDarkBg = Color(0xFF0A0F1E)
private val WeshahSurface = Color(0xFF111827)
private val WeshahCard = Color(0xFF1A2332)
private val WeshahAccent = Color(0xFF00E5FF)
private val WeshahRed = Color(0xFFEF4444)
private val WeshahGreen = Color(0xFF10B981)
private val WeshahOrange = Color(0xFFF59E0B)

private val DarkColorScheme = darkColorScheme(
    primary = WeshahTeal,
    onPrimary = Color(0xFF001F29),
    primaryContainer = WeshahDeepTeal,
    onPrimaryContainer = Color(0xFFB3E9F7),
    secondary = WeshahAccent,
    onSecondary = Color(0xFF001F24),
    secondaryContainer = Color(0xFF00364A),
    onSecondaryContainer = Color(0xFFA3EFFF),
    tertiary = WeshahGreen,
    onTertiary = Color(0xFF003824),
    error = WeshahRed,
    onError = Color.White,
    background = WeshahDarkBg,
    onBackground = Color(0xFFE2E8F0),
    surface = WeshahSurface,
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = WeshahCard,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF2D3748)
)

private val LightColorScheme = lightColorScheme(
    primary = WeshahDeepTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB3E9F7),
    onPrimaryContainer = Color(0xFF001F29),
    secondary = Color(0xFF0077B6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFA3EFFF),
    onSecondaryContainer = Color(0xFF001F24),
    tertiary = Color(0xFF059669),
    onTertiary = Color.White,
    error = WeshahRed,
    onError = Color.White,
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1)
)

@Composable
fun WeshahTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = WeshahTypography,
        shapes = WeshahShapes,
        content = content
    )
}

// Status colors — semantic, used for online/offline/blocked indicators
object WeshahStatusColors {
    val Online = WeshahGreen
    val Offline = Color(0xFF64748B)
    val Blocked = WeshahRed
    val Warning = WeshahOrange
    val Accent = WeshahAccent
}
