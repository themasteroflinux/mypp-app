package com.platinumpizzaria.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private const val SUPABASE_URL = "https://rrpnmxucqlpaaueufsmb.supabase.co"
private const val SUPABASE_KEY = "sb_publishable_scPdBcRV7V0X_VN1IaZPsw_BxxCvxmN"
private const val WEBSITE = "https://platinum-pizzaria.pages.dev"
private val http = OkHttpClient()
private val silver = Color(0xFFC1C6C8)
private val ink = Color(0xFF111820)
private val blue = Color(0xFF155EEF)

data class Member(val email: String, val points: Int?, val tier: String?)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PlatinumHome(this) }
    }
}

private fun api(path: String, method: String = "GET", json: JSONObject? = null, accessToken: String? = null): String {
    val builder = Request.Builder().url("$SUPABASE_URL$path")
        .addHeader("apikey", SUPABASE_KEY).addHeader("Accept", "application/json")
    if (accessToken != null) builder.addHeader("Authorization", "Bearer $accessToken")
    if (json != null) builder.addHeader("Content-Type", "application/json")
        .method(method, json.toString().toRequestBody("application/json".toMediaType()))
    else if (method != "GET") builder.method(method, ByteArray(0).toRequestBody(null))
    http.newCall(builder.build()).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val message = try { JSONObject(body).optString("message").ifBlank { JSONObject(body).optString("msg") } }
                catch (_: Exception) { "" }
            throw IOException(message.ifBlank { "Supabase request failed (${response.code})." })
        }
        return body
    }
}

private fun authenticate(context: Context, email: String, password: String, create: Boolean) {
    val path = if (create) "/auth/v1/signup" else "/auth/v1/token?grant_type=password"
    val result = JSONObject(api(path, "POST", JSONObject().put("email", email).put("password", password)))
    val user = result.optJSONObject("user") ?: result
    val token = result.optString("access_token")
    if (token.isBlank()) throw IOException("Account created. Confirm your email, then sign in.")
    context.getSharedPreferences("platinum_session", Context.MODE_PRIVATE).edit()
        .putString("access_token", token)
        .putString("refresh_token", result.optString("refresh_token"))
        .putString("user_id", user.optString("id"))
        .putString("email", user.optString("email", email))
        .apply()
}

private fun getMember(context: Context): Member {
    val prefs = context.getSharedPreferences("platinum_session", Context.MODE_PRIVATE)
    val token = prefs.getString("access_token", null) ?: throw IOException("Please sign in.")
    val id = prefs.getString("user_id", null) ?: throw IOException("Please sign in again.")
    val email = prefs.getString("email", "").orEmpty()
    val rows = JSONArray(api("/rest/v1/reward_accounts?select=points,tier&user_id=eq.$id", accessToken = token))
    if (rows.length() == 0) return Member(email, null, null)
    val row = rows.getJSONObject(0)
    return Member(email, if (row.isNull("points")) null else row.optInt("points"),
        row.optString("tier").takeUnless { it.isBlank() || it == "null" })
}

