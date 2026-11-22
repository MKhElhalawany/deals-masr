package com.dealsmasr.app.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dealsmasr.app.ui.appContainer
import com.dealsmasr.app.ui.theme.Brand
import kotlinx.coroutines.launch

private enum class Mode { LOGIN, SIGNUP, RESET, OWNER }

private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]{2,}$""")
private val EG_PHONE = Regex("""^(\+?20|0)?1[0125]\d{8}$""")

/** Login / sign-up / password reset backed by Supabase Auth. */
@Composable
fun AuthScreen(notConfigured: Boolean) {
    val auth = appContainer().auth
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(Mode.LOGIN) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var codeSent by rememberSaveable { mutableStateOf(false) }

    val emailOk = EMAIL.matches(email.trim())
    val canSubmit = !busy && !notConfigured && emailOk && when (mode) {
        Mode.LOGIN -> password.isNotEmpty()
        Mode.SIGNUP -> password.length >= 6 && name.trim().length >= 2 && EG_PHONE.matches(phone.replace(" ", ""))
        Mode.RESET -> true
        Mode.OWNER -> !codeSent || code.length in 6..10
    }

    fun submit() {
        busy = true; error = null; info = null
        scope.launch {
            runCatching {
                when (mode) {
                    Mode.LOGIN -> auth.signIn(email, password)
                    Mode.SIGNUP -> if (!auth.signUp(email, password, name, phone.replace(" ", ""))) {
                        info = "بعتنالك رسالة تأكيد على $email — افتحها واضغط اللينك، وبعدين سجّل دخول."
                        mode = Mode.LOGIN
                    }
                    Mode.RESET -> { auth.resetPassword(email); info = "لو الإيميل متسجل، هتوصلك رسالة لتغيير كلمة السر."; mode = Mode.LOGIN }
                    Mode.OWNER -> if (!codeSent) {
                        auth.sendOwnerCode(email); codeSent = true
                        info = "بعتنا رسالة على $email — افتحها على الموبايل ده واضغط اللينك، هيفتح التطبيق وتدخل على طول. (لو وصلك كود بدل اللينك اكتبه تحت.)"
                    } else {
                        // on success the app opens automatically (signed-in state)
                        if (!auth.ownerSignIn(email, code)) info = "دخلت كمستخدم عادي — الإيميل ده مش إيميل صاحب المتجر"
                    }
                }
                Unit
            }.onFailure { error = it.message ?: "حصل خطأ" }
            password = ""
            busy = false
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Brand, Color(0xFF0A1F4A), Color(0xFF060F24))))
            .systemBarsPadding().imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // owner login is hidden: long-press the logo
            Icon(androidx.compose.ui.res.painterResource(com.dealsmasr.app.R.drawable.ic_launcher_foreground), null, tint = Color.Unspecified, modifier = Modifier.size(96.dp).pointerInput(Unit) {
                detectTapGestures(onLongPress = { mode = Mode.OWNER; codeSent = false; code = ""; error = null; info = null })
            })
            Text("عروض مصر", color = com.dealsmasr.app.ui.theme.GoldLight, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text("كل خصومات أمازون ونون وتاون تيم في مكان واحد + SHEIN لحد باب البيت", color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center)
            Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                AnimatedContent(mode, label = "mode") { m ->
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            when (m) { Mode.LOGIN -> "تسجيل الدخول"; Mode.SIGNUP -> "حساب جديد"; Mode.RESET -> "نسيت كلمة السر"; Mode.OWNER -> "دخول صاحب المتجر" },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (notConfigured) {
                            Text("التطبيق لسه مش متوصل بقاعدة البيانات (Supabase). صاحب التطبيق محتاج يضيف بيانات المشروع.",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (m == Mode.SIGNUP) {
                            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("الاسم") }, singleLine = true)
                            OutlinedTextField(phone, { phone = it.filter { c -> c.isDigit() || c == '+' } }, Modifier.fillMaxWidth(),
                                label = { Text("رقم الموبايل") }, singleLine = true,
                                isError = phone.isNotEmpty() && !EG_PHONE.matches(phone),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next))
                        }
                        OutlinedTextField(email, { email = it.trim() }, Modifier.fillMaxWidth(), label = { Text("الإيميل") }, singleLine = true,
                            isError = email.isNotEmpty() && !emailOk,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                        if (m == Mode.OWNER && codeSent) {
                            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(10) }, Modifier.fillMaxWidth(),
                                label = { Text("الكود (لو وصلك كود بدل اللينك)") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done))
                        }
                        if (m == Mode.LOGIN || m == Mode.SIGNUP) {
                            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("كلمة السر") }, singleLine = true,
                                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                                supportingText = { if (m == Mode.SIGNUP) Text("6 حروف على الأقل") },
                                trailingIcon = {
                                    IconButton(onClick = { showPass = !showPass }) {
                                        Icon(if (showPass) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "إظهار")
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done))
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        Button(onClick = { submit() }, enabled = canSubmit, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                            else Text(when (m) {
                                Mode.LOGIN -> "دخول"; Mode.SIGNUP -> "إنشاء الحساب"; Mode.RESET -> "ابعت لينك التغيير"
                                Mode.OWNER -> if (codeSent) "تأكيد الكود" else "ابعت لينك الدخول"
                            })
                        }
                        Spacer(Modifier.height(2.dp))
                        when (m) {
                            Mode.LOGIN -> {
                                TextButton(onClick = { mode = Mode.SIGNUP; error = null; info = null }, Modifier.fillMaxWidth()) {
                                    Text("معندكش حساب؟ اعمل حساب جديد")
                                }
                                TextButton(onClick = { mode = Mode.RESET; error = null; info = null }, Modifier.fillMaxWidth()) {
                                    Text("نسيت كلمة السر؟")
                                }
                            }
                            else -> TextButton(onClick = { mode = Mode.LOGIN; codeSent = false; error = null }, Modifier.fillMaxWidth()) {
                                Text("عندك حساب؟ سجّل دخول")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Shown for the split second while a saved session is being restored. */
@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(Brand), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color.White)
    }
}
