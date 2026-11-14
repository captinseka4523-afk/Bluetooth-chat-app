package com.example.bluechat

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvFavorite: TextView
    private lateinit var btnListen: Button
    private lateinit var btnConnect: Button
    private lateinit var btnSendText: Button
    private lateinit var btnSendImage: Button
    private lateinit var btnRecordAudio: Button
    private lateinit var btnSendFile: Button
    private lateinit var etMessage: EditText
    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var connectedThread: ConnectedThread? = null
    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var audioFilePath: String = ""

    private lateinit var prefs: SharedPreferences
    private var favDeviceAddress: String? = null
    private var favDeviceName: String? = null
    private var isAutoReconnecting = false
    private val reconnectHandler = Handler(Looper.getMainLooper())

    // طابور الرسائل المعلقة التي ترسل تلقائياً فور اتصال الجهاز المفضل
    private data class QueuedPacket(val type: Byte, val fileName: String, val bytes: ByteArray)
    private val pendingQueue = ConcurrentLinkedQueue<QueuedPacket>()

    private val APP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val APP_NAME = "BlueChatPro"
    private val CHANNEL_ID = "bluechat_alerts"

    companion object {
        private const val REQ_PERMISSIONS = 100
        private const val REQ_PICK_IMAGE = 101
        private const val REQ_PICK_FILE = 102

        private const val TYPE_TEXT: Byte = 1
        private const val TYPE_IMAGE: Byte = 2
        private const val TYPE_AUDIO: Byte = 3
        private const val TYPE_FILE: Byte = 4
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("BlueChatPrefs", Context.MODE_PRIVATE)
        favDeviceAddress = prefs.getString("fav_address", null)
        favDeviceName = prefs.getString("fav_name", null)

        createNotificationChannel()
        setupProgrammaticUI()
        updateFavoriteLabel()

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "البلوتوث غير مدعوم على هذا الجهاز", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        requestAppPermissions()
        setupListeners()
    }

    private fun setupProgrammaticUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(24, 24, 24, 24)
        }

        val topCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedBg("#1E293B")
            setPadding(24, 20, 24, 20)
        }

        tvStatus = TextView(this).apply {
            text = "الحالة: غير متصل"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 15f
            gravity = Gravity.CENTER
        }

        tvFavorite = TextView(this).apply {
            text = "⭐ الجهاز المفضل: لم يتم التحديد بعد"
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 14)
        }

        val connButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        btnListen = Button(this).apply {
            text = "فتح غرفة (هاتف 1)"
            setTextColor(Color.WHITE)
            background = createRoundedBg("#0284C7")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 10
            }
        }

        btnConnect = Button(this).apply {
            text = "اتصال / مفضل (هاتف 2)"
            setTextColor(Color.WHITE)
            background = createRoundedBg("#10B981")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 10
            }
        }

        connButtonsRow.addView(btnListen)
        connButtonsRow.addView(btnConnect)
        topCard.addView(tvStatus)
        topCard.addView(tvFavorite)
        topCard.addView(connButtonsRow)
        root.addView(topCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 })

        scrollView = ScrollView(this).apply {
            background = createRoundedBg("#1E293B")
            setPadding(20, 20, 20, 20)
        }

        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(chatContainer)
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply { bottomMargin = 16 })

        val mediaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        btnSendImage = Button(this).apply {
            text = "📷 صورة"
            setTextColor(Color.WHITE)
            background = createRoundedBg("#8B5CF6")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
        }

        btnRecordAudio = Button(this).apply {
            text = "🎤 تسجيل صوت"
            setTextColor(Color.WHITE)
            background = createRoundedBg("#EC4899")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f).apply {
                marginEnd = 8
                marginStart = 8
            }
        }

        btnSendFile = Button(this).apply {
            text = "📎 ملف"
            setTextColor(Color.WHITE)
            background = createRoundedBg("#F59E0B")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8
            }
        }

        mediaRow.addView(btnSendImage)
        mediaRow.addView(btnRecordAudio)
        mediaRow.addView(btnSendFile)
        root.addView(mediaRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 14 })

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        etMessage = EditText(this).apply {
            hint = "اكتب رسالتك هنا..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            background = createRoundedBg("#334155")
            setPadding(28, 22, 28, 22)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 14
            }
        }

        btnSendText = Button(this).apply {
            text = "إرسال"
            setTextColor(Color.WHITE)
            background = createRoundedBg("#2563EB")
            setPadding(36, 22, 36, 22)
        }

        inputRow.addView(etMessage)
        inputRow.addView(btnSendText)
        root.addView(inputRow)

        setContentView(root)
    }

    private fun createRoundedBg(hexColor: String): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = 24f
            setColor(Color.parseColor(hexColor))
        }
    }

    private fun updateFavoriteLabel() {
        runOnUiThread {
            tvFavorite.text = if (favDeviceName != null) {
                "⭐ الجهاز المفضل: $favDeviceName (يدعم الإرسال بالانتظار)"
            } else {
                "⭐ الجهاز المفضل: اختر جهازاً من (اتصال) لحفظه كمفضل"
            }
        }
    }

    private fun setupListeners() {
        btnListen.setOnClickListener {
            if (requestAppPermissions()) startServer()
        }

        btnConnect.setOnClickListener {
            if (requestAppPermissions()) showPairedDevicesDialog()
        }

        btnSendText.setOnClickListener {
            val msg = etMessage.text.toString().trim()
            if (msg.isEmpty()) return@setOnClickListener
            val bytes = msg.toByteArray(Charsets.UTF_8)

            if (connectedThread != null) {
                connectedThread?.sendPacket(TYPE_TEXT, "text.txt", bytes)
                addTextBubble(msg, isSentByMe = true)
                etMessage.text.clear()
            } else if (favDeviceAddress != null) {
                // حفظ الرسالة في طابور الانتظار وتشغيل الرادار التلقائي
                pendingQueue.add(QueuedPacket(TYPE_TEXT, "text.txt", bytes))
                addTextBubble("$msg\n⏳ (معلقة - ستصل فور فتح $favDeviceName للبلوتوث)", isSentByMe = true)
                etMessage.text.clear()
                startAutoReconnect()
                promptWakeUpAlert(msg)
            } else {
                Toast.makeText(this, "اختر جهازاً مفضلاً أولاً من زر (اتصال)", Toast.LENGTH_LONG).show()
            }
        }

        btnSendImage.setOnClickListener {
            if (connectedThread != null || favDeviceAddress != null) {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
                startActivityForResult(Intent.createChooser(intent, "اختر صورة"), REQ_PICK_IMAGE)
            } else {
                Toast.makeText(this, "يرجى تحديد جهاز مفضل أو الاتصال أولاً", Toast.LENGTH_SHORT).show()
            }
        }

        btnSendFile.setOnClickListener {
            if (connectedThread != null || favDeviceAddress != null) {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                startActivityForResult(Intent.createChooser(intent, "اختر ملفاً"), REQ_PICK_FILE)
            } else {
                Toast.makeText(this, "يرجى تحديد جهاز مفضل أو الاتصال أولاً", Toast.LENGTH_SHORT).show()
            }
        }

        btnRecordAudio.setOnClickListener {
            if (connectedThread == null && favDeviceAddress == null) {
                Toast.makeText(this, "يرجى تحديد جهاز مفضل أو الاتصال أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMISSIONS)
                return@setOnClickListener
            }
            if (!isRecording) startAudioRecording() else stopAndSendAudio()
        }
    }

    // نافذة إرسال إشعار إيقاظ للطرف الآخر عندما يكون البلوتوث لديه مطفأ
    private fun promptWakeUpAlert(msgPreview: String) {
        AlertDialog.Builder(this)
            .setTitle("الطرف الآخر غير متصل حالياً 🔔")
            .setMessage("تم حفظ رسالتك في الانتظار وسيتم إرسالها تلقائياً بمجرد أن يفتح ($favDeviceName) البلوتوث.\n\nهل تريد إرسال (إشعار إيقاظ فوري) لهاتفه الآن لتنبيهه بفتح البلوتوث؟")
            .setPositiveButton("نعم، أرسل تنبيه إيقاظ") { _, _ ->
                try {
                    val wakeIntent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("smsto:")
                        putExtra("sms_body", "🔔 إشعار: أنا أحاول إرسال رسالة لك عبر تطبيق البلوتوث، يرجى تشغيل البلوتوث والاقتران بي لرؤية الرسالة!")
                    }
                    startActivity(wakeIntent)
                } catch (e: Exception) {
                    Toast.makeText(this, "تعذر فتح تطبيق الرسائل", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("انتظار فتح البلوتوث تلقائياً", null)
            .show()
    }

    // رادار إعادة الاتصال التلقائي بالجهاز المفضل وإرسال الرسائل المعلقة
    private fun startAutoReconnect() {
        if (isAutoReconnecting || favDeviceAddress == null) return
        isAutoReconnecting = true
        updateStatus("رادار البحث نشط: بانتظار فتح ($favDeviceName) للبلوتوث...")

        val runnable = object : Runnable {
            override fun run() {
                if (connectedThread != null) {
                    isAutoReconnecting = false
                    return
                }
                val device = bluetoothAdapter?.bondedDevices?.find { it.address == favDeviceAddress }
                if (device != null && bluetoothAdapter?.isEnabled == true) {
                    connectThread?.cancel()
                    connectThread = ConnectThread(device, isSilentRetry = true).also { it.start() }
                }
                if (isAutoReconnecting) {
                    reconnectHandler.postDelayed(this, 6000)
                }
            }
        }
        reconnectHandler.post(runnable)
    }

    private fun flushPendingQueue() {
        Thread {
            while (pendingQueue.isNotEmpty() && connectedThread != null) {
                val pkt = pendingQueue.poll() ?: break
                connectedThread?.sendPacket(pkt.type, pkt.fileName, pkt.bytes)
                Thread.sleep(300)
            }
            runOnUiThread {
                Toast.makeText(this, "تم تسليم جميع الرسائل المعلقة بنجاح!", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "تنبيهات رسائل البلوتوث",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "إشعارات الرسائل الجديدة وانقطاع الاتصال"
                enableVibration(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun showSystemNotification(title: String, content: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        builder.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify((System.currentTimeMillis() % 10000).toInt(), builder.build())
    }

    private fun requestAppPermissions(): Boolean {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
            needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            needed.add("android.permission.POST_NOTIFICATIONS")
        }
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
            return false
        }
        return true
    }

    private fun startAudioRecording() {
        try {
            val file = File(externalCacheDir, "voice_${System.currentTimeMillis()}.m4a")
            audioFilePath = file.absolutePath
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioFilePath)
                prepare()
                start()
            }
            isRecording = true
            btnRecordAudio.text = "⏹️ إرسال الصوت"
            btnRecordAudio.background = createRoundedBg("#DC2626")
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر بدء التسجيل: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAndSendAudio() {
        try {
            mediaRecorder?.apply { stop(); release() }
            mediaRecorder = null
            isRecording = false
            btnRecordAudio.text = "🎤 تسجيل صوت"
            btnRecordAudio.background = createRoundedBg("#EC4899")

            val file = File(audioFilePath)
            if (file.exists()) {
                val bytes = file.readBytes()
                if (connectedThread != null) {
                    connectedThread?.sendPacket(TYPE_AUDIO, file.name, bytes)
                } else {
                    pendingQueue.add(QueuedPacket(TYPE_AUDIO, file.name, bytes))
                    startAutoReconnect()
                }
                addAudioBubble(file.absolutePath, isSentByMe = true)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "خطأ في حفظ الصوت", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!

        Thread {
            try {
                if (requestCode == REQ_PICK_IMAGE) {
                    val input = contentResolver.openInputStream(uri)
                    val originalBitmap = BitmapFactory.decodeStream(input)
                    input?.close()

                    val maxDim = 900
                    val ratio = minOf(maxDim.toFloat() / originalBitmap.width, maxDim.toFloat() / originalBitmap.height, 1f)
                    val scaledBitmap = Bitmap.createScaledBitmap(
                        originalBitmap,
                        (originalBitmap.width * ratio).toInt(),
                        (originalBitmap.height * ratio).toInt(),
                        true
                    )
                    val out = ByteArrayOutputStream()
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
                    val bytes = out.toByteArray()

                    if (connectedThread != null) {
                        connectedThread?.sendPacket(TYPE_IMAGE, "IMG_${System.currentTimeMillis()}.jpg", bytes)
                    } else {
                        pendingQueue.add(QueuedPacket(TYPE_IMAGE, "IMG_${System.currentTimeMillis()}.jpg", bytes))
                        runOnUiThread { startAutoReconnect() }
                    }
                    addImageBubble(bytes, isSentByMe = true)

                } else if (requestCode == REQ_PICK_FILE) {
                    val fileName = getFileNameFromUri(uri)
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@Thread
                    if (connectedThread != null) {
                        connectedThread?.sendPacket(TYPE_FILE, fileName, bytes)
                    } else {
                        pendingQueue.add(QueuedPacket(TYPE_FILE, fileName, bytes))
                        runOnUiThread { startAutoReconnect() }
                    }
                    addTextBubble("📎 ملف: $fileName", isSentByMe = true)
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "فشل قراءة الملف", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "file_${System.currentTimeMillis()}"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) name = cursor.getString(index)
        }
        return name
    }

    private fun createBaseBubble(isSentByMe: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (isSentByMe) Gravity.END else Gravity.START
                setMargins(10, 10, 10, 10)
            }
            setPadding(28, 20, 28, 20)
            background = createRoundedBg(if (isSentByMe) "#2563EB" else "#334155")
        }
    }

    private fun addTextBubble(message: String, isSentByMe: Boolean) {
        runOnUiThread {
            val bubble = createBaseBubble(isSentByMe)
            val tvMsg = TextView(this).apply {
                text = message
                setTextColor(Color.WHITE)
                textSize = 16f
            }
            bubble.addView(tvMsg)
            bubble.addView(createTimeLabel())
            chatContainer.addView(bubble)
            scrollToBottom()
        }
    }

    private fun addImageBubble(imageBytes: ByteArray, isSentByMe: Boolean) {
        runOnUiThread {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return@runOnUiThread
            val bubble = createBaseBubble(isSentByMe)
            val iv = ImageView(this).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                maxWidth = 600
                maxHeight = 600
            }
            bubble.addView(iv)
            bubble.addView(createTimeLabel())
            chatContainer.addView(bubble)
            scrollToBottom()
        }
    }

    private fun addAudioBubble(filePath: String, isSentByMe: Boolean) {
        runOnUiThread {
            val bubble = createBaseBubble(isSentByMe)
            val btnPlay = Button(this).apply {
                text = "▶️ تشغيل الرسالة الصوتية"
                setTextColor(Color.WHITE)
                background = createRoundedBg("#0D9488")
                setOnClickListener {
                    try {
                        MediaPlayer().apply { setDataSource(filePath); prepare(); start() }
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "تعذر تشغيل الصوت", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            bubble.addView(btnPlay)
            bubble.addView(createTimeLabel())
            chatContainer.addView(bubble)
            scrollToBottom()
        }
    }

    private fun createTimeLabel(): TextView {
        val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
        return TextView(this).apply {
            text = time
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11f
            gravity = Gravity.END
            setPadding(0, 8, 0, 0)
        }
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun saveToDownloads(fileName: String, bytes: ByteArray): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let { contentResolver.openOutputStream(it)?.use { out -> out.write(bytes) } }
                "Downloads/$fileName"
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(dir, fileName)
                file.writeBytes(bytes)
                file.absolutePath
            }
        } catch (e: Exception) {
            val fallback = File(externalCacheDir, fileName)
            fallback.writeBytes(bytes)
            fallback.absolutePath
        }
    }

    private fun startServer() {
        acceptThread?.cancel()
        acceptThread = AcceptThread().also { it.start() }
        updateStatus("بانتظار اتصال الهاتف الآخر...")
    }

    private fun showPairedDevicesDialog() {
        val paired = bluetoothAdapter?.bondedDevices
        if (paired.isNullOrEmpty()) {
            Toast.makeText(this, "اقترن بالهاتف الآخر من إعدادات البلوتوث أولاً!", Toast.LENGTH_LONG).show()
            return
        }
        val list = paired.toList()
        val names = list.map { "${it.name ?: "هاتف"} ⭐\n(${it.address})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("اختر الهاتف للاتصال وحفظه كمفضل")
            .setItems(names) { _, which ->
                val dev = list[which]
                saveFavoriteDevice(dev.address, dev.name ?: "هاتف صديق")
                connectThread?.cancel()
                connectThread = ConnectThread(dev, isSilentRetry = false).also { it.start() }
                updateStatus("جاري الاتصال بـ ${dev.name}...")
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun saveFavoriteDevice(address: String, name: String) {
        favDeviceAddress = address
        favDeviceName = name
        prefs.edit().putString("fav_address", address).putString("fav_name", name).apply()
        updateFavoriteLabel()
    }

    private fun manageConnectedSocket(socket: BluetoothSocket, device: BluetoothDevice) {
        val name = device.name ?: "هاتف متصل"
        saveFavoriteDevice(device.address, name)
        isAutoReconnecting = false

        connectedThread?.cancel()
        connectedThread = ConnectedThread(socket, name).also { it.start() }
        updateStatus("متصل الآن مع: $name")
        showSystemNotification("تم الاتصال عبر البلوتوث ✅", "أنت متصل الآن مع $name ويمكنك تبادل الرسائل")

        if (pendingQueue.isNotEmpty()) {
            flushPendingQueue()
        }
    }

    private fun updateStatus(msg: String) {
        runOnUiThread { tvStatus.text = "الحالة: $msg" }
    }

    private inner class AcceptThread : Thread() {
        private val serverSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
            bluetoothAdapter?.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
        }

        override fun run() {
            while (true) {
                val socket = try { serverSocket?.accept() } catch (e: IOException) { break }
                if (socket != null) {
                    manageConnectedSocket(socket, socket.remoteDevice)
                    serverSocket?.close()
                    break
                }
            }
        }

        fun cancel() {
            try { serverSocket?.close() } catch (e: IOException) {}
        }
    }

    private inner class ConnectThread(
        private val device: BluetoothDevice,
        private val isSilentRetry: Boolean
    ) : Thread() {
        private val socket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createRfcommSocketToServiceRecord(APP_UUID)
        }

        override fun run() {
            bluetoothAdapter?.cancelDiscovery()
            try {
                socket?.connect()
                socket?.let { manageConnectedSocket(it, device) }
            } catch (e: IOException) {
                if (!isSilentRetry) {
                    updateStatus("الجهاز غير متصل حالياً - يمكنك إرسال رسائل بالانتظار")
                }
                try { socket?.close() } catch (ce: IOException) {}
            }
        }

        fun cancel() {
            try { socket?.close() } catch (e: IOException) {}
        }
    }

    private inner class ConnectedThread(
        private val socket: BluetoothSocket,
        private val peerName: String
    ) : Thread() {
        private val dataIn = DataInputStream(BufferedInputStream(socket.inputStream))
        private val dataOut = DataOutputStream(BufferedOutputStream(socket.outputStream))

        override fun run() {
            while (true) {
                try {
                    val type = dataIn.readByte()
                    val fileName = dataIn.readUTF()
                    val size = dataIn.readInt()
                    val payload = ByteArray(size)
                    dataIn.readFully(payload)

                    when (type) {
                        TYPE_TEXT -> {
                            val text = String(payload, Charsets.UTF_8)
                            addTextBubble(text, isSentByMe = false)
                            showSystemNotification("رسالة جديدة من $peerName 💬", text)
                        }
                        TYPE_IMAGE -> {
                            addImageBubble(payload, isSentByMe = false)
                            showSystemNotification("صورة جديدة من $peerName 📷", "افتح التطبيق لمشاهدة الصورة")
                        }
                        TYPE_AUDIO -> {
                            val tempAudio = File(externalCacheDir, "rx_${System.currentTimeMillis()}.m4a")
                            tempAudio.writeBytes(payload)
                            addAudioBubble(tempAudio.absolutePath, isSentByMe = false)
                            showSystemNotification("رسالة صوتية من $peerName 🎤", "افتح التطبيق للاستماع للتسجيل")
                        }
                        TYPE_FILE -> {
                            val savedPath = saveToDownloads(fileName, payload)
                            addTextBubble("📁 استلمت ملفاً: $fileName\nحُفظ في: $savedPath", isSentByMe = false)
                            showSystemNotification("ملف مستلم من $peerName 📎", "تم حفظ $fileName في التنزيلات")
                        }
                    }
                } catch (e: IOException) {
                    connectedThread = null
                    updateStatus("انقطع الاتصال مع $peerName")
                    showSystemNotification(
                        "⚠️ انقطع اتصال البلوتوث مع $peerName",
                        "يرجى التأكد من تشغيل البلوتوث والاقتران لاستلام الرسائل الجديدة"
                    )
                    // إعادة تفعيل الاستماع تلقائياً لاستقبال اتصال الطرف الآخر فور عودته
                    startServer()
                    break
                }
            }
        }

        @Synchronized
        fun sendPacket(type: Byte, fileName: String, bytes: ByteArray) {
            Thread {
                try {
                    synchronized(dataOut) {
                        dataOut.writeByte(type.toInt())
                        dataOut.writeUTF(fileName)
                        dataOut.writeInt(bytes.size)
                        dataOut.write(bytes)
                        dataOut.flush()
                    }
                } catch (e: IOException) {
                    updateStatus("فشل الإرسال - تم نقل الرسالة لطابور الانتظار")
                    pendingQueue.add(QueuedPacket(type, fileName, bytes))
                    startAutoReconnect()
                }
            }.start()
        }

        fun cancel() {
            try { socket.close() } catch (e: IOException) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isAutoReconnecting = false
        reconnectHandler.removeCallbacksAndMessages(null)
        acceptThread?.cancel()
        connectThread?.cancel()
        connectedThread?.cancel()
    }
}