@Composable
private fun PlatinumHome(context: Context) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var member by remember { mutableStateOf<Member?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var activePage by remember { mutableStateOf<String?>(null) }
    var browser by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = activePage != null) {
        val view = browser
        if (view != null && view.canGoBack()) view.goBack() else activePage = null
    }

    fun launchRequest(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            message = ""
            try { block() } catch (e: Exception) { message = e.message ?: "Something went wrong." }
            finally { busy = false }
        }
    }

    LaunchedEffect(refresh) {
        val saved = context.getSharedPreferences("platinum_session", Context.MODE_PRIVATE)
        if (saved.contains("access_token")) launchRequest {
            member = withContext(Dispatchers.IO) { getMember(context) }
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = blue, onPrimary = Color.White,
        background = Color(0xFFF1F3F4), surface = Color.White, onSurface = ink)) {
        Column(Modifier.fillMaxSize().background(Color(0xFFF1F3F4))
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(silver, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                    Text("P", fontSize = 26.sp, fontWeight = FontWeight.Black, color = ink)
                }
                Spacer(Modifier.width(11.dp))
                Column {
                    Text("PLATINUM", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = Color(0xFF5A6268))
                    Text("PIZZARIA", fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, color = ink)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { refresh++ }, enabled = !busy) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh points", tint = ink)
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(if (member == null) "Welcome to Platinum." else "Welcome back.",
                fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, color = ink)
            Text("Your pizza. Your points. Your rewards.", color = Color(0xFF68727A), fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))

            if (member != null) {
                Column(Modifier.fillMaxWidth().background(
                    Brush.linearGradient(listOf(Color(0xFF202A33), Color(0xFF46535D))),
                    RoundedCornerShape(24.dp)).padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("PLATINUM REWARDS", color = Color(0xFFD7DDE0), fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.Stars, contentDescription = null, tint = Color.White)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(member?.points?.toString() ?: "—", fontSize = 48.sp,
                        color = Color.White, fontWeight = FontWeight.Black)
                    Text("POINTS", color = Color(0xFFD7DDE0), fontSize = 12.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = Color(0x667FFFFFFF))
                    Spacer(Modifier.height(12.dp))
                    Text("MEMBER TIER", color = Color(0xFFD7DDE0), fontSize = 10.sp, letterSpacing = 1.sp)
                    Text(member?.tier ?: "New member", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text(member?.email.orEmpty(), color = Color(0xFF68727A), fontSize = 13.sp)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { activePage = "$WEBSITE/order.html"; browser = null },
                    modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.LocalPizza, contentDescription = null)
                    Spacer(Modifier.width(9.dp))
                    Text("ORDER NOW", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.ArrowForward, contentDescription = null)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { activePage = "$WEBSITE/menu.html"; browser = null },
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.MenuBook, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("EXPLORE THE MENU")
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = {
                    context.getSharedPreferences("platinum_session", Context.MODE_PRIVATE).edit().clear().apply()
                    member = null
                }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
            } else {
                Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Sign in to your rewards", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("Use the same email and password as the Platinum Pizzaria website.",
                            fontSize = 13.sp, color = Color(0xFF68727A))
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(email, { email = it }, label = { Text("Email") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(password, { password = it }, label = { Text("Password") },
                            singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = {
                            if (email.isBlank() || password.isBlank()) { message = "Enter your email and password."; return@Button }
                            launchRequest {
                                withContext(Dispatchers.IO) { authenticate(context, email.trim(), password, false) }
                                member = withContext(Dispatchers.IO) { getMember(context) }
                            }
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(14.dp)) { Text("SIGN IN", fontWeight = FontWeight.Bold) }
                        TextButton(onClick = {
                            if (email.isBlank() || password.length < 8) {
                                message = "Enter an email and a password of at least 8 characters."
                                return@TextButton
                            }
                            launchRequest {
                                withContext(Dispatchers.IO) { authenticate(context, email.trim(), password, true) }
                                member = withContext(Dispatchers.IO) { getMember(context) }
                            }
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Create an account") }
                    }
                }
            }
            if (busy) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (message.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(message, color = Color(0xFFB42318), fontSize = 13.sp)
            }
            Spacer(Modifier.height(28.dp))
            Text("PLATINUM PIZZARIA", fontWeight = FontWeight.Bold, color = Color(0xFF68727A),
                fontSize = 11.sp, letterSpacing = 1.5.sp)
            Text("Freshly made. Always platinum.", color = Color(0xFF899198), fontSize = 12.sp)
            Spacer(Modifier.height(24.dp))
        }

        if (activePage != null) {
            Column(Modifier.fillMaxSize().background(Color(0xFFF1F3F4))) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF202A33)).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        val view = browser
                        if (view != null && view.canGoBack()) view.goBack() else activePage = null
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Text(
                        if (activePage?.endsWith("menu.html") == true) "Menu" else "Order",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { browser?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = Color.White)
                    }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            webViewClient = WebViewClient()
                            webChromeClient = WebChromeClient()
                            browser = this
                            loadUrl(activePage!!)
                        }
                    },
                    update = { view ->
                        browser = view
                        if (view.url != activePage) view.loadUrl(activePage!!)
                    }
                )
            }
        }
    }
}
