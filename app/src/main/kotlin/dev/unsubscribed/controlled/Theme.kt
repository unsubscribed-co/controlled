package dev.unsubscribed.controlled

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

// "Lights down" (controlled/plan): dark-first, a near-black surface and one ember accent like a TV's standby light.
// No dynamic color. Text colors here are >= 4.5:1 on every surface they sit on (WCAG math), ember on black 6.8:1.
private val Dark = darkColorScheme(
    primary = Color(0xFFFF6A3D), onPrimary = Color(0xFF1A0A04),
    primaryContainer = Color(0xFF3A1B10), onPrimaryContainer = Color(0xFFFFD9CC),
    secondary = Color(0xFFB9B4AE), onSecondary = Color(0xFF16171B),
    secondaryContainer = Color(0xFF24262C), onSecondaryContainer = Color(0xFFF2EEE9),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0D0E11), onBackground = Color(0xFFF2EEE9),
    surface = Color(0xFF0D0E11), onSurface = Color(0xFFF2EEE9),
    surfaceVariant = Color(0xFF24262C), onSurfaceVariant = Color(0xFFB9B4AE),
    surfaceContainerLowest = Color(0xFF08090B), surfaceContainerLow = Color(0xFF131418),
    surfaceContainer = Color(0xFF17181D), surfaceContainerHigh = Color(0xFF1D1F24),
    surfaceContainerHighest = Color(0xFF24262C),
    inverseSurface = Color(0xFFF2EEE9), inverseOnSurface = Color(0xFF17181D), inversePrimary = Color(0xFFB83A12),
    outline = Color(0xFF85807A), outlineVariant = Color(0xFF2C2E35),
)

private val Light = lightColorScheme(
    primary = Color(0xFFB83A12), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE1D6), onPrimaryContainer = Color(0xFF3A1206),
    secondary = Color(0xFF5C5852), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAE5DF), onSecondaryContainer = Color(0xFF1C1A17),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFFCE4E1), onErrorContainer = Color(0xFF5C0F0A),
    background = Color(0xFFF7F4F0), onBackground = Color(0xFF1C1A17),
    surface = Color(0xFFF7F4F0), onSurface = Color(0xFF1C1A17),
    surfaceVariant = Color(0xFFEAE5DF), onSurfaceVariant = Color(0xFF5C5852),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBF9F6),
    surfaceContainer = Color(0xFFF0ECE7), surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFEAE5DF),
    inverseSurface = Color(0xFF2B2926), inverseOnSurface = Color(0xFFF2EEE9), inversePrimary = Color(0xFFFF6A3D),
    outline = Color(0xFF77726B), outlineVariant = Color(0xFFD9D3CC),
)

// Downloadable from Play services, so nothing is bundled; until they arrive (or offline on first run) the system font shows.
private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.font_certs,
)

/** Display: the suite's grotesque, for the TV's name and titles. */
private val Display = FontFamily(
    Font(GoogleFont("Bricolage Grotesque"), provider, FontWeight.SemiBold),
    Font(GoogleFont("Bricolage Grotesque"), provider, FontWeight.Bold),
)

private val Body = FontFamily(
    Font(GoogleFont("DM Sans"), provider, FontWeight.Normal),
    Font(GoogleFont("DM Sans"), provider, FontWeight.Medium),
    Font(GoogleFont("DM Sans"), provider, FontWeight.SemiBold),
)

private val Typography = Typography().run {
    fun TextStyle.display(weight: FontWeight) = copy(fontFamily = Display, fontWeight = weight, letterSpacing = (-0.01).em)
    fun TextStyle.body(weight: FontWeight = FontWeight.Normal) = copy(fontFamily = Body, fontWeight = weight)
    copy(
        headlineMedium = headlineMedium.display(FontWeight.Bold),
        headlineSmall = headlineSmall.display(FontWeight.Bold),
        titleLarge = titleLarge.display(FontWeight.SemiBold),
        titleMedium = titleMedium.display(FontWeight.SemiBold),
        titleSmall = titleSmall.body(FontWeight.SemiBold),
        bodyLarge = bodyLarge.body(),
        bodyMedium = bodyMedium.body(),
        bodySmall = bodySmall.body(),
        labelLarge = labelLarge.body(FontWeight.SemiBold),
        labelMedium = labelMedium.body(FontWeight.Medium),
        labelSmall = labelSmall.body(FontWeight.Medium),
    )
}

// The suite's corner scale (StackSplit's Theme.kt).
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** The system "Remove animations" setting (animator scale 0): motion collapses to its end state. */
@Composable
fun reducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

@Composable
fun ControlledTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Typography, shapes = AppShapes, content = content)
}
