package com.g2012.wirelessprint

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val ACTION_USB_PERMISSION = "com.g2012.wirelessprint.USB_PERMISSION"
    private lateinit var usbManager: UsbManager

    // UI elements
    private lateinit var senderLayout: LinearLayout
    private lateinit var serverLayout: LinearLayout
    private lateinit var tabSenderBtn: Button
    private lateinit var tabServerBtn: Button

    // Sender UI
    private lateinit var ipInput: EditText
    private lateinit var selectedFileText: TextView
    private lateinit var colorSwitch: CheckBox
    private lateinit var directUsbSwitch: CheckBox
    private lateinit var copiesInput: EditText
    private lateinit var senderStatusText: TextView
    private lateinit var printBtn: Button

    // Server UI
    private lateinit var serverIpText: TextView
    private lateinit var serverStatusText: TextView
    private lateinit var serverToggleBtn: Button

    private var selectedUri: Uri? = null
    private var selectedMime: String = ""
    private var pendingUsbAction: (() -> Unit)? = null

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            selectedMime = contentResolver.getType(uri) ?: ""
            selectedFileText.text = "Selected: ${uri.lastPathSegment ?: "File ready"}"
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        pendingUsbAction?.invoke()
                    } else {
                        serverStatusText.text = "USB permission denied."
                        senderStatusText.text = "USB permission denied."
                    }
                    pendingUsbAction = null
                }
                PrintBridgeService.ACTION_STATUS -> {
                    val msg = intent.getStringExtra(PrintBridgeService.EXTRA_STATUS) ?: return
                    serverStatusText.text = msg
                    updateServerUi()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        buildUi()

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(PrintBridgeService.ACTION_STATUS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        // Load saved IP
        val prefs = getSharedPreferences("g2012_prefs", Context.MODE_PRIVATE)
        ipInput.setText(prefs.getString("last_ip", ""))

        handleIncomingIntent(intent)
        updateServerUi()
        autoDiscoverSparePhone()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    selectedUri = uri
                    selectedMime = intent.type ?: contentResolver.getType(uri) ?: ""
                    selectedFileText.text = "Shared file ready to print!"
                    showTab(isServer = false)
                }
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                showTab(isServer = true)
                requestUsbPermissionAndRun { startBridgeService() }
            }
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
            setBackgroundColor(Color.WHITE)
        }

        // Top Mode Tabs
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 24)
        }
        tabSenderBtn = Button(this).apply {
            text = "MAIN PHONE\n(Send & Print)"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showTab(isServer = false) }
        }
        tabServerBtn = Button(this).apply {
            text = "SPARE PHONE\n(Printer Server)"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showTab(isServer = true) }
        }
        tabRow.addView(tabSenderBtn)
        tabRow.addView(tabServerBtn)
        root.addView(tabRow)

        // ==================== SENDER (MAIN PHONE) ====================
        senderLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val ipLabel = TextView(this).apply {
            text = "Spare Phone IP Address (Auto-detected on Wi-Fi):"
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 8, 0, 4)
        }
        ipInput = EditText(this).apply {
            hint = "e.g. 192.168.43.1"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val findBtn = Button(this).apply {
            text = "Auto-Find Spare Phone"
            setOnClickListener { autoDiscoverSparePhone() }
        }

        selectedFileText = TextView(this).apply {
            text = "No file selected yet (or Share any PDF/Photo to this app)"
            textSize = 15f
            setPadding(16, 20, 16, 20)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        val pickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 16)
        }
        val pickPdfBtn = Button(this).apply {
            text = "Pick PDF"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { pickFileLauncher.launch(arrayOf("application/pdf")) }
        }
        val pickImgBtn = Button(this).apply {
            text = "Pick Photo"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { pickFileLauncher.launch(arrayOf("image/*")) }
        }
        pickRow.addView(pickPdfBtn)
        pickRow.addView(pickImgBtn)

        colorSwitch = CheckBox(this).apply {
            text = "Print in Color (unchecked = Black & White)"
            isChecked = false
            textSize = 15f
        }
        directUsbSwitch = CheckBox(this).apply {
            text = "Print directly via USB-OTG (skip Wi-Fi)"
            isChecked = false
            textSize = 15f
        }

        val copiesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 16)
        }
        copiesRow.addView(TextView(this).apply {
            text = "Copies:  "
            textSize = 16f
        })
        copiesInput = EditText(this).apply {
            setText("1")
            inputType = InputType.TYPE_CLASS_NUMBER
            width = 160
        }
        copiesRow.addView(copiesInput)

        printBtn = Button(this).apply {
            text = "PRINT NOW"
            textSize = 18f
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            setPadding(24, 32, 24, 32)
            setOnClickListener { startPrintJob(isTestPage = false) }
        }

        val testBtn = Button(this).apply {
            text = "Print Quick Test Page"
            setOnClickListener { startPrintJob(isTestPage = true) }
        }

        senderStatusText = TextView(this).apply {
            text = "Ready. Connect to Spare Phone's Wi-Fi/Hotspot."
            textSize = 15f
            setPadding(0, 20, 0, 20)
            setTextColor(Color.DKGRAY)
        }

        senderLayout.addView(ipLabel)
        senderLayout.addView(ipInput)
        senderLayout.addView(findBtn)
        senderLayout.addView(selectedFileText)
        senderLayout.addView(pickRow)
        senderLayout.addView(colorSwitch)
        senderLayout.addView(directUsbSwitch)
        senderLayout.addView(copiesRow)
        senderLayout.addView(printBtn)
        senderLayout.addView(testBtn)
        senderLayout.addView(senderStatusText)

        // ==================== SERVER (SPARE PHONE) ====================
        serverLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        serverIpText = TextView(this).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#E3F2FD"))
        }

        serverStatusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 24, 0, 24)
            text = "1. Turn ON Hotspot on this Spare Phone (no SIM/data needed) OR join Wi-Fi.\n" +
                   "2. Turn ON Canon G2012 & plug it in via OTG.\n" +
                   "3. Tap Start Printer Server below."
        }

        serverToggleBtn = Button(this).apply {
            textSize = 18f
            setPadding(24, 32, 24, 32)
            setOnClickListener {
                if (PrintBridgeService.isRunning) {
                    stopService(Intent(this@MainActivity, PrintBridgeService::class.java))
                    updateServerUi()
                } else {
                    requestUsbPermissionAndRun { startBridgeService() }
                }
            }
        }

        val refreshIpBtn = Button(this).apply {
            text = "Refresh IP"
            setOnClickListener { updateServerUi() }
        }

        serverLayout.addView(serverIpText)
        serverLayout.addView(serverStatusText)
        serverLayout.addView(serverToggleBtn)
        serverLayout.addView(refreshIpBtn)

        root.addView(senderLayout)
        root.addView(serverLayout)
        scroll.addView(root)
        setContentView(scroll)

        showTab(isServer = PrintBridgeService.isRunning)
    }

    private fun showTab(isServer: Boolean) {
        senderLayout.visibility = if (isServer) View.GONE else View.VISIBLE
        serverLayout.visibility = if (isServer) View.VISIBLE else View.GONE
        tabSenderBtn.setBackgroundColor(if (!isServer) Color.parseColor("#1565C0") else Color.LTGRAY)
        tabSenderBtn.setTextColor(if (!isServer) Color.WHITE else Color.BLACK)
        tabServerBtn.setBackgroundColor(if (isServer) Color.parseColor("#2E7D32") else Color.LTGRAY)
        tabServerBtn.setTextColor(if (isServer) Color.WHITE else Color.BLACK)
    }

    private fun autoDiscoverSparePhone() {
        runOnUiThread { senderStatusText.text = "Searching Wi-Fi for Spare Phone..." }
        thread {
            var foundIp: String? = null
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 3500
                }
                val probe = "G2012_DISCOVER".toByteArray()
                val pkt = DatagramPacket(
                    probe, probe.size,
                    InetAddress.getByName("255.255.255.255"),
                    PrintBridgeService.DISCOVERY_PORT
                )
                sock.send(pkt)

                val buf = ByteArray(256)
                val resp = DatagramPacket(buf, buf.size)
                sock.receive(resp)
                val msg = String(resp.data, 0, resp.length)
                if (msg.startsWith("G2012_PRINTER_READY:")) {
                    foundIp = resp.address.hostAddress
                }
            } catch (_: Exception) {
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }

            runOnUiThread {
                if (foundIp != null) {
                    ipInput.setText(foundIp)
                    getSharedPreferences("g2012_prefs", Context.MODE_PRIVATE)
                        .edit().putString("last_ip", foundIp).apply()
                    senderStatusText.text = "Connected! Found Spare Phone at $foundIp"
                } else {
                    senderStatusText.text = "Auto-find didn't see the Spare Phone yet.\nMake sure Spare Phone Server is ON, or type its IP above."
                }
            }
        }
    }

    private fun startPrintJob(isTestPage: Boolean) {
        val useDirectUsb = directUsbSwitch.isChecked
        val targetIp = ipInput.text.toString().trim()
        val isColor = colorSwitch.isChecked
        val copies = (copiesInput.text.toString().toIntOrNull() ?: 1).coerceIn(1, 50)

        if (!isTestPage && selectedUri == null) {
            senderStatusText.text = "Please pick a PDF or Photo first (or tap Print Quick Test Page)."
            return
        }
        if (!useDirectUsb && targetIp.isEmpty()) {
            senderStatusText.text = "Enter Spare Phone IP address or tap Auto-Find."
            return
        }

        if (!useDirectUsb) {
            getSharedPreferences("g2012_prefs", Context.MODE_PRIVATE)
                .edit().putString("last_ip", targetIp).apply()
        }

        val runJob: () -> Unit = {
            printBtn.isEnabled = false
            thread {
                try {
                    updateSenderStatus("Loading built-in Canon G2012 driver...")
                    val initRes = GutenprintDriver.ensureInitialized(this)
                    if (initRes != "OK") {
                        throw IllegalStateException("Driver init failed: $initRes")
                    }

                    val bitmaps = if (isTestPage) {
                        listOf(createTestPageBitmap())
                    } else {
                        renderSelectedFileToBitmaps(selectedUri!!, selectedMime)
                    }

                    val outFile = File(cacheDir, "canon_page.prn")
                    for (copyIdx in 1..copies) {
                        for ((idx, bmp) in bitmaps.withIndex()) {
                            updateSenderStatus("Converting page ${idx + 1}/${bitmaps.size} (copy $copyIdx/$copies) for Canon G2012...")
                            val res = GutenprintDriver.renderBitmapToCanonFile(bmp, isColor, outFile)
                            if (!res.startsWith("OK:")) {
                                throw IllegalStateException(res)
                            }
                            val sizeKb = outFile.length() / 1024
                            if (useDirectUsb) {
                                updateSenderStatus("Sending page ${idx + 1} ($sizeKb KB) to USB printer...")
                                sendFileDirectUsb(outFile)
                            } else {
                                updateSenderStatus("Sending page ${idx + 1} ($sizeKb KB) over Wi-Fi to $targetIp...")
                                sendFileOverWifi(targetIp, outFile)
                            }
                        }
                    }
                    bitmaps.forEach { it.recycle() }
                    outFile.delete()
                    updateSenderStatus("DONE! Printed ${bitmaps.size * copies} page(s) on Canon G2012!")
                } catch (e: Exception) {
                    updateSenderStatus("Print failed: ${e.message}")
                } finally {
                    runOnUiThread { printBtn.isEnabled = true }
                }
            }
        }

        if (useDirectUsb) {
            requestUsbPermissionAndRun(runJob)
        } else {
            runJob()
        }
    }

    private fun createTestPageBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 64f
            typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText("Canon PIXMA G2012", 120f, 240f, paint)
        paint.textSize = 42f
        paint.typeface = Typeface.DEFAULT
        c.drawText("Wireless Print Test Page - SUCCESS!", 120f, 340f, paint)
        c.drawText("Printed directly from your custom Android app.", 120f, 420f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        c.drawRect(80f, 80f, 1160f, 1674f, paint)
        return bmp
    }

    private fun renderSelectedFileToBitmaps(uri: Uri, mime: String): List<Bitmap> {
        val targetW = 1654
        val targetH = 2339
        val result = mutableListOf<Bitmap>()

        if (mime.contains("pdf") || uri.toString().lowercase().endsWith(".pdf")) {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Cannot open PDF file.")
            pfd.use {
                PdfRenderer(it).use { renderer ->
                    for (i in 0 until renderer.pageCount) {
                        renderer.openPage(i).use { page ->
                            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bmp)
                            canvas.drawColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            result.add(bmp)
                        }
                    }
                }
            }
        } else {
            val src = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalStateException("Cannot decode image.")
            val pageBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(pageBmp)
            canvas.drawColor(Color.WHITE)
            val scale = minOf(targetW.toFloat() / src.width, targetH.toFloat() / src.height)
            val dw = (src.width * scale).toInt()
            val dh = (src.height * scale).toInt()
            val scaled = Bitmap.createScaledBitmap(src, dw, dh, true)
            canvas.drawBitmap(scaled, (targetW - dw) / 2f, (targetH - dh) / 2f, null)
            scaled.recycle()
            src.recycle()
            result.add(pageBmp)
        }
        return result
    }

    private fun sendFileOverWifi(ip: String, file: File) {
        NetworkUtils.connectToServer(this, ip, PrintBridgeService.PRINT_PORT).use { socket ->
            val out = socket.getOutputStream()
            FileInputStream(file).use { fis ->
                fis.copyTo(out, 16384)
            }
            out.flush()
        }
    }

    private fun sendFileDirectUsb(file: File) {
        val device = findUsbPrinter() ?: throw IllegalStateException("No USB printer found.")
        var usbInterface: UsbInterface? = null
        var outEndpoint: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_OUT
                    ) {
                        usbInterface = intf
                        outEndpoint = ep
                        break
                    }
                }
            }
            if (outEndpoint != null) break
        }
        if (usbInterface == null || outEndpoint == null) {
            throw IllegalStateException("USB printer endpoint not found.")
        }
        val conn = usbManager.openDevice(device) ?: throw IllegalStateException("Cannot open USB printer.")
        try {
            if (!conn.claimInterface(usbInterface, true)) throw IllegalStateException("Cannot claim USB interface.")
            val buf = ByteArray(16384)
            FileInputStream(file).use { fis ->
                var n: Int
                while (fis.read(buf).also { n = it } != -1) {
                    val chunk = if (n == buf.size) buf else buf.copyOf(n)
                    val sent = conn.bulkTransfer(outEndpoint, chunk, chunk.size, 15000)
                    if (sent < 0) throw IllegalStateException("USB transfer error.")
                }
            }
        } finally {
            try { conn.releaseInterface(usbInterface) } catch (_: Exception) {}
            try { conn.close() } catch (_: Exception) {}
        }
    }

    private fun requestUsbPermissionAndRun(action: () -> Unit) {
        val printer = findUsbPrinter()
        if (printer == null) {
            serverStatusText.text = "Canon G2012 not found! Turn printer ON and connect via OTG."
            senderStatusText.text = "Canon G2012 not found via OTG."
            return
        }
        if (usbManager.hasPermission(printer)) {
            action()
        } else {
            pendingUsbAction = action
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(printer, pi)
        }
    }

    private fun startBridgeService() {
        val intent = Intent(this, PrintBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        serverStatusText.text = "Starting server..."
        updateServerUi()
    }

    private fun findUsbPrinter(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { device ->
            device.vendorId == 0x04A9 || (0 until device.interfaceCount).any { idx ->
                device.getInterface(idx).interfaceClass == UsbConstants.USB_CLASS_PRINTER
            }
        }
    }

    private fun updateServerUi() {
        val ip = NetworkUtils.getBestLocalIp()
        serverIpText.text = "Spare Phone IP: $ip\nPort: ${PrintBridgeService.PRINT_PORT}"
        if (PrintBridgeService.isRunning) {
            serverToggleBtn.text = "Stop Printer Server"
            serverToggleBtn.setBackgroundColor(Color.parseColor("#C62828"))
            serverToggleBtn.setTextColor(Color.WHITE)
            serverStatusText.text = PrintBridgeService.lastStatus
        } else {
            serverToggleBtn.text = "Start Printer Server"
            serverToggleBtn.setBackgroundColor(Color.parseColor("#2E7D32"))
            serverToggleBtn.setTextColor(Color.WHITE)
        }
    }

    private fun updateSenderStatus(msg: String) {
        runOnUiThread { senderStatusText.text = msg }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
