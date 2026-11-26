package com.platinumpizzaria.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
private const val TALER_BACKEND = "https://raspberrypi.tail1b64ba.ts.net"
private val http = OkHttpClient()
private val silver = Color(0xFFC1C6C8)
private val ink = Color(0xFF111820)
private val blue = Color(0xFF155EEF)

data class Member(val email: String, val points: Int?, val tier: String?)
private enum class AppScreen { HOME, MENU, ORDER }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PlatinumHome(this) }
    }
}

private fun api(path: String, method: String = "GET", body: String? = null, accessToken: String? = null): String {
    val builder = Request.Builder().url(SUPABASE_URL + path)
        .addHeader("apikey", SUPABASE_KEY).addHeader("Accept", "application/json")
    if (accessToken != null) builder.addHeader("Authorization", "Bearer " + accessToken)
    if (body != null) {
        builder.addHeader("Content-Type", "application/json")
        if (path == "/rest/v1/orders") builder.addHeader("Prefer", "return=minimal")
        builder.method(method, body.toRequestBody("application/json".toMediaType()))
    } else if (method != "GET") builder.method(method, ByteArray(0).toRequestBody(null))
    http.newCall(builder.build()).execute().use { response ->
        val responseBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val message = try {
                JSONObject(responseBody).optString("message").ifBlank { JSONObject(responseBody).optString("msg") }
            } catch (_: Exception) { "" }
            throw IOException(message.ifBlank { "Supabase request failed (" + response.code + ")." })
        }
        return responseBody
    }
}

private fun authenticate(context: Context, email: String, password: String, create: Boolean) {
    val path = if (create) "/auth/v1/signup" else "/auth/v1/token?grant_type=password"
    val payload = JSONObject().put("email", email).put("password", password).toString()
    val result = JSONObject(api(path, "POST", payload))
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
    val rows = JSONArray(api("/rest/v1/reward_accounts?select=points,tier&user_id=eq." + id, accessToken = token))
    if (rows.length() == 0) return Member(email, null, null)
    val row = rows.getJSONObject(0)
    return Member(email, if (row.isNull("points")) null else row.optInt("points"),
        row.optString("tier").takeUnless { it.isBlank() || it == "null" })
}

private fun orderJson(
    context: Context, driver: String, name: String, personal: Int, combo: Int,
    group: Int, garlic: Int, payment: String
): JSONObject {
    val prefs = context.getSharedPreferences("platinum_session", Context.MODE_PRIVATE)
    val userId = prefs.getString("user_id", null)
    return JSONObject()
        .put("user_id", userId ?: JSONObject.NULL)
        .put("driver", driver)
        .put("name", name)
        .put("personpizza", personal)
        .put("onemancombo", combo)
        .put("gpizza", group)
        .put("garbread", garlic)
        .put("cardtype", payment)
}

private fun submitSupabaseOrder(context: Context, order: JSONObject) {
    val token = context.getSharedPreferences("platinum_session", Context.MODE_PRIVATE)
        .getString("access_token", null)
    api("/rest/v1/orders", "POST", JSONArray().put(order).toString(), accessToken = token)
}

private fun createTalerOrder(order: JSONObject): String {
    val body = order.toString().toRequestBody("application/json".toMediaType())
    val request = Request.Builder().url(WEBSITE + "/api/taler/create-order")
        .post(body).addHeader("Accept", "application/json").build()
    http.newCall(request).execute().use { response ->
        val responseBody = response.body?.string().orEmpty()
        val result = try { JSONObject(responseBody) } catch (_: Exception) { JSONObject() }
        if (!response.isSuccessful || result.optString("order_id").isBlank()) {
            throw IOException(result.optString("error").ifBlank {
                "Could not create GNU Taler payment (" + response.code + ")."
            })
        }
        return result.getString("order_id")
    }
}

