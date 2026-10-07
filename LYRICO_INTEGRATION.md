# Lyrico 1.6.0 文件入口研究

仅对用户提供的 `Lyrico_1.6.0-ad9c83f.apk` 做Manifest与有限类字节码检查，未修改、安装或重签该APK。当前余音跳转实现尚未改变。

## 结论

该版本可以直接进入传入歌曲的元数据编辑页，不需要先在Lyrico资料库寻找。正确入口是 `com.lonx.lyrico.action.EDIT_TAG`，不是标准 `android.intent.action.EDIT`。

Manifest 中 exported 的 `com.lonx.lyrico.MainActivity` 为 singleTop，接收：

- 专用 `com.lonx.lyrico.action.EDIT_TAG`，`audio/*`，`content` 或 `file` scheme。
- 标准 ACTION_VIEW，`audio/*`，`content` 或 `file` scheme。
- ACTION_SEND，`audio/*`。

实际处理函数从 `intent.data` 取专用编辑入口的URI，并读取flags；URI非空时使用 `EditMetadataDestination.invoke(uri.toString())` 作为页面目的地。onCreate和onNewIntent均走同一处理逻辑，热启动会setIntent。

## 建议接法（仅研究结果，未接入）

```kotlin
val intent = Intent("com.lonx.lyrico.action.EDIT_TAG")
    .setPackage("com.lonx.lyrico")
    .setDataAndType(songUri, "audio/*")
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    .apply { clipData = ClipData.newRawUri("audio", songUri) }
context.startActivity(intent)
```

继续保留现有包安装/resolve检测和返回资料库的重新扫描；未安装不打开、不跳商店。只有已具备且允许转授写权限时才能加WRITE flag。

## 权限与验证边界

- 优先使用可转授的content URI，不把文件路径字符串当URI，也不要向现代Android跨应用发送file URI。
- APK入口接受已有媒体读取权限或READ_URI授权，不要求WRITE flag。能打开编辑页不代表一定能写回原文件；实际保存仍受MediaStore或SAF Provider权限约束。
- 首次启动可能先弹媒体/通知授权。未真机验证重复打开同一URI及实际写回效果。
- 余音目前用ACTION_EDIT，目标没有该handler，因此可能退回启动首页；本文件只记录研究，不把待实现改动冒称已接入。

## 字节码证据定位

MainActivity 的Intent处理方法`s`中，专用action分支调用`getData`和`getFlags`；`onCreate`与`onNewIntent`均调用`s`。混淆类`gt2`在URI非空分支调用`EditMetadataDestination.invoke`。临时反汇编内容不打入APK、源码包或Git仓库。
