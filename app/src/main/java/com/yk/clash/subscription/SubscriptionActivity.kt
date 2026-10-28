package com.yk.clash.subscription

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.*
import com.yk.clash.ProfileStore
import com.yk.clash.ui.AppUi
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import android.os.Handler
import android.os.Looper

class SubscriptionActivity : Activity() {
    private lateinit var ui: AppUi
    private lateinit var store: ProfileStore
    private var profile: ProfileStore.Profile? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var operationId: String
    private lateinit var draftProfileId: String
    private val pollTask = object : Runnable {
        override fun run() { observeTask(); if (busy) main.postDelayed(this, 250) }
    }
    private val controls = mutableListOf<android.view.View>()
    private lateinit var name: EditText
    private lateinit var url: EditText
    private lateinit var agent: EditText
    private lateinit var interval: EditText
    private lateinit var status: TextView
    private lateinit var metadata: TextView
    private lateinit var automatic: TextView
    private lateinit var updateMode: TextView
    private lateinit var titleLabel: TextView
    private lateinit var saveButton: TextView
    private lateinit var updateButton: TextView
    private var auto = true
    private var mode = "auto"
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ui = AppUi(this); setTheme(if (ui.dark) com.yk.clash.R.style.AppThemeDark else com.yk.clash.R.style.AppTheme)
        super.onCreate(savedInstanceState)
        store = ProfileStore(applicationContext)
        operationId = savedInstanceState?.getString("operationId") ?: UUID.randomUUID().toString()
        draftProfileId = savedInstanceState?.getString("draftProfileId") ?: UUID.randomUUID().toString()
        val completed = SubscriptionTasks.get(operationId)?.result?.getOrNull()?.id
        val id = completed ?: savedInstanceState?.getString("id") ?: intent.getStringExtra("profileId")
            ?: store.list().find { it.id == draftProfileId }?.id
        profile = id?.let { chosen -> store.list().find { it.id == chosen } }
        if (id != null && profile == null) { toast("订阅已删除"); finish(); return }
        auto = savedInstanceState?.getBoolean("auto") ?: profile?.autoUpdate ?: true
        mode = savedInstanceState?.getString("mode") ?: profile?.updateMode ?: "auto"
        val root = ui.column().apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(8)); setBackgroundColor(ui.page) }
        root.addView(ui.row().apply {
            addView(ui.backButton { finish() })
            titleLabel = ui.text(if (profile == null) "添加远程订阅" else "订阅设置", 22f, ui.ink, true)
            addView(titleLabel, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(14) })
        })
        val body = ui.column()
        fun field(label: String, value: String, hintText: String = ""): EditText {
            body.addView(ui.gap(16)); body.addView(ui.text(label, 13f, ui.muted)); body.addView(ui.gap(7))
            return EditText(this).apply {
                setText(value); hint = hintText; setTextColor(ui.ink); setHintTextColor(ui.muted); textSize = 14f
                background = ui.surface(ui.block, 16); setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
                isSingleLine = true; body.addView(this); controls.add(this)
            }
        }
        name = field("订阅名称", savedInstanceState?.getString("name") ?: profile?.name ?: intent.getStringExtra("name").orEmpty(), "留空可使用服务器提供的名称")
        url = field("订阅地址", savedInstanceState?.getString("url") ?: profile?.url ?: intent.getStringExtra("url").orEmpty(), "https://…").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        agent = field("User-Agent", savedInstanceState?.getString("agent") ?: profile?.userAgent ?: "clash.meta")
        body.addView(ui.gap(16))
        updateMode = ui.button(modeLabel(mode), mode != "direct") {
            val values = listOf("auto", "direct", "proxy")
            ui.choiceDialog("订阅更新方式", values.map(::modeLabel),
                listOf("优先使用运行中的代理，网络失败时尝试直连", "直接使用系统网络下载订阅", "通过运行中的代理下载，失败时不切换直连"), values.indexOf(mode)) { which ->
                mode = values[which]; updateMode.text = modeLabel(mode); ui.paintButton(updateMode, mode != "direct")
            }
        }
        body.addView(updateMode); controls.add(updateMode)
        body.addView(ui.gap(8)); body.addView(ui.text("自动方式优先使用正在运行的本地代理，网络失败时尝试直连。", 12f, ui.muted))
        body.addView(ui.gap(16))
        automatic = ui.button(if (auto) "自动更新 · 已开启" else "自动更新 · 已关闭", auto) {
            auto = !auto; automatic.text = if (auto) "自动更新 · 已开启" else "自动更新 · 已关闭"
            ui.paintButton(automatic, auto)
        }
        body.addView(automatic); controls.add(automatic)
        interval = field("自动更新间隔（分钟）", savedInstanceState?.getString("interval") ?: (profile?.intervalMinutes ?: 1440).toString()).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        body.addView(ui.gap(8)); body.addView(ui.text("最短 15 分钟；后台更新由系统调度，省电或无网络时可能延后。", 12f, ui.muted))
        status = ui.text("", 13f, ui.muted); body.addView(ui.gap(16)); body.addView(status)
        metadata = ui.text("", 13f, ui.muted); body.addView(ui.gap(8)); body.addView(metadata)
        body.addView(ui.gap(16))
        saveButton = ui.button(if (profile == null) "导入订阅" else "保存设置", true) { persist(update = false) }
        body.addView(saveButton); controls.add(saveButton)
        body.addView(ui.gap(10))
        updateButton = ui.button("保存并立即更新") { persist(update = true) }.apply { visibility = if (profile == null) android.view.View.GONE else android.view.View.VISIBLE }
        body.addView(updateButton); controls.add(updateButton)
        body.addView(ui.gap(10)); body.addView(ui.button("复制订阅地址") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("订阅地址", url.text.toString().trim())); toast("订阅地址已复制")
        })
        body.addView(ui.gap(10)); body.addView(ui.button("打开订阅网站") {
            val website = profile?.website.orEmpty()
            if (website.isBlank()) toast("订阅服务器未提供网站地址")
            else runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(website))) }.onFailure { toast("无法打开网站") }
        })
        body.addView(ui.gap(16)); body.addView(ui.text("下载后直接保存，不检查配置格式或内核兼容性，可在配置页编辑 YAML。下载失败保留旧内容；启动时由内核校验。已连接的配置在重连后使用新内容。", 12f, ui.muted))
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); showMetadata(); observeTask()
    }

    private fun persist(update: Boolean) {
        if (busy) return
        val template = runCatching {
            val result = (profile ?: ProfileStore.Profile(draftProfileId, "", "")).copy(
                name = name.text.toString().trim(), url = url.text.toString().trim(), userAgent = agent.text.toString().trim(),
                autoUpdate = auto, intervalMinutes = interval.text.toString().toLongOrNull() ?: error("更新间隔必须为整数"), updateMode = mode)
            SubscriptionRepository.validate(result); result
        }.getOrElse { toast(it.message ?: "订阅设置无效"); return }
        val previous = profile
        busy = true; controls.forEach { it.isEnabled = false }; status.text = if (update || previous == null) "正在下载订阅…" else "正在保存…"
        val context = applicationContext
        SubscriptionTasks.start(operationId) {
            val profiles = ProfileStore(context)
            if (previous == null) SubscriptionRepository.update(context, template, create = true)
            else {
                val saved = profiles.saveSettings(previous, template)
                if (update) SubscriptionRepository.update(context, saved) else saved
            }
        }
        main.removeCallbacks(pollTask); main.post(pollTask)
    }

    private fun observeTask() {
        val task = SubscriptionTasks.get(operationId) ?: return
        val result = task.result
        busy = result == null
        controls.forEach { it.isEnabled = !busy }
        if (result == null) { status.text = "正在处理订阅…"; return }
        result.onSuccess {
            profile = store.list().find { p -> p.id == it.id } ?: it
            intent.putExtra("profileId", it.id); name.setText(profile!!.name)
            titleLabel.text = "订阅设置"; saveButton.text = "保存设置"; updateButton.visibility = android.view.View.VISIBLE
            status.text = "订阅处理完成，配置内容更新后需重新连接生效"
            status.setTextColor(ui.good); setResult(RESULT_OK)
        }.onFailure {
            val id = profile?.id ?: draftProfileId
            profile = store.list().find { p -> p.id == id }
            if (profile != null) {
                titleLabel.text = "订阅设置"; saveButton.text = "保存设置"; updateButton.visibility = android.view.View.VISIBLE
            }
            status.text = it.message ?: "订阅更新失败"; status.setTextColor(ui.danger)
        }
        showMetadata(); SubscriptionTasks.forget(operationId)
    }

    private fun showMetadata() {
        val value = profile
        fun time(value: Long) = if (value > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value)) else "暂无"
        metadata.text = if (value == null) "流量和到期信息将在服务器返回后显示。" else buildList {
            value.info?.let { add(it.summary()) }
            add("最近成功更新：${time(value.updatedAt)}")
            add("最近检查：${time(value.checkedAt)}")
            if (value.lastError.isNotEmpty()) add("最近提示：${value.lastError}")
        }.joinToString("\n")
    }
    private fun modeLabel(value: String) = when (value) { "direct" -> "直连更新"; "proxy" -> "代理更新"; else -> "自动选择更新方式" }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("operationId", operationId); out.putString("draftProfileId", draftProfileId)
        profile?.let { out.putString("id", it.id) }
        out.putString("name", name.text.toString()); out.putString("url", url.text.toString()); out.putString("agent", agent.text.toString())
        out.putString("interval", interval.text.toString()); out.putBoolean("auto", auto); out.putString("mode", mode)
        super.onSaveInstanceState(out)
    }
    override fun onResume() { super.onResume(); main.removeCallbacks(pollTask); main.post(pollTask) }
    override fun onPause() { main.removeCallbacks(pollTask); super.onPause() }
    override fun onDestroy() {
        main.removeCallbacks(pollTask)
        if (isFinishing) SubscriptionTasks.forget(operationId)
        super.onDestroy()
    }
}