private fun verifyTalerPayment(orderId: String): Boolean {
    val request = Request.Builder()
        .url(TALER_BACKEND + "/orders/" + Uri.encode(orderId))
        .header("Accept", "application/json").get().build()
    http.newCall(request).execute().use { response ->
        if (response.code == 402) throw IOException("Payment is not confirmed yet. Finish paying in your GNU Taler wallet, then try again.")
        if (response.code != 200) throw IOException("GNU Taler returned HTTP " + response.code + " while checking payment.")
        return true
    }
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
    var screen by remember { mutableStateOf(AppScreen.HOME) }

    BackHandler(enabled = screen != AppScreen.HOME) { screen = AppScreen.HOME }

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
        when (screen) {
            AppScreen.MENU -> NativeMenuScreen(
                onBack = { screen = AppScreen.HOME },
                onOrder = { screen = AppScreen.ORDER }
            )
            AppScreen.ORDER -> NativeOrderScreen(
                context = context,
                onBack = { screen = AppScreen.HOME }
            )
            AppScreen.HOME -> Column(Modifier.fillMaxSize().background(Color(0xFFF1F3F4))
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

                Spacer(Modifier.height(18.dp))
                Button(onClick = { screen = AppScreen.ORDER }, modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.LocalPizza, contentDescription = null)
                    Spacer(Modifier.width(9.dp))
                    Text("ORDER NOW", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.ArrowForward, contentDescription = null)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { screen = AppScreen.MENU }, modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.MenuBook, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("EXPLORE THE MENU")
                }
                if (member != null) {
                    TextButton(onClick = {
                        context.getSharedPreferences("platinum_session", Context.MODE_PRIVATE).edit().clear().apply()
                        member = null
                    }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
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
        }
    }
}

@Composable
private fun ShopHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF202A33)).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White) }
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Spacer(Modifier.weight(1f))
        Text("PLATINUM", color = Color(0xFFD7DDE0), fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun NativeMenuScreen(onBack: () -> Unit, onOrder: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xFFF1F3F4))) {
        ShopHeader("Our Menu", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(18.dp)) {
            Text("MADE FOR SHARING (OR NOT).", color = Color(0xFF68727A), fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Text("The menu", fontSize = 30.sp, fontWeight = FontWeight.Black, color = ink)
            Spacer(Modifier.height(16.dp))
            MenuItemCard("Personal Pizza", "A personal-sized pizza, made fresh.")
            MenuItemCard("Group Pizza", "A pizza made for sharing.")
            MenuItemCard("Garlic Bread", "A classic side to go with your pizza.")
            MenuItemCard("One Man Combo", "Comes with Personal Pizza and a Coca Cola fountain drink.")
            Spacer(Modifier.height(8.dp))
            Text("GNU Taler sandbox checkout currently prices each menu item at KUDOS 5.00. KUDOS are test currency, not real money.",
                color = Color(0xFF68727A), fontSize = 12.sp)
        }
        Button(onClick = onOrder, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp).height(54.dp),
            shape = RoundedCornerShape(16.dp)) {
            Text("START AN ORDER", fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
private fun MenuItemCard(name: String, description: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).background(Color(0xFFE8ECEF), RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.LocalPizza, contentDescription = null, tint = Color(0xFF35424D), modifier = Modifier.size(29.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = ink)
                Spacer(Modifier.height(4.dp))
                Text(description, fontSize = 13.sp, color = Color(0xFF68727A))
            }
        }
    }
}

@Composable
private fun QuantityPicker(label: String, quantity: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, color = ink, fontSize = 15.sp)
            Text("KUDOS 5.00 each for sandbox checkout", color = Color(0xFF68727A), fontSize = 11.sp)
        }
        IconButton(onClick = { onChange((quantity - 1).coerceAtLeast(0)) }, enabled = quantity > 0) {
            Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Remove one")
        }
        Text(quantity.toString(), fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 22.dp))
        IconButton(onClick = { onChange((quantity + 1).coerceAtMost(10)) }, enabled = quantity < 10) {
            Icon(Icons.Default.AddCircle, contentDescription = "Add one")
        }
    }
}

