/*
 * MIT License
 *
 * Copyright (c) 2019 Adetunji Dahunsi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.tunjid.rcswitchcontrol.a433mhz.services

import android.annotation.TargetApi
import android.app.Service
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.widget.Toast
import com.tunjid.androidx.core.components.services.SelfBinder
import com.tunjid.androidx.core.components.services.SelfBindingService
import com.tunjid.rcswitchcontrol.a433mhz.R
import com.tunjid.rcswitchcontrol.a433mhz.models.RfSwitch.Companion.SWITCH_PREFS
import com.tunjid.rcswitchcontrol.common.Broadcaster
import io.reactivex.disposables.CompositeDisposable
import java.util.*

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
class ClientBleService : Service(), SelfBindingService<ClientBleService> {

    val isConnected: Boolean
        get() = connectionState == ACTION_GATT_CONNECTED

    private val binder = Binder()
    private val disposable = CompositeDisposable()

    var connectionState = ACTION_GATT_DISCONNECTED
        private set
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null

    // Queue for reading multiple characteristics due to delay induced by callback.
    private val readQueue = LinkedList<BluetoothGattCharacteristic>()
    // Queue for writing multiple descriptors due to delay induced by callback.
    private val writeQueue = LinkedList<BluetoothGattDescriptor>()
    // Map of characteristics of interest
    private val characteristicMap = HashMap<String, BluetoothGattCharacteristic>()

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectionState = ACTION_GATT_CONNECTED
                    bluetoothGatt?.discoverServices()

                    // Save this device for connnecting later
                    getSharedPreferences(SWITCH_PREFS, Context.MODE_PRIVATE).edit()
                            .putString(LAST_PAIRED_DEVICE, connectedDevice!!.address)
                            .apply()
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    connectionState = ACTION_GATT_CONNECTING
                    broadcastUpdate(connectionState)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectionState = ACTION_GATT_DISCONNECTED
                    broadcastUpdate(connectionState)
                }
            }

            Log.i(TAG, "STATE = $connectionState")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val success = status == BluetoothGatt.GATT_SUCCESS

            if (success) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED)
                findGattServices(supportedGattServices)
                broadcastUpdate(connectionState)
            }

            Log.i(TAG, "onServicesDiscovered status: $success")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.i(TAG, "onDescriptorWrite: $status")

            // Remove the item that we just wrote
            if (status == BluetoothGatt.GATT_SUCCESS && writeQueue.size > 0) writeQueue.remove()

            // Read the top of the queue
            if (writeQueue.size > 0) bluetoothGatt?.writeDescriptor(writeQueue.element())
        }

        override// Checks queue for characteristics to be read and reads them
        fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            Log.i(TAG, "onCharacteristicRead: $status")

            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (readQueue.size > 0) readQueue.remove()

            when (characteristic.uuid.toString()) {
                C_HANDLE_CONTROL -> broadcastUpdate(ACTION_CONTROL, characteristic)
                C_HANDLE_SNIFFER -> broadcastUpdate(DATA_AVAILABLE_SNIFFER, characteristic)
                C_HANDLE_TRANSMITTER -> broadcastUpdate(DATA_AVAILABLE_TRANSMITTER, characteristic)
                else -> broadcastUpdate(DATA_AVAILABLE_UNKNOWN, characteristic)
            }

            if (readQueue.size > 0) bluetoothGatt?.readCharacteristic(readQueue.element())
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            when (characteristic.uuid.toString()) {
                C_HANDLE_CONTROL -> broadcastUpdate(ACTION_CONTROL, characteristic)
                C_HANDLE_SNIFFER -> broadcastUpdate(ACTION_SNIFFER, characteristic)
                C_HANDLE_TRANSMITTER -> broadcastUpdate(ACTION_TRANSMITTER, characteristic)
            }
        }
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after `BluetoothGatt#discoverServices()` completes successfully.
     *
     * @return A `List` of supported services.
     */
    private val supportedGattServices: List<BluetoothGattService>?
        get() = if (bluetoothGatt == null) null else bluetoothGatt?.services

    override fun onCreate() {
        super.onCreate()

        disposable.add(Broadcaster.listen(ACTION_TRANSMITTER).subscribe({ intent ->
            val data = intent.getStringExtra(DATA_AVAILABLE_TRANSMITTER)
            val transmission = Base64.decode(data, Base64.DEFAULT)
            writeCharacteristicArray(C_HANDLE_TRANSMITTER, transmission)
        }, Throwable::printStackTrace))
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        initialize(intent)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent): SelfBinder<ClientBleService> {
        initialize(intent)
        return binder
    }

    private fun initialize(intent: Intent?) {
        if (intent == null || isConnected) return

        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled || ACTION_GATT_CONNECTING == connectionState) return

        bluetoothAdapter = adapter

        val sharedPreferences = getSharedPreferences(SWITCH_PREFS, Context.MODE_PRIVATE)
        val lastConnectedDevice = sharedPreferences.getString(LAST_PAIRED_DEVICE, "")

        val bluetoothDevice = when {
            intent.hasExtra(BLUETOOTH_DEVICE) -> intent.getParcelableExtra(BLUETOOTH_DEVICE)
            lastConnectedDevice != null && lastConnectedDevice.isNotBlank() -> adapter.getRemoteDevice(lastConnectedDevice)
            else -> null
        }
        // Retrieve device from notification intent or shared preferences

        if (bluetoothDevice == null && connectedDevice == null) {
            // Service was restarted, but has no device to connect to. Close gatt and stop the service
            close()
            stopSelf()
            Log.i(TAG, "Restarted with no device to connect to, stopping service")
            return
        }

        if (bluetoothDevice != null) this.connectedDevice = bluetoothDevice

        connect(connectedDevice)

        Log.i(TAG, "Initialized BLE connection")
    }

    override fun onDestroy() {
        super.onDestroy()
        disposable.clear()
        close()
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param bluetoothDevice The device to connect to.
     * `BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)`
     * callback.
     */
    @TargetApi(Build.VERSION_CODES.M)
    fun connect(bluetoothDevice: BluetoothDevice?) {
        if (bluetoothAdapter == null || bluetoothDevice == null) {
            showToast(this, R.string.ble_service_adapter_uninitialized)
            return
        }

        // Previously connected device.  Try to reconnect.
        if (bluetoothDevice == connectedDevice && bluetoothGatt != null) {
            showToast(this, R.string.ble_service_reconnecting)

            when (bluetoothGatt?.connect()) {
                true -> broadcastUpdate({ connectionState = ACTION_GATT_CONNECTING; connectionState }())
                else -> showToast(this, R.string.ble_service_failed_to_connect)
            }
        } else {
            // Set the autoConnect parameter to true.
            bluetoothGatt = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> bluetoothDevice.connectGatt(this, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
                else -> bluetoothDevice.connectGatt(this, true, gattCallback)
            }

            connectionState = ACTION_GATT_CONNECTING

            broadcastUpdate(connectionState)
            showToast(this, R.string.ble_service_new_connection)
        }
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * `BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)`
     * callback.
     */
    fun disconnect() = withBluetooth { bluetoothGatt?.disconnect() }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    fun close() {
        connectionState = ACTION_GATT_DISCONNECTED
        if (bluetoothGatt == null) return

        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    fun writeCharacteristicArray(uuid: String, values: ByteArray) {
        withBluetooth {
            val characteristic = characteristicMap[uuid]

            if (characteristic == null) {
                showToast(this, R.string.disconnected)
                return@withBluetooth
            }

            characteristic.value = values
            bluetoothGatt?.writeCharacteristic(characteristic)
        }
    }

    /**
     * Used to send broadcasts that don't have attached data
     */
    private fun broadcastUpdate(action: String) {
        Broadcaster.push(Intent(action))
    }

    /**
     * Used to send broadcasts that have attached data
     */
    private fun broadcastUpdate(action: String, characteristic: BluetoothGattCharacteristic) {
        val intent = Intent(action)
        val rawData = characteristic.value

        if (rawData != null && rawData.isNotEmpty()) {
            when (characteristic.uuid.toString()) {
                C_HANDLE_CONTROL -> intent.putExtra(DATA_AVAILABLE_CONTROL, rawData)
                C_HANDLE_SNIFFER -> intent.putExtra(DATA_AVAILABLE_SNIFFER, rawData)
                C_HANDLE_TRANSMITTER -> Log.i(TAG, "Transmitted data: ")

                else -> {
                    val stringBuilder = StringBuilder(rawData.size)
                    for (byteChar in rawData) {
                        stringBuilder.append(byteChar.toInt())
                        stringBuilder.append(" ")
                    }
                    intent.putExtra(EXTRA_DATA, String(rawData) + "\n" + stringBuilder.toString())
                }
            }
        }
        Broadcaster.push(intent)
    }

    private fun withBluetooth(runnable: () -> Unit) {
        if (bluetoothAdapter != null && bluetoothGatt != null) runnable.invoke()
        else showToast(this, R.string.ble_service_adapter_uninitialized)
    }

    private fun findGattServices(gattServices: List<BluetoothGattService>?) {
        if (gattServices == null) return

        val handler = Handler(HandlerThread("IndicationSetThread").apply { start() }.looper)

        // Loops through available GATT Services.
        for (gattService in gattServices) {
            val gattCharacteristics = gattService.characteristics

            // Loops through available Characteristics and find the ones I'm interested in
            for (gattCharacteristic in gattCharacteristics) {
                val uuid = gattCharacteristic.uuid.toString()
                when (uuid) {
                    C_HANDLE_CONTROL, C_HANDLE_SNIFFER, C_HANDLE_TRANSMITTER -> handler.post { withBluetooth { setCharacteristicIndication(uuid) } }
                }
                characteristicMap[uuid] = gattCharacteristic
            }
        }
    }

    /**
     * Enables or disables notifications or indications on a given characteristic.
     *
     * @param uuid UUID of the Characteristic to act on.
     */

    private fun setCharacteristicIndication(uuid: String) {
        val characteristic = characteristicMap[uuid] ?: return

        bluetoothGatt?.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))

        descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        writeGattDescriptor(descriptor)
    }

    private fun writeGattDescriptor(descriptor: BluetoothGattDescriptor) {
        //put the descriptor into the write queue
        writeQueue.add(descriptor)

        //if there is only 1 item in the queue, then write it.  If more than 1, we handle asynchronously in the callback above
        if (writeQueue.size > 0) bluetoothGatt?.writeDescriptor(descriptor)
    }

    private inner class Binder : SelfBinder<ClientBleService>() {
        override val service
            get() = this@ClientBleService
    }

    companion object {

        private val TAG = ClientBleService::class.java.simpleName

        const val STATE_SNIFFING: Byte = 0

        // Services
        const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"
        const val DATA_TRANSCEIVER_SERVICE = "195ae58a-437a-489b-b0cd-b7c9c394bae4"
        // Characteristics
        const val C_HANDLE_CONTROL = "5fc569a0-74a9-4fa4-b8b7-8354c86e45a4"
        const val C_HANDLE_SNIFFER = "21819ab0-c937-4188-b0db-b9621e1696cd"
        const val C_HANDLE_TRANSMITTER = "3c79909b-cc1c-4bb9-8595-f99fa98c6503"

        // Keys for data
        const val BLUETOOTH_DEVICE = "BLUETOOTH_DEVICE"
        const val LAST_PAIRED_DEVICE = "LAST_PAIRED_DEVICE"
        const val ACTION_GATT_CONNECTED = "ACTION_GATT_CONNECTED"
        const val ACTION_GATT_CONNECTING = "ACTION_GATT_CONNECTING"
        const val ACTION_GATT_DISCONNECTED = "ACTION_GATT_DISCONNECTED"
        const val ACTION_GATT_SERVICES_DISCOVERED = "ACTION_GATT_SERVICES_DISCOVERED"
        const val ACTION_CONTROL = "ACTION_CONTROL"
        const val ACTION_SNIFFER = "ACTION_SNIFFER"
        const val ACTION_TRANSMITTER = "ACTION_TRANSMITTER"
        const val DATA_AVAILABLE_CONTROL = "DATA_AVAILABLE_CONTROL"
        const val DATA_AVAILABLE_SNIFFER = "DATA_AVAILABLE_SNIFFER"
        const val DATA_AVAILABLE_TRANSMITTER = "DATA_AVAILABLE_TRANSMITTER"
        const val DATA_AVAILABLE_UNKNOWN = "ACTION_DATA_AVAILABLE"
        const val EXTRA_DATA = "EXTRA_DATA"

        fun showToast(context: Context, resourceId: Int) {
            Toast.makeText(context, resourceId, Toast.LENGTH_SHORT).show()
        }
    }
}

///**
// * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
// * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
// * callback.
// *
// * @param characteristic The characteristic to read from.
// */
//public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
//
//    if (bluetoothAdapter == null || bluetoothGatt == null) {
//        showToast(this, R.string.ble_service_adapter_uninitialized);
//        return;
//    }
//
//    //put the characteristic into the read queue
//    readQueue.add(characteristic);
//
//    //if there is only 1 item in the queue, then read it.  If more than 1, we handle asynchronously in the callback above
//    //GIVE PRECEDENCE to descriptor writes.  They must all finish first.
//    if (readQueue.size() > 0) {
//        bluetoothGatt.readCharacteristic(characteristic);
//    }
//}