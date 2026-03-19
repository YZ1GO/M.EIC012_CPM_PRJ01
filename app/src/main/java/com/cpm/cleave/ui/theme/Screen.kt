package com.cpm.cleave.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cpm.cleave.data.Repository
import com.cpm.cleave.model.Group
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material3.ExposedDropdownMenuAnchorType
sealed class NavScreen(val route: String, val title: String, val icon: ImageVector? = null) {
    object Groups: NavScreen("groups", "Groups", Icons.Default.Groups)
    object Profile: NavScreen("profile", "Profile", Icons.Default.Person)
    object CreateGroup : NavScreen("create_group", "Create Group")
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
                BottomNavigationBar(navController)
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

                GroupsScreen(groupsViewModel, onCreateGroupClick = {
                    navController.navigate(NavScreen.CreateGroup.route)
                })
            }
            composable(NavScreen.Profile.route) { /**TODO**/ }

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
        }
    }
}

@Composable
fun BottomNavigationBar(navController: NavController) {
    NavigationBar {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

        navItems.forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.route,
                onClick = {
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    item.icon?.let { imageVector ->
                        Icon(imageVector, contentDescription = item.title)
                    }
                },
                label = { Text(item.title) }
            )
        }
    }
}

@Composable
fun GroupsScreen(groupsViewModel: GroupsViewModel, onCreateGroupClick: () -> Unit) {
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

            Spacer(modifier = Modifier.width(12.dp))

            IconButton(
                onClick = onCreateGroupClick,
                modifier = Modifier.size(56.dp).background(Color.LightGray.copy(alpha = 0.3f), CircleShape)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Group")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            items(uiState.groups) { group ->
                GroupListItem(group = group)
            }
        }
    }
}

@Composable
fun GroupListItem(group: Group) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
        }
    }
}

@Composable
fun ProfileScreen() {
    // TODO
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