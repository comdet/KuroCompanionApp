package com.carcompanion.companion.service

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Minimal tree-owner so Compose can run inside a WindowManager overlay (no Activity).
 *
 * Compose's `ComposeView` queries three view-tree owners during composition:
 *   - LifecycleOwner             — for DisposableEffect / LaunchedEffect lifecycles
 *   - SavedStateRegistryOwner    — for rememberSaveable
 *   - ViewModelStoreOwner        — for viewModel() (we don't use it but it's required)
 *
 * Activities provide all three; an overlay does not. This single object implements
 * all three so we don't pull in lifecycle-viewmodel-savedstate just for plumbing.
 */
internal class OverlayLifecycleOwner :
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    fun performRestore(savedState: Bundle?) {
        savedStateRegistryController.performRestore(savedState)
    }

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }
}
