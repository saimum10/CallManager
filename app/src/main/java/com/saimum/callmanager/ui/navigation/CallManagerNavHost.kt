package com.saimum.callmanager.ui.navigation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.saimum.callmanager.ui.screens.ForwardingScreen
import com.saimum.callmanager.ui.screens.HomeScreen
import com.saimum.callmanager.ui.screens.RecordingScreen
import com.saimum.callmanager.ui.screens.SettingsScreen
import kotlinx.coroutines.launch

/**
 * Scaffold-level navigation graph.
 *
 * One shared top bar (title + Radio info + Settings actions) covers all
 * three bottom-tab destinations, so both are reachable from Home,
 * Recording, and Forwarding alike — not just Home. The Settings screen
 * itself is a detail/back-stack screen and keeps its own back-navigation
 * top bar instead of this shared one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallManagerNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // True on the three main tabs. Settings is a detail screen: it has its own
    // back-arrow top bar and, like any detail screen, no bottom tab bar.
    val isTabDestination = bottomBarDestinations.any { dest ->
        currentDestination?.hierarchy?.any { it.route == dest.route } == true
    }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            if (isTabDestination) {
                TopAppBar(
                    title = { Text("Call Manager") },
                    actions = {
                        IconButton(onClick = {
                            if (!openRadioInfo(context)) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Not supported on this device")
                                }
                            }
                        }) {
                            AntennaSignalIcon(
                                modifier = Modifier.size(28.dp),
                                tint = LocalContentColor.current
                            )
                        }
                        IconButton(onClick = {
                            navController.navigate(Destination.Settings.route) {
                                launchSingleTop = true
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Settings",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (isTabDestination) NavigationBar {
                bottomBarDestinations.forEach { destination ->
                    val isSelected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            if (isSelected) return@NavigationBarItem
                            navigateToTab(navController, destination)
                        },
                        // Recording gets a real hand-drawn glyph (matches the
                        // provided reference mark); the other tabs are still
                        // a first-letter text stand-in until they get one too.
                        icon = {
                            if (destination == Destination.Recording) {
                                MicRecordIcon(modifier = Modifier.size(28.dp))
                            } else {
                                Text(destination.label.first().toString())
                            }
                        },
                        label = { Text(destination.label) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Home.route,
            // consumeWindowInsets tells the screens' own (nested) Scaffolds that
            // the bars above/below already took care of the system insets —
            // without it each one adds the status/navigation bar padding again.
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            composable(Destination.Home.route) {
                HomeScreen(
                    onNavigateToRecording = { navigateToTab(navController, Destination.Recording) },
                    onNavigateToForwarding = { navigateToTab(navController, Destination.Forwarding) }
                )
            }
            composable(Destination.Recording.route) {
                RecordingScreen()
            }
            composable(Destination.Forwarding.route) {
                ForwardingScreen()
            }
            composable(Destination.Settings.route) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * Navigates to one of the three bottom-tab destinations, preserving each
 * tab's own back-stack/scroll state the same way the bottom bar's own tap
 * handler always has. Shared by the bottom bar itself and by Status
 * page's "jump to Recording / Forwarding" card taps, so both paths land
 * in the exact same place with the exact same back-stack behavior.
 */
