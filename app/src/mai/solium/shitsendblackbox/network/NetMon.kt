package mai.solium.shitsendblackbox.network

import mai.solium.shitsendblackbox.data.BlocklistStore
import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 监控：记录经过 VPN 的 DNS 查询（域名、次数、解析结果、是否已被屏蔽），
 * 并识别「公共 DoH 服务商」与「App 自建 / 私有化加密 DNS」，
 * 停止时生成可导出的分析报告，帮助用户发现“偷偷回传”的域名。
 */
object NetMon {

    data class Entry(
        val domain: String,
        val count: Int,
        val first: Long,
        val last: Long,
        val ips: List<String>,
        val blocked: Boolean,
        /** "public"=公共 DoH/DoT 服务商；"private"=疑似私有化加密 DNS；null=普通域名 */
        val doh: String? = null,
        /** 首个观察到该域名的应用 uid；-1 表示未知（例如系统 DNS） */
        val uid: Int = -1,
        /** 数据来源：dns / sni */
        val via: String = "dns"
    )

    private class Rec {
        var count = 0
        var first = 0L
        var last = 0L
        var blocked = false
        var uid = -1
        var via = "dns"
        val ips = LinkedHashSet<String>(2)
    }

    private val map = ConcurrentHashMap<String, Rec>()
    /** uid -> 本次监控期间经隧道的字节数 */
    private val traffic = ConcurrentHashMap<Int, Long>()

    @Volatile var active: Boolean = false
        private set

    /** 已知公共加密 DNS 服务商（DoH/DoT）。 */
    val PUBLIC_DOH = listOf(
        "dns.google", "dns.google.com", "doh.pub", "dns.pub", "dot.pub",
        "dns.alidns.com", "cloudflare-dns.com", "mozilla.cloudflare-dns.com",
        "dns.quad9.net", "doh.360.cn", "doh.opendns.com", "dns.adguard-dns.com",
        "doh.dnspod.cn", "dns.nextdns.io", "doh.mullvad.net", "dns.sb"
    )

    /** 私有化 DoH 的域名特征标签。 */
    private val PRIVATE_DOH_LABELS = listOf("doh", "dot", "dns-query", "dnscrypt", "resolver")

    /** 判断域名像不像加密 DNS 端点：public / private / null。 */
    fun dohKind(domain: String): String? {
        val d = domain.trimEnd('.').lowercase()
        if (d.isEmpty()) return null
        if (PUBLIC_DOH.any { d == it || d.endsWith(".$it") }) return "public"
        for (label in d.split('.')) {
            if (label.isEmpty()) continue
            if (label in PRIVATE_DOH_LABELS) return "private"
            if (label.startsWith("doh") || label.startsWith("dot") || label.startsWith("dnscrypt")) return "private"
            if (label == "dns" || (label.startsWith("dns-") && label.length <= 12)) return "private"
        }
        return null
    }

    /** 定向匹配用的关键词：统计 / 广告 / 埋点 / 推送 常见命名。 */
    private val TRACK_WORDS = listOf(
        "analytics", "analysis", "tracker", "track", "stats", "statistic", "statistics",
        "logger", "logging", "logreport", "report", "collect", "beacon", "metric",
        "telemetry", "monitor", "sentry", "bugly", "umeng", "appsflyer", "adjust",
        "doubleclick", "adservice", "advert", "adsystem", "adx", "promote", "mdap",
        "pushservice", "log-api", "logservice"
    )

    fun start() {
        map.clear()
        traffic.clear()
        startedAt = System.currentTimeMillis()
        stoppedAt = 0L
        active = true
    }

    fun stop() {
        active = false
        stoppedAt = System.currentTimeMillis()
    }

    /** 放弃本次记录（不生成报告）。 */
    fun cancel() {
        map.clear()
        traffic.clear()
        active = false
        stoppedAt = 0L
        startedAt = 0L
    }

    var startedAt: Long = 0L
        private set
    var stoppedAt: Long = 0L
        private set

    fun record(domain: String, ip: String?, blocked: Boolean, uid: Int = -1, via: String = "dns") {
        if (!active) return
        val d = domain.trimEnd('.').lowercase()
        if (d.isEmpty() || d.length > 253) return
        val r = map[d]
        val rec = if (r == null) {
            val n = Rec()
            n.first = System.currentTimeMillis()
            map[d] = n
            n
        } else r
        synchronized(rec) {
            rec.count++
            rec.last = System.currentTimeMillis()
            if (blocked) rec.blocked = true
            if (uid > 0 && rec.uid <= 0) rec.uid = uid
            if (via == "sni") rec.via = "sni"
            if (!ip.isNullOrEmpty() && rec.ips.size < 4) rec.ips.add(ip)
        }
    }

    fun recordSni(domain: String, ip: String?, blocked: Boolean, uid: Int) =
        record(domain, ip, blocked, uid, "sni")

    fun addTraffic(uid: Int, bytes: Long) {
        if (!active || uid <= 0 || bytes <= 0) return
        traffic.merge(uid, bytes) { a, b -> a + b }
    }

    fun trafficMap(): Map<Int, Long> = HashMap(traffic)

