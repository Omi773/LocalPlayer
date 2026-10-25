package app.localplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVKit.AVRoutePickerView
import platform.UIKit.UIColor
import platform.UIKit.labelColor

@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun RoutePicker(modifier: Modifier) {
    UIKitView(
        factory = {
            AVRoutePickerView().apply {
                tintColor = UIColor.labelColor
                activeTintColor = UIColor.labelColor
                prioritizesVideoDevices = false
            }
        },
        modifier = modifier,
    )
}

fun MainViewController() = ComposeUIViewController {
    val bridge = remember { IosPlatformBridge() }
    val player = remember { IosAudioPlayer() }

    DisposableEffect(player) {
        onDispose {
            player.shutdown()
        }
    }

    App(bridge, player) { m -> RoutePicker(m) }
}
