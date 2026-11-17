package com.gwgm.manager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long)
            vm.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (ui.detail == null) {
                NavigationBar {
                    listOf(
                        Triple("Watch", Icons.Filled.Home, 0),
                        Triple("Apps", Icons.AutoMirrored.Filled.List, 1),
                        Triple("Games", Icons.Filled.PlayArrow, 2),
                        Triple("Optimize", Icons.Filled.Star, 3),
                        Triple("Safety", Icons.Filled.Info, 4),
                    ).forEach { (label, icon, idx) ->
                        NavigationBarItem(
                            selected = tab == idx,
                            onClick = { tab = idx },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            val detail = ui.detail
            when {
                detail != null -> PackageDetailScreen(detail, ui, vm, onBack = vm::closeDetail)
                tab == 0 -> DashboardScreen(ui, vm)
                tab == 1 -> InventoryScreen(ui, vm)
                tab == 2 -> GamesScreen(ui, vm)
                tab == 3 -> OptimizeScreen(ui, vm, openChecklist = { tab = 4 })
                else -> SafetyScreen(ui, vm)
            }
        }
        ui.review?.let { r -> ChangeReviewDialog(r, vm, openChecklist = { vm.closeDetail(); tab = 4 }) }
    }
}
