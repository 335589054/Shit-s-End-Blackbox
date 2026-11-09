package mai.solium.shitsendblackbox.data

import android.content.Context
import mai.solium.shitsendblackbox.core.Logx
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 原始安装包备份记录。apk 字段相对 getExternalFilesDir。 */
data class Backup(
    val pkg: String,
    val apk: String,
    val versionCode: Long,
    val versionName: String,
    val system: Boolean,
    val time: Long,
    /** 顶替时使用的占位包 versionCode（0 表示未顶替）。 */
    val stubVersion: Long = 0L
)

/** 三种可独立分区的权限维度。 */
enum class Perm(val id: String, val label: String) {
    NET("net", "网络"),
    LOC("loc", "定位"),
    REC("rec", "录屏");

    companion object {
        fun of(id: String?): Perm = entries.firstOrNull { it.id == id } ?: NET
    }
}

/** 一个应用在三个权限维度上的分区。 */
data class PermZones(val net: Zone, val loc: Zone, val rec: Zone) {
    fun of(p: Perm): Zone = when (p) {
        Perm.NET -> net
        Perm.LOC -> loc
        Perm.REC -> rec
    }

    fun with(p: Perm, z: Zone): PermZones = when (p) {
        Perm.NET -> copy(net = z)
        Perm.LOC -> copy(loc = z)
        Perm.REC -> copy(rec = z)
    }

    val allBlack: Boolean
        get() = net.id == Zone.BLACK.id && loc.id == Zone.BLACK.id && rec.id == Zone.BLACK.id
}

data class Zone(val id: String, val label: String, val desc: String) {
    companion object {
        val BLACK = Zone("black", "黑区", "禁止一切权限与联网")
        val GRAY = Zone("gray", "灰区", "仅前台/活跃时允许")
        val WHITE = Zone("white", "白区", "允许网络、定位、录屏")
        val ALL = listOf(BLACK, GRAY, WHITE)
        fun of(id: String?): Zone = when (id) {
            "black" -> BLACK
            "gray" -> GRAY
            else -> WHITE
        }
    }
}

object BackupStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "backups.json")

    fun all(ctx: Context): MutableMap<String, Backup> {
        val out = HashMap<String, Backup>()
        try {
            val f = file(ctx)
            if (!f.exists()) return out
            val o = JSONObject(f.readText())
            for (k in o.keys()) {
                val j = o.getJSONObject(k)
                out[k] = Backup(
                    k,
                    j.optString("apk"),
                    j.optLong("versionCode"),
                    j.optString("versionName", "?"),
                    j.optBoolean("system"),
                    j.optLong("time"),
                    j.optLong("stubVersion")
                )
            }
        } catch (t: Throwable) {
            Logx.w("BackupStore 读取失败: ${t.message}")
        }
        return out
    }

    fun save(ctx: Context, map: Map<String, Backup>) {
        val o = JSONObject()
        for ((k, b) in map) {
            o.put(k, JSONObject().apply {
                put("apk", b.apk); put("versionCode", b.versionCode); put("versionName", b.versionName)
                put("system", b.system); put("time", b.time); put("stubVersion", b.stubVersion)
            })
        }
        file(ctx).writeText(o.toString())
    }

    fun put(ctx: Context, b: Backup) {
        val m = all(ctx); m[b.pkg] = b; save(ctx, m)
    }

    fun remove(ctx: Context, pkg: String) {
        val m = all(ctx); m.remove(pkg); save(ctx, m)
    }
}

object ZoneStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "zones.json")

    fun all(ctx: Context): MutableMap<String, String> {
        val out = HashMap<String, String>()
        try {
            val f = file(ctx)
            if (!f.exists()) return out
            val o = JSONObject(f.readText())
            for (k in o.keys()) out[k] = o.optString(k, "white")
        } catch (t: Throwable) {
            Logx.w("ZoneStore 读取失败: ${t.message}")
        }
        return out
    }

    fun save(ctx: Context, map: Map<String, String>) = file(ctx).writeText(JSONObject(map).toString())

    fun zoneOf(ctx: Context, pkg: String): Zone? = all(ctx)[pkg]?.let { Zone.of(it) }

    fun set(ctx: Context, pkg: String, zone: Zone) {
        val m = all(ctx); m[pkg] = zone.id; save(ctx, m)
    }

    fun remove(ctx: Context, pkg: String) {
        val m = all(ctx); m.remove(pkg); save(ctx, m)
    }

    fun appsIn(ctx: Context, zone: Zone, allApps: List<AppInfo>): List<AppInfo> {
        val m = all(ctx)
        return allApps.filter { m[it.pkg] == zone.id }
    }
}

