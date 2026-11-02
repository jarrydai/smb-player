package com.dyx.smbplayer

import android.app.Application
import android.util.Log
import jcifs.util.Crypto
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

/** 应用入口：全局初始化 */
class NetPlayerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installNtlmCryptoProvider()
    }

    /**
     * jcifs-ng 用账号密码登录 SMB 时，NTLM 需要 MD4 计算 NT 哈希。
     *
     * jcifs.util.Crypto.getProvider() 的实现是「自己 new 一个 BouncyCastleProvider 并缓存」，
     * 并不是从 Security 列表里取，所以往 Security 里注册 BC 对它无效，
     * 必须通过 Crypto.initProvider(...) 显式注入。
     *
     * 这里注入一个完整的 BC 实例（APK 内已整包保留，见 proguard-rules.pro）：
     * 既让 MessageDigest.getInstance("MD4", provider) 命中，
     * 又顺带把 Security 列表里的裁剪版 BC 替换掉，作为二次兜底。
     * 调用必须早于任何 SMB 操作（Crypto 只允许首次注入）。
     */
    private fun installNtlmCryptoProvider() {
        try {
            val bc = BouncyCastleProvider()
            // jcifs 只允许注入一次；若已被初始化则忽略，退回 Security 兜底
            runCatching { Crypto.initProvider(bc) }
                .onFailure { Log.w(TAG, "jcifs provider 已初始化，跳过注入", it) }
            // Security 兜底：让其它直接 getInstance("MD4") 的代码也能命中完整实现
            if (Security.getProvider(bc.name) !== bc) {
                Security.removeProvider(bc.name)
                Security.addProvider(bc)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "初始化 BC 提供者失败", t)
        }
    }

    private companion object {
        const val TAG = "NetPlayer"
    }
}
