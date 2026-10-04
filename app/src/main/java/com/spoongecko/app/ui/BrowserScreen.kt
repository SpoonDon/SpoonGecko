@file:OptIn(ExperimentalMaterial3Api::class)

package com.spoongecko.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spoongecko.app.browser.BrowserViewModel
import com.spoongecko.app.browser.Tab
import com.spoongecko.app.util.UrlUtils

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onRequestBatteryExemption: () -> Unit,
    onRequestOemAutostart: () -> String
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activeTab = state.tabs.firstOrNull { it.id == state.activeTabId }
    val keyboard = LocalSoftwareKeyboardController.current

    var urlText by remember { mutableStateOf("") }
    var showTabs by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showOemDialog by remember { mutableStateOf(false) }
    var oemLabel by remember { mutableStateOf("") }

    LaunchedEffect(activeTab?.id, activeTab?.url) {
        val tab = activeTab ?: return@LaunchedEffect
        if (tab.url != "about:blank") urlText = tab.url
    }

    BackHandler(enabled = activeTab?.canGoBack == true) {
        viewModel.goBack()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {

            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().statusBarsPadding()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.goBack() },
                        enabled = activeTab?.canGoBack == true
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    IconButton(
                        onClick = { viewModel.goForward() },
                        enabled = activeTab?.canGoForward == true
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                    }
                    IconButton(onClick = {
                        if (activeTab?.isLoading == true) viewModel.stop() else viewModel.reload()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                    }

                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Search or enter address") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                viewModel.loadUrl(urlText)
                                keyboard?.hide()
                            }
                        )
                    )

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("New tab") },
                                onClick = { viewModel.addTab(); showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Tabs (${state.tabs.size})") },
                                onClick = { showTabs = true; showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Battery: don't kill SpoonGecko") },
                                onClick = { onRequestBatteryExemption(); showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("OEM autostart settings") },
                                onClick = {
                                    oemLabel = onRequestOemAutostart()
                                    showOemDialog = true
                                    showMenu = false
                                }
                            )
                        }
                    }
                }
            }

            if (activeTab?.isLoading == true) {
                LinearProgressIndicator(
                    progress = { (activeTab.progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val session = viewModel.activeSession()
                if (session != null) {
                    GeckoViewContainer(
                        session = session,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    if (showTabs) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showTabs = false },
            sheetState = sheetState
        ) {
            TabsSheet(
                tabs = state.tabs,
                activeId = state.activeTabId,
                onSelect = { viewModel.selectTab(it); showTabs = false },
                onClose = { viewModel.closeTab(it) },
                onNew = { viewModel.addTab(); showTabs = false }
            )
        }
    }

    if (showOemDialog) {
        AlertDialog(
            onDismissRequest = { showOemDialog = false },
            title = { Text("Keep SpoonGecko alive") },
            text = {
                Text(
                    "We tried to open: $oemLabel\n\n" +
                        "On OEM Android, allow SpoonGecko to autostart and disable " +
                        "battery restrictions for it. Otherwise the system will kill " +
                        "GeckoView in the background."
                )
            },
            confirmButton = {
                TextButton(onClick = { showOemDialog = false }) { Text("Got it") }
            }
        )
    }
}

@Composable
private fun TabsSheet(
    tabs: List<Tab>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Tabs", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = "New tab")
            }
        }

        LazyColumn(Modifier.fillMaxWidth()) {
            items(tabs, key = { it.id }) { tab ->
                Surface(
                    tonalElevation = if (tab.id == activeId) 3.dp else 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = tab.title.ifBlank { UrlUtils.prettify(tab.url).ifBlank { "New tab" } },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = UrlUtils.prettify(tab.url),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onClose(tab.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close tab")
                        }
                    }
                }
            }
        }
    }
}
