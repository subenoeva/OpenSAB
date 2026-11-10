package com.opensab.app.ui.common

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opensab.app.R
import com.opensab.app.theme.OpenSABTheme
import com.opensab.sab.session.vehicle.BodyStyle

@StringRes
fun BodyStyle.label(): Int = when (this) {
    BodyStyle.GENERIC -> R.string.body_style_generic
    BodyStyle.NAKED -> R.string.body_style_naked
    BodyStyle.SPORT -> R.string.body_style_sport
    BodyStyle.ADVENTURE -> R.string.body_style_adventure
    BodyStyle.TOURING -> R.string.body_style_touring
    BodyStyle.CUSTOM -> R.string.body_style_custom
    BodyStyle.SCOOTER -> R.string.body_style_scooter
}

fun BodyStyle.icon(): ImageVector = when (this) {
    BodyStyle.GENERIC -> Generic
    BodyStyle.NAKED -> Naked
    BodyStyle.SPORT -> Sport
    BodyStyle.ADVENTURE -> Adventure
    BodyStyle.TOURING -> Touring
    BodyStyle.CUSTOM -> Custom
    BodyStyle.SCOOTER -> Scooter
}

/** The seven body styles side by side, labelled: the one place to judge them as a family. */
@Composable
internal fun BodyStyleIconGrid(modifier: Modifier = Modifier, iconSize: Dp = 48.dp) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BodyStyle.entries.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { style ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = style.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Icon(
                            imageVector = style.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(style.label()),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Light Mode", showBackground = true)
@Preview(name = "Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun BodyStyleIconGridPreview() {
    OpenSABTheme {
        Surface { BodyStyleIconGrid() }
    }
}

/** One even-odd path per style, so wheels and cut-outs are holes in a single silhouette. */
private fun bikeIcon(name: String, pathData: String): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).addPath(
    pathData = addPathNodes(pathData),
    pathFillType = PathFillType.EvenOdd,
    fill = SolidColor(Color.Black)
).build()

private val Generic: ImageVector by lazy {
    bikeIcon(
        "BodyStyle.Generic",
        "M2.5 11.5L3.5 10L10 10L11 8.8L14 8.8L15 6.6L14.5 6L17.5 6L17.5 6.8L17.3 7L19.3 11.4L17.7 11.4L16.7 9.2L14.5 11.8L14.2 16L10.2 16L9.6 13L8 11.5ZM2 15.5A3.5 3.5 0 1 0 9 15.5A3.5 3.5 0 1 0 2 15.5ZM3.5 15.5A2 2 0 1 0 7.5 15.5A2 2 0 1 0 3.5 15.5ZM15 15.5A3.5 3.5 0 1 0 22 15.5A3.5 3.5 0 1 0 15 15.5ZM16.5 15.5A2 2 0 1 0 20.5 15.5A2 2 0 1 0 16.5 15.5Z"
    )
}

private val Naked: ImageVector by lazy {
    bikeIcon(
        "BodyStyle.Naked",
        "M2.5 11.2L2.8 9.2L4 9.6L8.6 9.6L9.4 8.2Q10.5 6.6 12.5 6.6L14.2 6.8L15.3 7.6L15.6 5.2L14.6 5.2L14.6 4.3L17.8 4.3L17.8 5.2L16.8 5.2L19.2 11.4L17.6 11.4L16.4 8.3L15.2 10.2L8.6 11.2ZM10 11.8L14.4 11.1L14.6 13.6L13.6 16.2L10.6 16.2L9.8 13.4ZM18.3 7.6A1.5 1.5 0 1 0 21.3 7.6A1.5 1.5 0 1 0 18.3 7.6ZM2 15.5A3.5 3.5 0 1 0 9 15.5A3.5 3.5 0 1 0 2 15.5ZM3.5 15.5A2 2 0 1 0 7.5 15.5A2 2 0 1 0 3.5 15.5ZM15 15.5A3.5 3.5 0 1 0 22 15.5A3.5 3.5 0 1 0 15 15.5ZM16.5 15.5A2 2 0 1 0 20.5 15.5A2 2 0 1 0 16.5 15.5Z"
    )
}

private val Sport: ImageVector by lazy {
    bikeIcon(
        "BodyStyle.Sport",
        "M2 8L8.5 9.8L10 9.6L11.2 8.4L14.5 8.2L16.2 6.6L17.4 6.2L18 6.9L21 9.6L20.6 10.6L16.5 11.3L15 12.4L14.3 15L13.4 15.8L10.6 15.8L9.7 13.5L8.5 11.3L4 10.8ZM2 15.5A3.5 3.5 0 1 0 9 15.5A3.5 3.5 0 1 0 2 15.5ZM3.5 15.5A2 2 0 1 0 7.5 15.5A2 2 0 1 0 3.5 15.5ZM15 15.5A3.5 3.5 0 1 0 22 15.5A3.5 3.5 0 1 0 15 15.5ZM16.5 15.5A2 2 0 1 0 20.5 15.5A2 2 0 1 0 16.5 15.5Z"
    )
}

private val Adventure: ImageVector by lazy {
    bikeIcon(
        "BodyStyle.Adventure",
        "M2.3 8.6L3 7.4L9.5 7.6L10.5 6.4L13.5 6.2L15 5.4L15.4 2.4L17 2.4L17.8 5.6L18.8 7.6L21.8 9.3L19.2 9.5L18.6 10.3L17.2 10.3L16.4 8.4L14.8 9L14 10.2L13.4 13.8L12.5 15L10.2 15L9.4 11.2L8.6 9.3L3.6 9.3ZM10.2 15.7L13 15.7L12.4 16.6L10.6 16.6ZM2 15.5A3.5 3.5 0 1 0 9 15.5A3.5 3.5 0 1 0 2 15.5ZM3.5 15.5A2 2 0 1 0 7.5 15.5A2 2 0 1 0 3.5 15.5ZM14 15A4 4 0 1 0 22 15A4 4 0 1 0 14 15ZM15.5 15A2.5 2.5 0 1 0 20.5 15A2.5 2.5 0 1 0 15.5 15Z"
    )
}

private val Touring: ImageVector by lazy {
    bikeIcon(
        "BodyStyle.Touring",
        "M5.8 7.2L8 7.2L8.6 8.3L11.5 8.3L12.3 7.5L14.5 7.5L15.5 6.5L16.2 2.2L17.4 2.2L19 6L20.5 7.5L20.8 9.5L19 10.8L16.2 11.2L15 12.5L14.3 15.8L10.2 15.8L9.5 12.3L9.6 9.4L8.8 8.7L5.8 8.7ZM2.6 5L4.6 5A0.6 0.6 0 0 1 5.2 5.6L5.2 8A0.6 0.6 0 0 1 4.6 8.6L2.6 8.6A0.6 0.6 0 0 1 2 8L2 5.6A0.6 0.6 0 0 1 2.6 5ZM2.6 9.3L8.2 9.3A0.6 0.6 0 0 1 8.8 9.9L8.8 10.9A0.6 0.6 0 0 1 8.2 11.5L2.6 11.5A0.6 0.6 0 0 1 2 10.9L2 9.9A0.6 0.6 0 0 1 2.6 9.3ZM2 15.5A3.5 3.5 0 1 0 9 15.5A3.5 3.5 0 1 0 2 15.5ZM3.5 15.5A2 2 0 1 0 7.5 15.5A2 2 0 1 0 3.5 15.5ZM15 15.5A3.5 3.5 0 1 0 22 15.5A3.5 3.5 0 1 0 15 15.5ZM16.5 15.5A2 2 0 1 0 20.5 15.5A2 2 0 1 0 16.5 15.5Z"
    )
}

private val Custom: ImageVector by lazy {
    bikeIcon(
        "BodyStyle.Custom",
        "M2.16 11.52A5.2 5.2 0 0 1 8.48 11.24L10 11Q11 11 11.6 9.4Q12.4 7.6 14.2 7.8L14.5 7.1L13 3.5L11.8 3.5L11.8 2.7L13.6 2.7L15.3 6.5L18.9 11.3L17.6 11.4L15.44 8.52L14.8 9.4L14.8 11.8L14.3 15.5L10.3 15.5L9.6 12.5L7.79 12.22A4 4 0 0 0 2.93 12.44ZM2 15.5A3.5 3.5 0 1 0 9 15.5A3.5 3.5 0 1 0 2 15.5ZM3.5 15.5A2 2 0 1 0 7.5 15.5A2 2 0 1 0 3.5 15.5ZM15 15.5A3.5 3.5 0 1 0 22 15.5A3.5 3.5 0 1 0 15 15.5ZM16.5 15.5A2 2 0 1 0 20.5 15.5A2 2 0 1 0 16.5 15.5Z"
    )
}

private val Scooter: ImageVector by lazy {
    bikeIcon(
        "BodyStyle.Scooter",
        "M2.3 12.6L2 11L3 9.5L7 9.2L8 9.8L10.2 9.8L10.9 11L11.4 13.3L15 13.3L16 11.5L16.2 6.8L15.4 4.6L17.4 4.6L18 6L19.8 9.5L21 11.5L21.2 12.5L18.5 12.8L16.5 14L15.9 15.8L9 15.8L8 13.5L7 12.8ZM2 16.25A2.75 2.75 0 1 0 7.5 16.25A2.75 2.75 0 1 0 2 16.25ZM3.5 16.25A1.25 1.25 0 1 0 6 16.25A1.25 1.25 0 1 0 3.5 16.25ZM16.5 16.25A2.75 2.75 0 1 0 22 16.25A2.75 2.75 0 1 0 16.5 16.25ZM18 16.25A1.25 1.25 0 1 0 20.5 16.25A1.25 1.25 0 1 0 18 16.25Z"
    )
}
