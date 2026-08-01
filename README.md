# MIUI 全局搜索使用 Google

适用于已 Root、带 KernelSU/Magisk 的小米设备。目标是把 MIUI 全局搜索中的 360 替换为 Google，并将 Google 设为默认搜索引擎。

## 原理

MIUI 全局搜索组件是 `com.android.quicksearchbox`，搜索引擎配置位于其应用数据目录中的 `searchengineNew.json`。脚本会：

- 将搜索引擎列表中的 360 改为 Google；
- 使用 `https://www.google.com/search?q={searchTerms}`；
- 将全局搜索和热榜的默认引擎设为 Google；
- 修改本地 SharedPreferences 中的默认值；
- 在原文件旁保存 `.codexbak-google-search` 备份。

## 使用

在 Windows PowerShell 中执行：

```powershell
adb connect DEVICE_IP:5555
.\scripts\patch-google-search.ps1 -Serial DEVICE_IP:5555
```

恢复原配置：

```powershell
.\scripts\restore-google-search.ps1 -Serial DEVICE_IP:5555
```

脚本需要设备上的 `su -M`（KernelSU mount-master）权限。执行后重新打开全局搜索。

## VPN 分应用

如果搜索页面打不开，Clash Meta 的分应用代理至少要包含：

- `com.android.quicksearchbox`：小米全局搜索；
- `com.android.browser`：小米浏览器，搜索按钮实际会跳转到这里。

仅勾选 Chrome 不足以覆盖小米全局搜索当前使用的浏览器。Clash 规则还需要让 `google.com`、`gstatic.com` 和 `googleapis.com` 走代理。

## 注意

小米组件会定期从服务器更新搜索引擎配置，直接修改应用数据可能被覆盖。需要长期保持时，可把修补脚本作为 KSU/Magisk 服务脚本的一部分，在网络和系统启动后重新执行。

## LSPosed 拦截模块

`xposed-module/` 是一个只作用于 `com.android.quicksearchbox` 的 LSPosed 模块。它在 `searchengineNew.json` 写入前、JSON 读取解析时改写云控内容，并拦截默认引擎 SharedPreferences 写入。模块不拦截其他应用，也不需要改动 QuickSearchBox APK；因此磁盘上的旧 JSON 即使仍显示 360，也不影响 QuickSearchBox 使用 Google。

GitHub Actions 会构建并签名 APK，产物位于 Actions 的 artifact 中。安装后在 LSPosed 中启用模块，并只勾选 `com.android.quicksearchbox`，重启该应用或重启手机。
