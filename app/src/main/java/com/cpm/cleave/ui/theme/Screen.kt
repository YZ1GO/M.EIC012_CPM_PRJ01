package com.cpm.cleave.ui.theme

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cpm.cleave.data.Repository
import com.cpm.cleave.model.Group
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import com.cpm.cleave.model.User
import kotlinx.coroutines.launch
sealed class NavScreen(val route: String, val title: String, val icon: ImageVector? = null) {
    object Groups: NavScreen("groups", "Groups", Icons.Default.Groups)
    object Profile: NavScreen("profile", "Profile", Icons.Default.Person)
    object CreateGroup : NavScreen("create_group", "Create Group")
    object JoinGroup : NavScreen("join_group", "Join Group")
    object GroupDetails : NavScreen("group_details/{groupId}", "Group Details") {
        fun createRoute(groupId: String): String = "group_details/$groupId"
    }
}

val navItems = listOf(NavScreen.Groups, NavScreen.Profile)

@OptIn(ExperimentalMaterial3Api::class) // TopAppBar is experimental yet
@Composable
fun MainScreen(repository: Repository) {
    val navController = rememberNavController()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = navItems.any { it.route == currentRoute }
    val canGoBack = !showBottomBar && currentRoute != null

    Scaffold(
        topBar = {
            if (canGoBack) {
                TopAppBar(
                    title = { Text("") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                BottomNavigationBar(
                    navController = navController,
                    onCreateGroupClick = { navController.navigate(NavScreen.CreateGroup.route) },
                    onJoinGroupClick = { navController.navigate(NavScreen.JoinGroup.route) }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavScreen.Groups.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(NavScreen.Groups.route) {
                val groupsViewModel: GroupsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { GroupsViewModel(repository) }
                    }
                )

                GroupsScreen(
                    groupsViewModel = groupsViewModel,
                    onGroupClick = { groupId ->
                        navController.navigate(NavScreen.GroupDetails.createRoute(groupId))
                    }
                )
            }
            composable(NavScreen.Profile.route) { ProfileScreen(repository) }

            composable(NavScreen.CreateGroup.route) {
                val createGroupViewModel: CreateGroupViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { CreateGroupViewModel(repository) }
                    }
                )

                CreateGroupScreen(createGroupViewModel, onNavigateBack = {
                    navController.popBackStack()
                })
            }

            composable(NavScreen.JoinGroup.route) {
                val joinGroupViewModel: JoinGroupViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { JoinGroupViewModel(repository) }
                    }
                )

                JoinGroupScreen(joinGroupViewModel, onNavigateBack = {
                    navController.popBackStack()
                })
            }

            composable(
                route = NavScreen.GroupDetails.route,
                arguments = listOf(navArgument("groupId") { type = NavType.StringType })
            ) { backStackEntry ->
                val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
                GroupDetailsScreen(repository = repository, groupId = groupId)
            }
        }
    }
}

@Composable
fun BottomNavigationBar(
    navController: NavController,
    onCreateGroupClick: () -> Unit,
    onJoinGroupClick: () -> Unit
) {
    var showActionMenu by remember { mutableStateOf(false) }

    NavigationBar {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

        NavigationBarItem(
            selected = currentRoute == NavScreen.Groups.route,
            onClick = {
                navController.navigate(NavScreen.Groups.route) {
                    popUpTo(navController.graph.startDestinationId) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            icon = { Icon(Icons.Default.Groups, contentDescription = NavScreen.Groups.title) },
            label = { Text(NavScreen.Groups.title) }
        )

        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = { showActionMenu = !showActionMenu },
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.Blue, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Group actions",
                    tint = Color.White
                )
            }

            DropdownMenu(
                expanded = showActionMenu,
                onDismissRequest = { showActionMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Create group") },
                    onClick = {
                        showActionMenu = false
                        onCreateGroupClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Join group") },
                    onClick = {
                        showActionMenu = false
                        onJoinGroupClick()
                    }
                )
            }
        }

        NavigationBarItem(
            selected = currentRoute == NavScreen.Profile.route,
            onClick = {
                navController.navigate(NavScreen.Profile.route) {
                    popUpTo(navController.graph.startDestinationId) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            icon = { Icon(Icons.Default.Person, contentDescription = NavScreen.Profile.title) },
            label = { Text(NavScreen.Profile.title) }
        )
    }
}

@Composable
fun GroupsScreen(groupsViewModel: GroupsViewModel, onGroupClick: (String) -> Unit) {
    LaunchedEffect(Unit) { groupsViewModel.loadGroups() }

    val uiState by groupsViewModel.uiState.collectAsState()

    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { groupsViewModel.onSearchQueryChanged(it) },
                placeholder = { Text("Search") },
                trailingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f).height(56.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focusManager.clearFocus()
                    }
                )
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            items(uiState.groups) { group ->
                GroupListItem(group = group, onClick = { onGroupClick(group.id) })
            }
        }
    }
}

