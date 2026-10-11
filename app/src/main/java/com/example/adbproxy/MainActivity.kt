package com.example.adbproxy

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle

class MainActivity : Activity() {
    override fun onCreate(s: Bundle?) { super.onCreate(s); handle(intent) }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handle(i) }

    private fun handle(i: Intent) {
        if (i.getStringExtra("action") == "stop") {
            startService(Intent(this, ProxyVpnService::class.java).setAction("STOP"))
            finish(); return
        }
        i.getStringExtra("proxy")?.let {
            getSharedPreferences("c", 0).edit()
                .putString("proxy", it)
                .putString("type", (i.getStringExtra("type") ?: "socks5").lowercase())
                .apply()
        }
        val prep = VpnService.prepare(this)
        if (prep != null) startActivityForResult(prep, 1) else startVpn()
    }

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        if (r == 1 && c == RESULT_OK) startVpn() else finish()
    }

    private fun startVpn() {
        startService(Intent(this, ProxyVpnService::class.java).setAction("START"))
        finish()
    }
}