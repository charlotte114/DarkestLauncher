package moe.ddlauncher.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------- 配色：初音未来 ----------------
// 整体是蓝绿系：主色初音绿 #39C5BB 压深一档保证白字可读，强调色是同族的青蓝，
// 连「错误 / 警告」也走蓝绿，全界面不出现红色。
// 不用系统动态取色：动态色会让每台机器长得都不一样，也会把这个主题的味道冲淡。
private val Miku = Color(0xFF39C5BB)
private val MikuBlue = Color(0xFF00A0B8)

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF0E8C82),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCF2ED),
    onPrimaryContainer = Color(0xFF00312D),
    secondary = Color(0xFF00869B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCBEEF5),
    onSecondaryContainer = Color(0xFF00323C),
    tertiary = Color(0xFF2F6B7A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD2E9EF),
    onTertiaryContainer = Color(0xFF0A2A31),
    background = Color(0xFFF3FAF9),
    onBackground = Color(0xFF12201F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF12201F),
    surfaceVariant = Color(0xFFE0F0EE),
    onSurfaceVariant = Color(0xFF40605D),
    surfaceTint = Color(0xFF0E8C82),
    surfaceBright = Color(0xFFFDFFFE),
    surfaceDim = Color(0xFFDDE7E5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7FCFB),
    surfaceContainer = Color(0xFFF1F8F7),
    surfaceContainerHigh = Color(0xFFEAF4F3),
    surfaceContainerHighest = Color(0xFFE4EFEE),
    outline = Color(0xFF7B9B98),
    outlineVariant = Color(0xFFCBE3E0),
    error = Color(0xFF116E8C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFD3ECF7),
    onErrorContainer = Color(0xFF00293A),
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFF5FE0D4),
    onPrimary = Color(0xFF00322E),
    primaryContainer = Color(0xFF0F4C47),
    onPrimaryContainer = Color(0xFFB4F4EC),
    secondary = Color(0xFF6FD9E8),
    onSecondary = Color(0xFF00363D),
    secondaryContainer = Color(0xFF0B4A55),
    onSecondaryContainer = Color(0xFFC3F0F7),
    tertiary = Color(0xFF9FCFD9),
    onTertiary = Color(0xFF00363F),
    tertiaryContainer = Color(0xFF17414A),
    onTertiaryContainer = Color(0xFFCDE9F0),
    background = Color(0xFF0A1416),
    onBackground = Color(0xFFDDEFEC),
    surface = Color(0xFF101E20),
    onSurface = Color(0xFFDDEFEC),
    surfaceVariant = Color(0xFF1B2B2D),
    onSurfaceVariant = Color(0xFFB2CECB),
    surfaceTint = Color(0xFF5FE0D4),
    surfaceBright = Color(0xFF2A3A3C),
    surfaceDim = Color(0xFF0A1416),
    surfaceContainerLowest = Color(0xFF060D0E),
    surfaceContainerLow = Color(0xFF0E1819),
    surfaceContainer = Color(0xFF132123),
    surfaceContainerHigh = Color(0xFF1A282A),
    surfaceContainerHighest = Color(0xFF213032),
    outline = Color(0xFF5E7C79),
    outlineVariant = Color(0xFF2C4042),
    error = Color(0xFF8ED4F2),
    onError = Color(0xFF00323F),
    errorContainer = Color(0xFF10404F),
    onErrorContainer = Color(0xFFD3EEF9),
)

/** 首页「本体卡」：固定的初音绿渐变，两个主题下都一样，当作视觉锚点 */
val HeroTop = Color(0xFF0B3B3A)
val HeroMid = Color(0xFF12726B)
val HeroBottom = Color(0xFF0A2C36)
val HeroText = Color(0xFFEAFDFB)
val HeroSub = Color(0xFFA9D8D3)
val HeroAccent = Color(0xFF54E0D3)

// ---------------- 字体 ----------------
// 标题加粗、字距略收，正文照旧；不额外塞字体文件，装到哪台机器都不缺字。
private val Base = Typography()

private val DDTypography = Typography(
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(letterSpacing = 0.3.sp),
)

private val DDShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun DDLTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) FallbackDark else FallbackLight,
        typography = DDTypography,
        shapes = DDShapes,
        content = content,
    )
}

val TagColors = listOf(Miku, MikuBlue)
