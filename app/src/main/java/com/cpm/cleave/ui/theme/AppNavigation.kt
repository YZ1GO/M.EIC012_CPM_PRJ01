package com.cpm.cleave.ui.theme

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
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
import com.cpm.cleave.domain.repository.contracts.IScannerRepository
import com.cpm.cleave.domain.usecase.GetAddExpenseMembersUseCase
import com.cpm.cleave.domain.usecase.GetGroupDetailsUseCase
import com.cpm.cleave.domain.usecase.GetGroupsUseCase
import com.cpm.cleave.domain.usecase.RequestCreateExpenseUseCase
import com.cpm.cleave.domain.usecase.RequestCreateGroupUseCase
import com.cpm.cleave.domain.usecase.RequestDeleteGroupUseCase
import com.cpm.cleave.domain.usecase.RequestExpelGroupMemberUseCase
import com.cpm.cleave.domain.usecase.RequestJoinGroupUseCase
import com.cpm.cleave.ui.features.addexpense.AddExpenseScreen
import com.cpm.cleave.ui.features.addexpense.AddExpenseViewModel
import com.cpm.cleave.ui.features.auth.AuthScreen
import com.cpm.cleave.ui.features.auth.AuthViewModel
import com.cpm.cleave.ui.features.creategroup.CreateGroupScreen
import com.cpm.cleave.ui.features.creategroup.CreateGroupViewModel
import com.cpm.cleave.ui.features.groups.GroupDetailsScreen
import com.cpm.cleave.ui.features.groups.GroupDetailsViewModel
import com.cpm.cleave.ui.features.groups.GroupsScreen
import com.cpm.cleave.ui.features.groups.GroupsViewModel
import com.cpm.cleave.ui.features.joingroup.JoinGroupScreen
import com.cpm.cleave.ui.features.joingroup.JoinGroupViewModel
import com.cpm.cleave.ui.features.profile.ProfileScreen
import com.cpm.cleave.ui.features.profile.ProfileViewModel

sealed class NavScreen(val route: String, val title: String, val icon: ImageVector? = null) {
    object Groups: NavScreen("groups", "Groups", Icons.Default.Groups)
    object Profile: NavScreen("profile", "Profile", Icons.Default.Person)
    object CreateGroup : NavScreen("create_group", "Create Group")
    object JoinGroup : NavScreen("join_group?joinCode={joinCode}", "Join Group") {
        fun createRoute(joinCode: String? = null): String {
            if (joinCode.isNullOrBlank()) return "join_group"
            return "join_group?joinCode=${Uri.encode(joinCode)}"
        }
    }
    object AddExpense : NavScreen("add_expense/{groupId}", "Add Expense") {
        fun createRoute(groupId: String): String = "add_expense/$groupId"
    }
    object GroupDetails : NavScreen("group_details/{groupId}", "Group Details") {
        fun createRoute(groupId: String): String = "group_details/$groupId"
    }
}

