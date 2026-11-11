package com.evsohbet

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

const val TAG = "EvSohbet"

/**
 * Gönderme tarafı.
 * Protokol (TCP): [int başlık uzunluğu][JSON başlık][dosyaysa 'size' bayt veri] -> karşı taraf 1 baytlık onay yollar.
 */
object Net {
    const val DISC_PORT = 45454   // UDP: cihaz keşfi
    const val TCP_PORT = 45455    // TCP: mesaj ve dosya

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun newMsg(chat: String) = Msg(
        id = UUID.randomUUID().toString(), chat = chat, fromId = Repo.myId,
        fromName = Repo.myName.value ?: "", mine = true,
        time = System.currentTimeMillis(), status = "sending",
    )

    fun sendText(chat: String, text: String) {
        val m = newMsg(chat).copy(text = text)
        Repo.add(m)
        scope.launch { deliver(null, m, null) }
    }

    fun sendFile(ctx: Context, chat: String, uri: Uri) {
        scope.launch {
            try {
                val cr = ctx.contentResolver
                var name = "dosya"
                var size = -1L
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        if (!c.isNull(0)) name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
                val mime = cr.getType(uri) ?: "application/octet-stream"
                var src = uri
                if (size < 0) {
                    // Boyutu bilinmiyorsa önce geçici dosyaya kopyala
                    val tmp = File(ctx.cacheDir, UUID.randomUUID().toString())
                    (cr.openInputStream(uri) ?: throw IOException("açılamadı")).use { i ->
                        tmp.outputStream().use { i.copyTo(it) }
                    }
                    size = tmp.length()
                    src = Uri.fromFile(tmp)
                }
                val m = newMsg(chat).copy(fileName = name, fileUri = uri.toString(), mime = mime, size = size)
                Repo.add(m)
                deliver(ctx, m, src)
            } catch (e: Exception) {
                Log.w(TAG, "dosya hazırlanamadı", e)
            }
        }
    }

    fun retry(ctx: Context, m: Msg) {
        if (m.status != "failed") return
        Repo.setStatus(m.id, "sending")
        scope.launch { deliver(ctx, m, m.fileUri?.let { Uri.parse(it) }) }
    }

    private fun deliver(ctx: Context?, m: Msg, src: Uri?) {
        val now = System.currentTimeMillis()
        val peers = Repo.peers.value
        val targets = if (m.chat == GROUP) peers.values.filter { it.isOnline(now) }
        else listOfNotNull(peers[m.chat]?.takeIf { it.ip != null })

        val h = JSONObject()
            .put("id", m.id).put("from", Repo.myId).put("fromName", Repo.myName.value ?: "")
            .put("chat", if (m.chat == GROUP) GROUP else "direct").put("time", m.time)
        if (m.fileName != null) {
            h.put("type", "file").put("fileName", m.fileName).put("size", m.size).put("mime", m.mime)
        } else {
            h.put("type", "text").put("text", m.text)
        }

        var ok = targets.isNotEmpty()
        for (p in targets) {
            if (!sendTo(ctx, p.ip!!, h, src, m.size)) ok = false
        }
        Repo.setStatus(m.id, if (ok) "ok" else "failed")
    }

    private fun sendTo(ctx: Context?, ip: String, h: JSONObject, src: Uri?, size: Long): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, TCP_PORT), 4000)
            s.soTimeout = 60_000
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
            val hb = h.toString().toByteArray(Charsets.UTF_8)
            out.writeInt(hb.size)
            out.write(hb)
            if (src != null) {
                val input = ctx!!.contentResolver.openInputStream(src) ?: throw IOException("dosya açılamadı")
                input.use { copyN(it, out, size) }
            }
            out.flush()
            DataInputStream(s.getInputStream()).readByte() == 1.toByte()
        }
    } catch (e: Exception) {
        Log.w(TAG, "$ip adresine gönderilemedi", e)
        false
    }
}

fun copyN(inp: InputStream, out: OutputStream, n: Long) {
    val buf = ByteArray(64 * 1024)
    var left = n
    while (left > 0) {
        val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
        if (r < 0) throw EOFException()
        out.write(buf, 0, r)
        left -= r
    }
}
