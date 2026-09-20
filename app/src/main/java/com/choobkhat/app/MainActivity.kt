package com.choobkhat.app

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var messagesList: RecyclerView
    private lateinit var inputText: EditText
    private lateinit var sendButton: android.widget.Button
    private lateinit var statusDot: TextView
    private lateinit var settingsButton: android.widget.ImageButton

    private val messages = mutableListOf<Message>()
    private lateinit var adapter: MessageAdapter
    private lateinit var prefs: android.content.SharedPreferences

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("choobkhat", Context.MODE_PRIVATE)

        messagesList = findViewById(R.id.messagesList)
        inputText = findViewById(R.id.inputText)
        sendButton = findViewById(R.id.sendButton)
        statusDot = findViewById(R.id.statusDot)
        settingsButton = findViewById(R.id.settingsButton)

        adapter = MessageAdapter(messages)
        messagesList.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        messagesList.adapter = adapter

        // پیام خوش‌آمد
        addMessage(getString(R.string.welcome), false)

        sendButton.setOnClickListener { sendMessage() }
        settingsButton.setOnClickListener { showSettings() }

        checkServer()
    }

    private fun serverUrl(): String {
        return prefs.getString("server_url", "http://192.168.1.39:8080") ?: "http://192.168.1.39:8080"
    }

    private fun checkServer() {
        val url = serverUrl().trimEnd('/') + "/health/"
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    withContext(Dispatchers.Main) {
                        if (resp.isSuccessful) {
                            statusDot.setBackgroundResource(R.drawable.dot_green)
                        } else {
                            statusDot.setBackgroundResource(R.drawable.dot_red)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusDot.setBackgroundResource(R.drawable.dot_red)
                }
            }
        }
    }

    private fun sendMessage() {
        val text = inputText.text.toString().trim()
        if (text.isEmpty()) return
        inputText.setText("")
        addMessage(text, true)
        statusDot.setBackgroundResource(R.drawable.dot_red)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reply = callServer(text)
                withContext(Dispatchers.Main) {
                    addMessage(reply, false)
                    statusDot.setBackgroundResource(R.drawable.dot_green)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addMessage("خطا: ${e.message}", false)
                }
            }
        }
    }

    private fun callServer(message: String): String {
        val url = serverUrl().trimEnd('/') + "/v1/chat/completions"

        val messagesArr = JSONArray()
        val userMsg = JSONObject()
        userMsg.put("role", "user")
        userMsg.put("content", message)
        messagesArr.put(userMsg)

        val payload = JSONObject()
        payload.put("model", "choobkhat")
        payload.put("messages", messagesArr)

        val body = payload.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val req = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw Exception("HTTP ${resp.code}: ${respBody.take(200)}")
            }
            val json = JSONObject(respBody)
            val choices = json.getJSONArray("choices")
            val first = choices.getJSONObject(0)
            val msgObj = first.getJSONObject("message")
            return msgObj.getString("content")
        }
    }

    private fun addMessage(text: String, isUser: Boolean) {
        messages.add(Message(text, isUser))
        adapter.notifyItemInserted(messages.size - 1)
        messagesList.scrollToPosition(messages.size - 1)
    }

    private fun showSettings() {
        val input = EditText(this)
        input.setText(serverUrl())
        input.inputType = InputType.TYPE_TEXT_VARIATION_URI
        input.setPadding(32, 32, 32, 32)

        AlertDialog.Builder(this)
            .setTitle(R.string.server_address)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newUrl = input.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    prefs.edit().putString("server_url", newUrl).apply()
                    checkServer()
                    Toast.makeText(this, "ذخیره شد", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

// ========== مدل پیام ==========
data class Message(val text: String, val isUser: Boolean)

// ========== Adapter ==========
class MessageAdapter(private val items: List<Message>) :
    RecyclerView.Adapter<MessageAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val bubble: TextView = view.findViewById(R.id.bubble)
        val container: LinearLayout = view as LinearLayout
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = items[position]
        holder.bubble.text = msg.text

        val lp = holder.bubble.layoutParams as LinearLayout.LayoutParams
        if (msg.isUser) {
            holder.container.gravity = Gravity.END
            holder.bubble.setBackgroundResource(R.drawable.bubble_out)
            holder.bubble.setTextColor(Color.WHITE)
        } else {
            holder.container.gravity = Gravity.START
            holder.bubble.setBackgroundResource(R.drawable.bubble_in)
            holder.bubble.setTextColor(Color.parseColor("#EAEAEA"))
        }
        holder.bubble.layoutParams = lp
    }

    override fun getItemCount() = items.size
}
