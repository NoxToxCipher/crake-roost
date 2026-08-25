package com.crake.roost

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL

/**
 * Lightweight, zero-dependency UPnP / SSDP port forwarding helper.
 * Automatically requests port mapping from the local gateway/router so
 * inbound Tox DHT and TCP relay packets can reach the device without manual
 * router configuration.
 */
object Upnp {

    data class MappingResult(
        val success: Boolean,
        val externalIp: String = "",
        val details: String = "",
    )

    fun tryForwardPorts(ports: List<Pair<Int, String>>, description: String = "Crake Roost"): MappingResult {
        return try {
            val localIp = getLocalIpv4() ?: return MappingResult(false, details = "No local IPv4 found")
            val locationUrl = discoverIgdLocation() ?: return MappingResult(false, details = "No UPnP router found on LAN")
            val controlUrl = extractControlUrl(locationUrl) ?: return MappingResult(false, details = "Could not parse UPnP control URL")

            var mappedCount = 0
            for ((port, proto) in ports) {
                if (soapAddPortMapping(controlUrl, localIp, port, proto, description)) {
                    mappedCount++
                }
            }

            if (mappedCount > 0) {
                val extIp = soapGetExternalIp(controlUrl) ?: ""
                MappingResult(true, externalIp = extIp, details = "Mapped $mappedCount port(s) via UPnP")
            } else {
                MappingResult(false, details = "Router rejected UPnP port mappings")
            }
        } catch (e: Exception) {
            MappingResult(false, details = "UPnP error: ${e.message}")
        }
    }

    private fun discoverIgdLocation(): String? {
        val ssdpRequest = "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n\r\n"

        val sendData = ssdpRequest.toByteArray()
        val socket = DatagramSocket()
        socket.soTimeout = 2500

        try {
            val group = InetAddress.getByName("239.255.255.250")
            val sendPacket = DatagramPacket(sendData, sendData.size, group, 1900)
            socket.send(sendPacket)

            val recvBuf = ByteArray(2048)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            socket.receive(recvPacket)

            val response = String(recvPacket.data, 0, recvPacket.length)
            for (line in response.lines()) {
                if (line.uppercase().startsWith("LOCATION:")) {
                    return line.substringAfter(":").trim()
                }
            }
        } catch (_: Exception) {
        } finally {
            socket.close()
        }
        return null
    }

    private fun extractControlUrl(locationUrl: String): String? {
        val url = URL(locationUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000

        val xml = conn.inputStream.bufferedReader().use { it.readText() }
        val serviceType = "WANIPConnection:1"
        val altServiceType = "WANPPPConnection:1"

        var serviceIdx = xml.indexOf(serviceType)
        if (serviceIdx == -1) serviceIdx = xml.indexOf(altServiceType)
        if (serviceIdx == -1) return null

        val controlTagOpen = "<controlURL>"
        val controlTagClose = "</controlURL>"
        val ctrlStart = xml.indexOf(controlTagOpen, serviceIdx)
        if (ctrlStart == -1) return null
        val ctrlEnd = xml.indexOf(controlTagClose, ctrlStart)
        if (ctrlEnd == -1) return null

        val path = xml.substring(ctrlStart + controlTagOpen.length, ctrlEnd).trim()
        return if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            val base = "${url.protocol}://${url.host}:${url.port}"
            if (path.startsWith("/")) "$base$path" else "$base/$path"
        }
    }

    private fun soapAddPortMapping(
        controlUrl: String,
        localIp: String,
        port: Int,
        proto: String,
        desc: String,
    ): Boolean {
        val soapBody = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:AddPortMapping xmlns:u="urn:schemas-upnp-org:service:WANIPConnection:1">
<NewRemoteHost></NewRemoteHost>
<NewExternalPort>$port</NewExternalPort>
<NewProtocol>$proto</NewProtocol>
<NewInternalPort>$port</NewInternalPort>
<NewInternalClient>$localIp</NewInternalClient>
<NewEnabled>1</NewEnabled>
<NewPortMappingDescription>$desc</NewPortMappingDescription>
<NewLeaseDuration>0</NewLeaseDuration>
</u:AddPortMapping>
</s:Body>
</s:Envelope>"""

        return sendSoap(controlUrl, "urn:schemas-upnp-org:service:WANIPConnection:1#AddPortMapping", soapBody)
    }

    private fun soapGetExternalIp(controlUrl: String): String? {
        val soapBody = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:GetExternalIPAddress xmlns:u="urn:schemas-upnp-org:service:WANIPConnection:1">
</u:GetExternalIPAddress>
</s:Body>
</s:Envelope>"""

        val res = sendSoapWithResponse(controlUrl, "urn:schemas-upnp-org:service:WANIPConnection:1#GetExternalIPAddress", soapBody) ?: return null
        val start = res.indexOf("<NewExternalIPAddress>")
        val end = res.indexOf("</NewExternalIPAddress>")
        return if (start != -1 && end != -1) res.substring(start + "<NewExternalIPAddress>".length, end) else null
    }

    private fun sendSoap(controlUrl: String, action: String, body: String): Boolean {
        return sendSoapWithResponse(controlUrl, action, body) != null
    }

    private fun sendSoapWithResponse(controlUrl: String, action: String, body: String): String? {
        val url = URL(controlUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        conn.setRequestProperty("SOAPAction", "\"$action\"")

        val bytes = body.toByteArray()
        conn.setRequestProperty("Content-Length", bytes.size.toString())

        conn.outputStream.use { it.write(bytes) }

        return if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            null
        }
    }

    private fun getLocalIpv4(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in interfaces) {
            if (iface.isLoopback || !iface.isUp) continue
            for (addr in iface.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }
}
