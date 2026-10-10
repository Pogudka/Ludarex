package com.example.messenger

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.View
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
    fun curHour() = System.currentTimeMillis() / 1000 / 3600
    fun keyFor(pass: String, hour: Long): SecretKeySpec {
        val md = MessageDigest.getInstance("SHA-256")
        return SecretKeySpec(md.digest("$pass:$hour".toByteArray()), "AES")
    }
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun enc(pass: String, text: String): String {
        val hour = curHour()
        val nonce = ByteArray(12)
        SecureRandom().nextBytes(nonce)
        nonce[0] = (hour shr 24).toByte()
        nonce[1] = (hour shr 16).toByte()
        nonce[2] = (hour shr 8).toByte()
        nonce[3] = hour.toByte()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keyFor(pass, hour), GCMParameterSpec(128, nonce))
        return hex(nonce.plus(c.doFinal(text.toByteArray())))
    }
    fun dec(pass: String, s: String): String? = try {
        val d = unhex(s)
        if (d.size < 28) null else {
            val hour = ((d[0].toLong() and 255) shl 24) or ((d[1].toLong() and 255) shl 16) or
                       ((d[2].toLong() and 255) shl 8) or (d[3].toLong() and 255)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, keyFor(pass, hour), GCMParameterSpec(128, d.copyOfRange(0, 12)))
            String(c.doFinal(d.copyOfRange(12, d.size)))
        }
    } catch (e: Exception) { null }
}

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var chatBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var ipField: EditText
    private lateinit var nameField: EditText
    private lateinit var keyField: EditText
    private lateinit var msgField: EditText
    @Volatile private var out: java.io.OutputStream? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var myPass = ""
    private val buf = StringBuilder()
    private val PICK_IMAGE = 1001

    private fun now() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun name() = nameField.text.toString().ifEmpty { "Аноним" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        ipField = EditText(this).apply { setText("127.0.0.1") }
        nameField = EditText(this).apply { hint = "Имя" }
        keyField = EditText(this).apply { hint = "Ключ (придёт с сервера)"; isEnabled = false }
        val connectBtn = Button(this).apply { text = "Подключиться" }
        chatBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            addView(chatBox)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        msgField = EditText(this).apply { hint = "Сообщение..." }
        val imgBtn = Button(this).apply { text = "📷 Фото" }
        val sendBtn = Button(this).apply { text = "Отправить" }
        listOf(ipField, nameField, keyField, connectBtn, scroll, msgField, imgBtn, sendBtn).forEach { root.addView(it) }
        setContentView(root)

        imgBtn.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI), PICK_IMAGE)
        }
        connectBtn.setOnClickListener {
            val ip = ipField.text.toString()
            val n = name()
            handler.post { chatBox.removeAllViews() }
            buf.setLength(0)
            Thread {
                try {
                    val s = Socket(ip, 9999)
                    socket = s
                    val ins = s.getInputStream()
                    out = s.getOutputStream()
                    val sb = StringBuilder()
                    while (true) {
                        val b = ins.read()
                        if (b == -1 || b == '\n'.code) break
                        sb.append(b.toChar())
                    }
                    myPass = sb.toString()
                    handler.post { keyField.setText(myPass) }
                    addTextLine("=== подключено, ключ получен ===")
                    sendLine(Crypto.enc(myPass, "=== $n зашёл в чат ==="))
                    val b = ByteArray(8192)
                    while (true) {
                        val nn = ins.read(b)
                        if (nn <= 0) break
                        handler.post { addText(String(b, 0, nn)) }
                    }
                    out = null; socket = null
                    addTextLine("=== оффлайн ===")
                } catch (e: Exception) {
                    out = null; socket = null
                    addTextLine("=== ошибка: " + e.message + " ===")
                }
            }.start()
        }
        sendBtn.setOnClickListener {
            val t = msgField.text.toString()
            if (t.isEmpty()) return@setOnClickListener
            if (t == "/exit") {
                try { socket?.close() } catch (_: Exception) {}
                out = null; socket = null
                addTextLine("=== ты вышел из чата ===")
                msgField.setText("")
                return@setOnClickListener
            }
            if (out == null) {
                addTextLine("=== нет подключения: нажми Подключиться ===")
                return@setOnClickListener
            }
            if (t.startsWith("/") || myPass.isEmpty()) sendLine(t)
            else sendLine(Crypto.enc(myPass, "[${now()}] [${name()}] $t"))
            msgField.setText("")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && data?.data != null) {
            try {
                val bmp = MediaStore.Images.Media.getBitmap(contentResolver, data.data!!)
                val maxW = 320
                val scaled = if (bmp.width > maxW)
                    Bitmap.createScaledBitmap(bmp, maxW, maxW * bmp.height / bmp.width, true) else bmp
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                val payload = "[IMG:$b64]"
                if (out == null) { addTextLine("=== нет подключения ==="); return }
                if (myPass.isEmpty()) sendLine(payload)
                else sendLine(Crypto.enc(myPass, "[${now()}] [${name()}] $payload"))
            } catch (e: Exception) {
                addTextLine("=== ошибка загрузки фото ===")
            }
        }
    }

    private fun sendLine(payload: String) {
        val o = out ?: return
        Thread { try { o.write((payload + "\n").toByteArray()); o.flush() } catch (_: Exception) {} }.start()
    }

    private fun addTextLine(text: String) {
        handler.post {
            chatBox.addView(TextView(this).apply {
                this.text = text
                setPadding(0, 4, 0, 4)
            })
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun addImageView(b64: String) {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        chatBox.addView(ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(dp(240), -2).apply { bottomMargin = 8 }
        })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addText(chunk: String) {
        buf.append(chunk)
        var i = buf.indexOf("\n")
        while (i >= 0) {
            val line = buf.substring(0, i)
            buf.delete(0, i + 1)
            render(line)
            i = buf.indexOf("\n")
        }
    }

    private fun render(line: String) {
        if (line.startsWith("===")) { addTextLine(line); return }
        val d = Crypto.dec(myPass, line.trim())
        if (d == null) {
            addTextLine("🔒 не читается (len=" + line.length + ") " + line.take(24))
            return
        }
        val m = Regex("\\[IMG:([^\\]]+)\\]").find(d)
        if (m != null) addImageView(m.groupValues[1])
        else addTextLine(d)
    }
}
