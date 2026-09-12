package com.jarvis.watchbridge.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun Avatar3DView(character: Int, speaking: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            NativeAvatar3DView(context).apply { setCharacter(character, speaking) }
        },
        update = { view -> view.setCharacter(character, speaking) }
    )
}
