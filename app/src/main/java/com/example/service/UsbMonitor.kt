package com.example.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.example.model.UsbDeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UsbMonitor(
    private val context: Context,
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit
) {
    companion object {
        const val ACTION_USB_PERMISSION = "com.example.streamrelay.USB_PERMISSION"
        const val USB_CLASS_VIDEO = 14
        const val USB_SUBCLASS_VIDEO_CONTROL = 1
        const val USB_SUBCLASS_VIDEO_STREAMING = 2
    }

    private val usbManager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val _connectedDevices = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    val connectedDevices: StateFlow<List<UsbDeviceInfo>> = _connectedDevices.asStateFlow()

    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    onLog("USB", "Device attached: ${device?.productName ?: device?.deviceName ?: "Unknown"}", false)
                    scanDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    onLog("USB", "Device detached: ${device?.productName ?: device?.deviceName ?: "Unknown"}", false)
                    scanDevices()
                }
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted) {
                            onLog("USB", "Permission granted for ${device?.productName ?: device?.deviceName}", false)
                        } else {
                            onLog("USB", "Permission denied for ${device?.productName ?: device?.deviceName}", true)
                        }
                        scanDevices()
                    }
                }
            }
        }
    }

    fun start() {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(usbReceiver, filter)
                }
                receiverRegistered = true
            } catch (e: Exception) {
                onLog("USB", "Failed to register USB receiver: ${e.message}", true)
            }
        }
        scanDevices()
    }

    fun stop() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (_: Exception) {}
            receiverRegistered = false
        }
    }

    fun scanDevices() {
        val manager = usbManager ?: run {
            onLog("USB", "UsbManager not available on this device", true)
            return
        }

        val deviceMap = manager.deviceList
        val list = mutableListOf<UsbDeviceInfo>()

        for ((_, device) in deviceMap) {
            var isUvc = device.deviceClass == USB_CLASS_VIDEO
            val ifaceSummaries = mutableListOf<String>()

            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                val ifClass = iface.interfaceClass
                val ifSubclass = iface.interfaceSubclass
                val endpoints = iface.endpointCount

                val typeDesc = when {
                    ifClass == USB_CLASS_VIDEO && ifSubclass == USB_SUBCLASS_VIDEO_CONTROL -> "UVC Video Control"
                    ifClass == USB_CLASS_VIDEO && ifSubclass == USB_SUBCLASS_VIDEO_STREAMING -> "UVC Video Streaming"
                    ifClass == USB_CLASS_VIDEO -> "UVC Video (Subclass $ifSubclass)"
                    ifClass == UsbConstants.USB_CLASS_AUDIO -> "USB Audio"
                    ifClass == UsbConstants.USB_CLASS_HID -> "HID"
                    ifClass == UsbConstants.USB_CLASS_MASS_STORAGE -> "Storage"
                    else -> "Class $ifClass, Sub $ifSubclass"
                }

                if (ifClass == USB_CLASS_VIDEO) {
                    isUvc = true
                }

                ifaceSummaries.add("IF#$i: $typeDesc ($endpoints EPs)")
            }

            val hasPerm = manager.hasPermission(device)

            val info = UsbDeviceInfo(
                deviceName = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                manufacturerName = device.manufacturerName,
                productName = device.productName,
                deviceClass = device.deviceClass,
                isUvcVideo = isUvc,
                interfaceCount = device.interfaceCount,
                interfacesSummary = ifaceSummaries,
                hasPermission = hasPerm
            )
            list.add(info)
        }

        _connectedDevices.value = list
        val uvcCount = list.count { it.isUvcVideo }
        onLog("USB", "Scan complete: ${list.size} USB device(s) found ($uvcCount UVC Video)", false)
    }

    fun requestUsbPermission(vendorId: Int, productId: Int) {
        try {
            val manager = usbManager ?: run {
                onLog("USB", "UsbManager is not available", true)
                return
            }
            val target = manager.deviceList.values.firstOrNull {
                it.vendorId == vendorId && it.productId == productId
            } ?: run {
                onLog("USB", "Device not found for permission request: $vendorId:$productId", true)
                return
            }

            val intent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                flags
            )

            onLog("USB", "Requesting USB system permission for ${target.productName ?: target.deviceName}...", false)
            manager.requestPermission(target, permissionIntent)
        } catch (e: Exception) {
            onLog("USB", "Error requesting USB permission: ${e.message}", true)
        }
    }
}
