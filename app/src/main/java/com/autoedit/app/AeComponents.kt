package com.autoedit.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shared, premium UI building blocks: gold/secondary buttons with press-scale
 * feedback, icon buttons, chips, cards, section headers, and empty states.
 * Keeping them in one place guarantees consistent radius/spacing/typography.
 */

private val BtnShape = RoundedCornerShape(16.dp)

@Composable
fun GoldButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: AeIcon.Kind? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Pressable(
        modifier = modifier
            .clip(BtnShape)
            .background(if (enabled) AeGold else AeGoldDim)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                AeIcon(icon, size = 18.dp, tint = Color(0xFF1A1405))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF1A1405),
                letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp)
            )
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: AeIcon.Kind? = null,
    onClick: () -> Unit
) {
    Pressable(
        modifier = modifier
            .clip(BtnShape)
            .background(AeSurface2)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                AeIcon(icon, size = 18.dp, tint = AeGold)
                Spacer(Modifier.width(8.dp))
            }
            Text(text = text, style = MaterialTheme.typography.titleSmall, color = AeText)
        }
    }
}

/** Subtle scale-on-press wrapper used by all clickable surfaces. */
@Composable
private fun Pressable(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    Box(
        modifier = modifier then Modifier.scale(scale),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun AeIconButton(
    icon: AeIcon.Kind,
    modifier: Modifier = Modifier,
    size: Int = 44,
    background: Color = AeSurface2,
    tint: Color = AeText,
    onClick: () -> Unit
) {
    Pressable(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick)
    ) {
        AeIcon(icon, size = (size * 0.5f).dp, tint = tint)
    }
}

@Composable
fun SectionHeader(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = AeGold)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun AeCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AeSurface)
            .padding(16.dp)
    ) { content() }
}

/**
 * Friendly empty-state block with a custom drawn illustration. Used instead of
 * blank screens on Home, timeline, and storage.
 */
@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    illustration: @Composable (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AeSurface)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        illustration ?: EmptyIllustration()
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = AeText, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AeTextDim, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

/** Minimal layered-frame + sparkle illustration drawn with vectors. */
@Composable
fun EmptyIllustration() {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(96.dp)) {
        val s = size.minDimension / 24f
        drawRoundRect(
            color = AeSurface2,
            topLeft = androidx.compose.ui.geometry.Offset(4f * s, 6f * s),
            size = androidx.compose.ui.geometry.Size(16f * s, 13f * s),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * s)
        )
        drawCircle(AeGold.copy(alpha = 0.25f), 5f * s, center = androidx.compose.ui.geometry.Offset(9f*s, 11f*s))
        drawPath(androidx.compose.ui.graphics.Path().apply {
            moveTo(8f*s,16f*s); lineTo(11f*s,12f*s); lineTo(14f*s,15f*s); lineTo(16f*s,13f*s); lineTo(18f*s,16f*s); close()
        }, AeGold.copy(alpha = 0.6f))
    }
}
