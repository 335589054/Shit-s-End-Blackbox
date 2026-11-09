package mai.solium.shitsendblackbox.data

import android.content.Context
import org.json.JSONObject

/**
 * 分区预设：把「哪些应用在哪个权限的哪个区」打包成可导入导出的 JSON。
 *
 * 文件格式（导出后可直接编辑再导入）：
 * {
 *   "format": "shitsendblackbox-zones", "version": 1, "name": "...", "desc": "...",
 *   "perms": ["net","loc","rec"],
 *   "defaults": {"net":"white","loc":"white","rec":"white"},
 *   "apps": {"com.tencent.mm": {"net":"white","loc":"white","rec":"white"}}
 * }
 */
data class ZoneSet(
    val name: String,
    val desc: String,
    /** 该预设作用在哪些权限维度上；不在此集合中的维度保持用户当前设置。 */
    val perms: Set<Perm>,
    val defaults: PermZones,
    /** 包名 → 三个维度的目标分区；未列出的包使用 defaults。 */
    val apps: Map<String, PermZones>
) {
    fun target(current: PermZones, pkg: String, app: AppInfo): PermZones {
        val override = apps[pkg]
        fun pick(p: Perm, fallback: Zone): Zone =
            if (p !in perms) fallback else override?.of(p) ?: defaults.of(p)
        val base = current
        return PermZones(
            net = pick(Perm.NET, base.net),
            loc = pick(Perm.LOC, base.loc),
            rec = pick(Perm.REC, base.rec)
        )
    }
}

object PresetStore {
    const val FORMAT = "shitsendblackbox-zones"
    const val VERSION = 1

    /** 内置预设：联网只放行 微信/QQ/支付宝/学习通，其余应用一律灰区（仅前台可联网）。 */
    fun builtin(): List<ZoneSet> = listOf(
        ZoneSet(
            name = "只保留微信/QQ/支付宝/学习通联网",
            desc = "联网：这 4 个应用放白区，其他所有应用进灰区（仅前台可联网）；定位、录屏保持你现在的设置。",
            perms = setOf(Perm.NET),
            defaults = PermZones(Zone.GRAY, Zone.WHITE, Zone.WHITE),
            apps = mapOf(
                "com.tencent.mm" to PermZones(Zone.WHITE, Zone.WHITE, Zone.WHITE),
                "com.tencent.mobileqq" to PermZones(Zone.WHITE, Zone.WHITE, Zone.WHITE),
                "com.eg.android.AlipayGphone" to PermZones(Zone.WHITE, Zone.WHITE, Zone.WHITE),
                "com.chaoxing.mobile" to PermZones(Zone.WHITE, Zone.WHITE, Zone.WHITE)
            )
        )
    )

    /** 导出当前全部分区（显式列出每个已装应用的三维分区）。 */
    fun exportJson(ctx: Context, apps: List<AppInfo>): String {
        val o = JSONObject()
        o.put("format", FORMAT)
        o.put("version", VERSION)
        o.put("name", "当前分区快照")
        o.put("desc", "由史终黑盒导出，可编辑后导入")
        o.put("perms", org.json.JSONArray(listOf(Perm.NET.id, Perm.LOC.id, Perm.REC.id)))
        o.put("defaults", zonesJson(PermZones(Zone.WHITE, Zone.WHITE, Zone.WHITE)))
        val raw = PermZoneStore.raw(ctx)
        val map = JSONObject()
        for (a in apps) map.put(a.pkg, zonesJson(PermZoneStore.zonesOf(raw, a)))
        o.put("apps", map)
        return o.toString(2)
    }

    /** 解析导入文件；格式不对返回 null。 */
    fun parse(text: String): ZoneSet? = try {
        val o = JSONObject(text)
        if (o.optString("format") != FORMAT) null else {
            val perms = o.optJSONArray("perms")?.let { arr ->
                (0 until arr.length()).mapNotNull { Perm.of(arr.optString(it)) }.toSet()
            } ?: setOf(Perm.NET, Perm.LOC, Perm.REC)
            val d = o.optJSONObject("defaults")
            val defaults = if (d != null) zonesOf(d) else PermZones(Zone.WHITE, Zone.WHITE, Zone.WHITE)
            val apps = HashMap<String, PermZones>()
            o.optJSONObject("apps")?.let { m ->
                for (pkg in m.keys()) m.optJSONObject(pkg)?.let { apps[pkg] = zonesOf(it) }
            }
            ZoneSet(
                o.optString("name", "导入的预设"),
                o.optString("desc", ""),
                perms,
                defaults,
                apps
            )
        }
    } catch (_: Throwable) {
        null
    }

    private fun zonesJson(z: PermZones): JSONObject = JSONObject().apply {
        put(Perm.NET.id, z.net.id)
        put(Perm.LOC.id, z.loc.id)
        put(Perm.REC.id, z.rec.id)
    }

    private fun zonesOf(o: JSONObject): PermZones = PermZones(
        Zone.of(o.optString(Perm.NET.id)),
        Zone.of(o.optString(Perm.LOC.id)),
        Zone.of(o.optString(Perm.REC.id))
    )
}