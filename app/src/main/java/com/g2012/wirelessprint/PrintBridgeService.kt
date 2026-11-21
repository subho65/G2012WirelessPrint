package com.g2012.wirelessprint

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class PrintBridgeService : Service() {

    companion object {
        const val CHANNEL_ID = "G2012WirelessPrintChannel"
        const val NOTIF_ID = 101
        const val PRINT_PORT = 9100
        const val DISCOVERY_PORT = 9101
        const val ACTION_STATUS = "com.g2012.wirelessprint.STATUS_UPDATE"
        const val EXTRA_STATUS = "status_text"
        @Volatile var isRunning = false
        @Volatile var lastStatus = "Stopped"
    }

    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) {
            broadcastStatus(lastStatus)
            return START_STICKY
        }

        createNotificationChannel()
        val ip = NetworkUtils.getBestLocalIp()
        val notif = buildNotification("Ready on $ip:$PRINT_PORT")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notif,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                }
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }

        acquireLocks()
        isRunning = true
        startDiscoveryThread()
        startServerThread()
        return START_STICKY
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "G2012WirelessPrint::WakeLock")
        wakeLock?.acquire()

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(lockMode, "G2012WirelessPrint::WifiLock")
        wifiLock?.acquire()
    }

    private fun startDiscoveryThread() {
        thread(name = "DiscoveryResponder") {
            try {
                udpSocket = DatagramSocket(DISCOVERY_PORT).apply {
                    broadcast = true
                    soTimeout = 2000
                }
                val buf = ByteArray(256)
                while (isRunning) {
                    // Broadcast presence every 2 seconds
                    try {
                        val ip = NetworkUtils.getBestLocalIp()
                        val msg = "G2012_PRINTER_READY:$ip".toByteArray()
                        val bcast = DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT)
                        udpSocket?.send(bcast)
                    } catch (_: Exception) {}

                    // Also reply to direct probes
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        udpSocket?.receive(pkt)
                        val text = String(pkt.data, 0, pkt.length)
                        if (text.startsWith("G2012_DISCOVER")) {
                            val ip = NetworkUtils.getBestLocalIp()
                            val reply = "G2012_PRINTER_READY:$ip".toByteArray()
                            udpSocket?.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port))
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    private fun startServerThread() {
        thread(name = "PrintServerThread") {
            try {
                serverSocket = ServerSocket(PRINT_PORT)
                val ip = NetworkUtils.getBestLocalIp()
                updateStatus("SERVER READY!\n\nIP: $ip\nPort: $PRINT_PORT\n\nNow open this app on your Main Phone and tap Print!")

                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    try {
                        updateStatus("Receiving print job...")
                        val totalBytes = forwardToUsbPrinter(client.getInputStream())
                        client.close()
                        val kb = totalBytes / 1024
                        val currentIp = NetworkUtils.getBestLocalIp()
                        updateStatus("Printed ($kb KB)!\n\nSERVER READY on $currentIp:$PRINT_PORT")
                    } catch (e: Exception) {
                        try { client.close() } catch (_: Exception) {}
                        updateStatus("Error: ${e.message}\n\nSERVER READY on ${NetworkUtils.getBestLocalIp()}:$PRINT_PORT")
                    }
                }
            } catch (e: Exception) {
                if (isRunning) updateStatus("Server error: ${e.message}")
            }
        }
    }

    private fun findPrinter(usbManager: UsbManager): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { device ->
            device.vendorId == 0x04A9 || (0 until device.interfaceCount).any { idx ->
                device.getInterface(idx).interfaceClass == UsbConstants.USB_CLASS_PRINTER
            }
        }
    }

    private fun forwardToUsbPrinter(inputStream: InputStream): Long {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val device = findPrinter(usbManager)
            ?: throw IllegalStateException("Canon G2012 not connected via OTG.")
        if (!usbManager.hasPermission(device)) {
            throw IllegalStateException("USB permission missing. Tap Start Server again.")
        }

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
            throw IllegalStateException("USB Printer Bulk OUT endpoint not found.")
        }

        val connection = usbManager.openDevice(device)
            ?: throw IllegalStateException("Cannot open USB device.")

        var totalBytes = 0L
        try {
            if (!connection.claimInterface(usbInterface, true)) {
                throw IllegalStateException("Cannot claim USB printer interface.")
            }
            val buffer = ByteArray(16384)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                var offset = 0
                while (offset < bytesRead) {
                    val chunk = if (offset == 0 && bytesRead == buffer.size) {
                        buffer
                    } else {
                        buffer.copyOfRange(offset, bytesRead)
                    }
                    val sent = connection.bulkTransfer(outEndpoint, chunk, chunk.size, 15000)
                    if (sent < 0) throw IllegalStateException("USB transfer failed.")
                    offset += sent
                    totalBytes += sent
                }
            }
        } finally {
            try { connection.releaseInterface(usbInterface) } catch (_: Exception) {}
            try { connection.close() } catch (_: Exception) {}
        }
        return totalBytes
    }

    private fun updateStatus(msg: String) {
        lastStatus = msg
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(msg.lineSequence().firstOrNull() ?: "Running"))
        broadcastStatus(msg)
    }

    private fun broadcastStatus(msg: String) {
        val intent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, msg)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "G2012 Print Server", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Canon G2012 Printer Server")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_printer)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        lastStatus = "Server stopped."
        broadcastStatus(lastStatus)
        super.onDestroy()
    }
}
