package com.saimum.callmanager.ui.navigation

sealed class Destination(val route: String, val label: String) {
    data object Home : Destination("home", "Status")
    data object Recording : Destination("recording", "Recording")
    data object Forwarding : Destination("forwarding", "Forwarding")
    data object Settings : Destination("settings", "Settings")
}

val bottomBarDestinations = listOf(
    Destination.Home,
    Destination.Recording,
    Destination.Forwarding
)
