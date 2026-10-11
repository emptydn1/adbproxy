package com.example.adbproxy

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import hev.htproxy.TProxyService
import java.io.File
import java.net.Socket

class ProxyVpnService : VpnService() {
    private val tag = "AdbProxy"
    private var tun: ParcelFileDescriptor? = null
    private var relay: HttpToSocks? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        Log.d(tag, "service: onStartCommand action=${i?.action}")
        if (i?.action == "STOP") { stopVpn(); stopSelf(); return START_NOT_STICKY }
        try {
            startVpn()
        } catch (e: Throwable) {
            Log.e(tag, "service: startVpn failed", e)
        }
        return START_STICKY
    }

    private fun q(s: String) = s.replace("'", "''")

    // Tim mang that (khong phai VPN)
    private fun findRealNetwork(): Network? {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull {
            val c = cm.getNetworkCapabilities(it)
            c != null &&
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    // Gan socket cua relay HTTP vao mang that
    private fun protectSocket(s: Socket) {
        val okProtect = protect(s)
        try {
            val net = findRealNetwork()
            if (net != null) {
                net.bindSocket(s)
                Log.d(tag, "protect=$okProtect, bound to network $net")
            } else {
                Log.e(tag, "protect=$okProtect, no non-VPN network found")
            }
        } catch (e: Exception) {
            Log.e(tag, "bindSocket failed: $e")
        }
    }

    // Gan ca tien trinh vao mang that (ap dung cho socket cua hev o che do SOCKS5)
    private fun bindProcessToRealNetwork() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val net = findRealNetwork()
            val ok = cm.bindProcessToNetwork(net)
            Log.d(tag, "bindProcessToNetwork($net) = $ok")
        } catch (e: Exception) {
            Log.e(tag, "bindProcessToNetwork failed: $e")
        }
    }

    private fun startVpn() {
        stopVpn()
        val sp = getSharedPreferences("c", 0)
        val raw = sp.getString("proxy", null)
        if (raw == null) { Log.e(tag, "service: no proxy saved"); return }
        val type = sp.getString("type", "socks5")

        // ip:port@user:pass
        val (hp, up) = raw.split("@", limit = 2)
        val (host, port) = hp.split(":", limit = 2)
        val (user, pass) = up.split(":", limit = 2)
        Log.d(tag, "service: type=$type host=$host port=$port")

        val sHost: String
        val sPort: Int
        val udpMode: String
        val auth: String
        if (type == "http") {
            val r = HttpToSocks(host, port.toInt(), user, pass) { protectSocket(it) }
                .also { it.start() }
            relay = r
            sHost = "127.0.0.1"; sPort = r.localPort; udpMode = "tcp"; auth = ""
        } else {
            sHost = host; sPort = port.toInt(); udpMode = "udp"
            auth = "  username: '${q(user)}'\n  password: '${q(pass)}'\n"
        }

        tun = Builder()
            .setSession("AdbProxy").setMtu(8500)
            .addAddress("198.18.0.1", 32).addRoute("0.0.0.0", 0)
            .addAddress("fc00::1", 128).addRoute("::", 0)
            .addDnsServer("198.18.0.2")
            .addDisallowedApplication(packageName)
            .establish()
        if (tun == null) { Log.e(tag, "service: establish() returned null"); return }

        // Xoa log cu (co the chua mat khau tu ban debug truoc)
        try { File(filesDir, "hev.log").delete() } catch (_: Exception) {}

        val cfg = File(filesDir, "tproxy.yml")
        cfg.writeText(
            "tunnel:\n  mtu: 8500\n  ipv4: 198.18.0.1\n  ipv6: 'fc00::1'\n" +
                    "socks5:\n  port: $sPort\n  address: '${q(sHost)}'\n  udp: '$udpMode'\n$auth" +
                    "mapdns:\n  address: 198.18.0.2\n  port: 53\n" +
                    "  network: 100.64.0.0\n  netmask: 255.192.0.0\n  cache-size: 10000\n" +
                    "misc:\n  log-level: error\n"
        )

        // Chi can o che do SOCKS5 (hev tu noi toi proxy)
        if (type != "http") bindProcessToRealNetwork()

        val ok = TProxyService.TProxyStartService(cfg.absolutePath, tun!!.fd)
        Log.d(tag, "service: TProxyStartService returned $ok, running=${TProxyService.TProxyIsRunning()}")
    }

    private fun stopVpn() {
        try {
            getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null)
        } catch (_: Exception) {}
        try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
        relay?.stop(); relay = null
        tun?.close(); tun = null
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }
}