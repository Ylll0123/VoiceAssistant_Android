package com.example.voiceassistant.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * 本机**所有能启动的应用**的目录。
 *
 * ## 为什么要有它
 * v5.0 的「打开应用」只认一张手写的常用表（微信、淘宝、B站……），
 * 说「打开原神」「打开学习通」就答「没找到」。用户要的是**任意应用**，
 * 所以这里直接问系统：谁有启动入口，就都收进来。
 *
 * ## 关于 Android 11 的可见性
 * 从 API 30 起，应用默认看不到「别人装了哪些应用」。想拿全量列表，
 * 要么在清单里声明 `<queries>`（只能列已知的几个），要么申请
 * `QUERY_ALL_PACKAGES`（能拿全量，但应用商店会问用途）。
 * 这是个语音助手，「帮我打开手机里的任意应用」是核心场景，所以选了后者。
 * 侧载自用没问题；真要上架，需要在商店表单里如实申报这个权限。
 *
 * ## 缓存
 * 拉一次列表大约几十毫秒，但每次说话都拉一遍太浪费。
 * 缓存 30 秒——新装的应用最多半分钟内被认出来，够用了。
 */
class AppCatalog(context: Context) {

    private val app: Context = context.applicationContext

    /** 一个可启动的应用 */
    data class Entry(val label: String, val packageName: String)

    @Volatile
    private var cached: List<Entry> = emptyList()

    @Volatile
    private var cachedAt = 0L

    // ------------------------------------------------------------------ 查询

    /** 全部可启动应用，按名称排序 */
    fun all(): List<Entry> {
        val now = System.currentTimeMillis()
        val hit = cached
        if (hit.isNotEmpty() && now - cachedAt < TTL_MS) return hit

        val list = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            app.packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { info ->
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    // 自己不出现在清单里，否则「打开 HardlySeek」会很怪
                    if (pkg == app.packageName) return@mapNotNull null
                    val label = runCatching {
                        info.loadLabel(app.packageManager).toString().trim()
                    }.getOrNull().orEmpty()
                    if (label.isEmpty()) null else Entry(label, pkg)
                }
                // 同一个包可能有多个启动入口（例如系统设置的快捷方式），留一个就够
                .distinctBy { it.packageName }
        }.getOrDefault(emptyList())

        cached = list
        cachedAt = now
        return list
    }

    /**
     * 按用户说的名字找应用。
     *
     * 匹配顺序（越靠前越确定）：
     * 1. 完全同名；
     * 2. 去掉「应用 / 软件 / app / 客户端」这类后缀后同名；
     * 3. 互相包含——取**名字更长**的那个，避免「哔哩」抢了「哔哩哔哩」；
     * 4. 直接报包名，或包名结尾对得上（`tencent.mm` → `com.tencent.mm`）。
     */
    fun find(raw: String): Entry? {
        val text = normalize(raw)
        if (text.isEmpty()) return null
        val list = all()
        if (list.isEmpty()) return null

        list.firstOrNull { normalize(it.label) == text }?.let { return it }

        val stripped = text
            .removeSuffix("客户端")
            .removeSuffix("应用")
            .removeSuffix("软件")
            .removeSuffix("app")
            .trim()
        if (stripped.isNotEmpty() && stripped != text) {
            list.firstOrNull { normalize(it.label) == stripped }?.let { return it }
        }

        // 包含匹配：两边都试，取 label 最长的一条
        val fuzzy = list.filter { entry ->
            val label = normalize(entry.label)
            label.isNotEmpty() && (label.contains(stripped.ifEmpty { text }) ||
                (stripped.length >= 2 && stripped.contains(label)))
        }.maxByOrNull { it.label.length }
        if (fuzzy != null) return fuzzy

        // 包名
        val lower = text.lowercase()
        if (lower.contains('.')) {
            list.firstOrNull { it.packageName.equals(lower, ignoreCase = true) }?.let { return it }
            list.filter { it.packageName.lowercase().endsWith(".$lower") }
                .maxByOrNull { it.label.length }?.let { return it }
            list.filter { it.packageName.lowercase().contains(lower) }
                .maxByOrNull { it.label.length }?.let { return it }
        }
        return null
    }

    /** 名字是不是一个真实存在的应用（本地 NLU 用它决定要不要认领「打开 XX」） */
    fun matches(raw: String): Boolean = find(raw) != null

    /**
     * 打开应用。
     *
     * @return 打开成功返回应用显示名；找不到或拉不起来返回 null
     */
    fun open(raw: String): String? {
        val entry = find(raw) ?: return null
        val intent = app.packageManager.getLaunchIntentForPackage(entry.packageName)
            ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val ok = runCatching { app.startActivity(intent) }.isSuccess
        return if (ok) entry.label else null
    }

    /** 给模型看的候选名单（只说前几个，免得提示词太长） */
    fun sample(count: Int = 8): String =
        all().take(count).joinToString("、") { it.label }

    // ------------------------------------------------------------------ 内部

    private fun normalize(raw: String): String =
        raw.trim().lowercase().replace(" ", "").replace("　", "")

    private companion object {
        /** 缓存 30 秒：新装应用最多半分钟内能被叫出来 */
        const val TTL_MS = 30_000L
    }
}

/** 找不到应用时的统一提示，避免各处写法不一 */
fun noAppFound(name: String): String = "手机里没找到「$name」，可能是没装，或者名字对不上。"

/** PackageManager 里查不到任何启动入口时，说明系统限制了可见性 */
internal fun PackageManager.hasAnyLauncher(): Boolean = runCatching {
    queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .isNotEmpty()
}.getOrDefault(false)
