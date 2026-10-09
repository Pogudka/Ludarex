package com.example.messenger

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.security.SecureRandom

object Crypto {
    fun key(pass: String): SecretKeySpec {
        val md = MessageDigest.getInstance("SHA-256")
        return SecretKeySpec(md.digest(pass.toByteArray()), "AES")
    }
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun enc(pass: String, text: String): String {
        val nonce = ByteArray(12); SecureRandom().nextBytes(nonce)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(pass), GCMParameterSpec(128, nonce))
        return hex(nonce.plus(c.doFinal(text.toByteArray())))
    }
    fun dec(pass: String, s: String): String? = try {
        val d = unhex(s)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(pass), GCMParameterSpec(128, d.copyOfRange(0, 12)))
        String(c.doFinal(d.copyOfRange(12, d.size)))
    } catch (e: Exception) { null }
}

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var log: TextView
    private lateinit var ipField: EditText
    private lateinit var nameField: EditText
    private lateinit var keyField: EditText
    private lateinit var msgField: EditText
    @Volatile private var out: java.io.OutputStream? = null
    private val buf = StringBuilder()
    private var myName = "Аноним"
    private var myPass = ""

    private fun now() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        ipField = EditText(this).apply { setText("127.0.0.1") }
        nameField = EditText(this).apply { hint = "Имя" }
        keyField = EditText(this).apply { hint = "Секретный ключ" }
        val connectBtn = Button(this).apply { text = "Подключиться" }
        log = TextView(this)
        val scroll = ScrollView(this).apply { addView(log); layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        msgField = EditText(this).apply { hint = "Сообщение..." }
        val sendBtn = Button(this).apply { text = "Отправить" }
        listOf(ipField, nameField, keyField, connectBtn, scroll, msgField, sendBtn).forEach { root.addView(it) }
        setContentView(root)

        connectBtn.setOnClickListener {
            val ip = ipField.text.toString()
            myName = nameField.text.toString().ifEmpty { "Аноним" }
            myPass = keyField.text.toString()
            Thread {
                try {
                    val s = Socket(ip, 9999)
                    out = s.getOutputStream()
                    handler.post { log.append("=== подключено ===\n") }
                    val join = "=== $myName зашёл в чат ==="
                    sendLine(if (myPass.isEmpty()) join else Crypto.enc(myPass, join))
                    val b = ByteArray(8192)
                    while (true) {
                        val n = s.getInputStream().read(b)
                        if (n <= 0) break
                        handler.post { addText(String(b, 0, n)) }
                    }
                    handler.post { log.append("=== оффлайн ===\n") }
                } catch (e: Exception) {
                    handler.post { log.append("=== ошибка: " + e.message + " ===\n") }
                }
            }.start()
        }
        sendBtn.setOnClickListener {
            val t = msgField.text.toString()
            if (t.isEmpty()) return@setOnClickListener
            if (t.startsWith("/") || myPass.isEmpty()) {
                sendLine(t)
            } else {
                sendLine(Crypto.enc(myPass, "[${now()}] [$myName] $t"))
            }
            msgField.setText("")
        }
    }

    private fun sendLine(payload: String) {
        val o = out ?: return
        Thread { try { o.write(payload.toByteArray()); o.flush() } catch (_: Exception) {} }.start()
    }

    private fun addText(chunk: String) {
        buf.append(chunk)
        var i = buf.indexOf("\n")
        while (i >= 0) {
            val line = buf.substring(0, i)
            buf.delete(0, i + 1)
            log.append(render(line) + "\n")
            i = buf.indexOf("\n")
        }
    }

    private fun render(line: String): String {
        if (line.startsWith("===")) return line
        val d = Crypto.dec(myPass, line.trim())
        return d ?: line
    }
}
