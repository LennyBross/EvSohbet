package com.evsohbet

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/** Arka planda çalışır: cihazları duyurur/bulur ve gelen mesajları alır. */
class NetService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var udpIn: DatagramSocket? = null
    private var server: ServerSocket? = null
    private var mlock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Notif.channels(this)
        val n = NotificationCompat.Builder(this, Notif.CH_SVC)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Ev Sohbet açık")
            .setContentText("Evdeki cihazlardan gelen mesajlar dinleniyor")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(Notif.openApp(this, null))
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        mlock = wifi.createMulticastLock("evsohbet").apply { setReferenceCounted(false); acquire() }

        scope.launch { announceLoop() }
        scope.launch { listenLoop() }
        scope.launch { serverLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        runCatching { udpIn?.close() }
        runCatching { server?.close() }
        runCatching { mlock?.release() }
        super.onDestroy()
    }

    // ---- Keşif: her 3 saniyede "ben buradayım" yayını ----
    private suspend fun announceLoop() {
        while (scope.isActive) {
            try {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    while (scope.isActive) {
                        val data = JSONObject()
                            .put("app", "evsohbet").put("id", Repo.myId).put("name", Repo.myName.value ?: "")
                            .toString().toByteArray(Charsets.UTF_8)
                        for (a in broadcastAddresses()) {
                            runCatching { s.send(DatagramPacket(data, data.size, a, Net.DISC_PORT)) }
                        }
                        delay(3000)
                    }
                }
            } catch (e: Exception) {
                delay(3000)
            }
        }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = mutableListOf(InetAddress.getByName("255.255.255.255"))
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { it.isUp && !it.isLoopback }
                ?.forEach { ni -> ni.interfaceAddresses.mapNotNullTo(out) { it.broadcast } }
        } catch (_: Exception) {
        }
        return out.distinct()
    }

    private suspend fun listenLoop() {
        while (scope.isActive) {
            try {
                val s = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(Net.DISC_PORT))
                }
                udpIn = s
                s.use {
                    val buf = ByteArray(2048)
                    while (scope.isActive) {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val o = runCatching { JSONObject(String(p.data, 0, p.length, Charsets.UTF_8)) }.getOrNull()
                            ?: continue
                        if (o.optString("app") != "evsohbet") continue
                        Repo.updatePeer(o.optString("id"), o.optString("name"), p.address.hostAddress)
                    }
                }
            } catch (e: Exception) {
                delay(2000)
            }
        }
    }

    // ---- Mesaj alma ----
    private suspend fun serverLoop() {
        while (scope.isActive) {
            try {
                val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Net.TCP_PORT)) }
                server = ss
                ss.use {
                    while (scope.isActive) {
                        val c = ss.accept()
                        scope.launch { handle(c) }
                    }
                }
            } catch (e: Exception) {
                delay(2000)
            }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = 60_000
                val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
                val len = inp.readInt()
                if (len <= 0 || len > 64 * 1024) return
                val hb = ByteArray(len)
                inp.readFully(hb)
                val h = JSONObject(String(hb, Charsets.UTF_8))

                val from = h.getString("from")
                val fromName = h.optString("fromName")
                Repo.updatePeer(from, fromName, s.inetAddress.hostAddress)
                val chat = if (h.optString("chat") == GROUP) GROUP else from

                var m = Msg(
                    id = h.getString("id"), chat = chat, fromId = from, fromName = fromName,
                    mine = false, time = h.optLong("time", System.currentTimeMillis()),
                )
                m = if (h.getString("type") == "file") {
                    val name = h.getString("fileName").substringAfterLast('/')
                    val size = h.getLong("size")
                    val mime = h.optString("mime").ifBlank { "application/octet-stream" }
                    val uri = saveDownload(name, mime, size, inp)
                    m.copy(fileName = name, fileUri = uri.toString(), mime = mime, size = size)
                } else {
                    m.copy(text = h.optString("text"))
                }
                DataOutputStream(s.getOutputStream()).run { writeByte(1); flush() }
                if (Repo.add(m)) Notif.message(this, m)
            }
        } catch (e: Exception) {
            Log.w(TAG, "mesaj alınamadı", e)
        }
    }

    /** Gelen dosyayı İndirilenler/EvSohbet klasörüne kaydeder. */
    private fun saveDownload(name: String, mime: String, size: Long, inp: InputStream): Uri {
        val cr = contentResolver
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/EvSohbet")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IOException("kaydedilemedi")
        try {
            (cr.openOutputStream(uri) ?: throw IOException("yazılamadı")).use { copyN(inp, it, size) }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
    }
}

object Notif {
    const val CH_SVC = "svc"
    const val CH_MSG = "msg"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SVC, "Arka plan hizmeti", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Mesajlar", NotificationManager.IMPORTANCE_HIGH))
    }

    fun openApp(ctx: Context, chat: String?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (chat != null) i.putExtra(EXTRA_CHAT, chat)
        return PendingIntent.getActivity(
            ctx, chat?.hashCode() ?: 0, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    @SuppressLint("MissingPermission")
    fun message(ctx: Context, m: Msg) {
        if (Repo.visible && Repo.activeChat == m.chat) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val title = if (m.chat == GROUP) "🏠 Ev Grubu · ${m.fromName}" else m.fromName
        val body = m.text ?: "📎 ${m.fileName}"
        val n = NotificationCompat.Builder(ctx, CH_MSG)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openApp(ctx, m.chat))
            .build()
        NotificationManagerCompat.from(ctx).notify(m.chat.hashCode(), n)
    }
}