    fun domainCount(): Int = map.size

    fun queryCount(): Int = map.values.sumOf { it.count }

    fun entries(): List<Entry> = map.map { (d, r) ->
        synchronized(r) {
            Entry(d, r.count, r.first, r.last, r.ips.toList(), r.blocked, dohKind(d), r.uid, r.via)
        }
    }.sortedWith(compareByDescending<Entry> { it.count }.thenBy { it.domain })

    fun elapsedMs(): Long {
        val end = if (stoppedAt > 0) stoppedAt else System.currentTimeMillis()
        return if (startedAt > 0) end - startedAt else 0L
    }

    private fun fmt(t: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(t))

    private fun fmtDur(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s} 秒"
            s < 3600 -> "${s / 60} 分 ${s % 60} 秒"
            else -> "${s / 3600} 小时 ${(s % 3600) / 60} 分"
        }
    }

    /** 命中已启用清单的组名；没有命中返回 null。 */
    private fun hitGroup(ctx: Context, domain: String): String? {
        for (g in BlocklistStore.groups(ctx)) {
            if (!g.enabled) continue
            if (BlocklistStore.matches(domain, g.domains.map { it.lowercase() }.toSet())) return g.name
        }
        return null
    }

    /** 按“域名标签”匹配，避免 static/statics 之类误判。 */
    private fun suspected(domain: String): Boolean {
        for (label in domain.split('.')) {
            if (label.isEmpty()) continue
            if (label == "static" || label == "statics") continue
            for (w in TRACK_WORDS) {
                if (label == w) return true
                if (label.startsWith(w) && label.length <= w.length + 4) return true
                if (label.endsWith(w) && label.length <= w.length + 6) return true
            }
        }
        return false
    }

    fun buildReport(ctx: Context): String {
        val list = entries()
        val sb = StringBuilder()
        val now = System.currentTimeMillis()
        sb.append("# 网络监控分析报告\n\n")
        sb.append("- 开始时间：").append(fmt(if (startedAt > 0) startedAt else now)).append('\n')
        sb.append("- 结束时间：").append(fmt(if (stoppedAt > 0) stoppedAt else now)).append('\n')
        sb.append("- 监控时长：").append(fmtDur(elapsedMs())).append('\n')
        sb.append("- DNS 查询：").append(queryCount()).append(" 次 · 域名 ").append(list.size).append(" 个\n")
        sb.append("- 说明：统计经过本机 VPN 的 DNS 查询；App 自带 DoH/DoT（不查系统 DNS）只能识别其访问的加密 DNS 端点，")
            .append("看不到具体查询内容；硬编码 IP 直连不在统计范围内。\n\n")

        val known = ArrayList<Pair<Entry, String>>()
        val doh = ArrayList<Entry>()
        val suspect = ArrayList<Entry>()
        val rest = ArrayList<Entry>()
        for (e in list) {
            val g = hitGroup(ctx, e.domain)
            when {
                e.doh != null -> doh.add(e)
                g != null -> known.add(e to g)
                suspected(e.domain) -> suspect.add(e)
                else -> rest.add(e)
            }
        }
        val publicDoh = doh.count { it.doh == "public" }
        val privateDoh = doh.count { it.doh == "private" }

        sb.append("## 一、命中已知隐私/回传清单（建议保持屏蔽）\n\n")
        if (known.isEmpty()) sb.append("（无）\n\n") else {
            sb.append("| 域名 | 查询次数 | 解析 IP | 命中清单 |\n|---|---|---|---|\n")
            for ((e, g) in known) sb.append("| ").append(e.domain).append(" | ").append(e.count)
                .append(" | ").append(e.ips.joinToString(",").ifEmpty { "-" })
                .append(" | ").append(g).append(" |\n")
            sb.append('\n')
        }

        sb.append("## 二、DoH / 私密 DNS 探测\n\n")
        sb.append("公共 DoH 服务商：").append(publicDoh).append(" 个 · 私有/自建 DoH 嫌疑：")
            .append(privateDoh).append(" 个\n\n")
        if (doh.isEmpty()) sb.append("（无）\n\n") else {
            sb.append("| 域名 | 类型 | 次数 | 解析 IP | 状态 |\n|---|---|---|---|---|\n")
            for (e in doh) {
                sb.append("| ").append(e.domain).append(" | ")
                    .append(if (e.doh == "public") "公共 DoH" else "私有 DoH？").append(" | ")
                    .append(e.count).append(" | ").append(e.ips.joinToString(",").ifEmpty { "-" })
                    .append(" | ").append(if (e.blocked) "已拦截" else "未拦截").append(" |\n")
            }
            sb.append('\n')
            if (privateDoh > 0) {
                sb.append("> 私有 DoH：出现这类域名说明有 App/SDK 绕开系统 DNS 自建解析通道，")
                    .append("意味着它后续访问的域名不会被本机 DNS 记录看到；建议直接限制该 App 联网。\n\n")
            }
        }

        sb.append("## 三、疑似统计/广告/埋点域名（建议加入屏蔽）\n\n")
        if (suspect.isEmpty()) sb.append("（无）\n\n") else {
            sb.append("| 域名 | 查询次数 | 解析 IP |\n|---|---|---|\n")
            for (e in suspect) sb.append("| ").append(e.domain).append(" | ").append(e.count)
                .append(" | ").append(e.ips.joinToString(",").ifEmpty { "-" }).append(" |\n")
            sb.append('\n')
        }

        sb.append("## 四、其他域名（按查询次数排序）\n\n")
        if (rest.isEmpty()) sb.append("（无）\n\n") else {
            sb.append("| 域名 | 查询次数 | 解析 IP | 来源 |\n|---|---|---|---|\n")
            for (e in rest.take(300)) sb.append("| ").append(e.domain).append(" | ").append(e.count)
                .append(" | ").append(e.ips.joinToString(",").ifEmpty { "-" })
                .append(" | ").append(if (e.via == "sni") "TLS SNI" else "DNS").append(" |\n")
            if (rest.size > 300) sb.append("\n（其余 ").append(rest.size - 300).append(" 个域名省略）\n")
            sb.append('\n')
        }

        sb.append("## 五、应用流量与 DoH 归属\n\n")
        if (traffic.isEmpty()) {
            sb.append("（无）本次没有拿到应用 UID，可能是连接过短或系统进程共用 UID。\n\n")
        } else {
            sb.append("| 应用 | UID | 流量 | 该应用访问的 DoH 端点 |\n|---|---|---|---|\n")
            for ((uid, bytes) in traffic.entries.sortedByDescending { it.value }) {
                val dohOf = list.filter { it.uid == uid && it.doh != null }
                    .map { it.domain + "(" + (if (it.doh == "public") "公共" else "私有") + ")" }
                sb.append("| ").append(appLabel(ctx, uid)).append(" | ").append(uid).append(" | ")
                    .append(fmtBytes(bytes)).append(" | ")
                    .append(dohOf.joinToString(",").ifEmpty { "-" }).append(" |\n")
            }
            sb.append('\n')
        }

        sb.append("## 建议操作\n\n")
        sb.append("1. 对「三」中的域名点「一键加入屏蔽」，或手动在「网络」页添加；\n")
        sb.append("2. 「一」中的域名若所属清单已开启则已生效，无需重复添加；\n")
        sb.append("3. 「二」里的公共 DoH 由「网络 → 加密 DNS 服务商」清单统一拦截；私有 DoH 建议直接限制该 App 联网；\n")
        sb.append("4. 屏蔽后回到本页「测试解析」确认返回 127.0.0.1。\n")
        return sb.toString()
    }

    private fun fmtBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", b / 1024.0 / 1024.0)
    }

    fun appLabel(ctx: Context, uid: Int): String {
        return try {
            val pm = ctx.packageManager
            val pkgs = pm.getPackagesForUid(uid) ?: return "uid $uid"
            val p = pkgs.firstOrNull() ?: return "uid $uid"
            val ai = pm.getApplicationInfo(p, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: Throwable) {
            "uid $uid"
        }
    }

    /** 疑似统计/广告/埋点的域名（供一键加入屏蔽）。 */
    fun suspectedDomains(ctx: Context): List<String> =
        entries().filter { e ->
            !e.blocked && e.doh == null && hitGroup(ctx, e.domain) == null && suspected(e.domain)
        }.map { it.domain }

    /** DoH 端点域名（供一键屏蔽）。 */
    fun dohDomains(): List<String> = entries().filter { it.doh != null }.map { it.domain }

    /** 报告摘要（对话框头部展示）。 */
    fun summary(ctx: Context): String {
        val list = entries()
        val known = list.count { it.doh == null && hitGroup(ctx, it.domain) != null }
        val suspect = list.count { e -> e.doh == null && hitGroup(ctx, e.domain) == null && suspected(e.domain) }
        val pub = list.count { it.doh == "public" }
        val priv = list.count { it.doh == "private" }
        return "时长 ${fmtDur(elapsedMs())} · 查询 ${queryCount()} 次 · 域名 ${list.size} 个\n" +
            "已知清单 ${known} 个 · 疑似追踪 ${suspect} 个\n" +
            "DoH：公共 ${pub} 个 · 私有 ${priv} 个 · 应用 ${traffic.size} 个"
    }

    /** 供导出为 JSON（可再导入分析）。 */
    fun toJson(ctx: Context): String {
        val arr = org.json.JSONArray()
        for (e in entries()) {
            arr.put(JSONObject().apply {
                put("domain", e.domain)
                put("count", e.count)
                put("first", e.first)
                put("last", e.last)
                put("blocked", e.blocked)
                put("ips", org.json.JSONArray(e.ips))
                put("knownGroup", hitGroup(ctx, e.domain) ?: JSONObject.NULL)
                put("suspected", suspected(e.domain))
                put("doh", e.doh ?: JSONObject.NULL)
                put("uid", e.uid)
                put("via", e.via)
            })
        }
        return JSONObject().apply {
            put("format", "shitsendblackbox-netmon")
            put("version", 2)
            put("startedAt", startedAt)
            put("stoppedAt", stoppedAt)
            put("domains", arr)
        }.toString(2)
    }
}