@Composable
private fun NativeOrderScreen(context: Context, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("platinum_taler", Context.MODE_PRIVATE) }
    var driver by remember { mutableStateOf("") }
    var driverDialog by remember { mutableStateOf(false) }
    var customerName by remember { mutableStateOf("") }
    var payment by remember { mutableStateOf("Credit/Debit") }
    var personal by remember { mutableIntStateOf(0) }
    var combo by remember { mutableIntStateOf(0) }
    var group by remember { mutableIntStateOf(0) }
    var garlic by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var talerOrderId by remember { mutableStateOf(prefs.getString("pending_order_id", null)) }
    val drivers = listOf(
        "Jason Naish", "Deacon Danford", "Fox Kay", "Jaemon Spano", "Norah Sellner",
        "Greer Moses", "Claire Gross", "Jacob Fitzhugh", "Jaxson Bennett", "Miles Berhinger"
    )
    val itemCount = personal + combo + group + garlic
    val talerTotal = itemCount * 5

    fun makeOrder(method: String): JSONObject = orderJson(
        context, driver, customerName.trim(), personal, combo, group, garlic, method
    )

    fun runTask(task: suspend () -> Unit) {
        scope.launch {
            busy = true
            message = ""
            try { task() } catch (e: Exception) { message = e.message ?: "Something went wrong." }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFFF1F3F4))) {
        ShopHeader("Place an Order", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            val signedInEmail = context.getSharedPreferences("platinum_session", Context.MODE_PRIVATE)
                .getString("email", null)
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD))) {
                Text(
                    if (signedInEmail != null) "Signed in as " + signedInEmail + ". This order will be linked to your rewards account."
                    else "You can order without signing in. This order will not be linked to a rewards account.",
                    modifier = Modifier.padding(13.dp), color = Color(0xFF664D03), fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(14.dp))
            Text("1. Choose your driver", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { driverDialog = true }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp)) {
                Text(if (driver.isBlank()) "Select a driver" else driver, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ExpandMore, contentDescription = null)
            }
            Spacer(Modifier.height(18.dp))
            Text("2. Build your order", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink)
            Spacer(Modifier.height(6.dp))
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(horizontal = 15.dp, vertical = 8.dp)) {
                    QuantityPicker("Personal Pizza", personal) { personal = it }
                    HorizontalDivider(color = Color(0xFFE6E9EB))
                    QuantityPicker("One Man Combo", combo) { combo = it }
                    HorizontalDivider(color = Color(0xFFE6E9EB))
                    QuantityPicker("Group Pizza", group) { group = it }
                    HorizontalDivider(color = Color(0xFFE6E9EB))
                    QuantityPicker("Garlic Bread", garlic) { garlic = it }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("3. Order name", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(customerName, { customerName = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Name for the order") }, singleLine = true, shape = RoundedCornerShape(13.dp))
            Spacer(Modifier.height(18.dp))
            Text("4. Payment method", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink)
            Spacer(Modifier.height(6.dp))
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(12.dp)) {
                    listOf("Credit/Debit", "Venmo", "PayPal", "ROTK E-port", "GNU Taler (Sandbox)").forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = payment == option, onClick = { payment = option })
                            Text(option, fontSize = 14.sp, color = ink)
                        }
                    }
                }
            }
            if (payment == "GNU Taler (Sandbox)") {
                Spacer(Modifier.height(10.dp))
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD))) {
                    Column(Modifier.padding(14.dp)) {
                        Text("GNU Taler sandbox checkout", fontWeight = FontWeight.Bold, color = Color(0xFF664D03))
                        Text("Total: KUDOS " + talerTotal.toString() + ".00", fontSize = 20.sp,
                            fontWeight = FontWeight.Black, color = Color(0xFF664D03))
                        Text("Each menu item is KUDOS 5.00. KUDOS are test currency, not real money.",
                            fontSize = 12.sp, color = Color(0xFF664D03))
                    }
                }
            }
            if (talerOrderId != null) {
                Spacer(Modifier.height(12.dp))
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFE7F3E8))) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Have you completed payment in your Taler wallet?", fontWeight = FontWeight.Bold, color = Color(0xFF185C2B))
                        Text("Return here after paying and verify the payment to submit the order.", fontSize = 12.sp, color = Color(0xFF185C2B))
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            runTask {
                                val id = talerOrderId ?: throw IOException("No pending GNU Taler order was found.")
                                withContext(Dispatchers.IO) { verifyTalerPayment(id) }
                                val savedJson = prefs.getString("pending_order_json", null)
                                    ?: throw IOException("The pending order details are missing. Please contact the restaurant with Taler order ID " + id + ".")
                                val savedOrder = JSONObject(savedJson)
                                withContext(Dispatchers.IO) { submitSupabaseOrder(context, savedOrder) }
                                prefs.edit().remove("pending_order_id").remove("pending_order_json").apply()
                                talerOrderId = null
                                message = "Payment verified! Your order has been submitted."
                            }
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("VERIFY PAYMENT & SUBMIT ORDER")
                        }
                        TextButton(onClick = {
                            val id = talerOrderId ?: return@TextButton
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TALER_BACKEND + "/orders/" + Uri.encode(id))))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Open Taler wallet/order again") }
                    }
                }
            }
            if (message.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(message, color = if (message.startsWith("Payment verified")) Color(0xFF18713A) else Color(0xFFB42318),
                    fontSize = 13.sp)
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    if (driver.isBlank()) { message = "Please select a driver."; return@Button }
                    if (customerName.isBlank()) { message = "Please enter a name for the order."; return@Button }
                    if (itemCount < 1) { message = "Please add at least one menu item."; return@Button }
                    if (talerOrderId != null) { message = "Finish or verify your existing GNU Taler order before starting another."; return@Button }
                    runTask {
                        val order = makeOrder(if (payment == "GNU Taler (Sandbox)") "GNU Taler (Sandbox - KUDOS test)" else payment)
                        if (payment == "GNU Taler (Sandbox)") {
                            val json = order.toString()
                            prefs.edit().putString("pending_order_json", json).apply()
                            val id = withContext(Dispatchers.IO) { createTalerOrder(order) }
                            prefs.edit().putString("pending_order_id", id).apply()
                            talerOrderId = id
                            withContext(Dispatchers.Main) {
                                context.startActivity(Intent(Intent.ACTION_VIEW,
                                    Uri.parse(TALER_BACKEND + "/orders/" + Uri.encode(id))))
                            }
                            message = "GNU Taler payment created. Complete it in your wallet, then return here to verify."
                        } else {
                            withContext(Dispatchers.IO) { submitSupabaseOrder(context, order) }
                            message = "Your order has been submitted."
                        }
                    }
                },
                enabled = !busy && talerOrderId == null,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("PLACE ORDER", fontWeight = FontWeight.Bold)
            }
            if (busy) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (driverDialog) {
        AlertDialog(
            onDismissRequest = { driverDialog = false },
            title = { Text("Choose your driver") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    drivers.forEach { option ->
                        TextButton(onClick = { driver = option; driverDialog = false },
                            modifier = Modifier.fillMaxWidth()) {
                            Text(option, modifier = Modifier.fillMaxWidth(), color = ink)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { driverDialog = false }) { Text("Close") } }
        )
    }
}