/**
 * 每应用 × 每权限 独立分区。
 * 默认：三权限全部白区（绿）——第一次打开时所有应用都在绿区，由用户/预设再收紧。
 */
object PermZoneStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "perm_zones.json")

    fun raw(ctx: Context): MutableMap<String, MutableMap<String, String>> {
        val out = HashMap<String, MutableMap<String, String>>()
        try {
            val f = file(ctx)
            if (!f.exists()) return out
            val o = JSONObject(f.readText())
            for (k in o.keys()) {
                val j = o.getJSONObject(k)
                val m = HashMap<String, String>()
                for (p in Perm.entries) m[p.id] = j.optString(p.id, "")
                out[k] = m
            }
        } catch (t: Throwable) {
            Logx.w("PermZoneStore 读取失败: ${t.message}")
        }
        return out
    }

    private fun save(ctx: Context, map: Map<String, Map<String, String>>) {
        val o = JSONObject()
        for ((pkg, m) in map) o.put(pkg, JSONObject(m as Map<*, *>))
        file(ctx).writeText(o.toString())
    }

    fun defaults(app: AppInfo): PermZones =
        PermZones(
            net = Zone.WHITE,
            loc = Zone.WHITE,
            rec = Zone.WHITE
        )

    fun zonesOf(ctx: Context, app: AppInfo): PermZones {
        val m = raw(ctx)[app.pkg] ?: return defaults(app)
        val d = defaults(app)
        return PermZones(
            net = Zone.of(m[Perm.NET.id]?.ifEmpty { null } ?: d.net.id),
            loc = Zone.of(m[Perm.LOC.id]?.ifEmpty { null } ?: d.loc.id),
            rec = Zone.of(m[Perm.REC.id]?.ifEmpty { null } ?: d.rec.id)
        )
    }

    fun zonesOf(map: Map<String, Map<String, String>>, app: AppInfo): PermZones {
        val m = map[app.pkg] ?: return defaults(app)
        val d = defaults(app)
        return PermZones(
            net = Zone.of(m[Perm.NET.id]?.ifEmpty { null } ?: d.net.id),
            loc = Zone.of(m[Perm.LOC.id]?.ifEmpty { null } ?: d.loc.id),
            rec = Zone.of(m[Perm.REC.id]?.ifEmpty { null } ?: d.rec.id)
        )
    }

    fun set(ctx: Context, pkg: String, perm: Perm, zone: Zone) {
        val all = raw(ctx)
        val m = all[pkg] ?: HashMap()
        m[perm.id] = zone.id
        all[pkg] = m
        save(ctx, all)
    }

    fun setMany(ctx: Context, pkgs: Collection<String>, perm: Perm, zone: Zone) {
        if (pkgs.isEmpty()) return
        val all = raw(ctx)
        for (pkg in pkgs) {
            val m = all[pkg] ?: HashMap()
            m[perm.id] = zone.id
            all[pkg] = m
        }
        save(ctx, all)
    }

    fun count(
        apps: List<AppInfo>,
        map: Map<String, Map<String, String>>,
        perm: Perm,
        zone: Zone
    ): Int = apps.count { zonesOf(map, it).of(perm).id == zone.id }
}

/** 记录「应用 × 权限」最近一次设置失败的原因；设置成功即清除。 */
object PermFailStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "perm_fail.json")
    private fun key(pkg: String, p: Perm) = "$pkg|${p.id}"

    fun all(ctx: Context): MutableMap<String, String> {
        val out = HashMap<String, String>()
        try {
            val f = file(ctx)
            if (!f.exists()) return out
            val o = JSONObject(f.readText())
            for (k in o.keys()) out[k] = o.optString(k)
        } catch (t: Throwable) {
            Logx.w("PermFailStore 读取失败: ${t.message}")
        }
        return out
    }

    private fun save(ctx: Context, m: Map<String, String>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        file(ctx).writeText(o.toString())
    }

    fun reason(ctx: Context, pkg: String, p: Perm): String = all(ctx)[key(pkg, p)] ?: ""

    fun set(ctx: Context, pkg: String, p: Perm, msg: String) {
        val m = all(ctx)
        m[key(pkg, p)] = msg.take(300)
        save(ctx, m)
    }

    fun clear(ctx: Context, pkg: String, p: Perm) {
        val m = all(ctx)
        if (m.remove(key(pkg, p)) != null) save(ctx, m)
    }

    fun clearAll(ctx: Context, pkg: String) {
        val m = all(ctx)
        var changed = false
        for (p in Perm.entries) if (m.remove(key(pkg, p)) != null) changed = true
        if (changed) save(ctx, m)
    }
}

