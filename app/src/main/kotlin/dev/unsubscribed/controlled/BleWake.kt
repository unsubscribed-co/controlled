package dev.unsubscribed.controlled

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.ParcelUuid

/**
 * Turning an LG TV on over Bluetooth LE, the way LG ThinQ does it (docs/research/lg-power-on.md): one 10 s advertisement,
 * no connection, with LG's company id 0x00C4, 8 bytes seen unchanged in every capture, and the TV's Bluetooth address.
 * shortcut: experiment (2026-10-26); the 8 bytes may depend on the phone or the ThinQ account. Promote it once that's known.
 */
object BleWake {
    /** An example of the 8 bytes in ThinQ's advertisement (made up: each phone's are its own, see docs/research/lg-power-on.md). */
    const val THINQ_PREFIX = "0a1b2c3d4e5f0700"

    /** LG's Bluetooth address is its Wi-Fi MAC plus one: a4:ce:da:61:d0:d4 -> a4 ce da 61 d0 d5. */
    fun bluetoothAddress(wifiMac: String): ByteArray? {
        val value = wifiMac.replace(":", "").replace("-", "").takeIf { it.length == 12 }?.toLongOrNull(16) ?: return null
        return ByteArray(6) { i -> ((value + 1) ushr (8 * (5 - i))).toByte() }
    }

    fun hex(s: String): ByteArray? = s.takeIf { it.length % 2 == 0 }?.chunked(2)?.map { it.toIntOrNull(16)?.toByte() ?: return null }?.toByteArray()

    /** Advertises [prefix] + the TV's Bluetooth address for 10 s; false when Bluetooth or the permission isn't there. */
    @SuppressLint("MissingPermission") // BLUETOOTH_ADVERTISE: checked by the caller, granted by adb for the experiment
    fun send(context: Context, prefix: ByteArray, tv: ByteArray): Boolean {
        val advertiser = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser ?: return false
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true).setConnectable(true).setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW) // 100 ms, as ThinQ
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder().addManufacturerData(0x00C4, prefix + tv).addServiceUuid(ParcelUuid.fromString("0000feba-0000-1000-8000-00805f9b34fb")).build()
        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) = log { "ble wake started: status=$status tx=$txPower" }
            override fun onAdvertisingSetStopped(set: AdvertisingSet?) = log { "ble wake stopped" }
        }
        return runCatching { advertiser.startAdvertisingSet(params, data, AdvertiseData.Builder().build(), null, null, 1000, 0, callback) }
            .onFailure { log { "ble wake failed: $it" } }.isSuccess
    }
}
