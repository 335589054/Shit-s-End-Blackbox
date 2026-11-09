package mai.solium.shitsendblackbox.cleaner

import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 在 shell(uid 2000) 下由 app_process 启动，直接通过 binder 调用
 * IPackageManager.deletePackageAsUser(pkg, -1, observer, user, flags)，
 * 绕过厂商对 `pm uninstall` 外壳命令的拦截。
 *
 * 注意：ActivityThread / IPackageDeleteObserver 都是隐藏 API，
 * 这里全部使用 Class.forName + 反射，避免编译期引用。
 *
 * 用法：CLASSPATH=<本应用 base.apk> app_process /system/bin \
 *        mai.solium.shitsendblackbox.cleaner.PackageUninstaller <pkg> <userId> [flags]
 */
object PackageUninstaller {
    private const val FLAG_KEEP_DATA = 1
    private const val FLAG_SYSTEM_APP = 4

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val uid = Process.myUid()
            if (uid != 2000 && uid != 0) {
                System.err.println("ERR_UID:$uid (需要 shell/root)")
                System.exit(3)
            }
            if (args.isEmpty()) {
                System.err.println("ERR_ARGS: <pkg> [userId] [flags]")
                System.exit(2)
            }
            val pkg = args[0]
            val user = if (args.size > 1) args[1].toIntOrNull() ?: 0 else 0
            val flags = if (args.size > 2) args[2].toIntOrNull()
                ?: (FLAG_KEEP_DATA or FLAG_SYSTEM_APP) else (FLAG_KEEP_DATA or FLAG_SYSTEM_APP)

            val ipm = packageManager() ?: run {
                System.err.println("ERR_PM:null")
                System.exit(3); return
            }
            // IPackageDeleteObserver 是隐藏类型：用 Class.forName + 动态代理
            val obsClass = Class.forName("android.content.pm.IPackageDeleteObserver")
            val observer = deleteObserver(obsClass)

            val m = findDeleteMethod(ipm) ?: run {
                System.err.println("ERR_REFLECT:deletePackageAsUser not found")
                System.exit(3); return
            }
            m.isAccessible = true
            m.invoke(ipm, pkg, -1, observer, user, flags)
            println("DELETE_REQUESTED $pkg user=$user flags=$flags")
            Thread.sleep(1200)
            System.exit(0)
        } catch (t: Throwable) {
            System.err.println("ERR_EXC:${t.javaClass.simpleName}:${t.message}")
            System.exit(3)
        }
    }

    private fun packageManager(): Any? = try {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("getPackageManager").invoke(null)
    } catch (t: Throwable) {
        System.err.println("ERR_AT:${t.message}")
        null
    }

    private fun findDeleteMethod(ipm: Any): Method? {
        for (m in ipm.javaClass.methods) {
            if (m.name == "deletePackageAsUser" && m.parameterTypes.size == 5 &&
                m.parameterTypes[0] == String::class.java
            ) return m
        }
        return null
    }

    /** 用动态代理实现 IPackageDeleteObserver，避免直接引用隐藏 AIDL stub。 */
    private fun deleteObserver(cl: Class<*>): Any? {
        return try {
            Proxy.newProxyInstance(cl.classLoader, arrayOf(cl), InvocationHandler { _, method, _ ->
                when (method.name) {
                    "packageDeleted" -> null
                    "asBinder" -> Binder()
                    else -> defaultValue(method)
                }
            })
        } catch (t: Throwable) {
            System.err.println("ERR_OBS:${t.message}")
            null
        }
    }

    private fun defaultValue(m: Method): Any? = when (m.returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        else -> null
    }
}
