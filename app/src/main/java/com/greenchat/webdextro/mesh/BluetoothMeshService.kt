package com.greenchat.webdextro.mesh

import android.content.Context
import android.util.Log
import com.greenchat.webdextro.crypto.EncryptionService
import com.greenchat.webdextro.crypto.MessagePadding
import com.greenchat.webdextro.model.GreenchatMessage
import com.greenchat.webdextro.model.DeliveryAck
import com.greenchat.webdextro.model.ReadReceipt
import com.greenchat.webdextro.protocol.GreenchatPacket
import com.greenchat.webdextro.protocol.MessageType
import com.greenchat.webdextro.protocol.SpecialRecipients
import kotlinx.coroutines.*
import java.util.*
import kotlin.random.Random

/**
 * Bluetooth mesh service - REFACTORED to use component-based architecture
 * 100% compatible with iOS version and maintains exact same UUIDs, packet format, and protocol logic
 * 
 * This is now a coordinator that orchestrates the following components:
 * - PeerManager: Peer lifecycle management
 * - FragmentManager: Message fragmentation and reassembly  
 * - SecurityManager: Security, duplicate detection, encryption
 * - StoreForwardManager: Offline message caching
 * - MessageHandler: Message type processing and relay logic
 * - BluetoothConnectionManager: BLE connections and GATT operations
 * - PacketProcessor: Incoming packet routing
 */
class BluetoothMeshService(private val context: Context) {
    
    companion object {
        private const val TAG = "BluetoothMeshService"
        private const val MAX_TTL: UByte = 7u
    }
    
    // My peer identification - same format as iOS
    val myPeerID: String = generateCompatiblePeerID()
    
    // Core components - each handling specific responsibilities
    private val encryptionService = EncryptionService(context)
    private val peerManager = PeerManager()
    private val fragmentManager = FragmentManager()
    private val securityManager = SecurityManager(encryptionService, myPeerID)
    private val storeForwardManager = StoreForwardManager()
    private val messageHandler = MessageHandler(myPeerID)
    internal val connectionManager = BluetoothConnectionManager(context, myPeerID) // Made internal for access
    private val packetProcessor = PacketProcessor(myPeerID)
    
    // Delegate for message callbacks (maintains same interface)
    var delegate: BluetoothMeshDelegate? = null
    
