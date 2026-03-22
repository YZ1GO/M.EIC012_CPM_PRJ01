package com.cpm.cleave.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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
import com.cpm.cleave.domain.repository.contracts.IAuthRepository
import com.cpm.cleave.domain.repository.contracts.IExpenseRepository
import com.cpm.cleave.domain.repository.contracts.IGroupRepository
import com.cpm.cleave.domain.usecase.GetAddExpenseMembersUseCase
import com.cpm.cleave.domain.usecase.GetGroupDetailsUseCase
import com.cpm.cleave.domain.usecase.GetGroupsUseCase
import com.cpm.cleave.domain.usecase.RequestCreateExpenseUseCase
import com.cpm.cleave.domain.usecase.RequestCreateGroupUseCase
import com.cpm.cleave.domain.usecase.RequestJoinGroupUseCase
import com.cpm.cleave.ui.features.addexpense.AddExpenseScreen
import com.cpm.cleave.ui.features.addexpense.AddExpenseViewModel
import com.cpm.cleave.ui.features.creategroup.CreateGroupScreen
import com.cpm.cleave.ui.features.creategroup.CreateGroupViewModel
import com.cpm.cleave.ui.features.groups.GroupDetailsScreen
import com.cpm.cleave.ui.features.groups.GroupDetailsViewModel
import com.cpm.cleave.ui.features.groups.GroupsScreen
import com.cpm.cleave.ui.features.groups.GroupsViewModel
import com.cpm.cleave.ui.features.joingroup.JoinGroupScreen
import com.cpm.cleave.ui.features.joingroup.JoinGroupViewModel
import com.cpm.cleave.ui.features.profile.ProfileScreen

sealed class NavScreen(val route: String, val title: String, val icon: ImageVector? = null) {
    object Groups: NavScreen("groups", "Groups", Icons.Default.Groups)
    object Profile: NavScreen("profile", "Profile", Icons.Default.Person)
    object CreateGroup : NavScreen("create_group", "Create Group")
    object JoinGroup : NavScreen("join_group", "Join Group")
    object AddExpense : NavScreen("add_expense/{groupId}", "Add Expense") {
        fun createRoute(groupId: String): String = "add_expense/$groupId"
    }
    object GroupDetails : NavScreen("group_details/{groupId}", "Group Details") {
        fun createRoute(groupId: String): String = "group_details/$groupId"
    }
}

val navItems = listOf(NavScreen.Groups, NavScreen.Profile)

@OptIn(ExperimentalMaterial3Api::class) // TopAppBar is experimental yet
@Composable
fun MainScreen(
    authRepository: IAuthRepository,
    groupRepository: IGroupRepository,
    expenseRepository: IExpenseRepository
) {
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
                        initializer { GroupsViewModel(GetGroupsUseCase(groupRepository)) }
                    }
                )

                GroupsScreen(
                    groupsViewModel = groupsViewModel,
                    onGroupClick = { groupId ->
                        navController.navigate(NavScreen.GroupDetails.createRoute(groupId))
                    }
                )
            }
            composable(NavScreen.Profile.route) {
                ProfileScreen(repository = authRepository)
            }

            composable(NavScreen.CreateGroup.route) {
                val createGroupViewModel: CreateGroupViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { CreateGroupViewModel(RequestCreateGroupUseCase(groupRepository)) }
                    }
                )

                CreateGroupScreen(createGroupViewModel, onNavigateBack = {
                    navController.popBackStack()
                })
            }

            composable(NavScreen.JoinGroup.route) {
                val joinGroupViewModel: JoinGroupViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { JoinGroupViewModel(RequestJoinGroupUseCase(groupRepository)) }
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
                val groupDetailsViewModel: GroupDetailsViewModel = viewModel(
                    key = "group_details_$groupId",
                    factory = viewModelFactory {
                        initializer {
                            GroupDetailsViewModel(
                                groupId = groupId,
                                getGroupDetailsUseCase = GetGroupDetailsUseCase(
                                    groupRepository = groupRepository,
                                    expenseRepository = expenseRepository
                                )
                            )
                        }
                    }
                )

                GroupDetailsScreen(
                    viewModel = groupDetailsViewModel,
                    onAddExpenseClick = { selectedGroupId ->
                        navController.navigate(NavScreen.AddExpense.createRoute(selectedGroupId))
                    }
                )
            }

            composable(
                route = NavScreen.AddExpense.route,
                arguments = listOf(navArgument("groupId") { type = NavType.StringType })
            ) { backStackEntry ->
                val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
                val addExpenseViewModel: AddExpenseViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            AddExpenseViewModel(
                                getAddExpenseMembersUseCase = GetAddExpenseMembersUseCase(groupRepository),
                                requestCreateExpenseUseCase = RequestCreateExpenseUseCase(expenseRepository),
                                groupId = groupId
                            )
                        }
                    }
                )

                AddExpenseScreen(
                    viewModel = addExpenseViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
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
