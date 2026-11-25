package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.auth.attemptAutoSignIn
import com.example.data.CallRepository
import com.example.ui.AuthScreen
import com.example.ui.CallScreen
import com.example.ui.CallViewModel
import com.example.ui.HomeScreen
import com.example.ui.HomeViewModel
import com.example.ui.theme.PulseCallTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

private const val ROUTE_HOME = "home"
private const val ROUTE_CALL_PATTERN = "call/{roomCode}/{isHost}/{takeover}"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PulseCallTheme {
                AppContent()
            }
        }
    }
}

@Composable
fun AppContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }
    var isCheckingSession by remember { mutableStateOf(Firebase.auth.currentUser == null) }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
            if (auth.currentUser != null) {
                com.example.auth.AuthPersistence.markSignedIn(context, auth.currentUser)
                isCheckingSession = false
            }
        }
        Firebase.auth.addAuthStateListener(listener)
        onDispose { Firebase.auth.removeAuthStateListener(listener) }
    }

    LaunchedEffect(Unit) {
        if (currentUser == null) {
            attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = {
                    currentUser = Firebase.auth.currentUser
                    isCheckingSession = false
                },
                onUnauthenticated = {
                    isCheckingSession = false
                },
                scope = scope
            )
        } else {
            com.example.auth.AuthPersistence.markSignedIn(context, currentUser)
            isCheckingSession = false
        }
    }

    val user = currentUser
    when {
        user != null -> {
            val uid = user.uid
            val displayName = user.displayName?.takeIf { it.isNotBlank() } ?: "PulseCall User"
            val photoUrl = user.photoUrl?.toString()

            key(uid) {
                AuthenticatedNavGraph(
                    currentUserId = uid,
                    userDisplayName = displayName,
                    userPhotoUrl = photoUrl
                )
            }
        }
        isCheckingSession -> {
            SessionRestoreScreen()
        }
        else -> {
            AuthScreen(
                onAuthSuccess = {
                    currentUser = Firebase.auth.currentUser
                    isCheckingSession = false
                }
            )
        }
    }
}

@Composable
private fun SessionRestoreScreen() {
    androidx.compose.foundation.layout.Box(
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
        ) {
            androidx.compose.material3.CircularProgressIndicator()
            androidx.compose.material3.Text(
                text = androidx.compose.ui.res.stringResource(R.string.restoring_session),
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AuthenticatedNavGraph(
    currentUserId: String,
    userDisplayName: String,
    userPhotoUrl: String?
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    NavHost(
        navController = navController,
        startDestination = ROUTE_HOME
    ) {
        composable(ROUTE_HOME) { backStackEntry ->
            val homeViewModel: HomeViewModel = viewModel(
                key = "home_$currentUserId",
                factory = viewModelFactory {
                    initializer {
                        val app = checkNotNull(this[APPLICATION_KEY])
                        val databaseId = app.getString(R.string.firestore_database_id)
                        val db = FirebaseFirestore.getInstance(databaseId)
                        HomeViewModel(
                            repository = CallRepository(db),
                            currentUserId = currentUserId,
                            userDisplayName = userDisplayName,
                            currentDeviceId = com.example.data.DeviceSessionId.getOrCreate(app)
                        )
                    }
                }
            )

            val handoffNotice = backStackEntry.savedStateHandle.get<String>("handoff_notice")
            LaunchedEffect(handoffNotice) {
                if (handoffNotice != null) {
                    homeViewModel.showMessage(handoffNotice)
                    backStackEntry.savedStateHandle.remove<String>("handoff_notice")
                }
            }

            HomeScreen(
                viewModel = homeViewModel,
                userDisplayName = userDisplayName,
                userPhotoUrl = userPhotoUrl,
                onStartCall = { roomCode ->
                    navController.navigate("call/$roomCode/true/false") {
                        launchSingleTop = true
                    }
                },
                onJoinCall = { roomCode, isHost, isTakeover ->
                    navController.navigate("call/$roomCode/$isHost/$isTakeover") {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(
            route = ROUTE_CALL_PATTERN,
            arguments = listOf(
                navArgument("roomCode") { type = NavType.StringType },
                navArgument("isHost") { type = NavType.BoolType },
                navArgument("takeover") { type = NavType.BoolType }
            )
        ) { backStackEntry ->
            val roomCode = backStackEntry.arguments?.getString("roomCode").orEmpty()
            val isHost = backStackEntry.arguments?.getBoolean("isHost") ?: true
            val isTakeover = backStackEntry.arguments?.getBoolean("takeover") ?: false

            val callViewModel: CallViewModel = viewModel(
                key = "call_${currentUserId}_${roomCode}_${isHost}_$isTakeover",
                factory = viewModelFactory {
                    initializer {
                        val app = checkNotNull(this[APPLICATION_KEY])
                        val databaseId = app.getString(R.string.firestore_database_id)
                        val db = FirebaseFirestore.getInstance(databaseId)
                        CallViewModel(
                            repository = CallRepository(db),
                            appContext = app.applicationContext,
                            currentUserId = currentUserId,
                            userDisplayName = userDisplayName,
                            roomCode = roomCode,
                            isHost = isHost,
                            isTakeover = isTakeover
                        )
                    }
                }
            )

            CallScreen(
                viewModel = callViewModel,
                onExitCall = {
                    navController.popBackStack()
                },
                onSwitchedDevice = {
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        "handoff_notice",
                        context.getString(R.string.switched_device_notice)
                    )
                    navController.popBackStack()
                }
            )
        }
    }
}
