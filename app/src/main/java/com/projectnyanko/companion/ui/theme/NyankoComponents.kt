package com.projectnyanko.companion.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnyanko.companion.R

private val PanelShape = RoundedCornerShape(22.dp)
private val PillShape = RoundedCornerShape(26.dp)
private val OutlineWidth = 3.dp

/** A cream card with the template's heavy ink outline. Every screen is built from these. */
@Composable
fun NyankoPanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(PaperDeep)
            .border(OutlineWidth, Ink, PanelShape)
            .padding(contentPadding),
        content = content,
    )
}

/** The yellow numbered blob that starts a panel heading. */
@Composable
fun StepBadge(number: String) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Accent)
            .border(OutlineWidth, Ink, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number,
            color = Ink,
            fontWeight = FontWeight.Black,
            fontSize = 17.sp,
        )
    }
}

@Composable
fun SectionHeading(number: String?, title: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (number != null) StepBadge(number)
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = Ink,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * The template's pill button: yellow for the primary action, paper for the rest, always with the
 * same ink outline so the two variants read as the same control.
 */
@Composable
fun NyankoPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
    @DrawableRes icon: Int? = R.drawable.icon_paw,
    height: Int = 54,
) {
    val fill = when {
        !enabled -> PaperDeep.copy(alpha = 0.55f)
        filled -> Accent
        else -> PaperLight
    }
    val contentColor = if (enabled) Ink else InkSoft.copy(alpha = 0.6f)
    Row(
        modifier = modifier
            .height(height.dp)
            .clip(PillShape)
            .background(fill)
            .border(OutlineWidth, if (enabled) Ink else InkSoft.copy(alpha = 0.5f), PillShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                alpha = if (enabled) 1f else 0.4f,
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Small selectable pill, used for the region switch. */
@Composable
fun NyankoToggleChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) Accent else PaperLight)
            .border(2.dp, Ink, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = Ink,
            fontSize = 13.sp,
        )
    }
}

/** Bordered paper input, matching the template's search field. */
@Composable
fun NyankoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    @DrawableRes leadingIcon: Int? = R.drawable.nav_search,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(modifier = modifier.fillMaxWidth()) {
    if (label != null) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = InkSoft,
            modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PaperLight)
            .border(OutlineWidth, Ink, shape)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (leadingIcon != null) {
            Image(
                painter = painterResource(leadingIcon),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                alpha = 0.75f,
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = InkSoft.copy(alpha = 0.8f),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = LocalTextStyle.current.merge(
                    MaterialTheme.typography.bodyLarge.copy(color = Ink),
                ),
                cursorBrush = SolidColor(Ink),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    }
}

/** One row of the bordered lists the template uses for results and tables. */
@Composable
fun NyankoRow(
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (highlight) Accent else PaperLight)
            .border(2.dp, Ink.copy(alpha = 0.85f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Bottom navigation bar: three outlined tabs, the selected one filled with the accent. */
@Composable
fun NyankoNavBar(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Paper)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            val shape = RoundedCornerShape(16.dp)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(62.dp)
                    .clip(shape)
                    .background(if (selected) Accent else PaperDeep)
                    .border(OutlineWidth, Ink, shape)
                    .clickable { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(item.icon),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    maxLines = 1,
                )
            }
        }
    }
}

data class NavItem(val label: String, @DrawableRes val icon: Int)

/** Small caption line used for attribution and status text. */
@Composable
fun NyankoCaption(text: String, modifier: Modifier = Modifier, color: Color = InkSoft) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = modifier,
    )
}
