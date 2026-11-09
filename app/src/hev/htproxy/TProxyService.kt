package hev.htproxy

/**
 * hev-socks5-tunnel 的 JNI 入口。
 * 原生库会把 native 方法注册到 `hev/htproxy/TProxyService` 这个类上，
 * 所以包名/类名/签名都不能改（对应 hev-jni.c 里的 CLSNAME）。
 */
object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    external fun TProxyStartService(configPath: String?, fd: Int): Boolean

    external fun TProxyStopService(): Boolean

    external fun TProxyIsRunning(): Boolean

    external fun TProxyGetStats(): LongArray
}