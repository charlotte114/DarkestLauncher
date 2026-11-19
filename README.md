# Darkest 启动器 · Darkest Launcher

给 Android 版《Darkest Dungeon》（移植版 2.2.1）做的启动器 / mod 管理器：
创意工坊下载、mod 开关、存档备份、游戏内设置，全在一处，不用连电脑。

仓库：<https://github.com/charlotte114/DarkestLauncher>

> 仓库里**不含任何游戏本体文件**（数据包、dex、so、贴图、本地化），只包含启动器自身源码。
> 游戏本体需要从你自己的游戏副本里导入，本仓库不提供也不分发。

整体配色取自初音未来的蓝绿系（`#39C5BB` / `#00A0B8`），界面里不出现红色。

- [使用教程](USAGE.md) —— 从安装、导入数据包到下载 mod 的完整步骤
- [更新日志](CHANGELOG.md) —— 各版本改动

## 功能

- **创意工坊**：直连 Steam CM，**公开条目免登录 / 免 API Key 即可搜索下载**；支持搜索 /
  排序（热门、最新、更新、订阅数）/ 时间范围 / 分页，下载后自动解包到
  `Android/media/com.darkestdungeon/mods/`，支持订阅、检查更新、一键全更
- **mod 管理**：开关（改 `project.xml` 后缀）、单条详情、覆盖冲突提示、一键全开 / 全关、
  批量导入 zip（一个包里的多个 mod 会逐个识别）、自定义分组（可命名 / 折叠 / 批量归组 /
  长按拖动排序，下载时可选目标组）
- **存档**：快照备份、还原、删除、导入 zip、导出（默认 `Download/DarkestSaves/`，或自选目录）
- **游戏内设置**：直接改 `persist.options.json` —— 分辨率、帧率、模糊、Gamma、五路音量、语言、
  字幕、玩法开关；写入前自动备份，可一键还原
- **其它**：Steam 账号登录、运行日志（启动器 + 游戏）、数据包导入 / 删除 / 自动检查 / 下载入口、
  免重启刷新

## 包名说明

`applicationId` 是 `com.darkestdungeon.launcher`，可以和原版游戏同时安装、互不覆盖。
`versionName` 保持 `2.2.1`，与内置的游戏运行时一致，避免第三方工具误判版本。
主界面上的「游戏 x.x.x」不是这个字段，而是**你导入的那份数据包的版本**（导入时读包里的
`AndroidManifest.xml`，读不到就用文件名猜，再不行显示「版本未知」）。
启动器自己的版本看设置页：**1.3**。

数据目录随包名走：`/sdcard/Android/data/com.darkestdungeon.launcher/`、`/sdcard/Android/media/com.darkestdungeon.launcher/mods/`，
与原版游戏完全独立，第一次使用需要重新导入数据包和 mod。

## 构建

环境：JDK 17、Android SDK 36。

```bash
git clone https://github.com/charlotte114/DarkestLauncher.git
cd DarkestLauncher
./gradlew :app:assembleRelease
```

产物在 `app/build/outputs/apk/release/`。签名口令从根目录的 `keystore.properties` 读：

```properties
storeFile=keystore/ddlauncher.jks
storePassword=******
keyAlias=ddlauncher
keyPassword=******
```

`keystore.properties`、`keystore/` 都在 `.gitignore` 里，不会被提交；没有这些文件就只会出未签名的包。

## 目录

```
app/              启动器 UI 与业务（Compose + Material 3）
workshop-core/    Steam CM 协议、工坊查询与下载
steam-protocol/   登录与 CM 连接
```

## 致谢

- [Apricityx/WorkshopAndroidDownloader](https://github.com/Apricityx/WorkshopAndroidDownloader) —— 工坊协议与下载引擎
- [ModinMobileSTS/Sts2MobileLauncher](https://github.com/ModinMobileSTS/Sts2MobileLauncher) —— 存档快照与日志查看的思路（MIT）

## 免责

本项目仅供学习与自用，不提供、不分发游戏本体。请使用自己合法持有的游戏副本；
因使用本工具造成的任何损失由使用者自负。

## License

MIT
