package com.yk.clash

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.yk.clash.ui.AppUi

object AppRoutingDialog {
    const val INSTALLED_APPS_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"
    data class Entry(val name: String, val packageName: String, val installed: Boolean)

    fun needsPermission(context: Context): Boolean {
        val manager = context.packageManager
        val supported = runCatching { manager.getPermissionInfo(INSTALLED_APPS_PERMISSION, 0) }.isSuccess
        return supported && context.checkSelfPermission(INSTALLED_APPS_PERMISSION) != PackageManager.PERMISSION_GRANTED
    }

    fun loadApps(context: Context, selected: Set<String>): List<Entry> {
        val manager = context.packageManager
        val apps = manager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            .mapNotNull { app ->
                if (app.uid == context.applicationInfo.uid) return@mapNotNull null
                Entry(runCatching { manager.getApplicationLabel(app).toString() }.getOrDefault(app.packageName), app.packageName, true)
            }
        check(apps.isNotEmpty()) { "系统未返回应用列表，请在应用权限中允许“读取应用列表”后重试" }
        val missing = (selected - apps.map { it.packageName }.toSet() - context.packageName)
            .map { Entry("未安装", it, false) }
        return (apps + missing).sortedWith(compareByDescending<Entry> { it.packageName in selected }.thenBy { it.name.lowercase() })
    }

    fun show(activity: Activity, ui: AppUi, apps: List<Entry>, initial: Set<String>, save: (Set<String>) -> Unit) {
        val selected = initial.toMutableSet()
        var visible = apps
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        val count = ui.text("已选 ${selected.size} 个应用", 13f, ui.muted)
        body.addView(ui.text("选择应用", 21f, ui.ink, true)); body.addView(ui.gap(16))
        val list = ListView(activity).apply {
            divider = android.graphics.drawable.ColorDrawable(ui.card); dividerHeight = ui.dp(8)
            setSelector(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = visible.size
            override fun getItem(position: Int) = visible[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val app = visible[position]
                val chosen = app.packageName in selected
                return ui.row().apply {
                    setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
                    addView(ui.column().apply {
                        addView(ui.text(app.name, 14f, if (chosen) ui.onPrimary else ui.ink))
                        addView(ui.gap(5))
                        addView(ui.text(app.packageName, 11f, if (chosen) ui.onPrimary else ui.muted))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(ui.text(if (chosen) "✓" else "○", 22f, if (chosen) ui.onPrimary else ui.primary))
                    background = ui.touch(if (chosen) ui.primary else ui.block, 18)
                    isSelected = chosen
                    contentDescription = "${app.name}，${if (chosen) "已选择" else "未选择"}"
                }
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val key = visible[position].packageName
            if (!selected.add(key)) selected.remove(key)
            count.text = "已选 ${selected.size} 个应用"; adapter.notifyDataSetChanged()
        }
        val search = ui.searchField("搜索应用名称或包名") { text ->
            val query = text.trim()
            visible = apps.filter { it.name.contains(query, true) || it.packageName.contains(query, true) }
            adapter.notifyDataSetChanged()
        }
        body.addView(search); body.addView(ui.gap(12)); body.addView(count)
        body.addView(ui.text("共享 UID 的应用会一起生效；未安装的条目可取消选择。", 11f, ui.muted))
        body.addView(ui.gap(12))
        body.addView(list, LinearLayout.LayoutParams(-1, (activity.resources.displayMetrics.heightPixels * 0.45f).toInt()))
        val dialog = AlertDialog.Builder(activity).setView(body)
            .setNegativeButton("取消", null).setPositiveButton("保存") { _, _ -> save(selected.toSet()) }.create()
        dialog.show(); ui.styleDialog(dialog)
    }
}
