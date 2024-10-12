package xyz.mendess.mtogo.ui.util

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest


@Composable
fun LongPressButton(
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit
) {
    val viewConfiguration = LocalViewConfiguration.current
    LaunchedEffect(interactionSource) {
        var isLongClick = false
        interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    isLongClick = false
                    delay(viewConfiguration.longPressTimeoutMillis)
                    isLongClick = true
                    onLongPress()
                }

                is PressInteraction.Release -> {
                    if (!isLongClick) {
                        onClick()
                    }
                }

            }
        }
    }


    Button(
        onClick = {},
        colors = colors,
        interactionSource = interactionSource,
        modifier = modifier,
        content = content
    )
}