    // Coroutines
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        setupDelegates()
        startPeriodicDebugLogging()
    }
    
    /**
     * Start periodic debug logging every 10 seconds
     */
    private fun startPeriodicDebugLogging() {
        serviceScope.launch {
            while (isActive) {
                try {
                    delay(10000) // 10 seconds
                    val debugInfo = getDebugStatus()
                    Log.d(TAG, "=== PERIODIC DEBUG STATUS ===")
                    Log.d(TAG, debugInfo)
                    Log.d(TAG, "=== END DEBUG STATUS ===")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic debug logging: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Setup delegate connections between components
     */
    private fun setupDelegates() {
        // PeerManager delegates to main mesh service delegate
        peerManager.delegate = object : PeerManagerDelegate {
            override fun onPeerConnected(nickname: String) {
                delegate?.didConnectToPeer(nickname)
            }
            
            override fun onPeerDisconnected(nickname: String) {
                delegate?.didDisconnectFromPeer(nickname)
            }
            
            override fun onPeerListUpdated(peerIDs: List<String>) {
                delegate?.didUpdatePeerList(peerIDs)
            }
        }
        
        // SecurityManager delegate for key exchange notifications
        securityManager.delegate = object : SecurityManagerDelegate {
            override fun onKeyExchangeCompleted(peerID: String) {
                // Send announcement and cached messages after key exchange
                serviceScope.launch {
                    delay(100)
                    sendAnnouncementToPeer(peerID)
                    
                    delay(500)
                    storeForwardManager.sendCachedMessages(peerID)
                }
            }
        }
        
        // StoreForwardManager delegates
        storeForwardManager.delegate = object : StoreForwardManagerDelegate {
            override fun isFavorite(peerID: String): Boolean {
                return delegate?.isFavorite(peerID) ?: false
            }
            
            override fun isPeerOnline(peerID: String): Boolean {
                return peerManager.isPeerActive(peerID)
            }
            
            override fun sendPacket(packet: GreenchatPacket) {
                connectionManager.broadcastPacket(packet)
            }
        }
        
        // MessageHandler delegates
        messageHandler.delegate = object : MessageHandlerDelegate {
            // Peer management
            override fun addOrUpdatePeer(peerID: String, nickname: String): Boolean {
                return peerManager.addOrUpdatePeer(peerID, nickname)
            }
            
            override fun removePeer(peerID: String) {
                peerManager.removePeer(peerID)
            }
            
            override fun updatePeerNickname(peerID: String, nickname: String) {
                peerManager.addOrUpdatePeer(peerID, nickname)
            }
            
            override fun getPeerNickname(peerID: String): String? {
                return peerManager.getPeerNickname(peerID)
            }
            
            override fun getNetworkSize(): Int {
                return peerManager.getActivePeerCount()
            }
            
            override fun getMyNickname(): String? {
                return delegate?.getNickname()
            }
            
            // Packet operations
            override fun sendPacket(packet: GreenchatPacket) {
                connectionManager.broadcastPacket(packet)
            }
            
            override fun relayPacket(packet: GreenchatPacket) {
                connectionManager.broadcastPacket(packet)
            }
            
            override fun getBroadcastRecipient(): ByteArray {
                return SpecialRecipients.BROADCAST
            }
            
            // Cryptographic operations
            override fun verifySignature(packet: GreenchatPacket, peerID: String): Boolean {
                return securityManager.verifySignature(packet, peerID)
            }
            
            override fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray? {
                return securityManager.encryptForPeer(data, recipientPeerID)
            }
            
            override fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray? {
                return securityManager.decryptFromPeer(encryptedData, senderPeerID)
            }
            
            // Message operations  
            override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
                return delegate?.decryptChannelMessage(encryptedContent, channel)
            }
            
            // Callbacks
            override fun onMessageReceived(message: GreenchatMessage) {
                delegate?.didReceiveMessage(message)
            }
            
            override fun onChannelLeave(channel: String, fromPeer: String) {
                delegate?.didReceiveChannelLeave(channel, fromPeer)
            }
            
            override fun onPeerDisconnected(nickname: String) {
                delegate?.didDisconnectFromPeer(nickname)
            }
            
            override fun onDeliveryAckReceived(ack: DeliveryAck) {
                delegate?.didReceiveDeliveryAck(ack)
            }
            
            override fun onReadReceiptReceived(receipt: ReadReceipt) {
                delegate?.didReceiveReadReceipt(receipt)
            }
        }
        
        // PacketProcessor delegates
        packetProcessor.delegate = object : PacketProcessorDelegate {
            override fun validatePacketSecurity(packet: GreenchatPacket, peerID: String): Boolean {
                return securityManager.validatePacket(packet, peerID)
            }
            
            override fun updatePeerLastSeen(peerID: String) {
                peerManager.updatePeerLastSeen(peerID)
            }
            
            override fun handleKeyExchange(packet: GreenchatPacket, peerID: String): Boolean {
                return runBlocking { securityManager.handleKeyExchange(packet, peerID) }
            }
            
            override fun handleAnnounce(packet: GreenchatPacket, peerID: String) {
                serviceScope.launch { messageHandler.handleAnnounce(packet, peerID) }
            }
            
            override fun handleMessage(packet: GreenchatPacket, peerID: String) {
                serviceScope.launch { messageHandler.handleMessage(packet, peerID) }
            }
            
            override fun handleLeave(packet: GreenchatPacket, peerID: String) {
                serviceScope.launch { messageHandler.handleLeave(packet, peerID) }
            }
            
            override fun handleFragment(packet: GreenchatPacket): GreenchatPacket? {
                return fragmentManager.handleFragment(packet)
            }
            
            override fun handleDeliveryAck(packet: GreenchatPacket, peerID: String) {
                serviceScope.launch { messageHandler.handleDeliveryAck(packet, peerID) }
            }
            
            override fun handleReadReceipt(packet: GreenchatPacket, peerID: String) {
                serviceScope.launch { messageHandler.handleReadReceipt(packet, peerID) }
            }
            
            override fun sendAnnouncementToPeer(peerID: String) {
                this@BluetoothMeshService.sendAnnouncementToPeer(peerID)
            }
            
            override fun sendCachedMessages(peerID: String) {
                storeForwardManager.sendCachedMessages(peerID)
            }
            
            override fun relayPacket(packet: GreenchatPacket) {
                connectionManager.broadcastPacket(packet)
            }
        }
        
        // BluetoothConnectionManager delegates
        connectionManager.delegate = object : BluetoothConnectionManagerDelegate {
            override fun onPacketReceived(packet: GreenchatPacket, peerID: String, device: android.bluetooth.BluetoothDevice?) {
                packetProcessor.processPacket(packet, peerID)
            }
            
            override fun onDeviceConnected(device: android.bluetooth.BluetoothDevice) {
                // Send key exchange to newly connected device
                serviceScope.launch {
                    delay(100) // Ensure connection is stable
                    sendKeyExchangeToDevice()
                }
            }
        }
    }
    
    /**
     * Start the mesh service
     */
    fun startServices() {
        Log.i(TAG, "Starting Bluetooth mesh service with peer ID: $myPeerID")
        
        if (connectionManager.startServices()) {
            Log.i(TAG, "Bluetooth services started successfully")
            
            // Send initial announcements after services are ready
            serviceScope.launch {
                delay(1000)
                sendBroadcastAnnounce()
            }
        } else {
            Log.e(TAG, "Failed to start Bluetooth services")
        }
    }
    
    /**
     * Stop all mesh services
     */
    fun stopServices() {
        Log.i(TAG, "Stopping Bluetooth mesh service")
        
        // Send leave announcement
        sendLeaveAnnouncement()
        
        serviceScope.launch {
            delay(200) // Give leave message time to send
            
            // Stop all components
            connectionManager.stopServices()
            peerManager.shutdown()
            fragmentManager.shutdown()
            securityManager.shutdown()
            storeForwardManager.shutdown()
            messageHandler.shutdown()
            packetProcessor.shutdown()
            
            serviceScope.cancel()
        }
    }
    
    /**
     * Send public message
     */
    fun sendMessage(content: String, mentions: List<String> = emptyList(), channel: String? = null) {
        if (content.isEmpty()) return
        
        serviceScope.launch {
            val nickname = delegate?.getNickname() ?: myPeerID
            
            val message = GreenchatMessage(
                sender = nickname,
                content = content,
                timestamp = Date(),
                isRelay = false,
                senderPeerID = myPeerID,
                mentions = if (mentions.isNotEmpty()) mentions else null,
                channel = channel
            )
            
            message.toBinaryPayload()?.let { messageData ->
                // Sign the message
                val signature = securityManager.signPacket(messageData)
                
                val packet = GreenchatPacket(
                    type = MessageType.MESSAGE.value,
                    senderID = myPeerID.toByteArray(),
                    recipientID = SpecialRecipients.BROADCAST,
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = messageData,
                    signature = signature,
                    ttl = MAX_TTL
                )
                
                // Send with random delay and retry for reliability
                // delay(Random.nextLong(50, 500))
                connectionManager.broadcastPacket(packet)
            }
        }
    }
    
    /**
     * Send private message
     */
    fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String? = null) {
        if (content.isEmpty() || recipientPeerID.isEmpty() || recipientNickname.isEmpty()) return
        
        serviceScope.launch {
            val nickname = delegate?.getNickname() ?: myPeerID
            
            val message = GreenchatMessage(
                id = messageID ?: UUID.randomUUID().toString(),
                sender = nickname,
                content = content,
                timestamp = Date(),
                isRelay = false,
                isPrivate = true,
                recipientNickname = recipientNickname,
                senderPeerID = myPeerID
            )
            
            message.toBinaryPayload()?.let { messageData ->
                try {
                    // Pad and encrypt
                    val blockSize = MessagePadding.optimalBlockSize(messageData.size)
                    val paddedData = MessagePadding.pad(messageData, blockSize)
                    val encryptedPayload = securityManager.encryptForPeer(paddedData, recipientPeerID)
                    
                    if (encryptedPayload != null) {
                        // Sign
                        val signature = securityManager.signPacket(encryptedPayload)
                        
                        val packet = GreenchatPacket(
                            type = MessageType.MESSAGE.value,
                            senderID = myPeerID.toByteArray(),
                            recipientID = recipientPeerID.toByteArray(),
                            timestamp = System.currentTimeMillis().toULong(),
                            payload = encryptedPayload,
                            signature = signature,
                            ttl = MAX_TTL
                        )
                        
                        // Cache for offline favorites
                        if (storeForwardManager.shouldCacheForPeer(recipientPeerID)) {
                            storeForwardManager.cacheMessage(packet, messageID ?: message.id)
                        }
                        
                        // Send with delay
                        delay(Random.nextLong(50, 500))
                        connectionManager.broadcastPacket(packet)
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send private message: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Send broadcast announce
     */
    fun sendBroadcastAnnounce() {
        serviceScope.launch {
            val nickname = delegate?.getNickname() ?: myPeerID
            
            val announcePacket = GreenchatPacket(
                type = MessageType.ANNOUNCE.value,
                ttl = 3u,
                senderID = myPeerID,
                payload = nickname.toByteArray()
            )
            
            // Send multiple times for reliability
            delay(Random.nextLong(0, 500))
            connectionManager.broadcastPacket(announcePacket)
            
            delay(500 + Random.nextLong(0, 500))
            connectionManager.broadcastPacket(announcePacket)
            
            delay(1000 + Random.nextLong(0, 500))
            connectionManager.broadcastPacket(announcePacket)
        }
    }
    
    /**
     * Send announcement to specific peer
     */
    private fun sendAnnouncementToPeer(peerID: String) {
        if (peerManager.hasAnnouncedToPeer(peerID)) return
        
        val nickname = delegate?.getNickname() ?: myPeerID
        val packet = GreenchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 3u,
            senderID = myPeerID,
            payload = nickname.toByteArray()
        )
        
        connectionManager.broadcastPacket(packet)
        peerManager.markPeerAsAnnouncedTo(peerID)
    }
    
    /**
     * Send key exchange
     */
    private fun sendKeyExchangeToDevice() {
        val publicKeyData = securityManager.getCombinedPublicKeyData()
        val packet = GreenchatPacket(
            type = MessageType.KEY_EXCHANGE.value,
            ttl = 1u,
            senderID = myPeerID,
            payload = publicKeyData
        )
        
        connectionManager.broadcastPacket(packet)
        Log.d(TAG, "Sent key exchange")
    }
    
    /**
     * Send leave announcement
     */
    private fun sendLeaveAnnouncement() {
        val nickname = delegate?.getNickname() ?: myPeerID
        val packet = GreenchatPacket(
            type = MessageType.LEAVE.value,
            ttl = 1u,
            senderID = myPeerID,
            payload = nickname.toByteArray()
        )
        
        connectionManager.broadcastPacket(packet)
    }
    
    /**
     * Get peer nicknames
     */
    fun getPeerNicknames(): Map<String, String> = peerManager.getAllPeerNicknames()
    
    /**
     * Get peer RSSI values  
     */
    fun getPeerRSSI(): Map<String, Int> = peerManager.getAllPeerRSSI()
    
    /**
     * Get debug status information
     */
    fun getDebugStatus(): String {
        return buildString {
            appendLine("=== Bluetooth Mesh Service Debug Status ===")
            appendLine("My Peer ID: $myPeerID")
            appendLine()
            appendLine(connectionManager.getDebugInfo())
            appendLine()
            appendLine(peerManager.getDebugInfo())
            appendLine()
            appendLine(fragmentManager.getDebugInfo())
            appendLine()
            appendLine(securityManager.getDebugInfo())
            appendLine()
            appendLine(storeForwardManager.getDebugInfo())
            appendLine()
            appendLine(messageHandler.getDebugInfo())
            appendLine()
            appendLine(packetProcessor.getDebugInfo())
        }
    }
    
    /**
     * Generate peer ID compatible with iOS
     */
    private fun generateCompatiblePeerID(): String {
        val randomBytes = ByteArray(4)
        Random.nextBytes(randomBytes)
        return randomBytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Delegate interface for mesh service callbacks (maintains exact same interface)
 */
interface BluetoothMeshDelegate {
    fun didReceiveMessage(message: GreenchatMessage)
    fun didConnectToPeer(peerID: String)
    fun didDisconnectFromPeer(peerID: String)
    fun didUpdatePeerList(peers: List<String>)
    fun didReceiveChannelLeave(channel: String, fromPeer: String)
    fun didReceiveDeliveryAck(ack: DeliveryAck)
    fun didReceiveReadReceipt(receipt: ReadReceipt)
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?
    fun getNickname(): String?
    fun isFavorite(peerID: String): Boolean
}
