package com.dyx.smbplayer.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dyx.smbplayer.data.model.RemoteProtocol
import com.dyx.smbplayer.data.model.RemoteServer
import com.dyx.smbplayer.ui.common.IconTile

/**
 * 添加 / 编辑网络服务器：支持 SMB 与 WebDAV，可先「测试连接」再保存。
 */
@Composable
fun ServerDialog(
    initial: RemoteServer?,
    prefillHost: String?,
    prefillProtocol: RemoteProtocol = RemoteProtocol.SMB,
    onTest: (RemoteServer, (Boolean, String) -> Unit) -> Unit,
    onSave: (RemoteServer) -> Unit,
    onDismiss: () -> Unit,
) {
    var protocol by remember { mutableStateOf(initial?.protocol ?: prefillProtocol) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: prefillHost ?: "") }
    var share by remember { mutableStateOf(initial?.share ?: "") }
    var domain by remember { mutableStateOf(initial?.domain ?: "") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var anonymous by remember { mutableStateOf(initial?.anonymous ?: false) }
    var secure by remember { mutableStateOf(initial?.secure ?: false) }
    var insecureTls by remember { mutableStateOf(initial?.insecureTls ?: false) }

    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    val isWebDav = protocol == RemoteProtocol.WEBDAV
    val parsed = parseWebDavInput(host, share, secure, isWebDav)

    val buildServer = {
        RemoteServer(
            id = initial?.id ?: 0L,
            name = name.ifBlank {
                host.ifBlank { if (isWebDav) "WebDAV 服务器" else "SMB 服务器" }
            },
            protocol = protocol,
            host = if (isWebDav) parsed.host else host.trim(),
            share = if (isWebDav) parsed.path else share.trim(),
            domain = if (isWebDav) "" else domain.trim(),
            username = username.trim(),
            password = password,
            anonymous = anonymous,
            secure = if (isWebDav) parsed.secure else false,
            insecureTls = insecureTls,
        )
    }

    val valid = if (isWebDav) parsed.host.isNotBlank() else host.isNotBlank()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 22.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(
                        icon = if (isWebDav) Icons.Rounded.Cloud else Icons.Rounded.Dns,
                        container = if (isWebDav) MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.primaryContainer,
                        tint = if (isWebDav) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.primary,
                        size = 46.dp,
                        iconSize = 23.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            if (initial == null) "添加服务器" else "编辑服务器",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            if (isWebDav) "通过 HTTP/WebDAV 访问网盘与 NAS"
                            else "通过 SMB 访问 Windows 共享与 NAS",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                ProtocolSelector(
                    protocol = protocol,
                    onChange = {
                        protocol = it
                        testResult = null
                    },
                )

                Spacer(Modifier.height(14.dp))
                Field(
                    value = name,
                    onValueChange = { name = it },
                    label = "名称（可选）",
                    placeholder = if (isWebDav) "家庭网盘" else "客厅 NAS",
                )
                Spacer(Modifier.height(10.dp))
                Field(
                    value = host,
                    onValueChange = { host = it },
                    label = if (isWebDav) "服务器地址" else "主机地址",
                    placeholder = if (isWebDav) "192.168.1.10:5005 或 nas.local" else "192.168.1.10 或 nas.local",
                    keyboardType = KeyboardType.Uri,
                    capitalization = KeyboardCapitalization.None,
                )
                if (isWebDav) {
                    Text(
                        "可直接粘贴完整地址，例如 https://nas.local:5006/dav",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Field(
                    value = share,
                    onValueChange = { share = it },
                    label = if (isWebDav) "路径（可选）" else "共享名（可选）",
                    placeholder = if (isWebDav) "/dav/视频" else "media",
                    capitalization = KeyboardCapitalization.None,
                )
                if (!isWebDav) {
                    Text(
                        "留空则打开时列出这台电脑/NAS 上的全部共享；填 / 视同留空",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }

                if (isWebDav) {
                    Spacer(Modifier.height(12.dp))
                    ToggleRow(
                        title = "使用 HTTPS",
                        subtitle = if (parsed.secure) "加密连接（端口通常是 5006 / 443）"
                        else "普通 HTTP（局域网更快，端口通常是 5005 / 80）",
                        checked = parsed.secure,
                        onCheckedChange = { secure = it },
                    )
                    if (parsed.secure) {
                        Spacer(Modifier.height(8.dp))
                        ToggleRow(
                            title = "允许自签名证书",
                            subtitle = "NAS 自带 https 证书不受信任时打开",
                            checked = insecureTls,
                            onCheckedChange = { insecureTls = it },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    title = if (isWebDav) "无需账号" else "匿名访问",
                    subtitle = if (isWebDav) "公开分享的 WebDAV 可免登录" else "访客 / guest 无需账号密码",
                    checked = anonymous,
                    onCheckedChange = { anonymous = it },
                )

                if (!anonymous) {
                    Spacer(Modifier.height(10.dp))
                    if (!isWebDav) {
                        Field(
                            value = domain,
                            onValueChange = { domain = it },
                            label = "域 / 工作组（可选）",
                            placeholder = "WORKGROUP",
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Field(
                        value = username,
                        onValueChange = { username = it },
                        label = "用户名",
                        placeholder = "admin",
                    )
                    Spacer(Modifier.height(10.dp))
                    Field(
                        value = password,
                        onValueChange = { password = it },
                        label = "密码",
                        placeholder = "••••••••",
                        visualTransformation = if (passwordVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailing = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                        },
                    )
                }

                testResult?.let { msg ->
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (testOk) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (testOk) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = if (testOk) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                msg,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (testOk) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        enabled = valid && !testing,
                        onClick = {
                            testing = true
                            testOk = false
                            testResult = "正在测试连接…"
                            onTest(buildServer()) { ok, msg ->
                                testing = false
                                testOk = ok
                                testResult = msg
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Text(if (testing) "测试中…" else "测试连接", style = MaterialTheme.typography.labelLarge)
                    }
                    Button(
                        enabled = valid,
                        onClick = { onSave(buildServer()) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Text("保存", style = MaterialTheme.typography.labelLarge)
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 协议切换：SMB / WebDAV */
@Composable
private fun ProtocolSelector(
    protocol: RemoteProtocol,
    onChange: (RemoteProtocol) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteProtocol.entries.forEach { item ->
            val selected = item == protocol
            FilterChip(
                selected = selected,
                onClick = { onChange(item) },
                label = {
                    Text(
                        if (item == RemoteProtocol.SMB) "SMB 共享" else "WebDAV 网盘",
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                leadingIcon = {
                    Icon(
                        if (item == RemoteProtocol.WEBDAV) Icons.Rounded.Cloud else Icons.Rounded.Dns,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private data class ParsedWebDav(val host: String, val path: String, val secure: Boolean)

/**
 * 解析 WebDAV 输入：
 * - `https://nas:5006/dav/视频` -> host=nas:5006, path=/dav/视频, secure=true
 * - 单独填写了路径框时以路径框为准
 */
private fun parseWebDavInput(
    rawInput: String,
    pathInput: String,
    secureInput: Boolean,
    enabled: Boolean,
): ParsedWebDav {
    if (!enabled) return ParsedWebDav(rawInput.trim(), pathInput.trim(), secureInput)
    var raw = rawInput.trim()
    var secure = secureInput
    when {
        raw.startsWith("https://", ignoreCase = true) -> {
            secure = true
            raw = raw.substring(8)
        }

        raw.startsWith("http://", ignoreCase = true) -> {
            secure = false
            raw = raw.substring(7)
        }
    }
    raw = raw.trimEnd('/')
    val slash = raw.indexOf('/')
    val hostPart = if (slash >= 0) raw.substring(0, slash) else raw
    var path = if (slash >= 0) raw.substring(slash) else ""
    val explicit = pathInput.trim()
    if (explicit.isNotEmpty()) path = explicit
    return ParsedWebDav(hostPart.trim(), path.trim(), secure)
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = keyboardType,
            capitalization = capitalization,
            // 服务器地址 / 用户名 / 路径不能被输入法自动纠错（会把 test 改成 testing 之类）
            autoCorrectEnabled = false,
        ),
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        modifier = Modifier.fillMaxWidth(),
    )
}
