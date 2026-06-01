package cz.naviglink.driver.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Material 3 wrapper s Naviglink barvami (modrá #1E6091 jako primary).
 *
 * Pro pilot držíme light scheme primárně — auto-displej obvykle v denním
 * světle. Dark mode následuje system preference.
 */
@Composable
fun NaviglinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}

private val NaviglinkBlue = Color(0xFF1E6091)
private val NaviglinkBlueLight = Color(0xFF4E8FBE)
private val NaviglinkBlueDark = Color(0xFF164A73)

private val LightColors = lightColorScheme(
    primary = NaviglinkBlue,
    onPrimary = Color.White,
    secondary = NaviglinkBlueLight,
    background = Color(0xFFF5F6F8),
    surface = Color.White,
    onBackground = Color(0xFF1A1D24),
    onSurface = Color(0xFF1A1D24),
)

private val DarkColors = darkColorScheme(
    primary = NaviglinkBlueLight,
    onPrimary = Color.White,
    secondary = NaviglinkBlue,
    background = Color(0xFF12141A),
    surface = Color(0xFF1C1F26),
    onBackground = Color(0xFFE5E7EB),
    onSurface = Color(0xFFE5E7EB),
)
