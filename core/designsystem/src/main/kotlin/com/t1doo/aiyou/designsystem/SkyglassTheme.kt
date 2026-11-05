package com.t1doo.aiyou.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class SkyglassAppearance { BLUE, SUNSET, NIGHT }
enum class SkyglassMotion { STILL, DRIFT, GLOW }
enum class SkyglassSurfaceKind { WELCOME, READING, INPUT, SHEET, NAVIGATION }

/** The stored choice is never changed by lifecycle, editing or reduced-motion overrides. */
fun skyglassMotionPlaying(choice: SkyglassMotion, foreground: Boolean, editing: Boolean,
    scrolling: Boolean, appReduced: Boolean, systemReduced: Boolean): Boolean =
    choice != SkyglassMotion.STILL && foreground && !editing && !scrolling && !appReduced && !systemReduced

@Immutable
data class SkyglassPalette(
    val primary: Color, val text: Color, val body: Color, val secondaryText: Color,
    val input: Color, val tint: Color, val welcome: Color,
    val readingStart: Color, val readingEnd: Color, val navigation: Color,
    val error: Color, val success: Color,
)

fun skyglassPalette(appearance: SkyglassAppearance): SkyglassPalette = when (appearance) {
    SkyglassAppearance.BLUE -> SkyglassPalette(
        Color(0xFF3866C3), Color(0xFF233B61), Color(0xFF344E70), Color(0xFF526987),
        Color(0xFFF7FAFF), Color(0xFFE6EDFC), Color(0x6FF2F7FF),
        Color(0xD9EDF5FF), Color(0xDAF0EFFF), Color(0xDBF7FAFF), Color(0xFFAC3935), Color(0xFF376A52))
    SkyglassAppearance.SUNSET -> SkyglassPalette(
        Color(0xFF994363), Color(0xFF513644), Color(0xFF664657), Color(0xFF775967),
        Color(0xFFFFF6F4), Color(0xFFF9E5EB), Color(0x70FFF2EC),
        Color(0xDAFFF4EA), Color(0xDBF9EDF1), Color(0xDFFFF5F0), Color(0xFFA43139), Color(0xFF376A52))
    SkyglassAppearance.NIGHT -> SkyglassPalette(
        Color(0xFF5969B8), Color(0xFFF0F1FF), Color(0xFFE0E7F7), Color(0xFFBEC7E3),
        Color(0xFF252B4C), Color(0xFF3A426B), Color(0x74222C4C),
        Color(0xE027324E), Color(0xD9302B50), Color(0xED202644), Color(0xFFFFB4AB), Color(0xFF98D9B4))
}

val LocalSkyglassPalette = staticCompositionLocalOf { skyglassPalette(SkyglassAppearance.BLUE) }

/** Opt-in migration boundary: legacy routes keep AIyouTheme until their parity is checked. */
@Composable
fun SkyglassTheme(appearance: SkyglassAppearance, content: @Composable () -> Unit) {
    val p = skyglassPalette(appearance)
    val base = if (appearance == SkyglassAppearance.NIGHT) darkColorScheme() else lightColorScheme()
    AIyouTheme {
        val typography = MaterialTheme.typography
        CompositionLocalProvider(LocalSkyglassPalette provides p, LocalContentColor provides p.text) {
            MaterialTheme(
                colorScheme = base.copy(primary=p.primary, onPrimary=Color.White,
                    primaryContainer=p.tint, onPrimaryContainer=p.text,
                    background=p.input, onBackground=p.text, surface=p.input, onSurface=p.text,
                    surfaceVariant=p.tint, onSurfaceVariant=p.secondaryText,
                    secondary=p.primary, onSecondary=Color.White, secondaryContainer=p.tint,
                    onSecondaryContainer=p.text, outline=p.secondaryText,
                    outlineVariant=p.secondaryText.copy(alpha=.16f), error=p.error,
                    surfaceTint=Color.Transparent),
                typography = typography.copy(
                    headlineLarge=typography.headlineLarge.copy(fontSize=22.sp,lineHeight=28.sp),
                    titleLarge=typography.titleLarge.copy(fontSize=18.sp,lineHeight=26.sp,fontWeight=FontWeight.Medium),
                    titleMedium=typography.titleMedium.copy(fontSize=16.sp,lineHeight=24.sp,fontWeight=FontWeight.Medium),
                    bodyLarge=typography.bodyLarge.copy(fontSize=16.sp,lineHeight=26.sp,fontWeight=FontWeight.Normal),
                    bodyMedium=typography.bodyMedium.copy(fontSize=16.sp,lineHeight=26.sp,fontWeight=FontWeight.Normal)),
                shapes=MaterialTheme.shapes.copy(medium=RoundedCornerShape(14.dp),extraLarge=RoundedCornerShape(24.dp)),
                content=content,
            )
        }
    }
}

/** API26-safe tinted surfaces. Only scenery may be blurred; foreground content stays sharp. */
@Composable
fun SkyglassSurface(kind: SkyglassSurfaceKind, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p=LocalSkyglassPalette.current
    val colors=when(kind) {
        SkyglassSurfaceKind.WELCOME -> listOf(p.welcome,p.welcome)
        SkyglassSurfaceKind.READING -> listOf(p.readingStart,p.readingEnd)
        SkyglassSurfaceKind.INPUT, SkyglassSurfaceKind.SHEET -> listOf(p.input,p.input)
        SkyglassSurfaceKind.NAVIGATION -> listOf(p.navigation,p.navigation)
    }
    val shape=if(kind==SkyglassSurfaceKind.SHEET) RoundedCornerShape(topStart=24.dp,topEnd=24.dp) else RoundedCornerShape(14.dp)
    Box(modifier.clip(shape).background(Brush.linearGradient(colors))) { content() }
}

@Composable
private fun SkyglassSurfaceSample(appearance: SkyglassAppearance) {
    SkyglassTheme(appearance) {
        val p=LocalSkyglassPalette.current
        Column(Modifier.background(p.tint).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            SkyglassSurface(SkyglassSurfaceKind.WELCOME) { Text("今天，慢慢记。",Modifier.padding(12.dp),color=p.text) }
            SkyglassSurface(SkyglassSurfaceKind.READING) {
                Column(Modifier.padding(16.dp)) {
                    Text("路过花店",style=MaterialTheme.typography.titleMedium,color=p.text)
                    Text("把生活里的一点温柔记下来。",style=MaterialTheme.typography.bodyLarge,color=p.body)
                }
            }
            SkyglassSurface(SkyglassSurfaceKind.INPUT) {
                Column(Modifier.padding(12.dp)) {
                    Text("这一刻，想记下什么？",color=p.secondaryText)
                    Button(onClick={}) { Text("记好") }
                }
            }
        }
    }
}

@Preview(name="Skyglass blue",widthDp=360) @Composable
private fun SkyglassBluePreview() = SkyglassSurfaceSample(SkyglassAppearance.BLUE)
@Preview(name="Skyglass sunset",widthDp=360) @Composable
private fun SkyglassSunsetPreview() = SkyglassSurfaceSample(SkyglassAppearance.SUNSET)
@Preview(name="Skyglass night",widthDp=360) @Composable
private fun SkyglassNightPreview() = SkyglassSurfaceSample(SkyglassAppearance.NIGHT)
@Preview(name="Skyglass large text",widthDp=320,fontScale=2f) @Composable
private fun SkyglassLargeTextPreview() = SkyglassSurfaceSample(SkyglassAppearance.BLUE)
