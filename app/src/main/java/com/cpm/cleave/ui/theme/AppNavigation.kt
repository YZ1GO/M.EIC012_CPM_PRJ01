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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
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
import com.cpm.cleave.domain.repository.contracts.IAuthRepository
import com.cpm.cleave.domain.repository.contracts.IExpenseRepository
import com.cpm.cleave.domain.repository.contracts.IGroupRepository
import com.cpm.cleave.domain.repository.contracts.IScannerRepository
import com.cpm.cleave.domain.usecase.*
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
    object Groups : NavScreen("groups", "Groups", Icons.Default.Groups)
    object Profile : NavScreen("profile", "Profile", Icons.Default.Person)
    object Auth : NavScreen("auth?register={register}", "Auth") {
        fun createRoute(register: Boolean): String = "auth?register=$register"
    }
    object CreateGroup : NavScreen("create_group", "Create Group")
    object JoinGroup : NavScreen("join_group?joinCode={joinCode}", "Join Group") {
        fun createRoute(joinCode: String? = null): String =
            if (joinCode.isNullOrBlank()) "join_group" else "join_group?joinCode=${Uri.encode(joinCode)}"
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
    var groupsRefreshNonce by remember { mutableIntStateOf(0) }
    var lastConsumedDeepLinkJoinCode by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        authRepository.getCurrentUser()
            .onSuccess { user -> isAuthenticated = user != null }
            .onFailure { isAuthenticated = false }
        
        if (!isAuthenticated && shouldAutoCreateGuest) {
            authRepository.getOrCreateAnonymousUser().onSuccess { isAuthenticated = true }
        }
        isAuthCheckInProgress = false
    }

    if (isAuthCheckInProgress) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
        return
    }

    if (!isAuthenticated) {
        val authViewModel: AuthViewModel = viewModel(
            key = "auth_flow_$authFlowSessionKey",
            factory = viewModelFactory {
                initializer { AuthViewModel(authRepository, initialRegisterMode = openAuthInRegisterMode) }
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
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        ),
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
            val joinCode = pendingDeepLinkJoinCode?.trim()?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
            if (!isAuthenticated || joinCode == lastConsumedDeepLinkJoinCode) return@LaunchedEffect
            lastConsumedDeepLinkJoinCode = joinCode
            navController.navigate(NavScreen.JoinGroup.createRoute(joinCode)) { launchSingleTop = true }
        }

        NavHost(
            navController = navController,
            startDestination = NavScreen.Groups.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            enterTransition = { fadeIn(tween(150)) },
            exitTransition = {
                if (targetState.destination.route !in listOf(NavScreen.Groups.route, NavScreen.Profile.route)) {
                    slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween(400, easing = FastOutSlowInEasing)) + fadeOut(tween(400))
                } else fadeOut(tween(150))
            },
            popEnterTransition = {
                if (initialState.destination.route !in listOf(NavScreen.Groups.route, NavScreen.Profile.route)) {
                    slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = tween(400, easing = FastOutSlowInEasing)) + fadeIn(tween(400))
                } else fadeIn(tween(150))
            },
            popExitTransition = { fadeOut(tween(150)) }
        ) {
            composable(NavScreen.Groups.route) {
                val groupsViewModel: GroupsViewModel = viewModel(
                    key = "groups_flow_$groupsSessionKey",
                    factory = viewModelFactory { initializer { GroupsViewModel(GetGroupsUseCase(groupRepository)) } }
                )
                GroupsScreen(
                    groupsViewModel = groupsViewModel,
                    refreshNonce = groupsRefreshNonce,
                    onGroupClick = { navController.navigate(NavScreen.GroupDetails.createRoute(it)) }
                )
            }
            
            composable(NavScreen.Profile.route) {
                val profileViewModel: ProfileViewModel = viewModel(factory = viewModelFactory { initializer { ProfileViewModel(authRepository) } })
                ProfileScreen(
                    viewModel = profileViewModel,
                    onSignedOut = { authFlowSessionKey++; isAuthenticated = false },
                    onSignInRequested = { navController.navigate(NavScreen.Auth.createRoute(register = false)) },
                    onRegisterRequested = { navController.navigate(NavScreen.Auth.createRoute(register = true)) }
                )
            }

            composable(
                route = NavScreen.Auth.route,
                arguments = listOf(navArgument("register") {
                    type = NavType.BoolType
                    defaultValue = false
                }),
                enterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)
                    ) + fadeIn(tween(360))
                },
                exitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { it / 3 },
                        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                    ) + fadeOut(tween(220))
                },
                popEnterTransition = {
                    slideInHorizontally(
                        initialOffsetX = { -it / 3 },
                        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                    ) + fadeIn(tween(220))
                },
                popExitTransition = {
                    slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing)
                    ) + fadeOut(tween(320))
                }
            ) { backStackEntry ->
                val registerMode = backStackEntry.arguments?.getBoolean("register") ?: false
                val authViewModel: AuthViewModel = viewModel(
                    key = "auth_flow_$authFlowSessionKey",
                    factory = viewModelFactory {
                        initializer { AuthViewModel(authRepository, initialRegisterMode = registerMode) }
                    }
                )
                Column(Modifier.fillMaxSize()) {
                    MinimalistTopBar { navController.popBackStack() }
                    AuthScreen(
                        viewModel = authViewModel,
                        defaultRegisterMode = registerMode,
                        onAuthenticated = {
                            openAuthInRegisterMode = false
                            navController.popBackStack()
                        },
                        showContinueAsGuest = false
                    )
                }
            }

            composable(NavScreen.CreateGroup.route) {
                val vm: CreateGroupViewModel = viewModel(factory = viewModelFactory { initializer { CreateGroupViewModel(RequestCreateGroupUseCase(groupRepository)) } })
                Column(Modifier.fillMaxSize()) {
                    MinimalistTopBar { navController.popBackStack() }
                    CreateGroupScreen(vm) {
                        groupsRefreshNonce++
                        navController.popBackStack()
                    }
                }
            }

            composable(
                route = NavScreen.JoinGroup.route, 
                arguments = listOf(navArgument("joinCode") { type = NavType.StringType; nullable = true })
            ) { backStackEntry ->
                val vm: JoinGroupViewModel = viewModel(factory = viewModelFactory { initializer { JoinGroupViewModel(RequestJoinGroupUseCase(groupRepository), scannerRepository) } })
                LaunchedEffect(Unit) { vm.applyDeepLinkJoinCode(backStackEntry.arguments?.getString("joinCode")) }
                Column(Modifier.fillMaxSize()) {
                    MinimalistTopBar { navController.popBackStack() }
                    JoinGroupScreen(vm) {
                        groupsRefreshNonce++
                        navController.popBackStack()
                    }
                }
            }

            composable(
                route = NavScreen.GroupDetails.route, 
                arguments = listOf(navArgument("groupId") { type = NavType.StringType })
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("groupId") ?: return@composable
                val vm: GroupDetailsViewModel = viewModel(
                    key = "details_$id", 
                    factory = viewModelFactory { 
                        initializer { 
                            GroupDetailsViewModel(
                                groupId = id, 
                                getGroupDetailsUseCase = GetGroupDetailsUseCase(groupRepository, expenseRepository, authRepository), 
                                requestSettleDebtUseCase = RequestSettleDebtUseCase(RequestCreateExpenseUseCase(expenseRepository)),
                                requestDeleteExpenseUseCase = RequestDeleteExpenseUseCase(expenseRepository),
                                requestDeleteGroupUseCase = RequestDeleteGroupUseCase(groupRepository),
                                requestExpelGroupMemberUseCase = RequestExpelGroupMemberUseCase(groupRepository)
                            ) 
                        } 
                    }
                )
                Column(Modifier.fillMaxSize()) {
                    MinimalistTopBar { navController.popBackStack() }
                    GroupDetailsScreen(
                        viewModel = vm, 
                        onAddExpenseClick = { navController.navigate(NavScreen.AddExpense.createRoute(it)) }, 
                        onGroupDeleted = { groupsSessionKey++; navController.navigate(NavScreen.Groups.route) { popUpTo(navController.graph.startDestinationId); launchSingleTop = true } }
                    )
                }
            }

            composable(
                route = NavScreen.AddExpense.route, 
                arguments = listOf(navArgument("groupId") { type = NavType.StringType })
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("groupId") ?: return@composable
                val vm: AddExpenseViewModel = viewModel(factory = viewModelFactory { initializer { AddExpenseViewModel(authRepository, scannerRepository, GetAddExpenseMembersUseCase(groupRepository), RequestCreateExpenseUseCase(expenseRepository), id) } })
                Column(Modifier.fillMaxSize()) {
                    MinimalistTopBar { navController.popBackStack() }
                    AddExpenseScreen(vm) { navController.popBackStack() }
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
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val barHeight = if (screenHeightDp <= 700) 72.dp else 80.dp // Slightly taller to house labels

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 16.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavItemBox(
                    modifier = Modifier.weight(1f), 
                    selected = currentRoute == NavScreen.Groups.route, 
                    icon = Icons.Default.Groups,
                    label = NavScreen.Groups.title
                ) {
                    navController.navigate(NavScreen.Groups.route) { 
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true 
                    }
                }

                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    val color = MaterialTheme.colorScheme.primary
                    Box {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .drawBehind {
                                    drawRoundRect(
                                        color = color,
                                        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)),
                                        cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx())
                                    )
                                }
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { showActionMenu = !showActionMenu },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Add, "Add", tint = color, modifier = Modifier.size(32.dp))
                        }

                        DropdownMenu(
                            expanded = showActionMenu,
                            onDismissRequest = { showActionMenu = false },
                            offset = DpOffset(x = (-60).dp, y = 0.dp),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.background(MaterialTheme.colorScheme.surface).padding(4.dp)
                        ) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.GroupAdd, null, tint = color, modifier = Modifier.size(20.dp)) },
                                text = { Text("Create group", style = MaterialTheme.typography.titleSmall) },
                                onClick = { showActionMenu = false; onCreateGroupClick() },
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).padding(horizontal = 4.dp)
                            )
                            Spacer(Modifier.height(4.dp))
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.Link, null, tint = color, modifier = Modifier.size(20.dp)) },
                                text = { Text("Join group", style = MaterialTheme.typography.titleSmall) },
                                onClick = { showActionMenu = false; onJoinGroupClick() },
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).padding(horizontal = 4.dp)
                            )
                        }
                    }
                }

                NavItemBox(
                    modifier = Modifier.weight(1f), 
                    selected = currentRoute == NavScreen.Profile.route, 
                    icon = Icons.Default.Person,
                    label = NavScreen.Profile.title
                ) {
                    navController.navigate(NavScreen.Profile.route) { 
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true 
                    }
                }
            }

            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
fun NavItemBox(
    modifier: Modifier, 
    selected: Boolean, 
    icon: ImageVector, 
    label: String,
    onClick: () -> Unit
) {
    val tint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), 
        label = "iconTint"
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, 
                indication = null, 
                onClick = onClick
            ), 
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon, 
            contentDescription = null, 
            tint = tint, 
            modifier = Modifier.size(26.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = tint,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp
            )
        )
    }
}

@Composable
fun MinimalistTopBar(onBackClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(CircleShape).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBackClick).padding(12.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        }
    }
}