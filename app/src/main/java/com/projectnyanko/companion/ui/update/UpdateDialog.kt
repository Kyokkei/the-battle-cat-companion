package com.projectnyanko.companion.ui.update

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.projectnyanko.companion.R
import com.projectnyanko.companion.data.update.UpdateInfo
import com.projectnyanko.companion.ui.theme.Ink
import com.projectnyanko.companion.ui.theme.InkSoft
import com.projectnyanko.companion.ui.theme.NyankoPillButton
import com.projectnyanko.companion.ui.theme.Paper
import com.projectnyanko.companion.ui.theme.PaperLight

/**
 * "A new build is out" — art on top, the artist's credit under it, then the two choices.
 *
 * [onUpdateNow] downloads the APK in the background and hands it to Android's installer, which is
 * why [progress] exists: a 78 MB download with no feedback reads as a broken button. [message]
 * carries the two things that can go wrong out loud — the install permission is missing, or the
 * download failed.
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit,
    progress: Int? = null,
    message: String? = null,
) {
    Dialog(onDismissRequest = onLater, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .padding(horizontal = 18.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Paper)
                .border(2.dp, Ink, RoundedCornerShape(22.dp))
                .padding(16.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.update_art),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(640f / 385f)
                    .clip(RoundedCornerShape(14.dp)),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.update_art_credit),
                style = MaterialTheme.typography.bodySmall,
                color = InkSoft,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
            if (info.notes != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = info.notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(PaperLight)
                        .padding(12.dp),
                )
            }
            if (message != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(PaperLight)
                        .padding(10.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            // Stacked, not side by side: half a dialog is not enough for "I'll update it later" in
            // any language, and the pill button ellipsises rather than wrapping.
            NyankoPillButton(
                text = if (progress != null) {
                    "Downloading… ${progress.coerceIn(0, 100)}%"
                } else {
                    stringResource(R.string.update_now)
                },
                onClick = { if (progress == null) onUpdateNow() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            NyankoPillButton(
                text = stringResource(R.string.update_later),
                onClick = onLater,
                filled = false,
                icon = null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
