#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""史终黑盒 构建脚本（不依赖 Gradle）。

用法:  python build.py [--install] [--no-sign]
依赖:  JDK 21, Android SDK build-tools 36.0.0 + platforms/android-36,
       Kotlin 2.1.0 编译器, 已解压的 Shizuku 13.1.5 classes jar。
"""
import os, sys, shutil, subprocess, zipfile, glob, time

ROOT = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(ROOT, "app")
WS = r"C:\Users\33558\Documents\deepseek-harness\default-workspace"
# aapt2 在本机无法写入 Desktop 下的目录（ERROR_INVALID_DATA），构建目录放到工作区
BUILD = os.path.join(WS, "out", "bbbuild")
SDK = os.path.join(WS, "out", "sdk")
BT = os.path.join(SDK, "build-tools", "36.0.0")
ANDROID_JAR = os.path.join(SDK, "platforms", "android-36", "android.jar")
KOTLINC = os.path.join(WS, "out", "tools", "kotlinc", "bin", "kotlinc.bat")
KOTLIN_STDLIB = os.path.join(WS, "out", "tools", "kotlinc", "lib", "kotlin-stdlib.jar")
SHIZUKU = os.path.join(WS, "out", "shizuku")
APKSIG = os.path.join(WS, "out", "libs", "apksig-8.7.3.jar")
JDK = r"C:\Program Files\Java\jdk-21.0.12"
JAVA = os.path.join(JDK, "bin", "java.exe")
KEYTOOL = os.path.join(JDK, "bin", "keytool.exe")
KEYSTORE = os.path.join(APP, "keystore", "blackbox.jks")
PACKAGE = "mai.solium.shitsendblackbox"
OUT_APK = os.path.join(ROOT, "dist", "shitsendblackbox-debug.apk")

ENV = dict(os.environ)
ENV["JAVA_HOME"] = JDK
ENV["PATH"] = os.path.join(JDK, "bin") + os.pathsep + ENV.get("PATH", "")


def run(cmd, **kw):
    print("+", " ".join(cmd) if isinstance(cmd, list) else cmd)
    p = subprocess.run(cmd, env=ENV, **kw)
    if p.returncode != 0:
        sys.exit("command failed (%d): %s" % (p.returncode, cmd))


def bat(name):
    p = os.path.join(BT, name + ".bat")
    if os.path.exists(p):
        return p
    return os.path.join(BT, name + ".exe")


def main():
    args = sys.argv[1:]
    if os.path.isdir(BUILD):
        shutil.rmtree(BUILD)
    os.makedirs(BUILD)
    os.makedirs(os.path.join(ROOT, "dist"), exist_ok=True)

    # 0. 签名证书
    if not os.path.exists(KEYSTORE):
        os.makedirs(os.path.dirname(KEYSTORE), exist_ok=True)
        run([KEYTOOL, "-genkeypair", "-keystore", KEYSTORE, "-alias", "blackbox",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-storepass", "android", "-keypass", "android",
             "-dname", "CN=ShiZhong BlackBox, O=Solium, C=CN"])

    # 1. 资源
    res_dir = os.path.join(APP, "res")
    res_out = os.path.join(BUILD, "res")
    os.makedirs(res_out)
    run([bat("aapt2"), "compile", "--dir", res_dir, "-o", res_out])
    res_flat = glob.glob(os.path.join(res_out, "*.flat"))

    # 2. 链接（产物含 manifest / resources.arsc / res）
    base_apk = os.path.join(BUILD, "base.apk")
    run([bat("aapt2"), "link", "-o", base_apk,
         "-I", ANDROID_JAR,
         "--manifest", os.path.join(APP, "AndroidManifest.xml"),
         "--min-sdk-version", "26", "--target-sdk-version", "36",
         "--version-code", "1", "--version-name", "1.0",
         "--auto-add-overlay"] + res_flat)

    # 3. Kotlin 编译
    src = os.path.join(APP, "src")
    kt_files = []
    for d, _, fs in os.walk(src):
        for f in fs:
            if f.endswith(".kt"):
                kt_files.append(os.path.join(d, f))
    classes = os.path.join(BUILD, "classes")
    os.makedirs(classes)
    cp = ";".join([ANDROID_JAR,
                          os.path.join(SHIZUKU, "api-13.1.5-classes.jar"),
                          os.path.join(SHIZUKU, "aidl-13.1.5-classes.jar"),
                          os.path.join(SHIZUKU, "provider-13.1.5-classes.jar"),
                          APKSIG])
    # 直接调用 java：kotlinc.bat 会把含分号的 -classpath 参数按 ; 拆开
    run([JAVA, "-cp", os.path.join(WS, "out", "tools", "kotlinc", "lib", "*"),
         "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"] + kt_files +
        ["-classpath", cp, "-d", classes, "-jvm-target", "1.8", "-nowarn"])

    # 4. dex（类文件打成 jar 再交给 d8，避免 Windows 命令行超长）
    dex_dir = os.path.join(BUILD, "dex")
    os.makedirs(dex_dir)
    classes_jar = os.path.join(BUILD, "classes.jar")
    run([os.path.join(JDK, "bin", "jar.exe"), "cf", classes_jar, "-C", classes, "."])
    run([bat("d8"), "--min-api", "26", "--lib", ANDROID_JAR,
         "--output", dex_dir, classes_jar,
         KOTLIN_STDLIB,
         os.path.join(SHIZUKU, "api-13.1.5-classes.jar"),
         os.path.join(SHIZUKU, "aidl-13.1.5-classes.jar"),
         os.path.join(SHIZUKU, "provider-13.1.5-classes.jar"),
         APKSIG])

    # 5. 组装 apk：base + classes.dex + assets
    assembled = os.path.join(BUILD, "assembled.apk")
    shutil.copyfile(base_apk, assembled)
    with zipfile.ZipFile(assembled, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(dex_dir, "classes.dex"), "classes.dex")
        libs = os.path.join(APP, "libs")
        if os.path.isdir(libs):
            for abi in sorted(os.listdir(libs)):
                d = os.path.join(libs, abi)
                if not os.path.isdir(d):
                    continue
                for f in sorted(os.listdir(d)):
                    if f.endswith(".so"):
                        z.write(os.path.join(d, f), "lib/" + abi + "/" + f)
        assets = os.path.join(APP, "assets")
        if os.path.isdir(assets):
            for d, _, fs in os.walk(assets):
                for f in fs:
                    p = os.path.join(d, f)
                    z.write(p, "assets/" + os.path.relpath(p, assets).replace(os.sep, "/"))

    if "--no-sign" in args:
        print("unsigned:", assembled)
        return

    # 6. zipalign + 签名
    aligned = os.path.join(BUILD, "aligned.apk")
    run([bat("zipalign"), "-p", "-f", "4", assembled, aligned])
    run([bat("apksigner"), "sign", "--ks", KEYSTORE, "--ks-pass", "pass:android",
         "--key-pass", "pass:android", "--v1-signing-enabled", "true",
         "--v2-signing-enabled", "true", "--out", OUT_APK, aligned])
    print("APK:", OUT_APK, os.path.getsize(OUT_APK), "bytes")

    if "--install" in args:
        adb = os.path.join(SDK, "platform-tools", "adb.exe")
        run([adb, "install", "-r", "-t", "--no-incremental", OUT_APK])


if __name__ == "__main__":
    main()
