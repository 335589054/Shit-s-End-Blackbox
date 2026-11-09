package mai.solium.shitsendblackbox.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * 包扫描：应用侧 PackageManager 读取（QUERY_ALL_PACKAGES），
 * 结合已知泄露组件清单 + 权限/图标启发式打分。
 */
object Scanner {
    /** 已知/高度疑似的运营商、厂商数据回传组件；搜索只在这份清单内匹配。 */
    val KNOWN_SUSPICIOUS: Map<String, String> = mapOf(
        "com.mobiletools.systemhelper" to "联通 OMA-DM 组件，上报 IMEI/ICCID/基站/位置/应用用量",
        "com.miui.guardprovider" to "小米安全组件，疑似录屏 + 应用清单回传",
        "com.miui.analytics" to "小米统计，采集设备标识与使用行为",
        "com.miui.msa.global" to "MSA 广告服务，定向投放与行为分析",
        "com.miui.miservice" to "服务与反馈，长期后台、可拉起百度定位",
        "com.miui.klo.bugreport" to "KLO Bug 上报，系统错误发送至小米服务器",
        "com.xiaomi.metoknlp" to "小米网络定位组件，涉及位置与画像 PII",
        "com.miui.regservice" to "内嵌中国移动 SDK，设备注册与运营商数据",
        "com.miui.phrase" to "含 CloudTelephonyManager，与 MiCloud 关联",
        "com.miui.hybrid" to "快应用框架，常被用作预装分发通道",
        "com.coloros.regservice" to "OPPO RegService，记录应用清单/启动/流量",
        "com.coloros.phonemanager" to "OPPO 手机管家，反诈遥测上传号码与通话信息",
        "com.oplus.statistics.rom" to "OPPO 系统统计，落库启动/BSSID/基站/MAC",
        "com.oplus.nearx.track.TrackApi" to "OPPO 遥测公共出口（appId 20092）",
        "com.coloros.activation" to "OPPO 激活服务，上报携带 IMEI 的注册请求",
        "com.vivo.contentcatcher" to "vivo 全局内容识别，疑似 OCR 后回传",
        "com.vivo.customtool" to "vivo 定制工具，预装冗余",
        "com.vivo.nps" to "vivo 数字车钥匙/互传服务",
        "com.vivo.seservice" to "vivo 安全服务，预装冗余",
        "com.qualcomm.qti.services.systemhelper" to "高通 SystemHelper 服务，常驻回传",
        "com.miui.systemAdSolution" to "系统广告服务",
        "com.xiaomi.mipicks" to "小米应用推荐",
        "com.iflytek.inputmethod.miui" to "讯飞输入法（小米定制）",
        "com.tencent.beacon" to "腾讯 Beacon 统计 SDK",
        "com.umeng.analytics" to "友盟统计",
        "com.baidu.location" to "百度定位 SDK",
        "com.samsung.android.analysis" to "三星分析服务",
        "com.oppo.analytics" to "OPPO 分析服务",
        "com.vivo.analytics" to "vivo 分析服务",
        "com.huawei.hwid" to "华为账号/推送组件"
    )

    /** 所有设备都可能预装、必须在清理页常显的组件；未扫描到（已卸载）时显示「已卸载」。 */
    val FORCED_SHOW: Map<String, String> = mapOf(
        "com.mobiletools.systemhelper" to "SystemHelper"
    )