@Composable
fun GroupListItem(group: Group, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // TODO CHANGE BOX TO IMAGE
        Box(
            modifier = Modifier.size(64.dp).border(1.dp, Color.Gray, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("?", fontSize = 32.sp, color = Color.Gray)
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Text(
                text = group.name,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Text(
                text = "Currency: ${group.currency}",
                color = Color.Gray,
                fontSize = 14.sp
            )
            Text(
                text = "Code: ${group.joinCode}",
                color = Color.Gray,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun GroupDetailsScreen(repository: Repository, groupId: String) {
    var group by remember { mutableStateOf<Group?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(groupId) {
        repository.getGroupById(groupId)
            .onSuccess { group = it }
            .onFailure { loadError = it.message ?: "Could not load group" }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Group Details", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)

        loadError?.let { Text(it, color = Color.Red) }

        val currentGroup = group
        if (currentGroup == null) {
            Text("Loading group...")
            return@Column
        }

        Text("Name: ${currentGroup.name}")
        Text("Currency: ${currentGroup.currency}")
        Text("Code: ${currentGroup.joinCode}")
        Spacer(modifier = Modifier.height(8.dp))
        Text("Members (${currentGroup.members.size})", fontWeight = FontWeight.Medium)

        if (currentGroup.members.isEmpty()) {
            Text("No members in this group yet.")
        } else {
            currentGroup.members.forEach { memberId ->
                Text("- $memberId", color = Color.Gray)
            }
        }
    }
}

@Composable
fun ProfileScreen(repository: Repository) {
    var currentUser by remember { mutableStateOf<User?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val limits = remember { repository.getAnonymousLimits() }
    val coroutineScope = rememberCoroutineScope()
    
    // TODO(remove-before-release): remove debug user-switch tools from profile UI.
    val isDebuggable = (LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    LaunchedEffect(Unit) {
        repository.getCurrentUser()
            .onSuccess { currentUser = it }
            .onFailure { loadError = it.message ?: "Could not load profile" }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Profile", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)

        loadError?.let {
            Text(text = it, color = Color.Red)
        }

        if (currentUser == null) {
            Text("Loading user...")
            return@Column
        }

        val user = currentUser!!
        Text("Name: ${user.name}")
        Text("Mode: ${if (user.isAnonymous) "Anonymous" else "Verified"}")
        Text("Groups joined: ${user.groups.size}")

        if (user.isAnonymous) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Anonymous limits", fontWeight = FontWeight.Medium)
            Text("- Max groups: ${limits.maxGroups}")
            Text("- Max expenses per group: ${limits.maxExpensesPerGroup}")
            Text("- Max total debt: ${limits.maxTotalDebt}")

            // TODO(remove-before-release): remove debug user-switch tools from profile UI.
            if (isDebuggable) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Debug tools", fontWeight = FontWeight.Medium)
                Text("Current user id: ${user.id}", color = Color.Gray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            repository.switchDebugAnonymousUser()
                                .onSuccess {
                                    currentUser = it
                                    loadError = null
                                }
                                .onFailure {
                                    loadError = it.message ?: "Could not switch debug user"
                                }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Switch Between User A/B", color = Color.White)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // TODO(remove-before-release): remove debug room database reset button.
                Button(
                    onClick = {
                        coroutineScope.launch {
                            repository.clearDebugDatabase()
                                .onSuccess {
                                    repository.switchDebugAnonymousUser()
                                        .onSuccess {
                                            currentUser = it
                                            loadError = null
                                        }
                                        .onFailure {
                                            loadError = it.message ?: "Cleared DB, but could not initialize debug user"
                                        }
                                }
                                .onFailure {
                                    loadError = it.message ?: "Could not clear local database"
                                }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB00020)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Clear All Room Data (Debug)", color = Color.White)
                }
            }
        }
    }
}

// ExposedDropdownMenuBox is experimental yet
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(viewModel: CreateGroupViewModel, onNavigateBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()

    var expanded by remember { mutableStateOf(false) }
    val currencies = listOf("Euro", "USD", "GBP")

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Create Group",
            color = Color.Blue,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Name Input Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = "Group Picture",
                modifier = Modifier.size(40.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text("Group name", fontSize = 14.sp)
                OutlinedTextField(
                    value = uiState.Name,
                    onValueChange = { viewModel.onNameChanged(it) },
                    placeholder = { Text("Value") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Currency Dropdown
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Currency", fontSize = 14.sp)
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = uiState.Currency,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)},
                    modifier = Modifier.fillMaxWidth().menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    shape = RoundedCornerShape(8.dp)
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    currencies.forEach { selectionOption ->
                        DropdownMenuItem(
                            text = { Text(selectionOption)},
                            onClick = {
                                viewModel.onCurrencyChanged(selectionOption)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        uiState.errorMessage?.let { message ->
            Text(
                text = message,
                color = Color.Red,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Button(
            onClick = {
                viewModel.createGroup(onSuccess = onNavigateBack)
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Blue),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.height(48.dp)
        ) {
            Text("Create Group", fontSize = 16.sp, color = Color.White)
        }
    }
}

@Composable
fun JoinGroupScreen(viewModel: JoinGroupViewModel, onNavigateBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Join Group",
            color = Color.Blue,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(48.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Join Code", fontSize = 14.sp)
            OutlinedTextField(
                value = uiState.joinCode,
                onValueChange = { viewModel.onJoinCodeChanged(it) },
                placeholder = { Text("Enter join code") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        uiState.errorMessage?.let { message ->
            Text(
                text = message,
                color = Color.Red,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Button(
            onClick = {
                viewModel.joinGroup(onSuccess = onNavigateBack)
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Blue),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.height(48.dp).fillMaxWidth(),
            enabled = !uiState.isLoading
        ) {
            Text(
                if (uiState.isLoading) "Joining..." else "Join Group",
                fontSize = 16.sp,
                color = Color.White
            )
        }
    }
}