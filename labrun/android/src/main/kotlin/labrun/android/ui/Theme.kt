package labrun.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import labrun.core.DesignTokens

/** docs/03_设计规范.md §1 的令牌，两端同名。 */
@Immutable
data class LabColors(
    val primary: Color, val onPrimary: Color, val bg: Color, val surface: Color, val surfaceAlt: Color,
    val text: Color, val muted: Color, val outline: Color,
    val due: Color, val overdue: Color, val done: Color, val missed: Color, val uncertain: Color,
)

private fun DesignTokens.Palette.toLab() = LabColors(
    Color(primary), Color(onPrimary), Color(bg), Color(surface), Color(surfaceAlt), Color(text), Color(muted), Color(outline),
    Color(due), Color(overdue), Color(done), Color(missed), Color(uncertain),
)
val LightLab = DesignTokens.light.toLab()
val DarkLab = DesignTokens.dark.toLab()

val LocalLab = staticCompositionLocalOf { LightLab }

private const val TNUM = "tnum"

object LabType {
    val display = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM)
    val title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 16.sp)
    val bodyNum = TextStyle(fontSize = 16.sp, fontFeatureSettings = TNUM)
    val label = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
    val labelNum = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TNUM)
    val caption = TextStyle(fontSize = 12.sp, fontFeatureSettings = TNUM)
}

@Composable
fun LabTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) DarkLab else LightLab
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.primary, onPrimary = c.onPrimary, background = c.bg, surface = c.surface,
        surfaceVariant = c.surfaceAlt, onSurface = c.text, onBackground = c.text, onSurfaceVariant = c.muted,
        outline = c.outline, outlineVariant = c.outline, error = c.overdue,
        surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surfaceAlt, surfaceContainerLow = c.surface,
    )
    val typo = Typography(
        titleLarge = LabType.title, titleMedium = LabType.title.copy(fontSize = 18.sp), bodyLarge = LabType.body,
        bodyMedium = LabType.body.copy(fontSize = 15.sp), labelLarge = LabType.label, bodySmall = LabType.caption,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalLab provides c) {
        MaterialTheme(colorScheme = scheme, typography = typo, content = content)
    }
}
