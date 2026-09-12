package com.jarvis.watchbridge.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.sceneview.SceneView
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.node.ModelNode

@Composable
fun Avatar3DView(character: Int, speaking: Boolean, modifier: Modifier = Modifier) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/CesiumMan.glb")
    SceneView(modifier = modifier, engine = engine, modelLoader = modelLoader, cameraManipulator = rememberCameraManipulator()) {
        modelInstance?.let {
            ModelNode(modelInstance = it, scaleToUnits = 2.6f, autoAnimate = true, isEditable = true)
        }
    }
}
