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
import java.math.BigInteger
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val MASTER_KEY = "ludarex-babylon-2026"

object Crypto {
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    fun groupKey(): ByteArray = sha256(MASTER_KEY.toByteArray())
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun enc(key: ByteArray, text: String): String {
        val nonce = ByteArray(12); SecureRandom().nextBytes(nonce)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return hex(nonce.plus(c.doFinal(text.toByteArray())))
    }
    fun dec(key: ByteArray, s: String): String? = try {
        val d = unhex(s)
        if (d.size < 28) null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, d.copyOfRange(0, 12)))
            String(c.doFinal(d.copyOfRange(12, d.size)))
        }
    } catch (e: Exception) { null }
    fun fingerprint(key: ByteArray): String = hex(sha256(key).copyOfRange(0, 4))
    fun safePrime(bits: Int): BigInteger {
        val r = SecureRandom()
        while (true) {
            val q = BigInteger(bits - 1, r).setBit(bits - 2).setBit(0).nextProbablePrime()
            val p = q.shiftLeft(1).add(BigInteger.ONE)
            if (p.bitLength() == bits && p.isProbablePrime(64)) return p
        }
    }
}

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var chatBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var ipField: EditText
    private lateinit var nameField: EditText
    private lateinit var peerField: EditText
    private lateinit var msgField: EditText
    @Volatile private var out: java.io.OutputStream? = null
    @Volatile private var socket: Socket? = null
    private val buf = StringBuilder()
    private val sessionKeys = mutableMapOf<String, ByteArray>()
    private val pendingA = mutableMapOf<String, BigInteger>()
    private val pendingP = mutableMapOf<String, BigInteger>()
    private var currentPeer = ""
    private val PICK_IMAGE = 1001

    private fun now() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun me() = nameField.text.toString().ifEmpty { "Аноним" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        ipField = EditText(this).apply { setText("127.0.0.1") }
        nameField = EditText(this).apply { hint = "Имя" }
        peerField = EditText(this).apply { hint = "Собеседник (для secret)" }
        val connectBtn = Button(this).apply { text = "Подключиться" }
        val secretBtn = Button(this).apply { text = "🔐 Secret chat" }
        chatBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            addView(chatBox)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        msgField = EditText(this).apply { hint = "Сообщение... (/reset ИМЯ)" }
        val imgBtn = Button(this).apply { text = "📷 Фото" }
        val sendBtn = Button(this).apply { text = "Отправить" }
        listOf(ipField, nameField, peerField, connectBtn, secretBtn, scroll, msgField, imgBtn, sendBtn).forEach { root.addView(it) }
        setContentView(root)

        secretBtn.setOnClickListener {
            val peer = peerField.text.toString()
            if (peer.isEmpty()) { addTextLine("укажи собеседника"); return@setOnClickListener }
            if (sessionKeys.containsKey(peer)) {
                currentPeer = peer
                addTextLine("сессия с $peer уже активна, отпечаток: ${Crypto.fingerprint(sessionKeys[peer]!!)}")
                return@setOnClickListener
            }
            currentPeer = peer
            Thread {
                val p = Crypto.safePrime(1024)
                val g = BigInteger.valueOf(2)
                val a = BigInteger(256, SecureRandom())
                val A = g.modPow(a, p)
                pendingA[peer] = a
                pendingP[peer] = p
                sendLine("@$peer DHREQ ${me()} ${p.toString(16)} ${g.toString(16)} ${A.toString(16)}")
                addTextLine("предложение ключа отправлено $peer...")
            }.start()
        }
        imgBtn.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI), PICK_IMAGE)
        }
        connectBtn.setOnClickListener {
            val ip = ipField.text.toString()
            val n = me()
            handler.post { chatBox.removeAllViews() }
            buf.setLength(0)
            sessionKeys.clear(); pendingA.clear(); pendingP.clear(); currentPeer = ""
            Thread {
                try {
                    val s = Socket(ip, 9999)
                    socket = s
                    out = s.getOutputStream()
                    sendLine("REG $n")
                    addTextLine("=== подключено ===")
                    val b = ByteArray(8192)
                    while (true) {
                        val nn = s.getInputStream().read(b)
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
            if (t.rfind("/reset ", 0) == 0) {
                val peer = t.substring(7)
                sessionKeys.remove(peer); pendingA.remove(peer); pendingP.remove(peer)
                if (currentPeer == peer) currentPeer = ""
                addTextLine("сессия с $peer сброшена")
                msgField.setText("")
                return@setOnClickListener
            }
            if (out == null) { addTextLine("=== нет подключения ==="); return@setOnClickListener }
            val body = "[${now()}] [${me()}] $t"
            dispatch(body)
            msgField.setText("")
        }
    }

    private fun dispatch(body: String, b64: String? = null) {
        val payload = if (b64 != null) body + "[IMG:$b64]" else body
        val isDm = currentPeer.isNotEmpty() && sessionKeys.containsKey(currentPeer)
        if (isDm) {
            val ct = Crypto.enc(sessionKeys[currentPeer]!!, payload)
            sendLine("@$currentPeer DM ${me()} $ct")
            if (b64 != null) handler.post { addImageView(b64) } else addTextLine(payload)
        } else {
            sendLine(Crypto.enc(Crypto.groupKey(), payload))
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
                if (out == null) { addTextLine("=== нет подключения ==="); return }
                dispatch("[${now()}] [${me()}] ", b64)
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
        if (line.isEmpty()) return
        val parts = line.split(" ")
        when (parts[0]) {
            "DHREQ" -> {
                if (parts.size < 5) return
                val from = parts[1]
                val p = BigInteger(parts[2], 16)
                val g = BigInteger(parts[3], 16)
                val A = BigInteger(parts[4], 16)
                Thread {
                    val b = BigInteger(256, SecureRandom())
                    val B = g.modPow(b, p)
                    val shared = A.modPow(b, p)
                    val key = Crypto.sha256(shared.toString(16).toByteArray())
                    sessionKeys[from] = key
                    sendLine("@$from DHRES ${me()} ${B.toString(16)}")
                    addTextLine("🔐 ключ с $from, отпечаток: ${Crypto.fingerprint(key)}")
                }.start()
            }
            "DHRES" -> {
                if (parts.size < 3) return
                val from = parts[1]
                val B = BigInteger(parts[2], 16)
                val a = pendingA[from] ?: return
                val p = pendingP[from] ?: return
                val shared = B.modPow(a, p)
                val key = Crypto.sha256(shared.toString(16).toByteArray())
                sessionKeys[from] = key
                pendingA.remove(from); pendingP.remove(from)
                addTextLine("🔐 ключ с $from, отпечаток: ${Crypto.fingerprint(key)}")
            }
            "DM" -> {
                if (parts.size < 3) return
                val from = parts[1]
                val ct = parts[2]
                val key = sessionKeys[from]
                if (key == null) { addTextLine("🔒 нет ключа с $from"); return }
                val d = Crypto.dec(key, ct)
                if (d == null) addTextLine("🔒 не читается от $from")
                else showContent(d)
            }
            "===" -> addTextLine(line)
            else -> {
                val d = Crypto.dec(Crypto.groupKey(), line)
                if (d == null) addTextLine("🔒 не читается (len=" + line.length + ")")
                else showContent(d)
            }
        }
    }

    private fun showContent(d: String) {
        val m = Regex("\\[IMG:([^\\]]+)\\]").find(d)
        if (m != null) {
            addTextLine(d.substring(0, m.range.first))
            addImageView(m.groupValues[1])
        } else addTextLine(d)
    }
}
