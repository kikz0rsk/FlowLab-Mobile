package com.danizek.dp

import java.nio.ByteBuffer
import java.net.InetAddress

enum class Protocol {
    TCP, UDP
}

class Packet(
    val sourcePort: Int,
    val destinationPort: Int,
    val protocol: Protocol,
    val sourceIp: InetAddress,
    val destinationIp: InetAddress,
    val data: ByteArray,
    val isIPv6: Boolean
) {

    companion object {
        // Deserialize byte array to Packet
        fun deserialize(bytes: ByteArray): Packet {
            val buffer = ByteBuffer.wrap(bytes)

            // Identify IP version
            val version = (buffer.get(0).toInt() shr 4) and 0x0F
            val isIPv6 = false

            val sourceIp: InetAddress
            val destinationIp: InetAddress
            val sourcePort: Int
            val destinationPort: Int
            val protocol: Protocol

            if (!isIPv6) {
                // Parse IPv4 Header (20 bytes)
                buffer.position(9) // Move to protocol field
                val protocolByte = buffer.get().toUInt()
                protocol = if (protocolByte == 6u) Protocol.TCP else Protocol.UDP

                buffer.position(12) // Move to source IP address

                val sourceIpBytes = ByteArray(4)
                val destinationIpBytes = ByteArray(4)
                buffer.get(sourceIpBytes) // Read source IP
                buffer.get(destinationIpBytes) // Read destination IP

                sourceIp = InetAddress.getByAddress(sourceIpBytes)
                destinationIp = InetAddress.getByAddress(destinationIpBytes)

                buffer.position(20) // Move to transport layer

                // Parse source and destination ports
                sourcePort = buffer.getShort().toInt() and 0xFFFF
                destinationPort = buffer.getShort().toInt() and 0xFFFF
            } else {
                // Parse IPv6 Header (40 bytes)
                buffer.position(6) // Move to next header field (protocol)
                val protocolByte = buffer.get().toInt()
                buffer.position(8) // Move to source IP address

                val sourceIpBytes = ByteArray(16)
                val destinationIpBytes = ByteArray(16)
                buffer.get(sourceIpBytes) // Read source IP
                buffer.get(destinationIpBytes) // Read destination IP

                sourceIp = InetAddress.getByAddress(sourceIpBytes)
                destinationIp = InetAddress.getByAddress(destinationIpBytes)

                buffer.position(40) // Move to transport layer

                // Parse source and destination ports
                sourcePort = buffer.getShort().toInt() and 0xFFFF
                destinationPort = buffer.getShort().toInt() and 0xFFFF

                protocol = if (protocolByte == 6) Protocol.TCP else Protocol.UDP
            }

            // Extract remaining data (payload)
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            return Packet(sourcePort, destinationPort, protocol, sourceIp, destinationIp, data, isIPv6)
        }
    }

    // Serialize Packet into a byte array
    fun serialize(): ByteArray {
        val ipHeaderSize = if (isIPv6) 40 else 20
        val buffer = ByteBuffer.allocate(ipHeaderSize + 20 + data.size) // 20 for TCP/UDP header

        // Write IP header
        if (isIPv6) {
            buffer.put(0x60.toByte()) // IPv6 version field
            buffer.position(8) // Skip to source IP address
            buffer.put(sourceIp.address) // Write source IP
            buffer.put(destinationIp.address) // Write destination IP
            buffer.position(40) // Skip rest of IPv6 header
        } else {
            buffer.put(0x45.toByte()) // IPv4 version and header length
            buffer.position(9) // Skip to protocol field
            buffer.put(if (protocol == Protocol.TCP) 6.toByte() else 17.toByte()) // Protocol
            buffer.position(12) // Skip to source IP address
            buffer.put(sourceIp.address) // Write source IP
            buffer.put(destinationIp.address) // Write destination IP
            buffer.position(20) // Skip rest of IPv4 header
        }

        // Write transport layer headers (TCP/UDP)
        buffer.putShort(sourcePort.toShort())
        buffer.putShort(destinationPort.toShort())

        if (protocol == Protocol.TCP) {
            // Simplified TCP header
            buffer.putInt(0) // Sequence number (placeholder)
            buffer.putInt(0) // Acknowledgment number (placeholder)
            buffer.putShort(0x5010.toShort()) // Data offset and flags
        } else {
            // Simplified UDP header
            buffer.putShort((8 + data.size).toShort()) // Length
            buffer.putShort(0) // Checksum placeholder
        }

        // Write data payload
        buffer.put(data)

        return buffer.array()
    }

    override fun toString(): String {
        return "Packet(protocol=$protocol, srcPort=$sourcePort, destPort=$destinationPort, srcIP=$sourceIp, destIP=$destinationIp, isIPv6=$isIPv6, data=${data.decodeToString()})"
    }
}
