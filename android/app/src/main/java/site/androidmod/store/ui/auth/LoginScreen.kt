package site.androidmod.store.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.androidmod.store.ui.ErrorBanner
import site.androidmod.store.ui.NoticeBanner
import site.androidmod.store.ui.SiteLinks
import site.androidmod.store.ui.openLink

@Composable
fun LoginScreen(viewModel: AuthViewModel, onRegister: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        // Scrolls because the soft keyboard halves the available height; without it
        // the sign-in button is unreachable on a short screen.
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AuthBrand(
            title = "Patched Store",
            subtitle = "Sign in to download patched builds",
            modifier = Modifier.padding(bottom = 28.dp),
        )

        // Where registering sent the verification link, if the user just did.
        NoticeBanner(state.notice)
        ErrorBanner(state.error)

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username or email") },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )

        Button(
            onClick = { viewModel.signIn(username.trim(), password) },
            enabled = !state.loading && username.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        ) {
            if (state.loading) {
                // Sized explicitly: the default indicator is taller than the button
                // and blew its height out while a sign-in was in flight.
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text("Sign in", modifier = Modifier.padding(start = 10.dp))
            } else {
                Text("Sign in")
            }
        }

        // The reset itself happens on the website: the emailed link opens a page
        // there, so a flow built in here would end in the browser regardless. A
        // Custom Tab keeps the user one back-press from this form.
        TextButton(onClick = { openLink(context, SiteLinks.FORGOT_PASSWORD) }, modifier = Modifier.padding(top = 8.dp)) {
            Text("Forgot password?")
        }

        TextButton(onClick = onRegister) {
            Text("No account? Create one")
        }
    }
}
