package dev.unsubscribed.controlled

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.UUID

/**
 * Spike (branch spike/ble-mouse, not shipped): the phone as a Bluetooth LE mouse (HID over GATT), to measure whether the
 * PC receives moves more evenly than over classic Bluetooth. Driven by adb only: --es ble_mouse start|draw|stop.
 */
@SuppressLint("MissingPermission", "NewApi")
object BleMouse {
    private fun u(short: Int) = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(short))
    private val CCCD = u(0x2902)
    private val REPORT_REF = u(0x2908)

    // Report 1: 3 buttons + 5 pad bits, x and y as 16-bit relative (as the classic descriptor's mouse).
    private val MAP = bytes(
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x01, 0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x02, 0x81, 0x06,
        0xC0, 0xC0,
    )

    private var server: BluetoothGattServer? = null
    private var host: BluetoothDevice? = null
    private lateinit var report: BluetoothGattCharacteristic
    private val pending = ArrayDeque<BluetoothGattService>()
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private var owedX = 0
    private var sent = 0

    private val values = HashMap<UUID, ByteArray>()

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            log { "ble mouse service ${service.uuid} status=$status" }
            pending.removeFirstOrNull()?.let { server?.addService(it) }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            log { "ble mouse ${device.address} state=$newState status=$status bond=${device.bondState}" }
            if (newState == BluetoothProfile.STATE_CONNECTED) host = device else if (host?.address == device.address) host = null
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, id: Int, offset: Int, c: BluetoothGattCharacteristic) {
            val v = values[c.uuid] ?: ByteArray(0)
            log { "ble mouse read ${c.uuid.toString().substring(4, 8)} offset=$offset" }
            server?.sendResponse(device, id, BluetoothGatt.GATT_SUCCESS, offset, v.copyOfRange(minOf(offset, v.size), v.size))
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, id: Int, offset: Int, d: BluetoothGattDescriptor) {
            val v = if (d.uuid == REPORT_REF) bytes(0x01, 0x01) else bytes(0x00, 0x00)
            log { "ble mouse read descriptor ${d.uuid.toString().substring(4, 8)} of ${d.characteristic.uuid.toString().substring(4, 8)}" }
            server?.sendResponse(device, id, BluetoothGatt.GATT_SUCCESS, offset, v)
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, id: Int, c: BluetoothGattCharacteristic, prepared: Boolean, needsResponse: Boolean, offset: Int, value: ByteArray?) {
            log { "ble mouse write ${c.uuid.toString().substring(4, 8)} ${value?.joinToString { "%02x".format(it) }}" }
            if (needsResponse) server?.sendResponse(device, id, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, id: Int, d: BluetoothGattDescriptor, prepared: Boolean, needsResponse: Boolean, offset: Int, value: ByteArray?) {
            log { "ble mouse subscribe ${d.characteristic.uuid} ${value?.joinToString { "%02x".format(it) }}" }
            if (needsResponse) server?.sendResponse(device, id, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            // Callbacks come on a binder thread: everything about sending runs on the main thread, so busy can't race.
            main.post { busy = false; if (owedX != 0) flush() }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) = log { "ble mouse mtu=$mtu" }
    }

    private fun char(uuid: Int, props: Int, perms: Int, value: ByteArray) =
        BluetoothGattCharacteristic(u(uuid), props, perms).also { values[it.uuid] = value }

    private fun cccd() = BluetoothGattDescriptor(CCCD, PERMISSION_READ_ENCRYPTED or PERMISSION_WRITE_ENCRYPTED)

    var publicAddress = false

    fun start(context: Context) {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val s = manager.openGattServer(context, callback) ?: return log { "ble mouse: no gatt server" }
        server = s
        val enc = PERMISSION_READ_ENCRYPTED
        val info = BluetoothGattService(u(0x180A), BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(char(0x2A50, PROPERTY_READ, enc, bytes(0x02, 0x6B, 0x1D, 0x46, 0x02, 0x01, 0x00)))
        }
        val battery = BluetoothGattService(u(0x180F), BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(char(0x2A19, PROPERTY_READ or PROPERTY_NOTIFY, enc, bytes(100)).apply { addDescriptor(cccd()) })
        }
        report = char(0x2A4D, PROPERTY_READ or PROPERTY_NOTIFY, enc, bytes(0, 0, 0, 0, 0)).apply {
            addDescriptor(cccd())
            addDescriptor(BluetoothGattDescriptor(REPORT_REF, PERMISSION_READ_ENCRYPTED))
        }
        val hid = BluetoothGattService(u(0x1812), BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(char(0x2A4A, PROPERTY_READ, enc, bytes(0x11, 0x01, 0x00, 0x02)))
            addCharacteristic(char(0x2A4B, PROPERTY_READ, enc, MAP))
            addCharacteristic(char(0x2A4C, PROPERTY_WRITE_NO_RESPONSE, PERMISSION_WRITE_ENCRYPTED, bytes(0)))
            addCharacteristic(char(0x2A4E, PROPERTY_READ or PROPERTY_WRITE_NO_RESPONSE or PROPERTY_WRITE, enc or PERMISSION_WRITE_ENCRYPTED, bytes(1)))
            addCharacteristic(report)
        }
        pending += listOf(battery, hid)
        s.addService(info)

        // Experiment: advertise from the phone's fixed (public) address, so a PC that bonded can always recognise it.
        if (publicAddress) {
            val params = android.bluetooth.le.AdvertisingSetParameters.Builder().setLegacyMode(true).setConnectable(true).setScannable(true)
                .setInterval(android.bluetooth.le.AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(android.bluetooth.le.AdvertisingSetParameters.TX_POWER_HIGH)
                .also { b -> runCatching { b.javaClass.getMethod("setOwnAddressType", Int::class.javaPrimitiveType).invoke(b, 0) }
                    .onFailure { log { "ble mouse setOwnAddressType: $it" } } }.build()
            log { "ble mouse params ${params}" }
            val d = AdvertiseData.Builder().setIncludeDeviceName(true).addServiceUuid(ParcelUuid(u(0x1812))).build()
            runCatching {
                manager.adapter.bluetoothLeAdvertiser?.startAdvertisingSet(params, d, null, null, null, object : android.bluetooth.le.AdvertisingSetCallback() {
                    override fun onAdvertisingSetStarted(set: android.bluetooth.le.AdvertisingSet?, txPower: Int, status: Int) =
                        log { "ble mouse public advertising status=$status" }
                })
            }.onFailure { log { "ble mouse public advertising threw $it" } }
            return
        }
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true).setTimeout(0).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(true).addServiceUuid(ParcelUuid(u(0x1812))).build()
        manager.adapter.bluetoothLeAdvertiser?.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(s: AdvertiseSettings) = log { "ble mouse advertising" }
            override fun onStartFailure(code: Int) = log { "ble mouse advertise failed $code" }
        })
    }

    /** Draws back and forth for 20 s: 3 px every 8 ms, merged while a notification is still going out. */
    fun draw() {
        val end = SystemClock.uptimeMillis() + 20_000
        var t = 0
        sent = 0
        owedX = 0
        val tick = object : Runnable {
            override fun run() {
                if (SystemClock.uptimeMillis() > end) return log { "ble mouse draw done, sent=$sent" }
                owedX += if ((t++ / 250) % 2 == 0) 3 else -3
                flush()
                main.postDelayed(this, 8)
            }
        }
        main.post(tick)
    }

    private fun flush() {
        val d = host ?: return
        if (busy) return
        val x = owedX.coerceIn(-32767, 32767)
        owedX -= x
        busy = true
        val r = server?.notifyCharacteristicChanged(d, report, false, bytes(0, x and 0xFF, x shr 8, 0, 0))
        if (r == BluetoothGatt.GATT_SUCCESS) sent++ else { busy = false; owedX += x; log { "ble mouse notify r=$r" } }
    }

    /** Experiment: the phone opens the LE link to the PC itself, so Windows reads the services again. */
    fun connect(context: Context, address: String) {
        val d = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        val g = d.connectGatt(context, false, object : android.bluetooth.BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) =
                log { "ble mouse client ${gatt.device.address} state=$newState status=$status" }
        }, BluetoothDevice.TRANSPORT_LE)
        log { "ble mouse client connect $address started=${g != null}" }
    }

    /** Experiment: stop being visible and drop the PC first, so it can't see the services go when the app dies. */
    fun bye(context: Context) {
        context.getSystemService(BluetoothManager::class.java).adapter.bluetoothLeAdvertiser?.stopAdvertising(object : AdvertiseCallback() {})
        host?.let { server?.cancelConnection(it) }
        log { "ble mouse bye host=${host?.address}" }
    }

    fun stop(context: Context) {
        context.getSystemService(BluetoothManager::class.java).adapter.bluetoothLeAdvertiser?.stopAdvertising(object : AdvertiseCallback() {})
        server?.close()
        server = null
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