private fun navigateToTab(navController: NavHostController, destination: Destination) {
    navController.navigate(destination.route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Opens the system's built-in radio/telephony diagnostics screen (the
 * "Phone info" page also reachable by dialing *#*#4636#*#*), bypassing the
 * dialer secret-code path since it doesn't trigger on every device/build.
 *
 * Tries explicit components in newest-to-oldest AOSP order:
 *  1. com.android.phone.settings.RadioInfo — current AOSP location, inside
 *     the Telephony app (packages/services/Telephony). Matches modern
 *     fields like per-source phone numbers and subscription id.
 *  2. com.android.settings.RadioInfo — where this screen lived on older
 *     (roughly pre-Android 10) AOSP builds, inside the Settings app.
 *
 * OEM builds (MIUI, One UI, ColorOS, etc.) may relocate or block this
 * activity entirely, so both attempts are wrapped defensively — the caller
 * shows a fallback message if neither launches.
 */
private fun openRadioInfo(context: Context): Boolean {
    val candidates = listOf(
        ComponentName("com.android.phone", "com.android.phone.settings.RadioInfo"),
        ComponentName("com.android.settings", "com.android.settings.RadioInfo")
    )
    for (component in candidates) {
        try {
            context.startActivity(Intent().setComponent(component))
            return true
        } catch (e: Exception) {
            // Not present / not exported on this build — try the next candidate.
        }
    }
    return false
}

/**
 * Recording tab glyph: a solid black circular badge with a white
 * microphone mark inside — matches the reference icon supplied for this
 * tab. Hand-drawn for the same reason [AntennaSignalIcon] is (no
 * material-icons-extended dependency), and, unlike the other bottom-bar
 * icons, deliberately uses fixed black/white colors rather than the
 * current content/tint color: it's meant to read as a distinct recording
 * mark, not a regular tinted nav glyph. Proportions are fractions of the
 * given size so it scales cleanly at any icon size.
 */
@Composable
private fun MicRecordIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val diameter = minOf(w, h)
        val center = Offset(w / 2f, h / 2f)

        drawCircle(color = Color.Black, radius = diameter / 2f, center = center)

        val strokeWidth = diameter * 0.075f

        // Mic capsule (the "head").
        val capsuleWidth = diameter * 0.22f
        val capsuleTop = h * 0.14f
        val capsuleBottom = h * 0.50f
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(center.x - capsuleWidth / 2f, capsuleTop),
            size = Size(capsuleWidth, capsuleBottom - capsuleTop),
            cornerRadius = CornerRadius(capsuleWidth / 2f, capsuleWidth / 2f)
        )

        // Outer holder: two vertical strokes hugging just outside the
        // capsule, closed off by a bottom half-circle arc.
        val holderHalfWidth = diameter * 0.185f
        val holderTop = h * 0.27f
        val holderStraightBottom = h * 0.50f
        drawLine(
            color = Color.White,
            start = Offset(center.x - holderHalfWidth, holderTop),
            end = Offset(center.x - holderHalfWidth, holderStraightBottom),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White,
            start = Offset(center.x + holderHalfWidth, holderTop),
            end = Offset(center.x + holderHalfWidth, holderStraightBottom),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawArc(
            color = Color.White,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(center.x - holderHalfWidth, holderStraightBottom - holderHalfWidth),
            size = Size(holderHalfWidth * 2f, holderHalfWidth * 2f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // Stem down to the base foot.
        drawLine(
            color = Color.White,
            start = Offset(center.x, holderStraightBottom + holderHalfWidth),
            end = Offset(center.x, h * 0.72f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // Base foot bar.
        val footWidth = diameter * 0.26f
        val footHeight = diameter * 0.05f
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(center.x - footWidth / 2f, h * 0.74f),
            size = Size(footWidth, footHeight),
            cornerRadius = CornerRadius(footHeight / 2f, footHeight / 2f)
        )
    }
}

/**
 * Antenna-with-ascending-signal-bars glyph, hand-drawn since it isn't part
 * of material-icons-core (and material-icons-extended isn't a dependency
 * here). Proportions are fractions of the given size so it scales cleanly
 * at any icon size.
 */
@Composable
private fun AntennaSignalIcon(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = w * 0.14f
        val apex = Offset(w * 0.17f, h * 0.42f)

        // Antenna head (the two converging strokes) and its stem.
        drawLine(
            color = tint,
            start = Offset(w * 0.01f, h * 0.04f),
            end = apex,
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.33f, h * 0.04f),
            end = apex,
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = apex,
            end = Offset(w * 0.17f, h * 0.92f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // Four ascending signal-strength bars.
        val barWidth = w * 0.085f
        val baseline = h * 0.92f
        val barHeightFractions = listOf(0.26f, 0.44f, 0.62f, 0.84f)
        val barCenterFractions = listOf(0.46f, 0.62f, 0.78f, 0.94f)
        barHeightFractions.forEachIndexed { index, heightFraction ->
            val barHeight = h * heightFraction
            val centerX = w * barCenterFractions[index]
            drawRoundRect(
                color = tint,
                topLeft = Offset(centerX - barWidth / 2f, baseline - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
