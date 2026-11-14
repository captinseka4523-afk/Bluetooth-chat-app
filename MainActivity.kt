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
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
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
import java.util.concurrent.CopyOnWriteArrayList

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnHostRoom: Button
    private lateinit var btnScanDevices: Button
    private lateinit var btnSendText: Button
    private lateinit var btnSendImage: Button
    private lateinit var btnRecordAudio: Button
    private lateinit var btnSendFile: Button
    private lateinit var etMessage: EditText
    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null

    private val clientThreads = CopyOnWriteArrayList<ConnectedThread>()
    private var hostClientThread: ConnectedThread? = null

    private val discoveredDeviceList = mutableListOf<BluetoothDevice>()
    private val discoveredDeviceNames = mutableListOf<String>()
    private lateinit var discoveryAdapter: ArrayAdapter<String>

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var audioFilePath: String = ""

    // لمعرفة ما إذا كان المستخدم داخل التطبيق أم خارجه
    private var isAppInForeground = false

    private val APP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val APP_NAME = "BlueChatElite"
    private val CHANNEL_ID = "bluechat_elite_channel"
    private val myDeviceName by lazy { android.os.Build.MODEL ?: "هاتف" }

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
        createNotificationChannel()
        setupGlassUI()

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "البلوتوث غير مدعوم على هذا الجهاز", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        requestAppPermissions()
        setupListeners()
        registerBluetoothReceiver()
    }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
    }

    override fun onPause() {
        super.onPause()
        isAppInForeground = false
    }

    // تصميم الجلاس ديزاين (Glassmorphism) الفاخر والألوان الأنيقة
    private fun setupGlassUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#070A12"))
            setPadding(24, 24, 24, 24)
        }

        // بطاقة الهيدر الزجاجية الفاخرة
        val topCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createGlassBackground("#1A2238", 28f)
            setPadding(24, 22, 24, 22)
        }

        val tvTitle = TextView(this).apply {
            text = "⚡ BlueChat Elite"
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 6)
        }

        tvStatus = TextView(this).apply {
            text = "الحالة: جاهز - أنشئ غرفة أو ابحث عن أجهزة"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        btnHostRoom = Button(this).apply {
            text = "🏠 إنشاء غرفة"
            setTextColor(Color.WHITE)
            background = createGlassBackground("#0284C7", 20f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
        }

        btnScanDevices = Button(this).apply {
            text = "🔍 بحث عن أجهزة"
            setTextColor(Color.WHITE)
            background = createGlassBackground("#059669", 20f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8
            }
        }

        buttonsRow.addView(btnHostRoom)
        buttonsRow.addView(btnScanDevices)
        topCard.addView(tvTitle)
        topCard.addView(tvStatus)
        topCard.addView(buttonsRow)
        root.addView(topCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 })

        // صندوق المحادثة الزجاجي
        scrollView = ScrollView(this).apply {
            background = createGlassBackground("#0F172A", 28f)
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

        // أزرار الوسائط المتعددة (صورة، صوت، ملف) بتصميم عصري
        val mediaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        btnSendImage = Button(this).apply {
            text = "📷 صورة"
            setTextColor(Color.WHITE)
            background = createGlassBackground("#7C3AED", 18f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6 }
        }

        btnRecordAudio = Button(this).apply {
            text = "🎤 صوت"
            setTextColor(Color.WHITE)
            background = createGlassBackground("#DB2777", 18f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 6; startMargin = 6
            }
        }

        btnSendFile = Button(this).apply {
            text = "📎 ملف"
            setTextColor(Color.WHITE)
            background = createGlassBackground("#D97706", 18f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { startMargin = 6 }
        }

        mediaRow.addView(btnSendImage)
        mediaRow.addView(btnRecordAudio)
        mediaRow.addView(btnSendFile)
        root.addView(mediaRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 12 })

        // شريط كتابة الرسائل العصري
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        etMessage = EditText(this).apply {
            hint = "اكتب رسالة..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            background = createGlassBackground("#1E293B", 22f)
            setPadding(24, 20, 24, 20)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 12
            }
        }

        btnSendText = Button(this).apply {
            text = "إرسال ➔"
            setTextColor(Color.WHITE)
            background = createGlassBackground("#2563EB", 22f)
            setPadding(32, 20, 32, 20)
        }

        inputRow.addView(etMessage)
        inputRow.addView(btnSendText)
        root.addView(inputRow)

        setContentView(root)
    }

    // دالة إنشاء مظهر الجلاس ديزاين (شراشف زجاجية ناعمة مع حواف)
    private fun createGlassBackground(hexColor: String, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.parseColor(hexColor))
            setStroke(2, Color.parseColor("#334155")) // إطار زجاجي رفيع أنيق
        }
    }

    private fun setupListeners() {
        btnHostRoom.setOnClickListener {
            if (requestAppPermissions()) startServer()
        }

        btnScanDevices.setOnClickListener {
            if (requestAppPermissions()) showInAppDiscoveryDialog()
        }

        btnSendText.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            val bytes = text.toByteArray(Charsets.UTF_8)
            broadcastPacket(TYPE_TEXT, "text.txt", bytes)
            addMessageBubble(myDeviceName, text, isSentByMe = true)
            etMessage.text.clear()
        }

        btnSendImage.setOnClickListener {
            if (isConnectedAny()) {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
                startActivityForResult(Intent.createChooser(intent, "اختر صورة"), REQ_PICK_IMAGE)
            } else {
                Toast.makeText(this, "أنشئ غرفة أو اتصل بجهاز أولاً", Toast.LENGTH_SHORT).show()
            }
        }

        btnSendFile.setOnClickListener {
            if (isConnectedAny()) {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                startActivityForResult(Intent.createChooser(intent, "اختر ملفاً"), REQ_PICK_FILE)
            } else {
                Toast.makeText(this, "أنشئ غرفة أو اتصل بجهاز أولاً", Toast.LENGTH_SHORT).show()
            }
        }

        btnRecordAudio.setOnClickListener {
            if (!isConnectedAny()) {
                Toast.makeText(this, "أنشئ غرفة أو اتصل بجهاز أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMISSIONS)
                return@setOnClickListener
            }
            if (!isRecording) startAudioRecording() else stopAndSendAudio()
        }
    }

    private fun isConnectedAny(): Boolean = clientThreads.isNotEmpty() || hostClientThread != null

    private fun showInAppDiscoveryDialog() {
        discoveredDeviceList.clear()
        discoveredDeviceNames.clear()

        bluetoothAdapter?.bondedDevices?.forEach {
            discoveredDeviceList.add(it)
            discoveredDeviceNames.add("⭐ مقترن: ${it.name ?: "جهاز"} (${it.address})")
        }

        discoveryAdapter = ArrayAdapter(this, android.R.layout.select_dialog_singlechoice, discoveredDeviceNames)

        AlertDialog.Builder(this)
            .setTitle("اختر جهازاً للاتصال المباشر 🔍")
            .setAdapter(discoveryAdapter) { _, which ->
                val device = discoveredDeviceList[which]
                bluetoothAdapter?.cancelDiscovery()
                connectToDevice(device)
            }
            .setPositiveButton("بحث عن أجهزة جديدة") { _, _ -> startDeviceDiscovery() }
            .setNegativeButton("إلغاء", null)
            .show()

        startDeviceDiscovery()
    }

    private fun startDeviceDiscovery() {
        if (bluetoothAdapter?.isDiscovering == true) bluetoothAdapter?.cancelDiscovery()
        bluetoothAdapter?.startDiscovery()
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (BluetoothDevice.ACTION_FOUND == intent.action) {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null && !discoveredDeviceList.contains(device)) {
                        discoveredDeviceList.add(device)
                        discoveredDeviceNames.add("📡 متاح: ${device.name ?: "جهاز"} (${device.address})")
                        if (::discoveryAdapter.isInitialized) discoveryAdapter.notifyDataSetChanged()
                    }
                }
            }
        }, filter)
    }

    private fun connectToDevice(device: BluetoothDevice) {
        connectThread?.cancel()
        connectThread = ConnectThread(device).also { it.start() }
        updateStatus("جاري الاتصال بـ ${device.name}...")
    }

    private fun startServer() {
        acceptThread?.cancel()
        acceptThread = AcceptThread().also { it.start() }
        updateStatus("الغرفة مفتوحة بانتظار انضمام الأصدقاء 🏠")
    }

    private fun updateStatus(msg: String) {
        runOnUiThread { tvStatus.text = "الحالة: $msg" }
    }

    private fun broadcastPacket(type: Byte, fileName: String, bytes: ByteArray) {
        for (ct in clientThreads) ct.sendPacket(type, myDeviceName, fileName, bytes)
        hostClientThread?.sendPacket(type, myDeviceName, fileName, bytes)
    }

    private inner class AcceptThread : Thread() {
        private val serverSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
            bluetoothAdapter?.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
        }

        override fun run() {
            while (true) {
                val socket = try { serverSocket?.accept() } catch (e: IOException) { break }
                if (socket != null) {
                    val ct = ConnectedThread(socket, socket.remoteDevice.name ?: "عضو")
                    clientThreads.add(ct)
                    ct.start()
                    updateStatus("انضم عضو جديد للغرفة! (${clientThreads.size} أعضاء)")
                }
            }
        }
        fun cancel() { try { serverSocket?.close() } catch (e: IOException) {} }
    }

    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {
        private val socket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createRfcommSocketToServiceRecord(APP_UUID)
        }

        override fun run() {
            bluetoothAdapter?.cancelDiscovery()
            try {
                socket?.connect()
                socket?.let {
                    hostClientThread = ConnectedThread(it, device.name ?: "المضيف").also { t -> t.start() }
                    updateStatus("متصل بنجاح مع غرفة: ${device.name}")
                }
            } catch (e: IOException) {
                updateStatus("فشل الاتصال بالغرفة")
                try { socket?.close() } catch (ce: IOException) {}
            }
        }
        fun cancel() { try { socket?.close() } catch (e: IOException) {} }
    }

    private inner class ConnectedThread(private val socket: BluetoothSocket, private val peerName: String) : Thread() {
        private val dataIn = DataInputStream(BufferedInputStream(socket.inputStream))
        private val dataOut = DataOutputStream(BufferedOutputStream(socket.outputStream))

        override fun run() {
            while (true) {
                try {
                    val type = dataIn.readByte()
                    val senderName = dataIn.readUTF()
                    val fileName = dataIn.readUTF()
                    val size = dataIn.readInt()
                    val payload = ByteArray(size)
                    dataIn.readFully(payload)

                    if (acceptThread != null && clientThreads.contains(this)) {
                        for (ct in clientThreads) {
                            if (ct != this) ct.sendPacket(type, senderName, fileName, payload)
                        }
                    }

                    when (type) {
                        TYPE_TEXT -> {
                            val text = String(payload, Charsets.UTF_8)
                            handleIncomingMessage(senderName, text, isImage = false) {
                                addMessageBubble(senderName, text, isSentByMe = false)
                            }
                        }
                        TYPE_IMAGE -> {
                            handleIncomingMessage(senderName, "صورة", isImage = true) {
                                addImageBubble(senderName, payload, isSentByMe = false)
                            }
                        }
                        TYPE_AUDIO -> {
                            val audioFile = File(externalCacheDir, "rx_${System.currentTimeMillis()}.m4a")
                            audioFile.writeBytes(payload)
                            handleIncomingMessage(senderName, "رسالة صوتية", isImage = false) {
                                addAudioBubble(senderName, audioFile.absolutePath, isSentByMe = false)
                            }
                        }
                        TYPE_FILE -> {
                            val saved = saveToDownloads(fileName, payload)
                            handleIncomingMessage(senderName, "ملف: $fileName", isImage = false) {
                                addMessageBubble(senderName, "📁 استلمت ملفاً: $fileName\n(حُفظ في $saved)", isSentByMe = false)
                            }
                        }
                    }
                } catch (e: IOException) {
                    clientThreads.remove(this)
                    if (hostClientThread == this) hostClientThread = null
                    updateStatus("انقطع الاتصال مع $peerName")
                    break
                }
            }
        }

        @Synchronized
        fun sendPacket(type: Byte, sender: String, fileName: String, bytes: ByteArray) {
            Thread {
                try {
                    synchronized(dataOut) {
                        dataOut.writeByte(type.toInt())
                        dataOut.writeUTF(sender)
                        dataOut.writeUTF(fileName)
                        dataOut.writeInt(bytes.size)
                        dataOut.write(bytes)
                        dataOut.flush()
                    }
                } catch (e: IOException) {}
            }.start()
        }

        fun cancel() { try { socket.close() } catch (e: IOException) {} }
    }

    // منطق الإشعارات الذكي: صوت بيب ناعم إذا كان داخل التطبيق، أو إشعار خارجي إذا كان خارجه
    private fun handleIncomingMessage(sender: String, summary: String, isImage: Boolean, action: () -> Unit) {
        runOnUiThread {
            action()
            if (isAppInForeground) {
                // تشغيل صوت "بيب" ناعم جداً وبسيط مثل واتساب
                try {
                    val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 30)
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                } catch (e: Exception) {}
            } else {
                // خارج التطبيق: إظهار إشعار رسمي في شريط الهاتف
                showSystemNotification("رسالة من $sender 💬", summary)
            }
        }
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
            btnRecordAudio.text = "⏹️ إيقاف وإرسال"
            btnRecordAudio.background = createGlassBackground("#DC2626", 18f)
        } catch (e: Exception) {}
    }

    private fun stopAndSendAudio() {
        try {
            mediaRecorder?.apply { stop(); release() }
            mediaRecorder = null
            isRecording = false
            btnRecordAudio.text = "🎤 صوت"
            btnRecordAudio.background = createGlassBackground("#DB2777", 18f)

            val file = File(audioFilePath)
            if (file.exists()) {
                val bytes = file.readBytes()
                broadcastPacket(TYPE_AUDIO, file.name, bytes)
                addAudioBubble(myDeviceName, file.absolutePath, isSentByMe = true)
            }
        } catch (e: Exception) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!

        Thread {
            try {
                if (requestCode == REQ_PICK_IMAGE) {
                    val input = contentResolver.openInputStream(uri)
                    val original = BitmapFactory.decodeStream(input)
                    input?.close()

                    val maxDim = 800
                    val ratio = minOf(maxDim.toFloat() / original.width, maxDim.toFloat() / original.height, 1f)
                    val scaled = Bitmap.createScaledBitmap(original, (original.width * ratio).toInt(), (original.height * ratio).toInt(), true)
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
                    val bytes = out.toByteArray()

                    broadcastPacket(TYPE_IMAGE, "IMG_${System.currentTimeMillis()}.jpg", bytes)
                    addImageBubble(myDeviceName, bytes, isSentByMe = true)
                } else if (requestCode == REQ_PICK_FILE) {
                    val fileName = getFileNameFromUri(uri)
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@Thread
                    broadcastPacket(TYPE_FILE, fileName, bytes)
                    addMessageBubble(myDeviceName, "📎 أرسلت ملفاً: $fileName", isSentByMe = true)
                }
            } catch (e: Exception) {}
        }.start()
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "file_${System.currentTimeMillis()}"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) name = cursor.getString(idx)
        }
        return name
    }

    private fun addMessageBubble(sender: String, message: String, isSentByMe: Boolean) {
        runOnUiThread {
            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = if (isSentByMe) Gravity.END else Gravity.START
                    setMargins(8, 8, 8, 8)
                }
                setPadding(24, 16, 24, 16)
                background = createGlassBackground(if (isSentByMe) "#1E3A8A" else "#1E293B", 22f)
            }

            val tvSender = TextView(this).apply {
                text = if (isSentByMe) "أنت" else sender
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val tvMsg = TextView(this).apply {
                text = message
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(0, 4, 0, 4)
            }

            bubble.addView(tvSender)
            bubble.addView(tvMsg)
            bubble.addView(createTimeLabel())
            chatContainer.addView(bubble)
            scrollToBottom()
        }
    }

    private fun addImageBubble(sender: String, bytes: ByteArray, isSentByMe: Boolean) {
        runOnUiThread {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runOnUiThread
            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = if (isSentByMe) Gravity.END else Gravity.START
                    setMargins(8, 8, 8, 8)
                }
                setPadding(20, 14, 20, 14)
                background = createGlassBackground(if (isSentByMe) "#1E3A8A" else "#1E293B", 22f)
            }

            val tvSender = TextView(this).apply {
                text = if (isSentByMe) "أنت" else sender
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val iv = ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                maxWidth = 550
                maxHeight = 550
            }

            bubble.addView(tvSender)
            bubble.addView(iv)
            bubble.addView(createTimeLabel())
            chatContainer.addView(bubble)
            scrollToBottom()
        }
    }

    private fun addAudioBubble(sender: String, path: String, isSentByMe: Boolean) {
        runOnUiThread {
            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = if (isSentByMe) Gravity.END else Gravity.START
                    setMargins(8, 8, 8, 8)
                }
                setPadding(20, 14, 20, 14)
                background = createGlassBackground(if (isSentByMe) "#1E3A8A" else "#1E293B", 22f)
            }

            val tvSender = TextView(this).apply {
                text = if (isSentByMe) "أنت" else sender
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val btnPlay = Button(this).apply {
                text = "▶️ تشغيل التسجيل الصوتي"
                setTextColor(Color.WHITE)
                background = createGlassBackground("#0F766E", 16f)
                setOnClickListener {
                    try {
                        MediaPlayer().apply { setDataSource(path); prepare(); start() }
                    } catch (e: Exception) {}
                }
            }

            bubble.addView(tvSender)
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
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
            gravity = Gravity.END
            setPadding(0, 4, 0, 0)
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "BlueChat Elite Notifications", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun showSystemNotification(title: String, body: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getActivity(this, 0, intent, flags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        builder.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pi)
            .setAutoCancel(true)

        getSystemService(NotificationManager::class.java)?.notify((System.currentTimeMillis() % 10000).toInt(), builder.build())
    }

    private fun requestAppPermissions(): Boolean {
        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
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

    override fun onDestroy() {
        super.onDestroy()
        acceptThread?.cancel()
        connectThread?.cancel()
        clientThreads.forEach { it.cancel() }
        hostClientThread?.cancel()
    }
}
