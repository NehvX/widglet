package com.widglet.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.widglet.app.data.AppSettings
import com.widglet.app.data.Repo

sealed interface Screen {
    data object Home : Screen
    data object Onboarding : Screen
    data class Editor(val id: Int) : Screen
    data class Photos(val id: Int) : Screen
    data class TouchUp(val id: Int, val uri: String) : Screen
    data object Storage : Screen
    data object Settings : Screen
}

/** Tiny back-stack navigator; enough for a handful of screens and avoids extra dependencies. */
class Nav(start: Screen) {
    val stack = mutableStateListOf(start)
    val current get() = stack.last()
    fun go(s: Screen) { stack.add(s) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun reset(s: Screen) { stack.clear(); stack.add(s) }
}

@Composable
fun AppRoot(openWidget: Int, configMode: Boolean, onConfigDone: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { Repo.get(ctx) }
    val app by repo.app.collectAsState(initial = null as AppSettings?)
    val nav = remember { Nav(if (configMode) Screen.Editor(openWidget) else Screen.Home) }

    LaunchedEffect(openWidget) {
        if (!configMode && openWidget != -1 && nav.current != Screen.Editor(openWidget)) {
            nav.reset(Screen.Home)
            nav.go(Screen.Editor(openWidget))
        }
    }

    val settings = app ?: return
    if (!settings.onboarded) {
        OnboardingScreen(onDone = { })
        return
    }

    BackHandler(enabled = nav.stack.size > 1) { nav.back() }

    when (val s = nav.current) {
        Screen.Home -> HomeScreen(nav)
        Screen.Onboarding -> OnboardingScreen(onDone = { nav.back() })
        is Screen.Editor -> EditorScreen(
            id = s.id, nav = nav, configMode = configMode,
            onClose = { if (configMode) onConfigDone() else nav.back() },
        )
        is Screen.Photos -> PhotosScreen(s.id, nav)
        is Screen.TouchUp -> TouchUpScreen(s.id, s.uri, nav)
        Screen.Storage -> StorageScreen(nav)
        Screen.Settings -> SettingsScreen(nav)
    }
}
