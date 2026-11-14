package com.truong.findmycar.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.truong.findmycar.AppContainer
import com.truong.findmycar.R
import com.truong.findmycar.ui.debug.DebugScreen
import com.truong.findmycar.ui.detail.SpotDetailScreen
import com.truong.findmycar.ui.detail.SpotDetailViewModel
import com.truong.findmycar.ui.find.FindCarScreen
import com.truong.findmycar.ui.find.FindCarViewModel
import com.truong.findmycar.ui.home.HomeScreen
import com.truong.findmycar.ui.home.HomeViewModel
import com.truong.findmycar.ui.onboarding.OnboardingScreen
import com.truong.findmycar.ui.settings.SettingsScreen
import com.truong.findmycar.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val DETAIL = "detail/{spotId}"
    const val FIND = "find/{spotId}"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val DEBUG = "debug"

    fun detail(spotId: Long) = "detail/$spotId"
    fun find(spotId: Long) = "find/$spotId"
}

@Composable
fun FindMyCarNavHost(
    container: AppContainer,
    pendingDetailSpotId: Long? = null,
    onPendingDetailConsumed: () -> Unit = {}
) {
    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val completed = container.settingsStore.settings.first().onboardingCompleted
        startDestination = if (completed) Routes.HOME else Routes.ONBOARDING
    }

    val resolvedStart = startDestination
    if (resolvedStart == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )
        return
    }

    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    LaunchedEffect(pendingDetailSpotId) {
        if (pendingDetailSpotId != null && pendingDetailSpotId > 0L) {
            navController.navigate(Routes.detail(pendingDetailSpotId)) {
                launchSingleTop = true
            }
            onPendingDetailConsumed()
        }
    }

    NavHost(
        navController = navController,
        startDestination = resolvedStart,
        enterTransition = {
            fadeIn(animationSpec = tween(240)) + scaleIn(
                initialScale = 0.96f,
                animationSpec = tween(240)
            )
        },
        exitTransition = {
            fadeOut(animationSpec = tween(200))
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(240))
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(200)) + scaleOut(
                targetScale = 0.96f,
                animationSpec = tween(200)
            )
        }
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onComplete = {
                    scope.launch {
                        container.settingsStore.setOnboardingCompleted(true)
                        if (navController.previousBackStackEntry != null) {
                            navController.popBackStack()
                        } else {
                            navController.navigate(Routes.HOME) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        }
                    }
                }
            )
        }

        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(
                factory = HomeViewModel.Factory(
                    repository = container.repository,
                    parkingDetector = container.parkingDetector
                )
            )
            HomeScreen(
                viewModel = vm,
                onOpenDetail = { spotId -> navController.navigate(Routes.detail(spotId)) },
                onOpenFind = { spotId -> navController.navigate(Routes.find(spotId)) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("spotId") { type = NavType.LongType })
        ) { backStackEntry ->
            val spotId = backStackEntry.arguments?.getLong("spotId") ?: return@composable
            val vm: SpotDetailViewModel = viewModel(
                factory = SpotDetailViewModel.Factory(
                    spotId = spotId,
                    repository = container.repository
                )
            )
            SpotDetailScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.FIND,
            arguments = listOf(navArgument("spotId") { type = NavType.LongType })
        ) { backStackEntry ->
            val spotId = backStackEntry.arguments?.getLong("spotId") ?: return@composable
            val context = LocalContext.current
            val vm: FindCarViewModel = viewModel(
                factory = FindCarViewModel.Factory(
                    spotId = spotId,
                    context = context,
                    repository = container.repository,
                    settingsStore = container.settingsStore
                )
            )
            FindCarScreen(
                viewModel = vm,
                onClose = { navController.popBackStack() }
            )
        }

        composable(Routes.HISTORY) {
            PlaceholderScreen(
                title = stringResource(R.string.placeholder_history_title),
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            val context = LocalContext.current
            val vm: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(context, container.settingsStore)
            )
            SettingsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenOnboarding = { navController.navigate(Routes.ONBOARDING) },
                onOpenDebug = { navController.navigate(Routes.DEBUG) }
            )
        }

        composable(Routes.DEBUG) {
            DebugScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceholderScreen(
    title: String,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.detail_back_cd)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.placeholder_coming_soon),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
