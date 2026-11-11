package com.evsohbet

import android.content.Context
import android.content.SharedPreferences
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.util.UUID

const val GROUP = "group"
const val EXTRA_CHAT = "chat"

data class Peer(val id: String, val name: String, val ip: String?, val lastSeen: Long) {
    fun isOnline(now: Long) = ip != null && now - lastSeen < 12_000
}

data class Msg(
    val id: String,
    val chat: String,          // GROUP veya karşı tarafın id'si
    val fromId: String,
    val fromName: String,
    val mine: Boolean,
    val time: Long,
    val text: String? = null,
    val fileName: String? = null,
    val fileUri: String? = null,
    val mime: String? = null,
    val size: Long = 0,
    val status: String = "ok",  // ok | sending | failed
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("chat", chat).put("fromId", fromId).put("fromName", fromName)
        .put("mine", mine).put("time", time).put("text", text).put("fileName", fileName)
        .put("fileUri", fileUri).put("mime", mime).put("size", size).put("status", status)

    companion object {
        private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
        fun fromJson(o: JSONObject) = Msg(
            id = o.getString("id"), chat = o.getString("chat"),
            fromId = o.getString("fromId"), fromName = o.optString("fromName"),
            mine = o.optBoolean("mine"), time = o.optLong("time"),
            text = o.str("text"), fileName = o.str("fileName"), fileUri = o.str("fileUri"),
            mime = o.str("mime"), size = o.optLong("size"), status = o.optString("status", "ok"),
        )
    }
}

/** Uygulamanın tüm durumu: kimlik, kişiler, mesajlar. */
object Repo {
    private lateinit var ctx: Context
    private lateinit var prefs: SharedPreferences
    private var inited = false
    private val lock = Any()

    lateinit var myId: String
        private set
    val myName = MutableStateFlow<String?>(null)
    val peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val messages = MutableStateFlow<List<Msg>>(emptyList())
    val unread = MutableStateFlow<Map<String, Int>>(emptyMap())

    @Volatile var activeChat: String? = null
    @Volatile var visible = false

    private val file get() = File(ctx.filesDir, "messages.jsonl")

    @Synchronized
    fun init(c: Context) {
        if (inited) return
        inited = true
        ctx = c.applicationContext
        prefs = ctx.getSharedPreferences("evsohbet", Context.MODE_PRIVATE)
        myId = prefs.getString("id", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
        myName.value = prefs.getString("name", null)

        prefs.getString("peers", null)?.let { s ->
            val o = JSONObject(s)
            peers.value = o.keys().asSequence().associateWith { Peer(it, o.getString(it), null, 0) }
        }
        if (file.exists()) {
            messages.value = file.readLines().mapNotNull { line ->
                runCatching { Msg.fromJson(JSONObject(line)) }.getOrNull()
                    ?.let { if (it.status == "sending") it.copy(status = "failed") else it }
            }
        }
    }

    fun setName(n: String) {
        prefs.edit().putString("name", n).apply()
        myName.value = n
    }

    fun updatePeer(id: String, name: String, ip: String?) {
        if (id.isBlank() || id == myId) return
        val old = peers.value[id]
        val newName = name.ifBlank { old?.name ?: "?" }
        peers.update { it + (id to Peer(id, newName, ip, System.currentTimeMillis())) }
        if (old?.name != newName) savePeers()
    }

    private fun savePeers() {
        val o = JSONObject()
        peers.value.values.forEach { o.put(it.id, it.name) }
        prefs.edit().putString("peers", o.toString()).apply()
    }

    /** Mesajı ekler; aynı mesaj daha önce geldiyse false döner. */
    fun add(m: Msg): Boolean {
        synchronized(lock) {
            if (messages.value.any { it.id == m.id && it.mine == m.mine }) return false
            messages.update { it + m }
            file.appendText(m.toJson().toString() + "\n")
        }
        if (!m.mine && !(visible && activeChat == m.chat)) {
            unread.update { it + (m.chat to ((it[m.chat] ?: 0) + 1)) }
        }
        return true
    }

    fun setStatus(id: String, status: String) {
        synchronized(lock) {
            messages.update { list -> list.map { if (it.id == id && it.mine) it.copy(status = status) else it } }
            file.writeText(messages.value.joinToString("") { it.toJson().toString() + "\n" })
        }
    }

    fun clearUnread(chat: String) {
        unread.update { it - chat }
        NotificationManagerCompat.from(ctx).cancel(chat.hashCode())
    }
}
