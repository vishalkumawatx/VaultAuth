package com.example.authanticator.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.authanticator.BuildConfig
import com.example.authanticator.R
import com.example.authanticator.backup.BackupScreen
import com.example.authanticator.data.AccountMetadata
import com.example.authanticator.settings.Preferences
import com.example.authanticator.security.UnlockIssue
import com.example.authanticator.security.UnlockProblem
import com.example.authanticator.scanner.ScannerScreen
import com.example.authanticator.totp.Algorithm
import com.example.authanticator.totp.Otp
import com.example.authanticator.totp.OtpType
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun VaultApp(model: VaultViewModel, authenticate: () -> Unit, copy: (String) -> Unit, securitySettings: () -> Unit,
    chooseExport: () -> Unit, chooseImport: () -> Unit, deviceAuthenticate: () -> Unit = authenticate) {
    val preferences by model.preferences.collectAsStateWithLifecycle()
    val unlocked by model.unlocked.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val authenticating by model.authenticating.collectAsStateWithLifecycle()
    val unlockProblem by model.unlockProblem.collectAsStateWithLifecycle()
    val pending by model.pending.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    VaultTheme(light = preferences.lightTheme, reducedMotion = preferences.reduceMotion) {
        LaunchedEffect(message) {
            message?.let { snackbars.showSnackbar(it); model.dismissMessage() }
        }
        Scaffold(snackbarHost = { SnackbarHost(snackbars) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    !preferences.onboarded -> Onboarding { model.preferences(preferences.copy(onboarded = true)) }
                    !unlocked -> LockedScreen(authenticate, securitySettings, unlockProblem, busy || authenticating, deviceAuthenticate)
                    else -> UnlockedApp(model, preferences, copy, securitySettings, authenticate, chooseExport, chooseImport)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
        if (unlocked) unlockProblem?.let { problem ->
            AlertDialog(onDismissRequest = model::dismissUnlockProblem,
                title = { Text(problem.title) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(problem.message)
                    Text("Reference: ${problem.issue.name}", style = MaterialTheme.typography.labelSmall)
                } },
                confirmButton = { TextButton(onClick = if (problem.canRetry) authenticate else model::dismissUnlockProblem) {
                    Text(if (problem.canRetry) "Try again" else "Close")
                } },
                dismissButton = { if (problem.canRetry) TextButton(onClick = model::dismissUnlockProblem) { Text("Cancel") } })
        }
        pending?.let { account ->
            AlertDialog(onDismissRequest = { if (!busy) model.cancelEnrollment() },
                icon = { Icon(Icons.Outlined.VerifiedUser, null) },
                title = { Text("Add this account?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(account.issuer.ifBlank { "Personal account" }, fontWeight = FontWeight.Bold)
                        Text(account.account)
                        Text("${account.type.name} / ${account.algorithm} / ${account.digits} digits")
                        Text(if (account.type == OtpType.HOTP) "Initial counter: ${account.counter}" else "Changes every ${account.period} seconds")
                        Text("Keep the service's recovery codes in a separate safe place.")
                    }
                },
                confirmButton = { TextButton(enabled = !busy, onClick = { model.saveEnrollment {} }) { Text("Add account") } },
                dismissButton = { TextButton(enabled = !busy, onClick = model::cancelEnrollment) { Text("Cancel") } })
        }
    }
}

@Composable
private fun Onboarding(continueAction: () -> Unit) {
    Page {
        Brand()
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Set up your authenticator", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Use your phone's fingerprint or screen lock to access verification codes.")
        Text("Keep your services' recovery codes. If you lose this phone, you will need those codes or an encrypted backup.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = continueAction, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Continue") }
    }
}

