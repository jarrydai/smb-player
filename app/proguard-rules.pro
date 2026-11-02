# ===== 通用 =====
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, Exceptions
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ===== jcifs-ng（SMB 协议栈依赖反射/可选加密实现，整体保留最稳妥）=====
-keep class jcifs.** { *; }
-keep interface jcifs.** { *; }
-dontwarn jcifs.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**

# ===== BouncyCastle 全量保留（jcifs NTLM 需要 MD4）=====
# jcifs.util.Crypto.getProvider() 会自己 new BouncyCastleProvider()，
# 再用 MessageDigest.getInstance("MD4", 该实例) 取算法。
# BouncyCastleProvider.setup() 是通过 "org.bouncycastle.jcajce.provider.digest.MD4$Digest"
# 这类字符串注册算法的，R8 无法追踪 -> 会把 jcajce 下的实现类当成无用类删掉，
# 结果 provider 里注册表还在、实现类没了，运行时抛
# NoSuchAlgorithmException: no such algorithm: MD4 for provider BC。
# 因此必须整包保留（debug 不混淆所以正常，只有 release 会踩到）。
-keep class org.bouncycastle.** { *; }

# ===== ExoPlayer / Media3：官方 AAR 已自带 consumer rules，这里只兜底 optional 依赖 =====
-dontwarn androidx.media3.**

# ===== 播放数据源/核心层：被属性配置以字符串形式引用，避免被裁剪 =====
-keep class com.dyx.smbplayer.data.** { *; }
-keep class com.dyx.smbplayer.core.** { *; }