    /** 绝对不能动的系统关键包。 */
    val CRITICAL: Set<String> = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.phone",
        "com.android.providers.settings", "com.android.providers.media",
        "com.android.providers.telephony", "com.android.providers.contacts",
        "com.android.shell", "com.android.packageinstaller", "com.android.permissioncontroller",
        "com.android.launcher3", "com.android.launcher", "com.google.android.gms",
        "com.android.bluetooth", "com.android.nfc", "com.android.se",
        "com.miui.home", "com.miui.securitycenter", "com.miui.securitycore",
        "com.miui.powerkeeper", "com.miui.notification", "com.xiaomi.misettings",
        "com.android.server.telecom", "com.android.keychain", "com.android.certinstaller"
    )

    fun isCritical(pkg: String): Boolean = pkg in CRITICAL || pkg.startsWith("com.android.providers.")

    /** 负责定位/通信等基础能力，允许隔离但禁止卸载，避免删掉后系统功能异常。 */
    val PROTECTED_DELETE: Set<String> = setOf(
        "com.xiaomi.metoknlp", "com.android.location.fused"
    )

    fun isProtectedDelete(pkg: String): Boolean = pkg in PROTECTED_DELETE

    fun scan(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val out = ArrayList<AppInfo>()
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA
        @Suppress("DEPRECATION")
        val list = pm.getInstalledPackages(flags)
        for (pi in list) {
            val ai: ApplicationInfo = pi.applicationInfo ?: continue
            val system = (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            val label = try { pm.getApplicationLabel(ai).toString() } catch (_: Throwable) { pi.packageName }
            val perms = pi.requestedPermissions?.toList() ?: emptyList()
            val hasInternet = perms.contains(android.Manifest.permission.INTERNET) ||
                perms.contains("android.permission.ACCESS_NETWORK_STATE")
            val hasLauncher = try { pm.getLaunchIntentForPackage(pi.packageName) != null } catch (_: Throwable) { false }
            var risk = 0
            val reasons = ArrayList<String>()
            if (system) { risk += 15; reasons.add("系统预装") }
            KNOWN_SUSPICIOUS[pi.packageName]?.let { risk += 60; reasons.add(it) }
            if (system && !hasLauncher) { risk += 10; reasons.add("无桌面图标") }
            if (perms.contains(android.Manifest.permission.READ_PHONE_STATE)) { risk += 12; reasons.add("读取设备标识") }
            if (perms.contains(android.Manifest.permission.ACCESS_FINE_LOCATION)) { risk += 10; reasons.add("精确定位") }
            if (perms.contains(android.Manifest.permission.READ_SMS)) { risk += 10; reasons.add("读取短信") }
            if (perms.contains("android.permission.GET_ACCOUNTS")) { risk += 6; reasons.add("读取账号") }
            if (perms.contains("android.permission.QUERY_ALL_PACKAGES")) { risk += 6; reasons.add("枚举应用") }
            if (pi.packageName in KNOWN_SUSPICIOUS.keys) risk = maxOf(risk, 80)
            if (pi.packageName in CRITICAL) {
                risk = 0
                reasons.clear()
                reasons.add("系统关键组件（受保护）")
            }
            out.add(
                AppInfo(
                    pkg = pi.packageName,
                    label = label,
                    system = system,
                    versionName = pi.versionName ?: "?",
                    versionCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong(),
                    uid = ai.uid,
                    hasInternet = hasInternet,
                    hasLauncher = hasLauncher,
                    permissions = perms,
                    risk = risk,
                    reasons = reasons
                )
            )
        }
        return out.sortedWith(compareByDescending<AppInfo> { it.risk }.thenBy { it.label })
    }

    /** 只取高风险（疑似）项。 */
    fun suspected(ctx: Context): List<AppInfo> = scan(ctx).filter { it.risk >= 40 }

    /**
     * 清理页搜索（m01683 附件3）：只在「已知可疑清单」内匹配包名/应用名，其余应用不出现在结果中。
     */
    fun search(apps: List<AppInfo>, query: String): List<AppInfo> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return apps
        return apps.filter {
            (it.pkg in KNOWN_SUSPICIOUS) &&
                (it.pkg.lowercase().contains(q) || it.label.lowercase().contains(q))
        }
    }

    /** 权限页搜索（m02193）：全体应用都能搜到，不受可疑清单限制。 */
    fun searchAll(apps: List<AppInfo>, query: String): List<AppInfo> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return apps
        return apps.filter { it.pkg.lowercase().contains(q) || it.label.lowercase().contains(q) }
    }
}