@Composable
private fun LockedScreen(unlock: () -> Unit, securitySettings: () -> Unit, problem: UnlockProblem?, busy: Boolean, deviceUnlock: () -> Unit) {
    Page {
        Brand()
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Vault locked", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        if (problem == null) {
            Text("Unlock to view your verification codes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(problem.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text(problem.message, style = MaterialTheme.typography.bodyMedium)
            if (problem.issue != UnlockIssue.SCREEN_LOCK_REQUIRED) Text("Nothing has been deleted. Do not clear app data.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text("Reference: ${problem.issue.name}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (problem?.canRetry != false) Button(onClick = unlock, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(if (problem == null) Icons.Outlined.Fingerprint else Icons.Outlined.Refresh, null)
            Spacer(Modifier.width(10.dp))
            Text(if (busy) "Unlocking..." else if (problem == null) "Unlock vault" else "Try again")
        }
        if (problem?.canRetry != false) OutlinedButton(onClick = deviceUnlock, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Password, null)
            Spacer(Modifier.width(10.dp))
            Text("Use PIN, pattern or password")
        }
        TextButton(onClick = securitySettings, enabled = !busy) { Text("Device security settings") }
    }
}

@Composable
private fun UnlockedApp(model: VaultViewModel, preferences: Preferences, copy: (String) -> Unit, securitySettings: () -> Unit,
    authenticate: () -> Unit, chooseExport: () -> Unit, chooseImport: () -> Unit) {
    val nav = rememberNavController()
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val reducedMotion = LocalReducedMotion.current
    NavHost(navController = nav, startDestination = "home",
        enterTransition = { if (reducedMotion) EnterTransition.None else fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 16 } },
        exitTransition = { if (reducedMotion) ExitTransition.None else fadeOut(tween(120)) },
        popEnterTransition = { if (reducedMotion) EnterTransition.None else fadeIn(tween(160)) },
        popExitTransition = { if (reducedMotion) ExitTransition.None else fadeOut(tween(120)) + slideOutHorizontally(tween(180)) { it / 16 } }) {
        composable("home") {
            HomeScreen(model, accounts, preferences, copy, { nav.navigate("add") },
                { nav.navigate("settings") }, { nav.navigate("details/$it") })
        }
        composable("add") {
            Page {
                PageHeader("Add account") { nav.popBackStack() }
                ActionRow("Scan QR code", "Read a setup code with your camera", Icons.Outlined.QrCodeScanner) { nav.navigate("scan") }
                ActionRow("Enter setup details", "Use a Base32 setup key", Icons.Outlined.Keyboard) { nav.navigate("manual") }
                ActionRow("Provisioning URI", "Paste a TOTP or HOTP setup link", Icons.Outlined.Link) { nav.navigate("uri") }
                ActionRow("Import encrypted backup", "Vault Auth encrypted export", Icons.Outlined.FileDownload) { nav.navigate("backup") }
                HorizontalDivider()
                Text("Supported authentication", style = MaterialTheme.typography.titleMedium)
                Text("Time-based TOTP: SHA1, SHA256, SHA512. Counter-based HOTP: SHA1. Both support 6-8 digits.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("SMS codes, passkeys, security keys, and push approvals stay with their service or device. Proprietary app-transfer codes are not standard setup QR codes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        composable("manual") { ManualScreen(model) { nav.popBackStack() } }
        composable("scan") {
            ScannerScreen({ model.prepareUri(it); nav.popBackStack() }, { nav.popBackStack() })
        }
        composable("uri") {
            var uri by remember { mutableStateOf("") }
            Page {
                PageHeader("Provisioning URI") { nav.popBackStack() }
                Text("Use the otpauth setup link supplied by your service. It contains your secret and is not saved as plaintext.")
                OutlinedTextField(uri, { uri = it.take(4096) }, Modifier.fillMaxWidth(), label = { Text("otpauth:// setup link") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                Button(onClick = { model.prepareUri(uri); uri = "" }, enabled = uri.isNotBlank()) { Text("Review account") }
            }
        }
        composable("details/{id}") { entry ->
            val account = accounts.firstOrNull { it.id == entry.arguments?.getString("id") }
            if (account != null) DetailsScreen(account, model) { nav.popBackStack() }
            else LaunchedEffect(Unit) { nav.popBackStack() }
        }
        composable("settings") {
            SettingsScreen(preferences, model::preferences, model::lock, securitySettings,
                { nav.navigate("about") }, { nav.navigate("backup") }, { nav.popBackStack() })
        }
        composable("backup") { BackupScreen(model, authenticate, chooseExport, chooseImport) { nav.popBackStack() } }
        composable("about") { AboutScreen({ nav.navigate("licenses") }, { nav.popBackStack() }) }
        composable("licenses") { FontLicensesScreen { nav.popBackStack() } }
    }
}

@Composable
private fun HomeScreen(model: VaultViewModel, accounts: List<AccountMetadata>, preferences: Preferences, copy: (String) -> Unit,
    add: () -> Unit, settings: () -> Unit, details: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var favorites by rememberSaveable { mutableStateOf(false) }
    var folder by rememberSaveable { mutableStateOf("") }
    val filtered = accounts.filter {
        (!favorites || it.favorite) && (folder.isBlank() || it.folder == folder) &&
            (it.issuer.contains(search, true) || it.account.contains(search, true) || it.folder.contains(search, true))
    }
    val reducedMotion = LocalReducedMotion.current
    Scaffold(floatingActionButton = {
        ExtendedFloatingActionButton(onClick = add, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Add account") })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Brand() }
                    ToolIcon(Icons.Outlined.Lock, "Lock vault", model::lock)
                    ToolIcon(Icons.Outlined.Settings, "Settings", settings)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Accounts", style = MaterialTheme.typography.headlineSmall)
                        Text("${accounts.size} ${if (accounts.size == 1) "account" else "accounts"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ToolIcon(if (preferences.hideCodes) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        if (preferences.hideCodes) "Show codes" else "Hide all codes") { model.preferences(preferences.copy(hideCodes = !preferences.hideCodes)) }
                }
            }
            item {
                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("Search accounts") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = {
                        if (search.isNotEmpty()) ToolIcon(Icons.Outlined.Close, "Clear search") { search = "" }
                    })
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(!favorites && folder.isBlank(), { favorites = false; folder = "" }, label = { Text("All ${accounts.size}") }) }
                    item { FilterChip(favorites, { favorites = !favorites }, label = { Text("Favorites") }, leadingIcon = { Icon(Icons.Outlined.Star, null, Modifier.size(18.dp)) }) }
                    items(accounts.map { it.folder }.filter { it.isNotBlank() }.distinct()) { name ->
                        FilterChip(folder == name, { folder = if (folder == name) "" else name }, label = { Text(name) })
                    }
                }
            }
            if (filtered.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (accounts.isEmpty()) "No accounts yet" else "No matching accounts", style = MaterialTheme.typography.titleMedium)
                    Text(if (accounts.isEmpty()) "Add an account to get verification codes." else "Try another name or folder.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (accounts.isEmpty()) OutlinedButton(onClick = add) { Text("Add your first account") }
                }
            }
            items(filtered, key = { it.id }) { account ->
                AccountCard(account, model, preferences, { model.copy(account.id, copy) }, { details(account.id) },
                    if (reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(180), placementSpec = tween(220), fadeOutSpec = tween(100)))
            }
        }
        }
    }
}

