# 网络播放器 SmbPlayer

一个直接播放 NAS / 局域网共享里视频的 Android 播放器。片源留在服务器上，不下载、不占手机空间；播放进度、服务器账号都存在手机本地。

支持 **SMB** 与 **WebDAV** 两种协议，同一套界面里浏览目录、播放视频、查看图片和文本，本地文件也能一起看。

## 功能

**连接**
- SMB（jcifs-ng，兼容 SMB1/2/3：Windows 共享、群晖、威联通、Samba）
- WebDAV（自研 HTTP/1.1 客户端，支持 Basic 认证、自签名证书、Range 分段请求）
- 局域网 mDNS 自动发现，也可手填地址；匿名访问与账号登录都支持
- 服务器可编辑、删除，密码本地保存

**浏览**
- 目录树逐层浏览，面包屑返回上级
- 按扩展名区分视频 / 图片 / 文本，各自用对应查看器打开
- 支持格式：视频 `mp4 mkv avi mov webm flv ts m4v wmv rmvb mpg mpeg 3gp m2ts vob ogv asf divx f4v m1v m2v ogm tp trp`；图片 `jpg png gif webp bmp heic heif avif`；文本 `txt log md csv json xml ini cfg conf yml yaml properties srt ass html bat sh`

**共享文件管理**
- 长按条目（或点顶栏多选图标）进入多选，可全选、逐个勾选
- 批量复制、移动、删除；单选时还可重命名；随时可新建文件夹
- 复制/移动前会弹出目标文件夹选择器，可逐级进入、返回上级、回到根目录，也能在目标位置新建文件夹
- 删除前二次确认，逐项执行并汇总失败原因，不会因一项失败中断整批
- 同名自动改成 `xxx (1)` 这类名字，`Overwrite: F` 保证不覆盖已有文件；禁止把文件夹放进它自己里面
- SMB 走 jcifs 的原子 rename / 递归流复制，WebDAV 走 `COPY` / `MOVE` / `DELETE` / `MKCOL`

**播放**
- Media3 / ExoPlayer 内核，硬件解码
- 自定义 `DataSource`，拖动进度条即定位，不用先缓冲整个文件
- 手势：左半屏竖滑调亮度、右半屏竖滑调音量、横滑快进快退（带目标时间气泡）
- 双击左右半屏 ±10 秒，单击显隐控制栏，长按 2.0x 倍速预览
- 倍速调节、循环播放、画面比例切换（适应 / 拉伸 / 裁剪）
- 断点续播：退出时记住位置，再进来提示「已从 xx:xx 继续播放」
- 沉浸式全屏，横竖屏自适应

**图片 / 文本**
- 图片查看器支持缩放拖动，单文件上限 64MB
- 文本查看器直接读远端文件，单文件上限 2MB

## 技术栈

| 项 | 选择 |
|---|---|
| 语言 / UI | Kotlin 2.0 + Jetpack Compose（Material3，深浅色主题） |
| 播放内核 | Media3 / ExoPlayer |
| SMB | jcifs-ng 2.1.10（含 BouncyCastle，供 MD4 摘要） |
| WebDAV | 自研 HTTP 客户端 + 自定义 DataSource |
| 持久化 | DataStore (Preferences) |
| 构建 | AGP 8.x / Gradle 8.9 / minSdk 26 / targetSdk 35 |

## 构建

```bash
# 需要 JDK 17 与 Android SDK 35
./gradlew :app:assembleRelease
```

正式包签名依赖根目录的 `keystore.properties` 与 `keystore/`（未入库）。没有这两项时 release 会自动退回 debug 签名，仍可编译安装。

debug 包名带 `.debug` 后缀，可与正式版共存，方便对比调试。

## 下载

最新正式版见 [Releases](../../releases/latest) 页，下载 `net-player-*.apk` 直接安装即可（Android 8.0 及以上）。

> 每个 Release 里还有 `mapping-vX.Y.Z.txt`，那是 R8 混淆映射，用于还原崩溃堆栈，日常安装不需要下载。

## 说明

本项目为个人自用工具，按现状提供。SMB 协议实现基于 jcifs-ng，ExoPlayer 为 Google 开源项目，遵循各自许可证。
