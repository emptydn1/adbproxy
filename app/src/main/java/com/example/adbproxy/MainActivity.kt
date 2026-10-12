package com.example.adbproxy

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit

class MainActivity : Activity() {

    private lateinit var etProxy: EditText
    private lateinit var rbSocks: RadioButton
    private lateinit var rbHttp: RadioButton
    private lateinit var tvStatus: TextView
    private var finishAfter = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        // Mo bang adb (co tham so) -> xu ly roi dong, khong hien giao dien
        if (handleAdb(intent)) return
        buildUi()
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        handleAdb(i)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------- Duong adb (giu nguyen nhu cu) ----------
    private fun handleAdb(i: Intent): Boolean {
        if (i.getStringExtra("action") == "stop") {
            stopProxy()
            finish()
            return true
        }
        val proxy = i.getStringExtra("proxy") ?: return false
        save(proxy, (i.getStringExtra("type") ?: "socks5").lowercase())
        startProxy(true)
        return true
    }

    // ---------- Giao dien nhap tay ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val sp = getSharedPreferences("c", 0)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        // Tranh bi thanh trang thai / thanh dieu huong che
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(dp(24) + b.left, dp(24) + b.top, dp(24) + b.right, dp(24) + b.bottom)
            insets
        }

        root.addView(TextView(this).apply {
            text = "AdbProxy"
            textSize = 24f
        })

        root.addView(TextView(this).apply {
            text = "Proxy (ip:port@user:pass hoặc ip:port:user:pass)"
            setPadding(0, dp(20), 0, dp(4))
        })

        etProxy = EditText(this).apply {
            hint = "31.59.20.176:6754@user:pass"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(sp.getString("proxy", ""))
        }
        root.addView(etProxy)

        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
        }
        rbSocks = RadioButton(this).apply {
            id = View.generateViewId()
            text = "SOCKS5 (khuyên dùng, có UDP)"
        }
        rbHttp = RadioButton(this).apply {
            id = View.generateViewId()
            text = "HTTP (chỉ TCP)"
        }
        group.addView(rbSocks)
        group.addView(rbHttp)
        root.addView(group)
        if (sp.getString("type", "socks5") == "http") rbHttp.isChecked = true
        else rbSocks.isChecked = true

        root.addView(Button(this).apply {
            text = "BẬT PROXY"
            setOnClickListener { onStartClicked() }
        })
        root.addView(Button(this).apply {
            text = "TẮT PROXY"
            setOnClickListener {
                stopProxy()
                tvStatus.postDelayed({ refreshStatus() }, 500)
            }
        })

        tvStatus = TextView(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            textSize = 16f
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(tvStatus)

        setContentView(root)
        refreshStatus()
    }

    private fun onStartClicked() {
        val norm = normalize(etProxy.text.toString())
        if (norm == null) {
            Toast.makeText(
                this,
                "Sai định dạng. Dùng ip:port@user:pass hoặc ip:port:user:pass",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        etProxy.setText(norm)
        save(norm, if (rbHttp.isChecked) "http" else "socks5")
        startProxy(false)
        tvStatus.text = "Đang bật..."
        tvStatus.postDelayed({ refreshStatus() }, 1200)
    }

    private fun refreshStatus() {
        if (!::tvStatus.isInitialized) return
        tvStatus.text = if (ProxyVpnService.running)
            "Trạng thái: ĐANG CHẠY" else "Trạng thái: đã tắt"
    }

    // Chap nhan ip:port@user:pass hoac ip:port:user:pass -> tra ve ip:port@user:pass
    private fun normalize(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.contains("@")) {
            val a = s.split("@", limit = 2)
            val h = a[0].split(":", limit = 2)
            val u = a[1].split(":", limit = 2)
            if (h.size == 2 && u.size == 2 &&
                h[0].isNotEmpty() && h[1].toIntOrNull() != null &&
                u[0].isNotEmpty() && u[1].isNotEmpty()
            ) return s
            return null
        }
        val p = s.split(":", limit = 4)
        if (p.size == 4 && p[0].isNotEmpty() && p[1].toIntOrNull() != null &&
            p[2].isNotEmpty() && p[3].isNotEmpty()
        ) return "${p[0]}:${p[1]}@${p[2]}:${p[3]}"
        return null
    }

    // ---------- Chung ----------
    private fun save(proxy: String, type: String) {
        getSharedPreferences("c", 0).edit {
            putString("proxy", proxy)
            putString("type", type)
        }
    }

    private fun startProxy(finish: Boolean) {
        finishAfter = finish
        val prep = VpnService.prepare(this)
        if (prep != null) startActivityForResult(prep, 1) else launchService()
    }

    private fun launchService() {
        startService(Intent(this, ProxyVpnService::class.java).setAction("START"))
        if (finishAfter) finish()
    }

    private fun stopProxy() {
        startService(Intent(this, ProxyVpnService::class.java).setAction("STOP"))
    }

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        if (r == 1 && c == RESULT_OK) {
            launchService()
        } else {
            Toast.makeText(this, "Chưa cấp quyền VPN", Toast.LENGTH_SHORT).show()
            if (finishAfter) finish()
        }
    }
}