val navItems = listOf(NavScreen.Groups, NavScreen.Profile)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    authRepository: IAuthRepository,
    groupRepository: IGroupRepository,
    expenseRepository: IExpenseRepository,
    scannerRepository: IScannerRepository,
    pendingDeepLinkJoinCode: String?
) {
    var isAuthCheckInProgress by remember { mutableStateOf(true) }
    var isAuthenticated by remember { mutableStateOf(false) }
    var shouldAutoCreateGuest by remember { mutableStateOf(true) }
    var openAuthInRegisterMode by remember { mutableStateOf(false) }
    var authFlowSessionKey by remember { mutableIntStateOf(0) }
    var groupsSessionKey by remember { mutableIntStateOf(0) }
    var lastConsumedDeepLinkJoinCode by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        authRepository.getCurrentUser()
            .onSuccess { user ->
                if (user != null) {
                    isAuthenticated = true
                } else {
                    if (shouldAutoCreateGuest) {
                        authRepository.getOrCreateAnonymousUser()
                            .onSuccess { isAuthenticated = true }
                            .onFailure { isAuthenticated = false }
                    } else {
                        isAuthenticated = false
                    }
                }
            }
            .onFailure { isAuthenticated = false }
        isAuthCheckInProgress = false
    }

    if (isAuthCheckInProgress) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(24.dp)) {
            Text("Loading...")
        }
        return
    }

    if (!isAuthenticated) {
        val authViewModel: AuthViewModel = viewModel(
            key = "auth_flow_$authFlowSessionKey",
            factory = viewModelFactory {
                initializer { AuthViewModel(authRepository) }
            }
        )

        AuthScreen(
            viewModel = authViewModel,
            defaultRegisterMode = openAuthInRegisterMode,
            onAuthenticated = {
                shouldAutoCreateGuest = true
                openAuthInRegisterMode = false
                isAuthenticated = true
            },
            showContinueAsGuest = true
        )
        return
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = navItems.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                BottomNavigationBar(
                    navController = navController,
                    onCreateGroupClick = { navController.navigate(NavScreen.CreateGroup.route) },
                    onJoinGroupClick = { navController.navigate(NavScreen.JoinGroup.createRoute()) }
                )
            }
        }
    ) { innerPadding ->
        LaunchedEffect(isAuthenticated, pendingDeepLinkJoinCode) {
            val joinCode = pendingDeepLinkJoinCode
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@LaunchedEffect

            if (!isAuthenticated || joinCode == lastConsumedDeepLinkJoinCode) return@LaunchedEffect

            lastConsumedDeepLinkJoinCode = joinCode
            navController.navigate(NavScreen.JoinGroup.createRoute(joinCode)) {
                launchSingleTop = true
            }
        }

        NavHost(
            navController = navController,
            startDestination = NavScreen.Groups.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { fadeIn(animationSpec = tween(150)) },
            exitTransition = {
                // Parallax effect: Push the old screen left when opening a child screen
                if (targetState.destination.route !in listOf(NavScreen.Groups.route, NavScreen.Profile.route)) {
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> -fullWidth / 3 },
                        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                    ) + fadeOut(animationSpec = tween(400))
                } else {
                    fadeOut(animationSpec = tween(150))
                }
            },
            popEnterTransition = {
                // Parallax effect: Pull the old screen back in from the left
                if (initialState.destination.route !in listOf(NavScreen.Groups.route, NavScreen.Profile.route)) {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> -fullWidth / 3 },
                        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(400))
                } else {
                    fadeIn(animationSpec = tween(150))
                }
            },
            popExitTransition = { fadeOut(animationSpec = tween(150)) }
        ) {
            composable(NavScreen.Groups.route) {
                val groupsViewModel: GroupsViewModel = viewModel(
                    key = "groups_flow_$groupsSessionKey",
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
                val profileViewModel: ProfileViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { ProfileViewModel(authRepository) }
                    }
                )

                ProfileScreen(
                    viewModel = profileViewModel,
                    onSignedOut = {
                        shouldAutoCreateGuest = false
                        openAuthInRegisterMode = false
                        authFlowSessionKey += 1
                        isAuthenticated = false
                    },
                    onRegisterRequested = {
                        shouldAutoCreateGuest = false
                        openAuthInRegisterMode = true
                        authFlowSessionKey += 1
                        isAuthenticated = false
                    }
                )
            }

            composable(
                route = NavScreen.CreateGroup.route,
                enterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> fullWidth }, // Start completely off-screen to the right
                        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(400))
                },
                popExitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> fullWidth }, // Slide back out to the right
                        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                    ) + fadeOut(animationSpec = tween(400))
                }
            ) {
                val createGroupViewModel: CreateGroupViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { CreateGroupViewModel(RequestCreateGroupUseCase(groupRepository)) }
                    }
                )

                Column(modifier = Modifier.fillMaxSize()) {
                    MinimalistTopBar(onBackClick = { navController.popBackStack() })
                    CreateGroupScreen(createGroupViewModel, onNavigateBack = {
                        groupsSessionKey += 1
                        navController.navigate(NavScreen.Groups.route) {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = false
                            }
                            launchSingleTop = true
                            restoreState = false
                        }
                    })
                }
            }

            composable(
                route = NavScreen.JoinGroup.route,
                arguments = listOf(
                    navArgument("joinCode") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
                enterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(400))
                },
                popExitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                    ) + fadeOut(animationSpec = tween(400))
                }
            ) { backStackEntry ->
                val deepLinkJoinCode = backStackEntry.arguments?.getString("joinCode")
                val joinGroupViewModel: JoinGroupViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            JoinGroupViewModel(
                                requestJoinGroupUseCase = RequestJoinGroupUseCase(groupRepository),
                                scannerRepository = scannerRepository
                            )
                        }
                    }
                )

                LaunchedEffect(deepLinkJoinCode) {
                    joinGroupViewModel.applyDeepLinkJoinCode(deepLinkJoinCode)
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    MinimalistTopBar(onBackClick = { navController.popBackStack() })
                    JoinGroupScreen(joinGroupViewModel, onNavigateBack = {
                        groupsSessionKey += 1
                        navController.navigate(NavScreen.Groups.route) {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = false
                            }
                            launchSingleTop = true
                            restoreState = false
                        }
                    })
                }
            }

            composable(
                route = NavScreen.GroupDetails.route,
                arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
                enterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(400))
                },
                popExitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                    ) + fadeOut(animationSpec = tween(400))
                }
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
                                    expenseRepository = expenseRepository,
                                    authRepository = authRepository
                                ),
                                requestDeleteGroupUseCase = RequestDeleteGroupUseCase(groupRepository),
                                requestExpelGroupMemberUseCase = RequestExpelGroupMemberUseCase(groupRepository)
                            )
                        }
                    }
                )

                Column(modifier = Modifier.fillMaxSize()) {
                    MinimalistTopBar(onBackClick = { navController.popBackStack() })
                    GroupDetailsScreen(
                        viewModel = groupDetailsViewModel,
                        onAddExpenseClick = { selectedGroupId ->
                            navController.navigate(NavScreen.AddExpense.createRoute(selectedGroupId))
                        },
                        onGroupDeleted = {
                            groupsSessionKey += 1
                            navController.navigate(NavScreen.Groups.route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = false
                                }
                                launchSingleTop = true
                                restoreState = false
                            }
                        }
                    )
                }
            }

            composable(
                route = NavScreen.AddExpense.route,
                arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
                enterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(400))
                },
                popExitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                    ) + fadeOut(animationSpec = tween(400))
                }
            ) { backStackEntry ->
                val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
                val addExpenseViewModel: AddExpenseViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            AddExpenseViewModel(
                                authRepository = authRepository,
                                scannerRepository = scannerRepository,
                                getAddExpenseMembersUseCase = GetAddExpenseMembersUseCase(groupRepository),
                                requestCreateExpenseUseCase = RequestCreateExpenseUseCase(expenseRepository),
                                groupId = groupId
                            )
                        }
                    }
                )

                Column(modifier = Modifier.fillMaxSize()) {
                    MinimalistTopBar(onBackClick = { navController.popBackStack() })
                    AddExpenseScreen(
                        viewModel = addExpenseViewModel,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
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
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                MinimalistNavItem(
                    selected = currentRoute == NavScreen.Groups.route,
                    onClick = {
                        navController.navigate(NavScreen.Groups.route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = NavScreen.Groups.icon ?: Icons.Default.Groups
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                val strokeColor = MaterialTheme.colorScheme.primary
                val density = LocalDensity.current
                val strokeWidth = with(density) { 2.dp.toPx() }
                
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .drawBehind {
                            drawRoundRect(
                                color = strokeColor,
                                style = Stroke(
                                    width = strokeWidth,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
                                ),
                                cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx())
                            )
                        }
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { showActionMenu = !showActionMenu }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Actions",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                DropdownMenu(
                    expanded = showActionMenu,
                    onDismissRequest = { showActionMenu = false },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(4.dp)
                ) {
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.GroupAdd,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        text = { 
                            Text(
                                text = "Create group",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            ) 
                        },
                        onClick = {
                            showActionMenu = false
                            onCreateGroupClick()
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .padding(horizontal = 4.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        text = { 
                            Text(
                                text = "Join group",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            ) 
                        },
                        onClick = {
                            showActionMenu = false
                            onJoinGroupClick()
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .padding(horizontal = 4.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                MinimalistNavItem(
                    selected = currentRoute == NavScreen.Profile.route,
                    onClick = {
                        navController.navigate(NavScreen.Profile.route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = NavScreen.Profile.icon ?: Icons.Default.Person
                )
            }
        }
    }
}

@Composable
fun MinimalistNavItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector
) {
    val tint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        label = "iconTint"
    )

    Box(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(32.dp)
        )
    }
}

@Composable
fun MinimalistTopBar(onBackClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBackClick
                )
                .padding(12.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}