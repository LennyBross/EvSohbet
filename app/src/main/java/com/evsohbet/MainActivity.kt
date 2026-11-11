package com.evsohbet

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val openChat = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Repo.init(applicationContext)
        openChat.value = intent?.getStringExtra(EXTRA_CHAT)
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { App(openChat) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CHAT)?.let { openChat.value = it }
    }

    override fun onResume() {
        super.onResume()
        Repo.visible = true
        openChat.value?.let { Repo.clearUnread(it) }
    }

    override fun onPause() {
        Repo.visible = false
        super.onPause()
    }
}

@Composable
fun App(openChat: MutableState<String?>) {
    val name by Repo.myName.collectAsState()
    if (name == null) {
        NameScreen()
        return
    }
    val ctx = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        ContextCompat.startForegroundService(ctx, Intent(ctx, NetService::class.java))
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val chat = openChat.value
    LaunchedEffect(chat) {
        Repo.activeChat = chat
        chat?.let { Repo.clearUnread(it) }
    }
    if (chat == null) {
        ChatList(onOpen = { openChat.value = it })
    } else {
        BackHandler { openChat.value = null }
        ChatScreen(chat, onBack = { openChat.value = null })
    }
}

@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

// ---------------- İsim ----------------

@Composable
fun NameScreen() {
    var n by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🏠 Ev Sohbet", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Evdekiler seni hangi isimle görsün?")
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = n, onValueChange = { n = it }, singleLine = true,
            label = { Text("Adın") }, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { Repo.setName(n.trim()) }, enabled = n.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Başla") }
    }
}

