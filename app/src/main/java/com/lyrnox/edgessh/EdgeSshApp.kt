package com.lyrnox.edgessh

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Android 自带的 "BC" provider 是裁剪版（缺少 x25519 等 SSH 握手需要的算法），
 * 这里换成 sshj 自带的完整版 BouncyCastle，否则密钥交换会失败。
 */
class EdgeSshApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
