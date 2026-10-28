package com.yk.clash

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.*
import com.yk.clash.editor.GlobalOverrideStore
import com.yk.clash.ui.AppUi
import java.util.concurrent.Executors

class RuntimeYamlActivity : Activity() {
    private lateinit var ui: AppUi
    private val worker = Executors.newSingleThreadExecutor()
    private var current = ""
    private var preview = ""
    private var latest: String? = null
    private var showLatest = false
    private var loading = true
    private var previewError = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        ui = AppUi(this); setTheme(if (ui.dark) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        render()
        worker.execute {
            val last = RuntimeYamlStore(this).read()?.let(YamlDisplay::format)
            val result = runCatching {
                val store = ProfileStore(this)
                val profile = store.selected() ?: error("请先导入并选择配置")
                val merged = GlobalOverrideStore(this).resolve(store.read(profile))
                val mode = getSharedPreferences("network", MODE_PRIVATE).getString("ipv6", "profile") ?: "profile"
                YamlDisplay.format(NativeCore.previewConfig(merged, mode))
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                latest = last; preview = result.getOrDefault("")
                previewError = result.exceptionOrNull()?.message.orEmpty()
                showLatest = last != null && ClashVpnService.state == "已连接"
                loading = false; render()
            }
        }
    }

    private fun render() {
        val root = ui.column().apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(8)); setBackgroundColor(ui.page) }
        root.addView(ui.row().apply {
            addView(ui.backButton { finish() })
            addView(ui.text("最终 YAML", 22f, ui.ink, true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(12) })
            if (!loading) addView(ui.button("导出") {
                if (current.isEmpty()) return@button
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "application/yaml"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE, "ykclash-runtime.yaml")
                }, 20)
            })
        })
        if (loading) {
            root.addView(ui.text("正在生成运行配置…", 14f, ui.muted)); setContentView(root); return
        }
        root.addView(ui.row().apply {
            addView(ui.button("当前配置预览", !showLatest) { showLatest = false; render() }, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = ui.dp(12) })
            addView(ui.button("最近实际运行", showLatest) { showLatest = true; render() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(8); topMargin = ui.dp(12) })
        })
        current = if (showLatest) latest.orEmpty() else preview
        root.addView(ui.gap(10))
        root.addView(ui.text(if (showLatest) "最近一次成功接入 VPN 时实际生成的配置；可能与后来保存的设置不同。" else "使用已保存的全局覆写生成；控制凭据和 TUN 描述符在连接时分配。", 12f, ui.muted))
        root.addView(ui.gap(10))
        if (current.isEmpty()) {
            root.addView(ui.text(if (showLatest) "尚未生成运行配置，请连接一次代理。" else previewError.ifEmpty { "暂无配置" }, 14f, ui.muted))
        } else {
            val text = EditText(this).apply {
                setText(current); keyListener = null; setTextIsSelectable(true)
                typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f; setTextColor(ui.ink)
                background = ui.surface(ui.card, 18); setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
                gravity = android.view.Gravity.TOP or android.view.Gravity.START; setHorizontallyScrolling(true)
            }
            root.addView(text, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
    }

    @Deprecated("Platform result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 20 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val yaml = current
        worker.execute {
            val result = runCatching {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(yaml.toByteArray(Charsets.UTF_8)) } ?: error("无法写入文件")
            }
            runOnUiThread { if (!isDestroyed && !isFinishing) Toast.makeText(this, if (result.isSuccess) "YAML 已导出" else result.exceptionOrNull()?.message ?: "导出失败", Toast.LENGTH_LONG).show() }
        }
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
