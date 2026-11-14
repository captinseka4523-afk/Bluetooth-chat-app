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

    private fun setupGlassUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#070A12"))
            setPadding(24, 24, 24, 24)
        }

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
                marginEnd = 6; startMargin = 6 // تم التصحيح إلى startMargin -> wait, let me ensure it's marginStart!
            }
        }

        btnSendFile = Button(this).apply {
            text = "📎 ملف"
            setTextColor(Color.WHITE)
            background = createGlassBackground("#D97706", 18f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 6 }
        }
        
        // Correction check: Kotlin code below uses marginStart properly
        // Let's rewrite those two buttons cleanly in the code block below:
