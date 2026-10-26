package dev.unsubscribed.controlled

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID

/**
 * DEV EXPERIMENT (docs/research/lg-ble-registration.md, "GATT wake-enrol"): probe whether the TV exposes LG's LECCP
 * GATT service to our (unsigned) connection, and a raw-write hook to try the enrol frames live. This is the spike's
 * step 1 — it deliberately does NOT implement the pairing state machine, because two bytes of it (the SET_PAIRING
 * sub=1 actionData layout and the response/notify characteristic) could not be reconstructed byte-exact from the
 * decompile, and guessing them would just fail silently. Build those only after the probe shows FEB9 is reachable.
 * shortcut: probe only; promote to the full pair->SET_WAKE_UP flow once the probe confirms service + notify.
 */
object GattWake {
    private fun uuid(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
    val SERVICE = uuid("feb9")           // UUID_LECCP_SERVICE
    val ACTION = uuid("2ab8")            // UUID_LECCP_ACTION — Request frames go here
    val MODE = uuid("2abf")              // UUID_LECCP_MODE — readMode reads this
    private val CCCD = uuid("2902")

    /** BleModel$BleAction$Request.getBytes: [actionId][subActionId][actionData...]. */
    fun frame(actionId: Int, sub: Int, data: ByteArray = ByteArray(0)) =
        byteArrayOf(actionId.toByte(), sub.toByte()) + data

    /** SET_WAKE_UP: actionId 11, sub 1, no data (SeamlessRegisterHelper.actionWakeup). */
    val SET_WAKE_UP = frame(11, 1)

    /** SET_PAIRING send-PIN: actionId 12, sub 2, PairingData.getBytes = [pairCode=2][pin ascii]. */
    fun pairPin(pin: String) = frame(12, 2, byteArrayOf(2) + pin.toByteArray())

    private var gatt: BluetoothGatt? = null
    private var action: BluetoothGattCharacteristic? = null
    @Volatile private var connecting = false

    private val callback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log { "gatt state=$newState status=$status" }
            if (newState == BluetoothGatt.STATE_CONNECTED) g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log { "gatt services status=$status" }
            g.services.forEach { s ->
                log { "svc ${s.uuid}" + s.characteristics.joinToString("") { "\n  chr ${it.uuid} props=${it.properties}" } }
            }
            val svc = g.getService(SERVICE) ?: run { log { "gatt LECCP service FEB9 NOT exposed" }; return }
            action = svc.getCharacteristic(ACTION)?.also { c ->
                g.setCharacteristicNotification(c, true)
                c.getDescriptor(CCCD)?.let { d ->
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
            }
            svc.getCharacteristic(MODE)?.let { g.readCharacteristic(it) }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            log { "gatt read ${c.uuid} status=$status = ${c.value?.toHex()}" }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            log { "gatt notify ${c.uuid} = ${c.value?.toHex()}" }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            log { "gatt write ${c.uuid} status=$status" }
        }
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT: granted by adb for the experiment
    fun probe(context: Context, tvColonMac: String): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        val device = runCatching { adapter.getRemoteDevice(tvColonMac.uppercase()) }.getOrNull() ?: return false // getRemoteDevice requires uppercase
        log { "gatt probe connecting $tvColonMac" }
        gatt = device.connectGatt(context, false, callback)
        return gatt != null
    }

    /**
     * Scan for the TV's LECCP advert and connect to the address it is advertising from (the TV uses a random adv
     * address; its public identity is not directly connectable unprovisioned). Connects to the first device whose
     * advert carries service FEB9 or LG manufacturer 0x00C4. Logs every LG advert so we see what the TV emits while on.
     */
    @SuppressLint("MissingPermission") // BLUETOOTH_SCAN + CONNECT: granted by adb for the experiment
    fun scanAndProbe(context: Context): Boolean {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return false
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val rec = r.scanRecord
                val hasFeb9 = rec?.serviceUuids?.any { it.uuid == SERVICE } == true
                val lg = rec?.getManufacturerSpecificData(0x00C4)
                if (!hasFeb9 && lg == null) return
                if (connecting) return
                connecting = true
                log { "gatt scan found ${r.device.address} feb9=$hasFeb9 lg=${lg?.toHex()} name=${rec?.deviceName}" }
                scanner.stopScan(this)
                connect(context, r.device)
            }
            override fun onScanFailed(code: Int) = log { "gatt scan failed code=$code" }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        log { "gatt scan starting (FEB9)" }
        scanner.startScan(filters, settings, cb)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun connect(context: Context, device: android.bluetooth.BluetoothDevice) {
        log { "gatt connecting ${device.address}" }
        gatt = device.connectGatt(context, false, callback)
    }

    /** Write a raw frame to ACTION on the open probe connection (e.g. SET_WAKE_UP, or pairPin after we learn pairing). */
    @SuppressLint("MissingPermission")
    fun write(bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = action ?: return false
        c.value = bytes
        return g.writeCharacteristic(c).also { log { "gatt write ${bytes.toHex()} queued=$it" } }
    }

    /** Read one FEB9 characteristic by short UUID (e.g. "2abf" MODE, "2abd" PAIR_CODES, "2abe" PAIR_KEY_DATA). */
    @SuppressLint("MissingPermission")
    fun read(short: String): Boolean {
        val g = gatt ?: return false
        val c = g.getService(SERVICE)?.getCharacteristic(uuid(short)) ?: return false
        return g.readCharacteristic(c)
    }

    fun close() { gatt?.close(); gatt = null; action = null; connecting = false }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