/** 记录隔离/顶替等操作状态，供清理页展示“放行 / 隔离 / 顶替”。 */
object OpsStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "ops.json")

    fun all(ctx: Context): MutableMap<String, String> {
        val out = HashMap<String, String>()
        try {
            val f = file(ctx)
            if (!f.exists()) return out
            val o = JSONObject(f.readText())
            for (k in o.keys()) out[k] = o.optString(k)
        } catch (t: Throwable) {
            Logx.w("OpsStore 读取失败: ${t.message}")
        }
        return out
    }

    private fun save(ctx: Context, m: Map<String, String>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        file(ctx).writeText(o.toString())
    }

    fun status(ctx: Context, pkg: String): String = all(ctx)[pkg] ?: ""

    fun set(ctx: Context, pkg: String, status: String) {
        val m = all(ctx); m[pkg] = status; save(ctx, m)
    }

    fun clear(ctx: Context, pkg: String) {
        val m = all(ctx); m.remove(pkg); save(ctx, m)
    }
}

data class BlockGroup(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val domains: List<String>,
    val ips: List<String>
)

object BlocklistStore {
    private fun userFile(ctx: Context) = File(ctx.filesDir, "blocklist_user.json")

    fun seedGroups(ctx: Context): List<BlockGroup> {
        val out = ArrayList<BlockGroup>()
        try {
            val text = ctx.assets.open("blocklist.json").bufferedReader().use { it.readText() }
            val arr = JSONObject(text).getJSONArray("groups")
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                out.add(
                    BlockGroup(
                        j.getString("id"), j.getString("name"), j.optBoolean("enabled", true),
                        j.optJSONArray("domains")?.toStringList() ?: emptyList(),
                        j.optJSONArray("ips")?.toStringList() ?: emptyList()
                    )
                )
            }
        } catch (t: Throwable) {
            Logx.w("内置黑名单读取失败: ${t.message}")
        }
        return out
    }

    private fun overrides(ctx: Context): JSONObject = try {
        val f = userFile(ctx)
        if (f.exists()) JSONObject(f.readText()) else JSONObject()
    } catch (_: Throwable) { JSONObject() }

    private fun saveOverrides(ctx: Context, o: JSONObject) = userFile(ctx).writeText(o.toString())

    fun groups(ctx: Context): List<BlockGroup> {
        val o = overrides(ctx)
        val disabled = o.optJSONArray("disabledGroups")?.toStringSet() ?: emptySet()
        val enabled = o.optJSONArray("enabledGroups")?.toStringSet() ?: emptySet()
        val extraDomains = o.optJSONArray("extraDomains")?.toStringList() ?: emptyList()
        val extraIps = o.optJSONArray("extraIps")?.toStringList() ?: emptyList()
        val removed = o.optJSONArray("removedDomains")?.toStringSet() ?: emptySet()
        val list = seedGroups(ctx).map { g ->
            g.copy(
                enabled = (g.enabled || g.id in enabled) && g.id !in disabled,
                domains = (g.domains.filter { it !in removed } + if (g.id == "user") extraDomains else emptyList()),
                ips = g.ips + if (g.id == "user") extraIps else emptyList()
            )
        }.toMutableList()
        val user = list.firstOrNull { it.id == "user" }
        if (user == null && (extraDomains.isNotEmpty() || extraIps.isNotEmpty())) {
            list.add(BlockGroup("user", "手动添加", true, extraDomains, extraIps))
        }
        return list
    }

    fun setEnabled(ctx: Context, id: String, enabled: Boolean) {
        val o = overrides(ctx)
        val off = (o.optJSONArray("disabledGroups")?.toStringSet() ?: emptySet()).toMutableSet()
        val on = (o.optJSONArray("enabledGroups")?.toStringSet() ?: emptySet()).toMutableSet()
        if (enabled) {
            off.remove(id)
            on.add(id)
        } else {
            on.remove(id)
            off.add(id)
        }
        o.put("disabledGroups", JSONArray(off.toList()))
        o.put("enabledGroups", JSONArray(on.toList()))
        saveOverrides(ctx, o)
    }

    fun addDomain(ctx: Context, d: String) {
        val o = overrides(ctx)
        val arr = o.optJSONArray("extraDomains") ?: JSONArray()
        val list = arr.toStringList().toMutableList()
        if (d !in list) list.add(d)
        o.put("extraDomains", JSONArray(list))
        saveOverrides(ctx, o)
    }

    fun addIp(ctx: Context, ip: String) {
        val o = overrides(ctx)
        val arr = o.optJSONArray("extraIps") ?: JSONArray()
        val list = arr.toStringList().toMutableList()
        if (ip !in list) list.add(ip)
        o.put("extraIps", JSONArray(list))
        saveOverrides(ctx, o)
    }

    fun removeDomain(ctx: Context, d: String) {
        val o = overrides(ctx)
        val extra = o.optJSONArray("extraDomains")?.toStringList()?.filter { it != d } ?: emptyList()
        o.put("extraDomains", JSONArray(extra))
        val removed = (o.optJSONArray("removedDomains")?.toStringSet() ?: emptySet()).toMutableSet()
        removed.add(d)
        o.put("removedDomains", JSONArray(removed.toList()))
        saveOverrides(ctx, o)
    }

    fun removeIp(ctx: Context, ip: String) {
        val o = overrides(ctx)
        val extra = o.optJSONArray("extraIps")?.toStringList()?.filter { it != ip } ?: emptyList()
        o.put("extraIps", JSONArray(extra))
        saveOverrides(ctx, o)
    }

    fun activeDomains(ctx: Context): Set<String> =
        groups(ctx).filter { it.enabled }.flatMap { it.domains }.map { it.lowercase() }.toSet()

    fun activeIps(ctx: Context): List<String> =
        groups(ctx).filter { it.enabled }.flatMap { it.ips }

    /** 后缀匹配：a.b.example.com 命中 example.com；不匹配 notexample.com。 */
    fun matches(host: String, domains: Set<String>): Boolean {
        val h = host.trimEnd('.').lowercase()
        for (d in domains) {
            if (h == d || h.endsWith(".$d")) return true
        }
        return false
    }
}