@Composable
fun NameDialog(onDismiss: () -> Unit) {
    var n by remember { mutableStateOf(Repo.myName.value ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adını değiştir") },
        text = { OutlinedTextField(value = n, onValueChange = { n = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { if (n.isNotBlank()) Repo.setName(n.trim()); onDismiss() }) { Text("Kaydet") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

// ---------------- Sohbet listesi ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatList(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val peers by Repo.peers.collectAsState()
    val msgs by Repo.messages.collectAsState()
    val unread by Repo.unread.collectAsState()
    val myName by Repo.myName.collectAsState()
    val now = rememberNow()
    val batteryOk = remember(now) { isIgnoringBattery(ctx) }
    var editName by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ev Sohbet") },
                actions = { TextButton(onClick = { editName = true }) { Text("👤 ${myName ?: ""}") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (!batteryOk) item(key = "battery") {
                Card(Modifier.padding(12.dp).fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Ekran kapalıyken de mesaj alabilmek için pil kısıtlamasını kaldır.")
                        TextButton(onClick = { requestBattery(ctx) }) { Text("Kısıtlamayı kaldır") }
                    }
                }
            }
            val onlineCount = peers.values.count { it.isOnline(now) }
            item(key = GROUP) {
                ChatRow(
                    avatar = "🏠", title = "Ev Grubu", subtitle = "$onlineCount kişi çevrimiçi",
                    preview = lastPreview(msgs, GROUP), unread = unread[GROUP] ?: 0,
                    online = onlineCount > 0, onClick = { onOpen(GROUP) },
                )
            }
            val sorted = peers.values.sortedWith(
                compareByDescending<Peer> { it.isOnline(now) }.thenBy { it.name.lowercase() }
            )
            items(sorted, key = { it.id }) { p ->
                val on = p.isOnline(now)
                ChatRow(
                    avatar = p.name.take(1).uppercase(), title = p.name,
                    subtitle = if (on) "çevrimiçi" else "çevrimdışı",
                    preview = lastPreview(msgs, p.id), unread = unread[p.id] ?: 0,
                    online = on, onClick = { onOpen(p.id) },
                )
            }
            if (peers.isEmpty()) item(key = "empty") {
                Text(
                    "Henüz kimse görünmüyor.\n\nDiğer telefonlarda da uygulamayı açın ve hepsinin aynı Wi-Fi ağına bağlı olduğundan emin olun.",
                    Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (editName) NameDialog(onDismiss = { editName = false })
}

fun lastPreview(msgs: List<Msg>, chat: String): String? =
    msgs.lastOrNull { it.chat == chat }?.let {
        val who = when {
            it.mine -> "Sen: "
            chat == GROUP -> "${it.fromName}: "
            else -> ""
        }
        who + (it.text ?: "📎 ${it.fileName}")
    }

@Composable
fun ChatRow(
    avatar: String, title: String, subtitle: String, preview: String?,
    unread: Int, online: Boolean, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(avatar, fontSize = 20.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (online) Color(0xFF34C759) else Color.Gray))
            }
            Text(
                preview ?: subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (unread > 0) Badge { Text("$unread") }
    }
}

// ---------------- Sohbet ekranı ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(chat: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val all by Repo.messages.collectAsState()
    val peers by Repo.peers.collectAsState()
    val now = rememberNow()
    val msgs = remember(all, chat) { all.filter { it.chat == chat } }
    val title = if (chat == GROUP) "🏠 Ev Grubu" else peers[chat]?.name ?: "?"
    val subtitle = when {
        chat == GROUP -> "${peers.values.count { it.isOnline(now) }} kişi çevrimiçi"
        peers[chat]?.isOnline(now) == true -> "çevrimiçi"
        else -> "çevrimdışı"
    }
    var text by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { u ->
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            Net.sendFile(ctx.applicationContext, chat, u)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri") }
                },
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("📎", fontSize = 22.sp) }
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.weight(1f), placeholder = { Text("Mesaj") },
                    maxLines = 4, shape = RoundedCornerShape(24.dp),
                )
                IconButton(onClick = {
                    val t = text.trim()
                    if (t.isNotEmpty()) {
                        Net.sendText(chat, t)
                        text = ""
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Gönder", tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(8.dp),
        ) {
            items(msgs, key = { it.id + it.mine }) { m -> Bubble(m, showSender = chat == GROUP) }
        }
    }
}

@Composable
fun Bubble(m: Msg, showSender: Boolean) {
    val ctx = LocalContext.current
    val bg = if (m.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(bg)
                .clickable(enabled = m.fileUri != null || m.status == "failed") {
                    if (m.status == "failed") Net.retry(ctx.applicationContext, m) else openFile(ctx, m)
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (showSender && !m.mine) {
                Text(
                    m.fromName, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                )
            }
            if (m.fileName != null) {
                if (m.mime?.startsWith("image/") == true && m.fileUri != null) Thumb(m.fileUri)
                Text("📎 ${m.fileName}", fontWeight = FontWeight.Medium)
                Text(Formatter.formatShortFileSize(ctx, m.size), style = MaterialTheme.typography.bodySmall)
            }
            m.text?.let { Text(it) }
            Text(
                statusLine(m), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
fun Thumb(uri: String) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.loadThumbnail(Uri.parse(uri), android.util.Size(480, 480), null).asImageBitmap()
            }.getOrNull()
        }
    }
    bmp?.let {
        Image(
            it, contentDescription = null,
            modifier = Modifier.widthIn(max = 260.dp).padding(bottom = 4.dp).clip(RoundedCornerShape(10.dp)),
        )
    }
}

fun statusLine(m: Msg): String {
    val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.time))
    return t + when (m.status) {
        "sending" -> " · gönderiliyor…"
        "failed" -> " · ❗ gönderilemedi, tekrar denemek için dokun"
        else -> if (m.mine) " ✓" else ""
    }
}

fun openFile(ctx: Context, m: Msg) {
    val uri = m.fileUri ?: return
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(uri), m.mime ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Toast.makeText(ctx, "Bu dosyayı açacak bir uygulama bulunamadı", Toast.LENGTH_SHORT).show()
    }
}

fun isIgnoringBattery(ctx: Context): Boolean =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

@SuppressLint("BatteryLife")
fun requestBattery(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        )
    }
}
