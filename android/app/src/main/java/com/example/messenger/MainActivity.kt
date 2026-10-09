package com.example.messenger

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import java.net.Socket

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var log: TextView
    private lateinit var ipField: EditText
    private lateinit var nameField: EditText
    private lateinit var msgField: EditText
    @Volatile private var out: java.io.OutputStream? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        ipField = EditText(this).apply { setText("127.0.0.1") }
        nameField = EditText(this).apply { hint = "Имя" }
        val connectBtn = Button(this).apply { text = "Подключиться" }
        log = TextView(this)
        val scroll = ScrollView(this).apply { addView(log); layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        msgField = EditText(this).apply { hint = "Сообщение..." }
        val sendBtn = Button(this).apply { text = "Отправить" }
        listOf(ipField, nameField, connectBtn, scroll, msgField, sendBtn).forEach { root.addView(it) }
        setContentView(root)

        connectBtn.setOnClickListener {
            val ip = ipField.text.toString()
            val name = nameField.text.toString().ifEmpty { "Аноним" }
            Thread {
                try {
                    val s = Socket(ip, 9999)
                    out = s.getOutputStream()
                    out!!.write(name.toByteArray()); out!!.flush()
                    handler.post { log.append("=== подключено ===\n") }
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = s.getInputStream().read(buf)
                        if (n <= 0) break
                        val text = String(buf, 0, n)
                        handler.post { log.append(text) }
                    }
                    handler.post { log.append("=== оффлайн ===\n") }
                } catch (e: Exception) {
                    handler.post { log.append("=== ошибка: " + e.message + " ===\n") }
                }
            }.start()
        }
        sendBtn.setOnClickListener {
            val t = msgField.text.toString()
            val o = out
            if (t.isNotEmpty() && o != null) {
                Thread { try { o.write(t.toByteArray()); o.flush() } catch (_: Exception) {} }.start()
                msgField.setText("")
            }
        }
    }
}