@Composable
private fun AccountCard(account: AccountMetadata, model: VaultViewModel, preferences: Preferences, copy: () -> Unit, details: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    Text((account.issuer.ifBlank { account.account }).take(2).uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(account.issuer.ifBlank { "Personal" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(account.account, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (account.folder.isNotBlank()) Text(account.folder, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
                ToolIcon(if (account.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, if (account.favorite) "Remove favorite" else "Add favorite") {
                    model.edit(account.copy(favorite = !account.favorite))
                }
                ToolIcon(Icons.Outlined.MoreVert, "Account details", details)
            }
            CodeDisplay(account, model, preferences, copy)
            if (account.otpType == "HOTP") CounterControl(account, model) else Countdown(account.period, model)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun CodeDisplay(account: AccountMetadata, model: VaultViewModel, preferences: Preferences, copy: () -> Unit) {
    val codeFlow = remember(account.id) { model.codes.map { it[account.id] }.distinctUntilChanged() }
    val code by codeFlow.collectAsStateWithLifecycle(initialValue = null)
    var revealed by remember(code, preferences.hideCodes) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (preferences.hideCodes && !revealed) "*".repeat(account.digits) else code?.chunked(if (account.digits == 8) 4 else 3)?.joinToString(" ") ?: "------",
            Modifier.weight(1f), fontSize = 30.sp, fontWeight = FontWeight.Medium, fontFamily = CodeFont,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
        if (preferences.hideCodes) ToolIcon(if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Toggle code visibility") { revealed = !revealed }
        if (preferences.clipboard) ToolIcon(Icons.Outlined.ContentCopy, "Copy code", copy)
    }
}

@Composable
private fun CounterControl(account: AccountMetadata, model: VaultViewModel) {
    var confirming by remember(account.id, account.counter) { mutableStateOf(false) }
    val busy by model.busy.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("HOTP / Counter ${account.counter}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { confirming = true }, enabled = !busy && account.counter < Long.MAX_VALUE) {
            Icon(Icons.Outlined.SkipNext, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Next code")
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { if (!busy) confirming = false },
        title = { Text("Advance to the next code?") },
        text = { Text("Advance after using this code, or when your service asks you to. Skipping codes can require resynchronization. Counter ${account.counter} will become ${account.counter + 1}.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { model.advanceCounter(account.id); confirming = false }) { Text("Advance counter") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } })
}

@Composable
private fun Countdown(period: Int, model: VaultViewModel) {
    val time by model.time.collectAsStateWithLifecycle()
    val remaining = if (time >= 0) Otp.remaining(time, period) else 0
    val progress by animateFloatAsState(remaining.toFloat() / period,
        if (LocalReducedMotion.current || remaining == period) snap() else tween(1000, easing = LinearEasing), label = "Code lifetime")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f).height(3.dp),
            color = if (remaining <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Text(if (remaining < 60) "${remaining}s" else "${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}",
            Modifier.widthIn(min = 40.dp), style = MaterialTheme.typography.labelMedium, fontFamily = CodeFont)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualScreen(model: VaultViewModel, back: () -> Unit) {
    var issuer by rememberSaveable { mutableStateOf("") }
    var account by rememberSaveable { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var algorithm by rememberSaveable { mutableStateOf(Algorithm.SHA1.name) }
    var digits by rememberSaveable { mutableStateOf("6") }
    var period by rememberSaveable { mutableStateOf("30") }
    var type by rememberSaveable { mutableStateOf(OtpType.TOTP.name) }
    var counter by rememberSaveable { mutableStateOf("0") }
    Page {
        PageHeader("Setup details", back)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            OtpType.entries.forEachIndexed { index, option ->
                SegmentedButton(selected = type == option.name, onClick = { type = option.name; if (option == OtpType.HOTP) algorithm = Algorithm.SHA1.name },
                    shape = SegmentedButtonDefaults.itemShape(index, 2), label = { Text(if (option == OtpType.TOTP) "Time-based" else "Counter-based") })
            }
        }
        OutlinedTextField(issuer, { issuer = it.take(100) }, Modifier.fillMaxWidth(), label = { Text("Issuer (optional)") }, singleLine = true)
        OutlinedTextField(account, { account = it.take(160) }, Modifier.fillMaxWidth(), label = { Text("Account name") }, singleLine = true)
        OutlinedTextField(secret, { secret = it.take(1024) }, Modifier.fillMaxWidth(), label = { Text("Base32 setup key") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password), singleLine = true)
        Text("Use the exact settings supplied by the service.", style = MaterialTheme.typography.bodyMedium)
        OptionMenu("Algorithm", algorithm, if (type == "HOTP") listOf("SHA1") else Algorithm.entries.map { it.name }) { algorithm = it }
        OptionMenu("Digits", digits, listOf("6", "7", "8")) { digits = it }
        if (type == "TOTP") OutlinedTextField(period, { period = it.take(5) }, Modifier.fillMaxWidth(), label = { Text("Period in seconds (1-86400)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        else OutlinedTextField(counter, { counter = it.take(19) }, Modifier.fillMaxWidth(), label = { Text("Initial counter") },
            supportingText = { Text("Use the counter supplied by your service, usually 0.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        Button(onClick = { model.prepareManual(issuer, account, secret, Algorithm.valueOf(algorithm), digits, period, OtpType.valueOf(type), counter); secret = "" },
            enabled = secret.isNotBlank() && account.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Review account") }
    }
}

@Composable
private fun DetailsScreen(account: AccountMetadata, model: VaultViewModel, back: () -> Unit) {
    var issuer by rememberSaveable(account.id) { mutableStateOf(account.issuer) }
    var name by rememberSaveable(account.id) { mutableStateOf(account.account) }
    var folder by rememberSaveable(account.id) { mutableStateOf(account.folder) }
    var deleting by remember { mutableStateOf(false) }
    Page {
        PageHeader("Account details", back)
        OutlinedTextField(issuer, { issuer = it.take(100) }, Modifier.fillMaxWidth(), label = { Text("Issuer") })
        OutlinedTextField(name, { name = it.take(160) }, Modifier.fillMaxWidth(), label = { Text("Account name") })
        OutlinedTextField(folder, { folder = it.take(64) }, Modifier.fillMaxWidth(), label = { Text("Folder (optional)") })
        Text("${account.otpType} / ${account.algorithm} / ${account.digits} digits", style = MaterialTheme.typography.bodyMedium)
        Text(if (account.otpType == "HOTP") "Counter: ${account.counter}" else "Period: ${account.period} seconds", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { model.edit(account.copy(issuer = issuer.trim(), account = name.trim(), folder = folder.trim()), back) }, enabled = name.isNotBlank()) { Text("Save changes") }
        HorizontalDivider()
        TextButton(onClick = { deleting = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Icon(Icons.Outlined.Delete, null); Spacer(Modifier.width(8.dp)); Text("Delete account")
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete this account?") },
        text = { Text("This removes ${account.issuer.ifBlank { account.account }} from this device. It does not disable two-factor authentication at the service. Keep another way to sign in.") },
        confirmButton = { TextButton(onClick = { model.delete(account.id, back); deleting = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}

@Composable
private fun SettingsScreen(preferences: Preferences, update: (Preferences) -> Unit, lock: () -> Unit,
    securitySettings: () -> Unit, about: () -> Unit, backup: () -> Unit, back: () -> Unit) {
    Page {
        PageHeader("Security & settings", back)
        Text("Device authentication", style = MaterialTheme.typography.titleLarge)
        Text("Unlock with fingerprint or your phone's screen lock.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ActionRow("Biometric setup", "Android security settings", Icons.Outlined.Fingerprint, securitySettings)
        SettingSwitch("Lock in background", preferences.lockInBackground) { update(preferences.copy(lockInBackground = it)) }
        OptionMenu("Inactivity timeout", "${preferences.timeoutSeconds} seconds", listOf("30 seconds", "60 seconds", "300 seconds")) {
            update(preferences.copy(timeoutSeconds = it.substringBefore(' ').toInt()))
        }
        HorizontalDivider()
        Text("Privacy", style = MaterialTheme.typography.titleLarge)
        SettingSwitch("Hide codes by default", preferences.hideCodes) { update(preferences.copy(hideCodes = it)) }
        SettingSwitch("Allow copying codes", preferences.clipboard) { update(preferences.copy(clipboard = it)) }
        Text("Screenshots are blocked. Copied codes clear after 30 seconds or when you leave the app. Other apps may retain copies.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        ActionRow("Backup & restore", "Encrypted export and recovery", Icons.Outlined.Save, backup)
        SettingSwitch("Light theme", preferences.lightTheme) { update(preferences.copy(lightTheme = it)) }
        SettingSwitch("Reduce motion", preferences.reduceMotion) { update(preferences.copy(reduceMotion = it)) }
        ActionRow("About & privacy", "Version ${BuildConfig.VERSION_NAME}", Icons.Outlined.Info, about)
        OutlinedButton(onClick = lock, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Lock, null); Spacer(Modifier.width(8.dp)); Text("Lock now") }
    }
}

@Composable
private fun AboutScreen(licenses: () -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    Page {
        PageHeader("About & privacy", back)
        Brand()
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelLarge)
        Text("Privacy policy", style = MaterialTheme.typography.titleLarge)
        Text("Vault Auth generates verification codes locally. It has no server, analytics, advertising, or Internet permission. Account names are indexed locally; setup keys are stored inside an authenticated encrypted vault.")
        Text("Permissions", style = MaterialTheme.typography.titleLarge)
        Text("Camera access is requested only for QR enrollment. The bundled scanner works offline and does not save camera images. Biometric permission invokes Android authentication; biometric templates never enter this app.")
        Text("Time & compatibility", style = MaterialTheme.typography.titleLarge)
        Text("TOTP codes use Unix time, not your time zone. HOTP uses a saved counter that advances only when you choose Next code. Enable automatic date and time for TOTP; follow your service's resynchronization process for HOTP.")
        OutlinedButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_DATE_SETTINGS)) }) { Text("Android date & time settings") }
        Text("Security boundaries", style = MaterialTheme.typography.titleLarge)
        Text("An unlocked vault holds decrypted secrets in memory until locked. A compromised OS, malicious keyboard, accessibility service, or someone who knows your device credential may access sensitive information. Screenshots and memory erasure cannot be guaranteed on every device.")
        Text("Open-source components", style = MaterialTheme.typography.titleLarge)
        Text("AndroidX, Jetpack Compose, Room, DataStore, and Apache Commons Codec use the Apache 2.0 license. java-otp by Jon Chambers uses the MIT license. This application has not completed independent security review.")
        ActionRow("Font licenses", "Manrope and JetBrains Mono / SIL Open Font License", Icons.Outlined.Description, licenses)
    }
}

@Composable
private fun FontLicensesScreen(back: () -> Unit) {
    val context = LocalContext.current
    val licenses by produceState("Loading licenses...") {
        value = withContext(Dispatchers.IO) {
            listOf("Manrope-OFL.txt", "JetBrainsMono-OFL.txt").joinToString("\n\n") { name ->
                context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
            }
        }
    }
    Page { PageHeader("Font licenses", back); Text(licenses, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Key, null, Modifier.size(24.dp), MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun Page(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
internal fun PageHeader(title: String, back: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ToolIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", back)
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(description) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick) { Icon(icon, description) }
    }
}

@Composable
internal fun ActionRow(title: String, subtitle: String, icon: ImageVector, action: () -> Unit) {
    Surface(onClick = action, color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall) }
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = change), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, null)
    }
}

@Composable
private fun OptionMenu(label: String, value: String, options: List<String>, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { open = true }, color = Color.Transparent) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, Modifier.weight(1f))
                Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Outlined.ExpandMore, null)
            }
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { select(option); open = false }) }
        }
    }
}