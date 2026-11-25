package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
        }
        Firebase.auth.addAuthStateListener(listener)
        onDispose { Firebase.auth.removeAuthStateListener(listener) }
    }

    LaunchedEffect(Unit) {
        if (currentUser == null) {
            attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = { currentUser = Firebase.auth.currentUser },
                onUnauthenticated = {},
                scope = scope
            )
        }
    }

    val user = currentUser
    if (user == null) {
        AuthScreen(
            onAuthSuccess = { currentUser = Firebase.auth.currentUser }
        )
    } else {
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
