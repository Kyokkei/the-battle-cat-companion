package com.projectnyanko.companion.ui.seed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.projectnyanko.companion.domain.seed.CatRarity
import com.projectnyanko.companion.ui.theme.Accent
import com.projectnyanko.companion.ui.theme.Ink
import com.projectnyanko.companion.ui.theme.InkSoft
import com.projectnyanko.companion.ui.theme.PaperLight

/** Paper card that fills most of the screen, used for every picker and the seek flow. */
@Composable
internal fun NyankoDialogFrame(
    onDismiss: () -> Unit,
    dismissable: Boolean = true,
    fillHeight: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (dismissable) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissable,
            dismissOnClickOutside = dismissable,
        ),
    ) {
        val shape = RoundedCornerShape(24.dp)
        val windowHeight = with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.height.toDp()
        }
        val maxHeight = windowHeight * 0.92f
        Column(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .then(if (fillHeight) Modifier.fillMaxHeight(0.92f) else Modifier.heightIn(max = maxHeight))
                .clip(shape)
                .background(PaperLight)
                .border(3.dp, Ink, shape)
                .padding(16.dp),
            content = content,
        )
    }
}

/** Battle Cats rarity tint, used for the dot next to a cat name. */
internal fun rarityColor(rarity: CatRarity): Color = when (rarity) {
    CatRarity.RARE -> Color(0xFF3E6FA8)
    CatRarity.SUPER_RARE -> Color(0xFFB0498C)
    CatRarity.UBER_RARE -> Color(0xFFC98A17)
    CatRarity.LEGEND_RARE -> Color(0xFF7B4FB0)
}

@Composable
internal fun RarityDot(rarity: CatRarity, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(rarityColor(rarity)),
    )
}

/** Tiny uppercase label such as NEXT / LAST / 11-ROLL. */
@Composable
internal fun MicroChip(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = Accent,
    foreground: Color = Ink,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            fontSize = 9.sp,
            color = foreground,
        )
    }
}

@Composable
internal fun DialogHeader(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Ink)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = InkSoft)
        }
    }
}

@Composable
internal fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = InkSoft)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = Ink,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
