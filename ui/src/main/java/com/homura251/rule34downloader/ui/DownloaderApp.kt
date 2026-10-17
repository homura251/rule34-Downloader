package com.homura251.rule34downloader.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.homura251.rule34downloader.domain.*

private data class Destination(val route: String, val label: String, val icon: ImageVector)
private val destinations = listOf(Destination("subscriptions", "订阅", Icons.Rounded.CollectionsBookmark),
    Destination("gallery", "作品", Icons.Rounded.PhotoLibrary), Destination("tasks", "任务", Icons.Rounded.Download),
    Destination("settings", "设置", Icons.Rounded.Settings))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloaderApp(services: AppServices, onRequestNotificationPermission: () -> Unit) {
    val startup by services.startup.collectAsStateWithLifecycle()
    if (startup.status == StartupStatus.ERROR) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Text(startup.error.orEmpty())
            Spacer(Modifier.height(16.dp))
            Button(onClick = services::retryStartup) { Text("重新尝试") }
        }
        return
    }
    val account by services.account.state.collectAsStateWithLifecycle()
    if (startup.status == StartupStatus.LOADING || account.status == AccountStatus.LOADING) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val navigation = rememberNavController()
    val entry by navigation.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val start = remember { if (account.status == AccountStatus.MISSING) "setup" else "subscriptions" }
    val snackbar = remember { SnackbarHostState() }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    val add: AddSubscriptionViewModel = viewModel(factory = remember(services) { viewModelFactory { initializer { AddSubscriptionViewModel(services) } } })
    val folderSettings: SettingsViewModel = viewModel(key = "folder-settings", factory = remember(services) { viewModelFactory { initializer { SettingsViewModel(services) } } })
    val preferences by services.settings.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) folderSettings.linkFolder(uri.toString()) }
    ReportMessages(folderSettings, snackbar)
    fun openSettings() { navigation.navigate("settings") { launchSingleTop = true } }
    fun addSubscription() { if (account.canUseApi) { add.reset(); showAdd = true } else openSettings() }
    fun select(destination: Destination) {
        navigation.navigate(destination.route) {
            popUpTo("subscriptions") { saveState = true }
            launchSingleTop = true; restoreState = true
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        val wide = maxWidth >= 600.dp
        val setup = route == "setup"
        val detail = route == "collection/{id}"
        Scaffold(
            topBar = { if (!setup) TopAppBar(title = { Text(if (detail) "作品集" else destinations.firstOrNull { it.route == route }?.label ?: "资料库") },
                navigationIcon = { if (detail) IconButton({ navigation.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
                actions = { if (route == "subscriptions") TextButton(onClick = ::addSubscription, modifier = Modifier.testTag("add-author")) {
                    Icon(if (account.canUseApi) Icons.Rounded.Add else Icons.Rounded.Key, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp)); Text(if (account.canUseApi) "添加订阅" else "配置 API")
                } }) },
            snackbarHost = { SnackbarHost(snackbar) { Snackbar(it, modifier = Modifier.testTag("app-message")) } },
            bottomBar = { if (!wide && !setup && !detail) NavigationBar {
                destinations.forEach { destination -> NavigationBarItem(selected = route == destination.route, onClick = { select(destination) },
                    icon = { Icon(destination.icon, destination.label) }, label = { Text(destination.label) }, modifier = Modifier.testTag("nav-${destination.route}")) }
            } },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide && !setup && !detail) NavigationRail {
                    destinations.forEach { destination -> NavigationRailItem(selected = route == destination.route, onClick = { select(destination) },
                        icon = { Icon(destination.icon, destination.label) }, label = { Text(destination.label) }, modifier = Modifier.testTag("nav-${destination.route}")) }
                }
                Column(Modifier.weight(1f)) {
                    if (!setup && route != "settings") AccountBanner(account, ::openSettings)
                    NavHost(navigation, startDestination = start, modifier = Modifier.weight(1f)) {
                        composable("setup") {
                            val model: SettingsViewModel = viewModel(factory = remember(services) { viewModelFactory { initializer { SettingsViewModel(services) } } })
                            val setupState by model.state.collectAsStateWithLifecycle()
                            LaunchedEffect(account.canUseApi, setupState.validating, setupState.accountError) {
                                if (account.canUseApi && !setupState.validating && setupState.accountError == null)
                                    navigation.navigate("subscriptions") { popUpTo("setup") { inclusive = true } }
                            }
                            SettingsScreen(model, snackbar, {}, onRequestNotificationPermission, setup = true, onBrowseOffline = {
                                navigation.navigate("subscriptions") { popUpTo("setup") { inclusive = true } }
                            })
                        }
                        composable("subscriptions") {
                            val model: SubscriptionsViewModel = viewModel(factory = remember(services) { viewModelFactory { initializer { SubscriptionsViewModel(services.library, services.tasks) } } })
                            SubscriptionsScreen(model, account, snackbar, { id -> navigation.navigate("collection/${Uri.encode(id)}") },
                                { add.reset(); showAdd = true }, ::openSettings, onRequestNotificationPermission)
                        }
                        composable("gallery") {
                            val model: GalleryViewModel = viewModel(factory = remember(services) { viewModelFactory { initializer { GalleryViewModel(services.library, createSavedStateHandle()) } } })
                            GalleryScreen(model, account.canUseApi, ::openSettings)
                        }
                        composable("collection/{id}") { destination ->
                            val id = destination.arguments?.getString("id")
                            val model: GalleryViewModel = viewModel(factory = remember(services, id) { viewModelFactory { initializer { GalleryViewModel(services.library, createSavedStateHandle(), id) } } })
                            GalleryScreen(model, account.canUseApi, ::openSettings)
                        }
                        composable("tasks") {
                            val model: TasksViewModel = viewModel(factory = remember(services) { viewModelFactory { initializer { TasksViewModel(services.library, services.tasks) } } })
                            TasksScreen(model, account.canUseApi, snackbar, onRequestNotificationPermission, ::openSettings)
                        }
                        composable("settings") {
                            val model: SettingsViewModel = viewModel(factory = remember(services) { viewModelFactory { initializer { SettingsViewModel(services) } } })
                            SettingsScreen(model, snackbar, {
                                val initial = preferences.existingDownloadsTreeUri?.let { value -> runCatching {
                                    val tree = Uri.parse(value)
                                    DocumentsContract.buildDocumentUri(checkNotNull(tree.authority), DocumentsContract.getTreeDocumentId(tree))
                                }.getOrNull() }
                                picker.launch(initial ?: DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/Rule34 Downloader"))
                            }, onRequestNotificationPermission, onAddAuthor = ::addSubscription)
                        }
                    }
                }
            }
        }
    }
    if (showAdd) AddSubscriptionDialog(add, { showAdd = false }, onRequestNotificationPermission)
}
