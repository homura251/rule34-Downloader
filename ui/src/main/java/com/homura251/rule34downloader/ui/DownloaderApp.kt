package com.homura251.rule34downloader.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.*
import androidx.navigation.createGraph
import com.homura251.rule34downloader.domain.*

private data class Destination(val route: String, val label: String, val icon: ImageVector)
private val destinations = listOf(Destination("subscriptions", "作者", Icons.Rounded.CollectionsBookmark),
    Destination("gallery", "全部作品", Icons.Rounded.PhotoLibrary), Destination("tasks", "下载", Icons.Rounded.Download))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloaderApp(services: AppServices, onRequestNotificationPermission: () -> Unit,
    failureAuthor: String? = null, onFailureNavigationHandled: () -> Unit = {}) {
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
    val requestNotifications by rememberUpdatedState(onRequestNotificationPermission)
    if (startup.status == StartupStatus.LOADING || account.status == AccountStatus.LOADING) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val navigation = rememberNavController()
    val entry by navigation.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    LaunchedEffect(failureAuthor, route) {
        if (failureAuthor != null && route != null) {
            navigation.navigate("tasks/${Uri.encode(failureAuthor)}") { launchSingleTop = true }
            onFailureNavigationHandled()
        }
    }
    val start = "subscriptions"
    val snackbar = remember { SnackbarHostState() }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    val add: AddSubscriptionViewModel = viewModel(factory = remember(services) { viewModelFactory { initializer { AddSubscriptionViewModel(services) } } })
    val folderSettings: SettingsViewModel = viewModel(key = "folder-settings", factory = remember(services) { viewModelFactory { initializer { SettingsViewModel(services) } } })
    val preferences by services.settings.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) folderSettings.linkFolder(uri.toString()) }
    ReportMessages(folderSettings, snackbar)
    fun openSettings() { navigation.navigate("settings") { launchSingleTop = true } }
    fun addSubscription() { add.reset(); showAdd = true }
    var returnToAdd by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(account.canUseApi, route) {
        if (returnToAdd && route != null && route != "settings") {
            returnToAdd = false; showAdd = true; if (account.canUseApi) add.submit()
        }
    }
    fun select(destination: Destination) {
        navigation.navigate(destination.route) {
            popUpTo("subscriptions") { saveState = true }
            launchSingleTop = true; restoreState = true
        }
    }
    // Keep the graph outside Scaffold's subcomposition: hiding navigation chrome
    // must not replace the graph while opening the first author.
    val graph = remember(navigation, services) {
        navigation.createGraph(startDestination = start) {
            composable("subscriptions") {
                val model: SubscriptionsViewModel = viewModel(factory = remember(services) { viewModelFactory { initializer { SubscriptionsViewModel(services.library, services.tasks) } } })
                SubscriptionsScreen(model, snackbar, { id -> navigation.navigate("collection/${Uri.encode(id)}") },
                    { add.reset(); showAdd = true }, requestNotifications, onFailures = { id -> navigation.navigate("tasks/${Uri.encode(id)}") })
            }
            composable("gallery") {
                val model: GalleryViewModel = viewModel(factory = remember(services) { viewModelFactory { initializer { GalleryViewModel(services.library, createSavedStateHandle()) } } })
                GalleryScreen(model)
            }
            composable("collection/{id}") { destination ->
                val id = destination.arguments?.getString("id")
                val model: GalleryViewModel = viewModel(factory = remember(services, id) { viewModelFactory { initializer { GalleryViewModel(services.library, createSavedStateHandle(), id) } } })
                GalleryScreen(model)
            }
            composable("tasks") {
                val model: TasksViewModel = viewModel(factory = remember(services) { viewModelFactory { initializer { TasksViewModel(services.library, services.tasks) } } })
                TasksScreen(model, snackbar, requestNotifications, ::openSettings)
            }
            composable("tasks/{author}") { destination ->
                val model: TasksViewModel = viewModel(factory = remember(services) { viewModelFactory { initializer { TasksViewModel(services.library, services.tasks) } } })
                TasksScreen(model, snackbar, requestNotifications, ::openSettings, destination.arguments?.getString("author"))
            }
            composable("settings") {
                val model: SettingsViewModel = viewModel(factory = remember(services) { viewModelFactory { initializer { SettingsViewModel(services) } } })
                SettingsScreen(model, snackbar, {
                    val initial = preferences.existingDownloadsTreeUri?.let { value -> runCatching {
                        val tree = Uri.parse(value)
                        DocumentsContract.buildDocumentUri(checkNotNull(tree.authority), DocumentsContract.getTreeDocumentId(tree))
                    }.getOrNull() }
                    picker.launch(initial ?: DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/Rule34 Downloader"))
                }, requestNotifications, onAddAuthor = ::addSubscription)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val detail = route == "collection/{id}" || route == "tasks/{author}" || route == "settings"
        Scaffold(
            topBar = { TopAppBar(title = { Text(when (route) { "collection/{id}" -> entry?.arguments?.getString("id") ?: "作者作品"; "tasks/{author}" -> "失败文件"; "settings" -> "设置"; else -> destinations.firstOrNull { it.route == route }?.label ?: "资料库" }) },
                navigationIcon = { if (detail) IconButton({ navigation.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
                actions = { if (route == "subscriptions") TextButton(onClick = ::addSubscription, modifier = Modifier.testTag("add-author")) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp)); Text("添加作者")
                }; if (route != "settings") IconButton(::openSettings, Modifier.testTag("nav-settings")) { Icon(Icons.Rounded.Settings, "设置") } }) },
            snackbarHost = { SnackbarHost(snackbar) { Snackbar(it, modifier = Modifier.testTag("app-message")) } },
            bottomBar = { if (!wide && !detail) NavigationBar {
                destinations.forEach { destination -> NavigationBarItem(selected = route == destination.route, onClick = { select(destination) },
                    icon = { Icon(destination.icon, destination.label) }, label = { Text(destination.label) }, modifier = Modifier.testTag("nav-${destination.route}")) }
            } },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide && !detail) NavigationRail {
                    destinations.forEach { destination -> NavigationRailItem(selected = route == destination.route, onClick = { select(destination) },
                        icon = { Icon(destination.icon, destination.label) }, label = { Text(destination.label) }, modifier = Modifier.testTag("nav-${destination.route}")) }
                }
                NavHost(navigation, graph, modifier = Modifier.weight(1f).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface),
                    enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None })
            }
        }
    }
    if (showAdd) AddSubscriptionDialog(add, { add.reset(); showAdd = false }, onRequestNotificationPermission,
        onAdded = { id -> showAdd = false; navigation.navigate("collection/${Uri.encode(id)}") },
        onSettings = { showAdd = false; returnToAdd = true; openSettings() })
}
