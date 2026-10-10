package site.androidmod.store.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Dark only, on purpose.
 *
 * There is no light scheme and no `isSystemInDarkTheme()` branch here: the website
 * this client belongs to pins `color-scheme: dark`, and a phone client that flipped
 * to a light palette on a light-mode device would be the only surface of the product
 * that did. The XML window theme in res/values/themes.xml is pinned the same way, so
 * the pre-Compose frame cannot flash light either.
 */
private val PapDarkScheme = darkColorScheme(
    primary = PapAccentStrong,
    onPrimary = PapOnAccent,
    primaryContainer = PapAccent,
    onPrimaryContainer = PapText,

    secondary = PapAccent,
    onSecondary = PapText,
    secondaryContainer = PapAccentSoft,
    onSecondaryContainer = PapText,

    tertiary = PapSuccess,
    onTertiary = PapOnAccent,
    tertiaryContainer = PapSuccessBg,
    onTertiaryContainer = PapSuccess,

    background = PapBg,
    onBackground = PapText,
    surface = PapBg,
    onSurface = PapText,
    surfaceVariant = PapCardHover,
    onSurfaceVariant = PapMuted,

    // Material draws cards, bars and menus from these rather than from `surface`,
    // so the whole app's depth ordering lives in these five lines.
    surfaceContainerLowest = PapBg,
    surfaceContainerLow = PapCard,
    surfaceContainer = PapCard,
    surfaceContainerHigh = PapCardHover,
    surfaceContainerHighest = PapElevated,

    error = PapDanger,
    onError = PapOnAccent,
    errorContainer = PapDangerBg,
    onErrorContainer = PapDanger,

    outline = PapBorderStrong,
    outlineVariant = PapBorder,
    scrim = PapBg,
)

/** Matches the site's --pap-radius-sm / -md / -lg (10 / 18 / 26 px). */
private val PapShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun StoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PapDarkScheme,
        typography = PapTypography,
        shapes = PapShapes,
        content = content,
    )
}
