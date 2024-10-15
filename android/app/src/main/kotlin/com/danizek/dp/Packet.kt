package com.danizek.dp

import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.TcpPacket
import org.pcap4j.packet.UdpPacket
import org.pcap4j.packet.namednumber.IpNumber
import org.pcap4j.packet.namednumber.UdpPort
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
    val ipVersion: Int,
    val headerSize: Int,
    val payloadSize: Int,
    val totalLength: Int
) {

    companion object {
        // Deserialize byte array to Packet
        fun deserialize(bytes: ByteArray): Packet {
            val buffer = ByteBuffer.wrap(bytes)

            // Identify IP version
            val ipVersion = (buffer.get(0).toInt() shr 4) and 0x0F

            val sourceIp: InetAddress
            val destinationIp: InetAddress
            val sourcePort: Int
            val destinationPort: Int
            val protocol: Protocol
            var headerSize: Int = 0
            var payloadSize: Int = 0
            var totalLength: Int = 0

            if (ipVersion == 4) {
                // Parse IPv4 Header (20 bytes)
                val ihl = buffer.get(0).toInt() and 0xF // Get header length
                headerSize = ihl * 4

                buffer.position(2)
                totalLength = buffer.getShort().toInt() and 0xFFFF
                payloadSize = totalLength - headerSize

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

            return Packet(sourcePort, destinationPort, protocol, sourceIp, destinationIp, data, ipVersion, headerSize, payloadSize, totalLength)
        }

        fun toString(packet: IpV4Packet): String {
            if (packet.header.protocol == IpNumber.TCP) {
                val tcpPacket = packet.payload as TcpPacket
                var flags = ""
                if (tcpPacket.header.syn) {
                    flags += "SYN "
                }
                if (tcpPacket.header.ack) {
                    flags += "ACK "
                }
                if (tcpPacket.header.fin) {
                    flags += "FIN "
                }
                if (tcpPacket.header.rst) {
                    flags += "RST "
                }
                if (tcpPacket.header.psh) {
                    flags += "PSH "
                }
                if (tcpPacket.header.urg) {
                    flags += "URG "
                }
                return "Packet(protocol=TCP, srcIP=${packet.header.srcAddr}, srcPort=${tcpPacket.header.srcPort}, destIP=${packet.header.dstAddr}, destPort=${tcpPacket.header.dstPort}, flags=${flags}, payloadLen=${if (tcpPacket.payload == null) 0 else tcpPacket.payload.rawData.size}), seq=${tcpPacket.header.sequenceNumber.toUInt()}, ack=${tcpPacket.header.acknowledgmentNumber.toUInt()}, options=${tcpPacket.header.options}, content=${tcpPacket.payload?.rawData?.toString(Charsets.UTF_8)}"
            } else {
                val udpPacket = packet.payload as UdpPacket
                return "Packet(protocol=UDP, srcIP=${packet.header.srcAddr}, srcPort=${udpPacket.header.srcPort}, destIP=${packet.header.dstAddr}, destPort=${udpPacket.header.dstPort}, payloadLen=${if (udpPacket.payload == null) 0 else udpPacket.payload.rawData.size})"
            }
        }
    }

    override fun toString(): String {
        return "Packet(protocol=$protocol, srcPort=$sourcePort, destPort=$destinationPort, srcIP=$sourceIp, destIP=$destinationIp, ipVersion=${ipVersion}, len=${data.size})"
    }
}