/** 清理页手动添加的包名：不在可疑清单内也保留在清理列表中（直到用户移除或卸载）。 */
object ManualStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "manual.json")

    fun all(ctx: Context): Set<String> = try {
        val f = file(ctx)
        if (f.exists()) JSONArray(f.readText()).toStringSet() else emptySet()
    } catch (_: Throwable) { emptySet() }

    fun add(ctx: Context, pkg: String) {
        val s = all(ctx).toMutableSet(); s.add(pkg)
        file(ctx).writeText(JSONArray(s.toList()).toString())
    }

    fun remove(ctx: Context, pkg: String) {
        val s = all(ctx).toMutableSet(); s.remove(pkg)
        file(ctx).writeText(JSONArray(s.toList()).toString())
    }
}

object SettingsStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "settings.json")

    fun json(ctx: Context): JSONObject = try {
        val f = file(ctx); if (f.exists()) JSONObject(f.readText()) else JSONObject()
    } catch (_: Throwable) { JSONObject() }

    fun save(ctx: Context, o: JSONObject) = file(ctx).writeText(o.toString())

    fun flag(ctx: Context, key: String, def: Boolean): Boolean = json(ctx).optBoolean(key, def)
    fun setFlag(ctx: Context, key: String, v: Boolean) {
        val o = json(ctx); o.put(key, v); save(ctx, o)
    }
    fun string(ctx: Context, key: String, def: String): String = json(ctx).optString(key, def)
    fun setString(ctx: Context, key: String, v: String) {
        val o = json(ctx); o.put(key, v); save(ctx, o)
    }

    const val VPN_ON = "vpnOn"
    const val SUSPEND_BLACK = "suspendBlack"
    const val DNS_UPSTREAM = "dnsUpstream"
    const val AUTO_APPLY = "autoApply"
    const val ANTI_SATELLITE = "antiSatellite"
    /** 全隧道（实验）：0.0.0.0/0 进 tun，用 SNI 监控/屏蔽 App 自带 DoH */
    const val FULL_TUNNEL = "fullTunnel"
}

private fun JSONArray.toStringList(): List<String> = (0 until length()).map { optString(it) }

private fun JSONArray.toStringSet(): Set<String> = toStringList().toSet()
