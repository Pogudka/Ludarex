package com.example.messenger

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.widget.*
import java.io.ByteArrayOutputStream
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
    @Volatile private var socket: Socket? = null
    private val buf = StringBuilder()
    private var myName = "Аноним"
    private var myPass = ""
    private val PICK_IMAGE = 1001

    private fun now() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        ipField = EditText(this).apply { setText("127.0.0.1") }
        nameField = EditText(this).apply { hint = "Имя" }
        keyField = EditText(this).apply { hint = "Секретный ключ" }
        val genBtn = Button(this).apply { text = "🎲 Сгенерировать ключ" }
        val connectBtn = Button(this).apply { text = "Подключиться" }
        log = TextView(this)
        val scroll = ScrollView(this).apply { addView(log); layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        msgField = EditText(this).apply { hint = "Сообщение..." }
        val imgBtn = Button(this).apply { text = "📷 Фото" }
        val sendBtn = Button(this).apply { text = "Отправить" }
        listOf(ipField, nameField, keyField, genBtn, connectBtn, scroll, msgField, imgBtn, sendBtn).forEach { root.addView(it) }
        setContentView(root)

        genBtn.setOnClickListener {
            val b = ByteArray(16); SecureRandom().nextBytes(b)
            keyField.setText(Crypto.hex(b))
        }

        imgBtn.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, PICK_IMAGE)
        }

        connectBtn.setOnClickListener {
            val ip = ipField.text.toString()
            myName = nameField.text.toString().ifEmpty { "Аноним" }
            myPass = keyField.text.toString()
            Thread {
                try {
                    val s = Socket(ip, 9999)
                    socket = s
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
            if (t == "/exit") {
                try { socket?.close() } catch (_: Exception) {}
                out = null; socket = null
                log.append("=== ты вышел из чата ===\n")
                msgField.setText("")
                return@setOnClickListener
            }
            if (t.startsWith("/") || myPass.isEmpty()) sendLine(t)
            else sendLine(Crypto.enc(myPass, "[${now()}] [$myName] $t"))
            msgField.setText("")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            try {
                val bmp = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                val scaled = Bitmap.createScaledBitmap(bmp, 400, 400 * bmp.height / bmp.width, true)
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                val payload = "[IMG:$b64]"
                if (myPass.isEmpty()) sendLine(payload)
                else sendLine(Crypto.enc(myPass, "[${now()}] [$myName] $payload"))
                handler.post { log.append("[отправил фото]\n") }
            } catch (e: Exception) {
                handler.post { log.append("=== ошибка загрузки фото ===\n") }
            }
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
        val d = Crypto.dec(myPass, line.trim()) ?: return line
        val imgMatch = Regex("\\[IMG:([^\\]]+)\\]").find(d)
        if (imgMatch != null) {
            val b64 = imgMatch.groupValues[1]
            val html = "<html><body><img src='data:image/jpeg;base64,$b64' style='max-width:100%'/></body></html>"
            val tmp = java.io.File.createTempFile("img_", ".html", cacheDir)
            tmp.writeText(html)
            handler.post {
                val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(tmp))
                startActivity(intent)
            }
            return "[📷 фото - нажми для просмотра]"
        }
        return d
    }
}
