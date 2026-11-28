package com.example.remind

import androidx.core.app.NotificationManagerCompat
import com.example.remind.ui.NotificationHistoryScreen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.remind.ui.EditNoteScreen
import com.example.remind.ui.HomeScreen
import com.example.remind.ui.LibraryScreen
import com.example.remind.ui.NoteDetailScreen
import com.example.remind.ui.RemindTheme
import com.example.remind.ui.ReviewScreen
import com.example.remind.ui.SettingsScreen
import com.example.remind.ui.isAppInDarkTheme

class MainActivity : ComponentActivity() {

    /** Bumped every time a notification asks to start the review, so the same request can repeat. */
    private var reviewRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent?.action == ACTION_START_REVIEW) reviewRequest++

        val repo = (application as RemindApp).repository
        setContent {
            val themeMode by repo.themeMode.collectAsStateWithLifecycle()
            val dynamicColor by repo.dynamicColor.collectAsStateWithLifecycle()
            val readingFont by repo.readingFont.collectAsStateWithLifecycle()
            val dark = isAppInDarkTheme(themeMode)

            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }

            RemindTheme(dark = dark, dynamicColor = dynamicColor, readingFont = readingFont) {
                RequestNotificationPermission()
                RemindNavHost(reviewRequest)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app clears every Yaad notification from the bar.
        NotificationManagerCompat.from(this).cancelAll()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_START_REVIEW) reviewRequest++
    }

    companion object {
        const val ACTION_START_REVIEW = "com.example.remind.START_REVIEW"
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val LIBRARY = "library?deck={deck}&starred={starred}"
    const val EDIT = "edit?noteId={noteId}"
    const val NOTE = "note/{noteId}"
    const val REVIEW = "review?noteId={noteId}&deck={deck}&starred={starred}"
    const val SETTINGS = "settings"
    const val HISTORY = "notifications"

    fun library(deck: String?, starred: Boolean) =
        "library?starred=$starred" + (deck?.let { "&deck=" + Uri.encode(it) } ?: "")

    fun edit(noteId: Long = -1) = "edit?noteId=$noteId"
    fun note(noteId: Long) = "note/$noteId"
    fun review(noteId: Long = -1, deck: String? = null, starred: Boolean = false) =
        "review?noteId=$noteId&starred=$starred" + (deck?.let { "&deck=" + Uri.encode(it) } ?: "")
}

private const val DURATION = 320

private fun AnimatedContentTransitionScope<NavBackStackEntry>.forward(): EnterTransition =
    slideInHorizontally(tween(DURATION)) { it / 4 } + fadeIn(tween(DURATION))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.forwardExit(): ExitTransition =
    slideOutHorizontally(tween(DURATION)) { -it / 8 } + fadeOut(tween(DURATION / 2))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.back(): EnterTransition =
    slideInHorizontally(tween(DURATION)) { -it / 8 } + fadeIn(tween(DURATION))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.backExit(): ExitTransition =
    slideOutHorizontally(tween(DURATION)) { it / 4 } + fadeOut(tween(DURATION / 2))

@Composable
private fun RemindNavHost(reviewRequest: Int) {
    val nav = rememberNavController()

    LaunchedEffect(reviewRequest) {
        if (reviewRequest > 0) nav.navigate(Routes.review()) { launchSingleTop = true }
    }

    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        enterTransition = { forward() },
        exitTransition = { forwardExit() },
        popEnterTransition = { back() },
        popExitTransition = { backExit() },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onAdd = { nav.navigate(Routes.edit()) },
                onOpenNote = { nav.navigate(Routes.note(it)) },
                onStartReview = { nav.navigate(Routes.review()) },
                onOpenLibrary = { deck, starred -> nav.navigate(Routes.library(deck, starred)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.LIBRARY,
            arguments = listOf(
                navArgument("deck") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("starred") { type = NavType.BoolType; defaultValue = false },
            ),
        ) {
            LibraryScreen(
                onBack = { nav.popBackStack() },
                onOpenNote = { nav.navigate(Routes.note(it)) },
                onPractice = { deck, starred -> nav.navigate(Routes.review(deck = deck, starred = starred)) },
            )
        }
        composable(
            Routes.EDIT,
            arguments = listOf(navArgument("noteId") { type = NavType.LongType; defaultValue = -1L }),
        ) {
            EditNoteScreen(onBack = { nav.popBackStack() }, onSaved = { nav.popBackStack() })
        }
        composable(
            Routes.NOTE,
            arguments = listOf(navArgument("noteId") { type = NavType.LongType }),
        ) {
            NoteDetailScreen(
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate(Routes.edit(it)) },
                onRevise = { nav.navigate(Routes.review(noteId = it)) },
            )
        }
        composable(
            Routes.REVIEW,
            arguments = listOf(
                navArgument("noteId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("deck") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("starred") { type = NavType.BoolType; defaultValue = false },
            ),
            enterTransition = { slideInVertically(tween(DURATION)) { it / 3 } + fadeIn(tween(DURATION)) },
            popExitTransition = { slideOutVertically(tween(DURATION)) { it / 3 } + fadeOut(tween(DURATION / 2)) },
        ) {
            ReviewScreen(onClose = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() }, onOpenHistory = { nav.navigate(Routes.HISTORY) })
        }
        composable(Routes.HISTORY) {
            NotificationHistoryScreen(onBack = { nav.popBackStack() }, onOpenNote = { nav.navigate(Routes.note(it)) })
        }
    }
}
