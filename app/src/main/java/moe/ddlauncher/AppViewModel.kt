package moe.ddlauncher

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import java.io.File
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class AppViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val LAUNCHER_VERSION = "1.2"

        /** 搜索硬超时：网络卡住时 30 秒内必须有结果或报错，别一直转圈 */
        private const val SEARCH_TIMEOUT_MS = 30_000L
    }

    data class Ui(
        val loading: Boolean = true,
        val mods: List<ModInfo> = emptyList(),
        val conflicts: List<Conflict> = emptyList(),
        val busy: String? = null,
        val hasAccess: Boolean = false,
        val packInstalled: Boolean = false,
        val packSize: Long = 0L,
        val packProgress: String? = null,
        val versionName: String = "",
        /** 已导入数据包对应的游戏版本（2.2.1 / 2.2.2 …）；读不出来就是 null */
        val gameVersion: String? = null,
        /** 工坊更新检查：mod 目录名 → 工坊最新 time_updated */
        val updates: Map<String, Long> = emptyMap(),
        val updateBusy: Boolean = false,
        /** 更新 / 检查进行中的一句话，显示在列表顶部 */
        val updateStatus: String? = null,
        /** 存档快照列表（新的在前） */
        val snapshots: List<SaveRepo.Snapshot> = emptyList(),
        val saveBusy: Boolean = false,
        /** 导出存档的结果，显示在存档面板里 */
        val saveNote: String? = null,
        /** 看日志用的文本，非空就弹日志框 */
        val logText: String? = null,
        val logBusy: Boolean = false,
        /** 自定义分组：组列表 + 归属 + 组内顺序 + 下载目标组 */
        val groups: ModGroups.Snapshot = ModGroups.Snapshot(),
        /** mod 详情里改分组后，重新按组排好的段落（给 UI 直接用） */
        val sections: List<ModGroups.Section> = emptyList(),
    )

    private val repo = ModRepo()
    private val importer = ZipImport(repo)
    private val groupStore = ModGroups(app)

    init {
        // 加速通道需要 Context 拿开关和日志；失败就静默退回直连
        runCatching { WorkshopRepo.init(app) }
    }

    private val _state = MutableStateFlow(Ui())
    val state: StateFlow<Ui> = _state.asStateFlow()

    fun hasStorageAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        getApplication<Application>().checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 启动时的加载分两段，先出界面再补细节：
     *  1) 快：扫 mods 目录、读分组、读版本记录、读缓存的容量 —— 只碰几百个小文件，几十毫秒；
     *  2) 慢：容量兜底统计（遍历上万个文件）、版本补齐（可能要翻 Download 里的几 GB 包）——
     *     这些放到界面出来之后再跑，别挡着列表显示。
     * 以前这两段是一个事务，慢的那步会把 mod 列表和数据包卡片一起拖住，看起来就是「启动卡一下」。
     */
    fun refresh() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val access = hasStorageAccess()
            _state.update { it.copy(hasAccess = access, loading = true, versionName = versionName()) }
            if (!access) {
                _state.update { it.copy(loading = false, mods = emptyList(), conflicts = emptyList(), sections = emptyList()) }
                return@launch
            }

            val t0 = android.os.SystemClock.elapsedRealtime()
            val fast = withContext(Dispatchers.IO) {
                repo.ensureDir()
                val mods = repo.scan()
                val installed = DataPack.isInstalled(ctx)
                ScanResult(
                    mods = mods,
                    conflicts = emptyList(), // 后台 analyze 补
                    installed = installed,
                    // 导入时记下的数；没有就先用 0，界面显示「容量统计中」，后台补
                    size = if (installed) DataPack.cachedSize(ctx) else 0L,
                    version = if (installed) DataPack.version(ctx) else null,
                    groups = groupStore.also { it.invalidate() }.snapshot(),
                )
            }
            _state.update {
                it.copy(
                    loading = false,
                    mods = fast.mods,
                    conflicts = fast.conflicts,
                    packInstalled = fast.installed,
                    packSize = fast.size,
                    gameVersion = fast.version,
                    groups = fast.groups,
                    sections = fast.groups.arrange(fast.mods),
                )
            }
            DdLog.i(
                ctx, "perf",
                "首屏 ${android.os.SystemClock.elapsedRealtime() - t0}ms mods=${fast.mods.size} " +
                    "pack=${fast.installed}"
            )
            // 文件数、体积、跨 mod 冲突：要挨个 stat 几千个文件，放到界面出来之后再算。
            // 算完按 id 贴回当前列表，中途用户切了开关也不会错位。
            if (fast.mods.isNotEmpty()) {
                val analysis = withContext(Dispatchers.IO) {
                    runCatching { repo.analyze(fast.mods) }.getOrNull()
                }
                if (analysis != null) {
                    _state.update { ui ->
                        val patched = ui.mods.map { mod ->
                            val s = analysis.stats[mod.id] ?: return@map mod
                            mod.copy(fileCount = s.files, sizeBytes = s.bytes)
                        }
                        ui.copy(
                            mods = patched,
                            conflicts = analysis.conflicts,
                            sections = ui.groups.arrange(patched),
                        )
                    }
                    DdLog.i(
                        ctx, "perf",
                        "mod 统计 +${android.os.SystemClock.elapsedRealtime() - t0}ms " +
                            "conflicts=${analysis.conflicts.size}"
                    )
                }
            }

            if (!fast.installed) return@launch
            // 容量：有记录就不再扫；没有才统计一次，统计完顺手存下来
            if (fast.size <= 0) {
                val size = withContext(Dispatchers.IO) { DataPack.sizeOf(ctx) }
                if (size > 0) {
                    DataPack.storeSize(ctx, size)
                    _state.update { it.copy(packSize = size) }
                }
                DdLog.i(
                    ctx, "perf",
                    "容量统计 +${android.os.SystemClock.elapsedRealtime() - t0}ms size=$size"
                )
            }

            // 版本：只有在完全没有记录时才去 Download 里翻包，翻到了再补上
            if (fast.version == null) {
                val ver = withContext(Dispatchers.IO) {
                    runCatching { DataPack.recoverVersion(ctx) }.getOrNull()
                }
                if (ver != null) _state.update { it.copy(gameVersion = ver) }
                DdLog.i(
                    ctx, "perf",
                    "版本补齐 +${android.os.SystemClock.elapsedRealtime() - t0}ms ver=${ver ?: "无"}"
                )
            }
        }
    }

    private data class ScanResult(
        val mods: List<ModInfo>,
        val conflicts: List<Conflict>,
        val installed: Boolean,
        val size: Long,
        val version: String?,
        val groups: ModGroups.Snapshot,
    )

    /**
     * 这里只返回启动器自己的版本。
     * APK 的 versionName 固定是 2.2.1（和内置运行时对齐，避免第三方工具误判更新），
     * 但游戏版本要以「这次导入的数据包」为准，见 Ui.gameVersion。
     */
    private fun versionName(): String = "启动器 $LAUNCHER_VERSION"

    /** 一键开启 / 关闭全部 mod（改 project.xml 后缀，进游戏才生效） */
    fun setAllMods(enabled: Boolean) {
        val targets = _state.value.mods.filter { it.enabled != enabled }
        if (targets.isEmpty()) {
            note(if (enabled) "已经全部开启了" else "已经全部关闭了")
            return
        }
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            _state.update {
                it.copy(busy = "正在${if (enabled) "开启" else "关闭"} ${targets.size} 个 mod…")
            }
            val failed = withContext(Dispatchers.IO) {
                targets.count { m -> runCatching { repo.setEnabled(m, enabled) }.getOrDefault(false).not() }
            }
            _state.update { it.copy(busy = null) }
            DdLog.i(ctx, "mods", "setAll enabled=$enabled total=${targets.size} failed=$failed")
            refresh()
            note(
                if (failed == 0) "已${if (enabled) "开启" else "关闭"} ${targets.size} 个 mod（进游戏生效）"
                else "有 $failed 个没改成，可能目录被占用"
            )
        }
    }

    fun importDataPack(uri: Uri) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            _state.update { it.copy(packProgress = "准备中…") }
            val result = withContext(Dispatchers.IO) {
                DataPack.install(ctx, uri) { p ->
                    _state.update {
                        it.copy(packProgress = "${DataPack.gb(p.bytes)} · ${p.current.takeLast(36)}")
                    }
                }
            }
            _state.update { it.copy(packProgress = null) }
            note("数据包：${result.message}")
            refresh()
        }
    }

    /** 扫 Download 目录找数据包，找到就自动导入；找不到提示去下载 */
    fun checkAndImportDataPack() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            _state.update { it.copy(busy = "正在检查 Download 目录…") }
            val found = withContext(Dispatchers.IO) { DataPack.findInDownloads() }
            if (found == null) {
                _state.update { it.copy(busy = null) }
                note("数据包检查：Download 目录里没找到可导入的安装包，先点「下载数据包」")
                return@launch
            }
            note("数据包检查：找到 ${found.name}，开始导入")
            _state.update { it.copy(busy = null) }
            importDataPack(android.net.Uri.fromFile(found))
        }
    }

    fun removeDataPack() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            _state.update { it.copy(busy = "正在删除数据包…") }
            val ok = withContext(Dispatchers.IO) { DataPack.remove(ctx) }
            _state.update { it.copy(busy = null) }
            note(if (ok) "数据包已删除" else "删除未完成")
            refresh()
        }
    }

    fun toggle(info: ModInfo, enabled: Boolean) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { repo.setEnabled(info, enabled) }
            val name = info.displayName
            note(
                if (ok) {
                    if (enabled) "已启用 $name（进游戏生效）" else "已禁用 $name（进游戏生效）"
                } else "改名失败：目录可能被占用"
            )
            refresh()
        }
    }

    fun delete(info: ModInfo) {
        viewModelScope.launch {
            _state.update { it.copy(busy = "正在删除 ${info.displayName}") }
            val ok = withContext(Dispatchers.IO) { repo.delete(info) }
            _state.update { it.copy(busy = null) }
            note(if (ok) "已删除 ${info.displayName}" else "删除不完整，可能有文件被占用")
            refresh()
        }
    }

    // ---------------- 自定义分组 ----------------

    /** 分组只影响启动器里的列表，不动 mods 目录结构（游戏只认平铺的 mod 文件夹） */
    private fun syncGroups() {
        val snap = groupStore.snapshot()
        _state.update { it.copy(groups = snap, sections = snap.arrange(it.mods)) }
    }

    fun createGroup(name: String): Boolean {
        val group = groupStore.createGroup(name) ?: return false
        DdLog.i(getApplication(), "grp", "create ${group.name}")
        syncGroups()
        return true
    }

    fun renameGroup(id: String, name: String): Boolean {
        val ok = groupStore.renameGroup(id, name)
        if (ok) syncGroups()
        return ok
    }

    fun deleteGroup(id: String) {
        groupStore.deleteGroup(id)
        DdLog.i(getApplication(), "grp", "delete $id")
        syncGroups()
    }

    fun moveModsToGroup(ids: List<String>, groupId: String?) {
        if (ids.isEmpty()) return
        groupStore.assign(ids, groupId)
        DdLog.i(getApplication(), "grp", "move ${ids.size} -> ${groupId ?: "-"}")
        syncGroups()
    }

    /** 拖动排序落库：整组的顺序覆盖写 */
    fun reorderGroup(groupId: String?, ids: List<String>) {
        groupStore.setOrder(groupId, ids)
        syncGroups()
    }

    fun setDownloadGroup(groupId: String?) {
        groupStore.setDownloadTarget(groupId)
        syncGroups()
    }

    fun moveGroup(from: Int, to: Int) {
        groupStore.moveGroups(from, to)
        syncGroups()
    }

    /** 新下载/新装进来的 mod 归到「下载目标组」；本来就属于某个组的（覆盖安装）不动 */
    private fun groupNewInstall(dirName: String?) {
        if (dirName.isNullOrBlank()) return
        val snap = groupStore.snapshot()
        val target = snap.downloadTarget ?: return
        if (!snap.exists(target)) return
        if (snap.groupOf(dirName).isNotEmpty()) return
        groupStore.assign(listOf(dirName), target)
        DdLog.i(getApplication(), "grp", "install $dirName -> ${snap.nameOf(target)}")
    }

    fun importZips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val outcomes = ArrayList<ImportOutcome>()
            for ((index, uri) in uris.withIndex()) {
                _state.update { it.copy(busy = "正在导入 ${index + 1}/${uris.size}") }
                val outcome = withContext(Dispatchers.IO) {
                    importer.import(ctx, uri) { done, total ->
                        _state.update {
                            it.copy(busy = "正在导入 ${index + 1}/${uris.size} · $done/$total")
                        }
                    }
                }
                outcomes.add(outcome)
            }
            val ok = outcomes.count { it.ok }
            val fail = outcomes.filterNot { it.ok }
            val summary = buildString {
                append("导入完成：成功 $ok 个")
                if (fail.isNotEmpty()) {
                    append("，失败 ${fail.size} 个（")
                    append(fail.take(2).joinToString("；") { "${it.name}：${it.detail}" })
                    if (fail.size > 2) append(" 等")
                    append("）")
                }
                if (ok > 0) append("，进游戏时自动转换")
            }
            _state.update { it.copy(busy = null) }
            note(summary)
            refresh()
        }
    }

    /**
     * 界面上的 Toast / Snackbar 已全部去掉：这些原本要弹出来的话只写进日志，
     * 想看就去设置 → 查看日志，平时不打扰。
     */
    private fun note(message: String, tag: String = "ui") {
        runCatching { DdLog.i(getApplication(), tag, message) }
    }

    // ---------------- 存档快照 ----------------

    fun loadSnapshots() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { SaveRepo.list() }.getOrDefault(emptyList())
            }
            _state.update { it.copy(snapshots = list) }
        }
    }

    fun backupSaves() {
        if (_state.value.saveBusy) return
        viewModelScope.launch {
            _state.update { it.copy(saveBusy = true) }
            val r = withContext(Dispatchers.IO) { runCatching { SaveRepo.create("manual") } }
            _state.update { it.copy(saveBusy = false) }
            r.onSuccess { s ->
                DdLog.i(getApplication(), "save", "backup ok ${s.file.name} ${s.sizeBytes}")
                loadSnapshots()
                note("已备份存档：${s.file.name}")
            }.onFailure {
                DdLog.e(getApplication(), "save", it)
                note("备份失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    /** 从文件管理器挑来的 zip 导入存档（覆盖前自动备份，导入的包也存一份快照） */
    fun importSave(uri: Uri) {
        if (_state.value.saveBusy) return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            _state.update { it.copy(saveBusy = true) }
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(ctx.cacheDir, "import-save-${System.currentTimeMillis()}.zip")
                    try {
                        ctx.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { input.copyTo(it) }
                        } ?: throw IllegalStateException("读不到这个文件（可能已被移走，或没有读取权限）")
                        SaveRepo.importFrom(tmp)
                    } finally {
                        tmp.delete()
                    }
                }
            }
            _state.update { it.copy(saveBusy = false) }
            r.onSuccess { snap ->
                DdLog.i(ctx, "save", "import ok ${snap.file.name} ${snap.sizeBytes}")
                loadSnapshots()
                note("已导入存档，覆盖前的存档留成了「导入前自动备份」")
            }.onFailure {
                DdLog.e(ctx, "save", it)
                note("导入失败：${FailText.of(it)}")
            }
        }
    }

    fun restoreSave(id: String) {
        if (_state.value.saveBusy) return
        val snap = _state.value.snapshots.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            _state.update { it.copy(saveBusy = true) }
            val r = withContext(Dispatchers.IO) { runCatching { SaveRepo.restore(snap) } }
            _state.update { it.copy(saveBusy = false) }
            r.onSuccess {
                DdLog.i(getApplication(), "save", "restore ok ${snap.file.name}")
                loadSnapshots()
                note("已还原 ${snap.timeText} 的存档（旧存档另存了一份）")
            }.onFailure {
                DdLog.e(getApplication(), "save", it)
                note("还原失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    /** 导出某份存档：treeUri 为 null 时导出到 Download/DarkestSaves/ */
    fun exportSave(id: String, treeUri: Uri?) {
        val snap = _state.value.snapshots.firstOrNull { it.id == id } ?: return
        if (_state.value.saveBusy) return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            _state.update { it.copy(saveBusy = true, saveNote = null) }
            val r = withContext(Dispatchers.IO) { runCatching { SaveRepo.exportTo(ctx, snap, treeUri) } }
            _state.update {
                it.copy(
                    saveBusy = false,
                    saveNote = r.fold(
                        onSuccess = { path -> "已导出到 $path" },
                        onFailure = { e -> "导出失败：${e.message ?: e.javaClass.simpleName}" },
                    ),
                )
            }
            r.onSuccess { DdLog.i(ctx, "save", "export ok $id -> $it") }
                .onFailure { DdLog.e(ctx, "save", it) }
        }
    }

    fun clearSaveNote() = _state.update { it.copy(saveNote = null) }

    fun deleteSave(id: String) {
        val snap = _state.value.snapshots.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { SaveRepo.delete(snap) } }
            loadSnapshots()
            note("已删除这个备份")
        }
    }

    /**
     * 存档备份/还原自检：写测试文件 → 备份 → 篡改 → 还原 → 校验 → 清理。
     * 只由 adb 入口触发（`-e selftest save`），正常使用永远不会跑到。
     * 存档目录第三方 App 读不到，端到端验证只能让 App 自己来。
     */
    fun saveSelfTest() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val probe = java.io.File(Game.saveDir(), "profile_0/__selftest.txt")
                    probe.parentFile?.mkdirs()
                    probe.writeText("v1-original")
                    val snap = SaveRepo.create("manual")
                    probe.writeText("v2-modified")
                    SaveRepo.restore(snap)
                    val after = probe.readText()
                    probe.delete()
                    SaveRepo.delete(snap)
                    after
                }
            }
            result.onSuccess { text ->
                val ok = text == "v1-original"
                DdLog.i(ctx, "save", "selftest ${if (ok) "PASS" else "FAIL"} restored=[$text]")
                note(if (ok) "存档自检通过" else "存档自检失败：还原后是 $text")
            }.onFailure {
                DdLog.e(ctx, "save", it)
                note("存档自检失败：${it.message ?: it.javaClass.simpleName}")
            }
            loadSnapshots()
        }
    }

    // ---------------- 日志 ----------------

    /** 读启动器自己的落盘日志（工坊/下载/存档都记在这里） */
    fun loadLauncherLog() {
        viewModelScope.launch {
            _state.update { it.copy(logBusy = true, logText = "") }
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val f = java.io.File(DdLog.path(getApplication()))
                    if (!f.isFile) "（还没有日志，跑一次下载或启动游戏再来看）"
                    else f.readLines().takeLast(2000).joinToString("\n")
                }.getOrElse { "读日志失败：${it.message}" }
            }
            _state.update { it.copy(logBusy = false, logText = text) }
        }
    }

    /**
     * 读系统日志里本进程的输出（`nx: ...` 那批就是游戏启动的判定依据）。
     * Android 只允许读自己 uid 的日志，所以这里只能看到本 App + 游戏进程的输出，正好够用。
     */
    fun loadGameLog() {
        viewModelScope.launch {
            _state.update { it.copy(logBusy = true, logText = "") }
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val proc = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "4000")
                        .redirectErrorStream(true)
                        .start()
                    val raw = proc.inputStream.bufferedReader().readText()
                    proc.waitFor()
                    val lines = raw.lines().filter {
                        it.contains(" dd ") || it.contains("nx:") || it.contains("darkest") ||
                            it.contains("AndroidRuntime")
                    }
                    if (lines.isEmpty()) "（没抓到本 App 的日志）"
                    else lines.takeLast(1500).joinToString("\n")
                }.getOrElse { "读系统日志失败：${it.message}" }
            }
            _state.update { it.copy(logBusy = false, logText = text) }
        }
    }

    fun closeLog() = _state.update { it.copy(logText = null) }

    /** 清空落盘日志（连 .old 备份一起删），顺便把界面上的日志正文收掉 */
    fun clearLauncherLog() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val ctx = getApplication<Application>()
                DdLog.clear(ctx)
                runCatching { java.io.File(DdLog.path(ctx) + ".old").delete() }
            }
            _state.update { it.copy(logText = null) }
        }
    }

    private fun wsNote(message: String) = note(message, "ws")

    /**
     * 工坊自检：把三条链路各自跑一遍并计时，用来区分「是网络的问题还是代码的问题」。
     * 思路来自 Sts2MobileLauncher 的 `SteamWorkshopCatalog.runDiagnostics()`。
     * 这个网络下 Steam 的 HTTPS 时常间歇性黑洞，没有这个东西只能靠猜。
     */
    fun runWorkshopSelfCheck() {
        if (_ws.value.selfCheckBusy) return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            _ws.update { it.copy(selfCheckBusy = true, selfCheck = listOf("跑测试中…")) }
            val lines = mutableListOf<String>()
            fun add(s: String) {
                lines.add(s)
                _ws.update { it.copy(selfCheck = lines.toList()) }
            }

            // 1) Web API 元数据（可选，但要能连上 api.steampowered.com）
            val t1 = System.currentTimeMillis()
            runCatching { WorkshopRepo.details(840391233) }
                .onSuccess { add("① 元数据 API  OK ${System.currentTimeMillis() - t1}ms · ${it.title}") }
                .onFailure { add("① 元数据 API  失败 ${System.currentTimeMillis() - t1}ms · ${FailText.of(it)}") }

            // 2) CM 匿名握手（下载必须，走 wss:443）
            val t2 = System.currentTimeMillis()
            runCatching { WorkshopRepo.probeCm() }
                .onSuccess { add("② CM 握手  OK ${System.currentTimeMillis() - t2}ms · $it") }
                .onFailure { add("② CM 握手  失败 ${System.currentTimeMillis() - t2}ms · ${FailText.of(it)}") }

            // 3) 登录态 CM（订阅/带身份下载要用）
            val acc = SteamAuth.load(ctx)
            if (acc == null) {
                add("③ 登录态 CM  跳过（没登录 Steam）")
            } else {
                val t3 = System.currentTimeMillis()
                runCatching {
                    WorkshopRepo.mySubscriptionsViaCm(acc.toSession("DD Launcher"), page = 1, pageSize = 1)
                }.onSuccess {
                    add("③ 登录态 CM  OK ${System.currentTimeMillis() - t3}ms · 订阅 ${it.total} 条")
                }.onFailure {
                    add("③ 登录态 CM  失败 ${System.currentTimeMillis() - t3}ms · ${FailText.of(it)}")
                }
            }

            add("— 内置目录 ${WorkshopCatalog.items.size} 条（离线可用，不受网络影响）")
            add("— 并发抓块 ${chunkConcurrency()} · 登录 ${if (acc != null) acc.accountName else "未登录"}")
            lines.forEach { DdLog.i(ctx, "selfcheck", it) }
            _ws.update { it.copy(selfCheckBusy = false) }
        }
    }

    fun closeSelfCheck() = _ws.update { it.copy(selfCheck = null) }

    // ---------------- 工坊 ----------------

    data class WorkshopState(
        val query: String = "",
        /** 当前列表（已按 query/tag/sort 过滤好） */
        val results: List<WorkshopCatalog.Entry> = emptyList(),
        /** 外部合集的原始条目；非空时列表以它为准，而不是内置目录 */
        val collectionEntries: List<WorkshopCatalog.Entry> = emptyList(),
        val item: WorkshopRepo.Item? = null,
        val looking: Boolean = false,
        val downloading: Boolean = false,
        val status: String = "",
        val detail: String = "",
        val progress: Float? = null,
        val error: String? = null,
        val installed: Map<Long, Long> = emptyMap(),
        val targetId: Long? = null,
        // 浏览
        val sort: WorkshopCatalog.Sort = WorkshopCatalog.Sort.Popular,
        val timeWindow: WorkshopCatalog.TimeWindow = WorkshopCatalog.TimeWindow.All,
        val tag: String? = null,
        val collection: String? = null,
        val visible: Int = 36,
        val catalogTotal: Int = 0,
        val builtAt: Long = 0L,
        val collections: List<WorkshopCatalog.CollectionInfo> = emptyList(),
        val topTags: List<Pair<String, Int>> = emptyList(),
        val loadingList: Boolean = false,
        val apiKey: String = "",
        val onlinePage: Int = 0,
        /** 联网搜索命中的总数（用来判断还能不能翻页） */
        val onlineTotal: Int = 0,
        /** 当前列表是不是联网搜索结果 */
        val onlineSource: Boolean = false,
        val onlineLoadingMore: Boolean = false,
        val showSettings: Boolean = false,
        // Steam 账号
        val loggedIn: Boolean = false,
        val accountName: String = "",
        val steamId: Long = 0L,
        val subsLoading: Boolean = false,
        val subscriptions: List<WorkshopCatalog.Entry> = emptyList(),
        val subsTotal: Int = 0,
        val showSubs: Boolean = false,
        val subscribing: Set<Long> = emptySet(),
        /** 并发抓块的条数，工坊设置里可调 */
        val chunkConcurrency: Int = 4,
        /** 工坊自检的逐行结果 */
        val selfCheck: List<String>? = null,
        val selfCheckBusy: Boolean = false,
    ) {
        val total: Int get() = results.size
        val shown: List<WorkshopCatalog.Entry> get() = results.take(visible)
    }

    private val _ws = MutableStateFlow(WorkshopState())
    val ws: StateFlow<WorkshopState> = _ws.asStateFlow()

    private val prefs get() = getApplication<Application>().getSharedPreferences("workshop", 0)
    private val settings get() = getApplication<Application>().getSharedPreferences("settings", 0)

    fun loadWorkshopInstalled() {
        val map = prefs.all.mapNotNull { (k, v) ->
            val id = k.toLongOrNull() ?: return@mapNotNull null
            id to (v as? Long ?: 0L)
        }.toMap()
        _ws.update { it.copy(installed = map) }
    }

    /**
     * 从工坊装过的 mod：mod 目录名 → (workshop id, 安装时的 time_updated)。
     * 只有走过工坊下载的 mod 才有这份记录，手动拖进来的 zip 没有，也就没法检查更新。
     */
    private fun installedWorkshopMap(): Map<String, Pair<Long, Long>> {
        val out = LinkedHashMap<String, Pair<Long, Long>>()
        val all = prefs.all
        all.forEach { (k, v) ->
            if (!k.startsWith("dir_")) return@forEach
            val id = k.removePrefix("dir_").toLongOrNull() ?: return@forEach
            val name = v as? String ?: return@forEach
            val installedAt = (all[id.toString()] as? Long) ?: 0L
            out[name] = id to installedAt
        }
        return out
    }

    /** 主界面「检查工坊更新」：批量问一次工坊的 time_updated，比本地记录新就算有更新 */
    fun checkModUpdates() {
        val map = installedWorkshopMap()
        if (map.isEmpty()) {
            note("没有从工坊安装的 mod，没法检查更新")
            return
        }
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            _state.update { it.copy(updateBusy = true, updateStatus = "检查 ${map.size} 个 mod 的更新…") }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val byId = map.values.map { it.first }.distinct()
                        .chunked(50)
                        .flatMap { chunk -> retryIo { WorkshopRepo.detailsBatch(chunk) } }
                        .associateBy { it.id }
                    map.mapNotNull { (name, v) ->
                        val remote = byId[v.first]?.updated ?: return@mapNotNull null
                        if (remote > v.second) name to remote else null
                    }.toMap()
                }
            }
            _state.update { it.copy(updateBusy = false, updateStatus = null) }
            result.onSuccess { updates ->
                DdLog.i(ctx, "upd", "checked=${map.size} updates=${updates.size}")
                _state.update { it.copy(updates = updates) }
                note(if (updates.isEmpty()) "检查完了，${map.size} 个 mod 都是最新的" else "有 ${updates.size} 个 mod 可以更新")
            }.onFailure { e ->
                DdLog.e(ctx, "upd", e)
                note("检查更新失败：${FailText.of(e)}")
            }
        }
    }

    /** 主界面单个 mod「更新」：走和工坊页一样的下载，装回原来的目录名 */
    fun updateMod(dirName: String) {
        val entry = installedWorkshopMap()[dirName] ?: run {
            note("找不到 $dirName 对应的工坊条目")
            return
        }
        installFromWorkshop(entry.first, dirName)
    }

    /**
     * 按工坊 ID 下载并装进 mods —— 工坊更新、批量更新、以及 adb 调试入口都走这一条。
     * [preferDir] 传了就覆盖那个目录，不然按 prefs 里记的原目录覆盖，都没有就新建。
     */
    fun installFromWorkshop(id: Long, preferDir: String? = null) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            DdLog.i(ctx, "upd", "installFromWorkshop id=$id prefer=$preferDir")
            // 优先级：显式指定 > prefs 记的目录名 > 扫 project.xml 里的 PublishedFileId
            // 最后这条是为了 0.4.0 装过的老 mod —— 那时候还没记 dir_<id>，
            // 不兜底就会在前面加一份「标题 (2)」出来。
            val dirName = preferDir
                ?: prefs.getString("dir_$id", null)
                ?: withContext(Dispatchers.IO) {
                    runCatching {
                        repo.scan()
                            .filter { it.workshopId == id.toString() }
                            .minByOrNull { if (it.dir.name.endsWith(")")) 1 else 0 }
                            ?.dir?.name
                    }.getOrNull()
                }
            val label = dirName ?: "工坊 $id"
            _state.update { it.copy(updateBusy = true, updateStatus = "准备 $label") }
            val result = runCatching {
                performInstall(ctx, id, dirName) { text ->
                    _state.update { it.copy(updateStatus = "$label $text") }
                }
            }
            _state.update { it.copy(updateBusy = false, updateStatus = null) }
            result.onSuccess { res ->
                DdLog.i(ctx, "upd", "install id=$id dir=$dirName ok=${res.ok} msg=${res.message}")
                if (res.ok && dirName != null) _state.update { it.copy(updates = it.updates - dirName) }
                note(if (res.ok) res.message else "安装失败：${res.message}")
                refresh()
            }.onFailure { e ->
                DdLog.e(ctx, "upd", e)
                note("安装失败：${FailText.of(e)}")
            }
        }
    }

    /**
     * Steam 的 HTTPS 在这网络下是间歇性黑洞（TCP 能连、TLS 握手超时），
     * 单次请求经常 timeout，隔几百毫秒再试基本就过。只对「查元数据」这种无害请求用。
     */
    private suspend fun <T> retryIo(times: Int = 4, block: suspend () -> T): T {
        var last: Throwable? = null
        repeat(times) { i ->
            runCatching { block() }.onSuccess { return it }.onFailure {
                last = it
                DdLog.i(getApplication(), "retry", "attempt ${i + 1}/$times failed: ${it.message}")
            }
            if (i < times - 1) kotlinx.coroutines.delay(600L + i * 500L)
        }
        throw last ?: IllegalStateException("retry failed")
    }

    /** 真正的下载 + 安装，返回结果；不让异常穿透，交给调用方 runCatching */
    private suspend fun performInstall(
        ctx: Application,
        id: Long,
        preferDir: String?,
        onStatus: (String) -> Unit = {},
    ): WorkshopRepo.InstallResult = withContext(Dispatchers.IO) {
        val item = retryIo { WorkshopRepo.details(id) }
        DdLog.i(ctx, "upd", "resolved id=$id title=${item.title} size=${item.sizeBytes} updated=${item.updated}")
        // 只在确实登录着的时候才用账号态；否则走匿名，才能命中公开 CDN 回退
        val account = SteamAuth.load(ctx)?.takeIf { _ws.value.loggedIn }?.toSession("DD Launcher")
        val outDir = java.io.File(ctx.cacheDir, "workshop/$id")
        // 保留 .chunks 缓存以便续传/复用，只有装成功后才清
        outDir.mkdirs()
        WorkshopRepo.download(
            item, outDir, account, chunkConcurrency(), SteamIdentity.of(ctx),
        ).collect { ev ->
            when (ev) {
                is top.apricityx.workshop.workshop.DownloadEvent.Progress -> {
                    val ratio = ev.totalBytes?.takeIf { it > 0 }?.let {
                        (ev.writtenBytes.toFloat() / it).coerceIn(0f, 1f)
                    }
                    onStatus(
                        DataPack.gb(ev.writtenBytes) + (ratio?.let { r -> " ${(r * 100).toInt()}%" } ?: ""),
                    )
                }
                is top.apricityx.workshop.workshop.DownloadEvent.LogAppended ->
                    DdLog.i(ctx, "upd", ev.line)
                is top.apricityx.workshop.workshop.DownloadEvent.Failed ->
                    throw IllegalStateException(ev.message)
                else -> Unit
            }
        }
        val res = WorkshopRepo.install(ctx, repo, item, outDir, preferDir)
        if (res.ok) outDir.deleteRecursively()
        if (res.ok) {
            prefs.edit()
                .putLong(item.id.toString(), item.updated)
                .putString("dir_${item.id}", res.dirName ?: preferDir)
                .apply()
        }
        res
    }

    /** 一次更新全部可更新的 mod，串行跑，别把带宽和 CM 连接挤爆 */
    fun updateAllMods() {
        val names = _state.value.updates.keys.toList()
        if (names.isEmpty()) return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            var ok = 0
            var fail = 0
            names.forEachIndexed { idx, name ->
                _state.update { it.copy(updateBusy = true, updateStatus = "(${idx + 1}/${names.size}) 更新 $name") }
                val id = installedWorkshopMap()[name]?.first
                val r = if (id == null) Result.failure(IllegalStateException("找不到 $name 的工坊 ID"))
                else runCatching { performInstall(ctx, id, name) }
                if (r.getOrNull()?.ok == true) {
                    ok++
                    _state.update { it.copy(updates = it.updates - name) }
                } else {
                    fail++
                    DdLog.i(ctx, "upd", "batch $name failed: ${r.exceptionOrNull()?.message ?: r.getOrNull()?.message}")
                }
            }
            _state.update { it.copy(updateBusy = false, updateStatus = null) }
            note("批量更新完成：成功 $ok 个，失败 $fail 个")
            refresh()
        }
    }

    /** 进工坊页时调用：解析内置目录（一次），把 key 一起读出来 */
    fun wsInit() {
        // 只用创意工坊的在线内容，内置那 670 条目录不再参与列表
        _ws.update {
            it.copy(
                apiKey = settings.getString("steam_api_key", "").orEmpty(),
                chunkConcurrency = chunkConcurrency(),
            )
        }
        if (_ws.value.results.isEmpty() && !_ws.value.loadingList) wsReload()
    }

    /** 重新拉第一页（切排序、切时间窗、或者列表空着的时候用） */
    fun wsReload() {
        _ws.update { it.copy(onlinePage = 0) }
        wsSearchOnline(append = false)
    }

    fun wsSetTimeWindow(w: WorkshopCatalog.TimeWindow) {
        _ws.update { it.copy(timeWindow = w) }
        wsReload()
    }

    fun wsLeave() {
        // 页面关掉就把目录放掉，别一直占着
        WorkshopCatalog.release()
        _ws.update {
            it.copy(
                results = emptyList(), catalogTotal = 0, collectionEntries = emptyList(),
                collections = emptyList(), topTags = emptyList(),
            )
        }
    }

    private fun recompute(keepVisible: Boolean = false) {
        val s = _ws.value
        val list = WorkshopCatalog.search(
            query = s.query,
            tag = null,
            collection = null,
            sort = s.sort,
            source = s.collectionEntries,
        )
        _ws.update {
            it.copy(results = list, visible = if (keepVisible) maxOf(it.visible, 36) else 36)
        }
    }

    fun wsSetQuery(text: String) {
        _ws.update { it.copy(query = text, error = null) }
        recompute()
    }

    fun wsSetSort(sort: WorkshopCatalog.Sort) {
        _ws.update { it.copy(sort = sort) }
        wsReload()
    }

    fun wsSetTag(tag: String?) {
        _ws.update { it.copy(tag = if (it.tag == tag) null else tag) }
        recompute()
    }

    fun wsSetCollection(id: String?) {
        _ws.update { it.copy(collection = if (it.collection == id) null else id) }
        recompute()
    }

    fun wsLoadMore() {
        val s = _ws.value
        // 联网结果：本地只有已拉到的那几页，滚到底就该去问下一页，而不是把 visible 加大
        if (s.onlineSource) {
            val loaded = s.results.size
            if (s.visible >= loaded && loaded < s.onlineTotal && !s.loadingList && !s.onlineLoadingMore) {
                _ws.update { it.copy(onlineLoadingMore = true) }
                wsSearchOnline(append = true)
            }
            _ws.update { it.copy(visible = it.visible + 36) }
            return
        }
        _ws.update { it.copy(visible = it.visible + 36) }
    }

    /** 清掉筛选重新拉第一页 */
    fun wsResetSource() {
        _ws.update {
            it.copy(collection = null, tag = null, query = "", onlinePage = 0)
        }
        wsReload()
    }

    fun wsShowSettings(show: Boolean) {
        _ws.update { it.copy(showSettings = show) }
    }

    fun wsSaveKey(key: String) {
        settings.edit().putString("steam_api_key", key.trim()).apply()
        _ws.update { it.copy(apiKey = key.trim(), showSettings = false) }
        wsNote(if (key.isBlank()) "已清空 API Key，改用内置目录搜索" else "已保存，可联网搜索整个工坊")
    }

    /** 打开条目详情：先用本地元数据秒开，再后台补完整简介 */
    fun wsOpen(entry: WorkshopCatalog.Entry) {
        _ws.update {
            it.copy(item = WorkshopRepo.itemOf(entry), error = null, targetId = entry.id, looking = false)
        }
        viewModelScope.launch {
            val full = runCatching { WorkshopRepo.details(entry.id) }.getOrNull() ?: return@launch
            if (_ws.value.targetId == entry.id) _ws.update { it.copy(item = full) }
        }
    }

    /**
     * 粘贴链接 / ID 打开。
     * 先当"合集"试一次（合集能一次带出几十上百个 mod），不是合集再当单条查。
     */
    fun wsLookup() {
        val id = WorkshopRepo.parseId(_ws.value.query)
        if (id == null) {
            _ws.update { it.copy(error = "认不出工坊 ID，贴链接或纯数字都行", item = null) }
            return
        }
        viewModelScope.launch {
            _ws.update {
                it.copy(
                    looking = true, error = null, item = null, targetId = id,
                    collectionEntries = emptyList(), collection = null, tag = null, onlinePage = 0,
                )
            }
            val children = runCatching { WorkshopRepo.collection(id) }.getOrNull()
            if (!children.isNullOrEmpty()) {
                _ws.update { it.copy(status = "展开合集：${children.size} 个条目") }
                val collected = ArrayList<WorkshopRepo.Item>(children.size)
                children.chunked(100).forEachIndexed { i, batch ->
                    _ws.update { it.copy(status = "读取元数据 ${collected.size}/${children.size}") }
                    runCatching { WorkshopRepo.detailsBatch(batch) }.getOrNull()?.let { collected += it }
                }
                val entries = collected.map { it.toEntry(collectionId = id.toString()) }
                if (entries.isEmpty()) {
                    _ws.update { it.copy(looking = false, status = "", error = "合集里没有可用的条目") }
                    return@launch
                }
                _ws.update {
                    it.copy(
                        looking = false, status = "",
                        collectionEntries = entries,
                        collection = null, tag = null, query = "",
                        onlineSource = false, onlineTotal = 0, onlinePage = 0,
                    )
                }
                recompute()
                wsNote("已展开合集：${entries.size} 个 mod")
                return@launch
            }

            val result = runCatching { WorkshopRepo.details(id) }
            _ws.update {
                it.copy(
                    looking = false,
                    item = result.getOrNull(),
                    error = result.exceptionOrNull()?.let { e -> e.message ?: e.javaClass.simpleName },
                )
            }
        }
    }

    /**
     * 搜索整个工坊：公开条目匿名可搜（CM / 网页兜底），登录 / API Key 只是更快的备用链路。
     *
     * 排序策略照搬上游 `searchCurrentGameWorkshop`：
     * **有搜索词时一律按「文本相关度」搜、且不限时间窗**。
     * 否则「本周 + 订阅最多」这类过滤会叠在关键词上，把结果压到几乎没有，
     * 表现出来就是「搜不到东西」。UI 上用户选的排序/时间窗保持不变，只影响浏览。
     */
    fun wsSearchOnline(append: Boolean = false) {
        val key = _ws.value.apiKey.trim()
        val account = SteamAuth.load(getApplication())
        val text = _ws.value.query.trim()
        val searching = text.isNotBlank()
        // 搜索就用 Popular 的文本相关度 query_type（12），时间窗不参与
        val reqSort = if (searching) WorkshopCatalog.Sort.Popular else _ws.value.sort
        val reqDays = if (searching) 0 else if (reqSort.supportsTimeWindow) _ws.value.timeWindow.days else 0
        val page = if (append) _ws.value.onlinePage + 1 else 1
        val ctx = getApplication<Application>()
        DdLog.i(
            ctx, "search",
            "start text=[$text] sort=${reqSort.label}${if (reqSort != _ws.value.sort) "(UI:${_ws.value.sort.label})" else ""} " +
                "days=$reqDays page=$page loggedIn=${account != null} hasKey=${key.isNotEmpty()}",
        )
        viewModelScope.launch {
            // 刷新（非翻页）时不清空旧列表，避免搜一下先闪成空白
            _ws.update { it.copy(loadingList = true, error = null, onlinePage = page) }
            val res = runCatching {
                // 30 秒硬超时：网络卡住时别让用户一直看着转圈
                withTimeout(SEARCH_TIMEOUT_MS) {
                    when {
                        // 登录态走 CM：实测 access_token 不被 IPublishedFileService 接受，别浪费一次请求
                        account != null ->
                            // CM 偶尔掉线，搜一次自动重连一次
                            retryIo(times = 2) {
                                WorkshopRepo.searchViaCm(
                                    account.toSession("DD Launcher"), text, page, reqSort,
                                    days = reqDays,
                                )
                            }
                        key.isNotEmpty() -> WorkshopRepo.queryFiles(key, text, page, reqSort)
                            .let { WorkshopRepo.SubscriptionPage(it, it.size) }
                        // 匿名：先用 CM（下载 mod 本来就在用的节点，不需要网页）；CM 不行再退回爬网页
                        else -> runCatching {
                            retryIo(times = 2) {
                                WorkshopRepo.searchAnonymousViaCm(text, page, reqSort, days = reqDays)
                            }
                        }.recoverCatching { cmErr ->
                            DdLog.i(ctx, "search", "匿名 CM 失败，退回网页抓取：${cmErr.message}")
                            WorkshopBrowseRepo.search(text, page, reqSort, days = reqDays)
                                .let { WorkshopRepo.SubscriptionPage(it.items, it.total) }
                        }.getOrThrow()
                    }
                }
            }
            res.onSuccess { result ->
                DdLog.i(ctx, "search", "ok items=${result.items.size} total=${result.total}")
                val entries = result.items.map { it.toEntry() }
                val merged = if (append) _ws.value.collectionEntries + entries else entries
                _ws.update {
                    it.copy(
                        loadingList = false,
                        onlineLoadingMore = false,
                        collectionEntries = merged.distinctBy { e -> e.id }.toMutableList(),
                        query = it.query,
                        onlinePage = page,
                        onlineTotal = result.total,
                        onlineSource = true,
                    )
                }
                recompute(keepVisible = append)
                if (entries.isEmpty() && !append) {
                    _ws.update { it.copy(error = "没有匹配的 mod") }
                }
            }.onFailure { e ->
                DdLog.e(getApplication(), "search", e)
                val networkish = generateSequence(e) { it.cause }.any {
                    it is java.io.IOException ||
                        it is kotlinx.coroutines.TimeoutCancellationException ||
                        it.javaClass.simpleName.contains("Timeout", ignoreCase = true)
                }
                _ws.update {
                    it.copy(
                        loadingList = false,
                        onlineLoadingMore = false,
                        error = if (networkish)
                            "连不上创意工坊：网络环境可能无法直连 Steam，开加速器 / 科学上网后重试，或登录 Steam 账号"
                        else "联网搜索失败：${FailText.of(e)}",
                    )
                }
            }
        }
    }

    /** adb 调试：直接跑一次联网搜索并把结果写进日志 */
    fun debugSearch(text: String, queryType: Int? = null) {
        val ctx = getApplication<Application>()
        _ws.update { it.copy(query = text) }
        viewModelScope.launch {
            val account = SteamAuth.load(ctx)
            val key = _ws.value.apiKey.trim()
            DdLog.i(ctx, "search", "debug query=[$text] loggedIn=${account != null} hasKey=${key.isNotEmpty()}")
            val started = System.currentTimeMillis()
            runCatching {
                when {
                    account != null -> retryIo(times = 2) {
                        WorkshopRepo.searchViaCm(
                            account.toSession("DD Launcher"), text, 1, _ws.value.sort,
                            queryTypeOverride = queryType,
                        )
                    }
                    key.isNotEmpty() -> WorkshopRepo.queryFiles(key, text, 1, _ws.value.sort)
                        .let { WorkshopRepo.SubscriptionPage(it, it.size) }
                    else -> runCatching {
                        retryIo(times = 2) {
                            WorkshopRepo.searchAnonymousViaCm(text, 1, _ws.value.sort, queryTypeOverride = queryType)
                        }
                    }.recoverCatching {
                        WorkshopBrowseRepo.search(text, 1, _ws.value.sort, days = 0)
                            .let { WorkshopRepo.SubscriptionPage(it.items, it.total) }
                    }.getOrThrow()
                }
            }.onSuccess { page ->
                DdLog.i(ctx, "search", "OK ${System.currentTimeMillis() - started}ms items=${page.items.size} total=${page.total}")
                page.items.take(5).forEach { DdLog.i(ctx, "search", "  ${it.id} ${it.title}") }
                // 顺便验证翻页：再拉一页，看有没有重复/是不是真新内容
                if (account != null && page.items.size >= 10) {
                    runCatching {
                        retryIo(times = 2) {
                            WorkshopRepo.searchViaCm(account.toSession("DD Launcher"), text, 2, _ws.value.sort)
                        }
                    }.onSuccess { p2 ->
                        val overlap = p2.items.map { it.id }.intersect(page.items.map { it.id }.toSet()).size
                        DdLog.i(
                            ctx, "search",
                            "page2 items=${p2.items.size} total=${p2.total} 与第一页重复=$overlap 首条=${p2.items.firstOrNull()?.title}",
                        )
                    }.onFailure { DdLog.e(ctx, "search", it) }
                }
            }.onFailure {
                DdLog.e(ctx, "search", it)
            }
        }
    }

    fun wsCloseDetail() {
        _ws.update { it.copy(item = null, targetId = null) }
    }

    private fun WorkshopRepo.Item.toEntry(collectionId: String? = null) = WorkshopCatalog.Entry(
        id = id,
        title = title,
        author = creator,
        summary = description.take(160),
        subs = subscriptions.toInt(),
        updated = updated,
        tags = tags,
        size = sizeBytes,
        preview = previewUrl,
        collections = listOfNotNull(collectionId),
    )

    // ---------------- Steam 登录 ----------------

    enum class LoginStage { Credentials, Guard, Waiting }

    data class LoginState(
        val open: Boolean = false,
        val busy: Boolean = false,
        val stage: LoginStage = LoginStage.Credentials,
        val hint: String = "",
        val error: String? = null,
    )

    private val _login = MutableStateFlow(LoginState())
    val login: StateFlow<LoginState> = _login.asStateFlow()

    private var pendingLogin: SteamAuth.Pending? = null

    /** 进工坊页时恢复登录态 */
    /** 打开设置页时轻量同步一次（只读本地，不联网、不拉列表） */
    fun settingsInit() {
        steamInit()
        _ws.update {
            it.copy(
                chunkConcurrency = chunkConcurrency(),
                apiKey = settings.getString("steam_api_key", "").orEmpty(),
            )
        }
    }

    /** 清掉 cacheDir/workshop 里解压出来的临时文件；已经装进 mods 的不动 */
    fun clearWorkshopCache() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val freed = withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "workshop")
                val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                dir.deleteRecursively()
                size
            }
            DdLog.i(ctx, "cache", "cleared $freed")
            note(
                if (freed > 0) "已清理 ${repo.humanSize(freed)} 下载缓存"
                else "没有可清理的下载缓存"
            )
        }
    }

    fun steamInit() {
        val acc = SteamAuth.load(getApplication())
        _ws.update {
            it.copy(
                loggedIn = acc != null,
                accountName = acc?.accountName.orEmpty(),
                steamId = acc?.steamId ?: 0L,
            )
        }
    }

    fun steamShowLogin(open: Boolean) {
        if (!open) {
            pendingLogin?.close()
            pendingLogin = null
        }
        _login.update {
            if (open) it.copy(open = true, busy = false, stage = LoginStage.Credentials, error = null, hint = "")
            else it.copy(open = false, busy = false, error = null, hint = "")
        }
    }

    private suspend fun accessTokenFrom(acc: SteamAuth.Account): String {
        val ctx = getApplication<Application>()
        SteamAuth.cachedAccessToken(ctx)?.let { return it }
        val tokens = withContext(Dispatchers.IO) {
            SteamAuth.client(WorkshopRepo.http)
                .generateAccessTokenForApp(acc.toSession("DD Launcher"), allowRenewal = true)
        }
        tokens.refreshToken?.let { SteamAuth.save(ctx, acc.copy(refreshToken = it)) }
        SteamAuth.putAccessToken(ctx, tokens.accessToken)
        return tokens.accessToken
    }

    private suspend fun ensureAccessToken(): String {
        val acc = SteamAuth.load(getApplication()) ?: throw IllegalStateException("还没有登录 Steam")
        return accessTokenFrom(acc)
    }

    fun steamLoginStart(username: String, password: String) {
        if (username.isBlank() || password.isEmpty()) {
            _login.update { it.copy(error = "账号和密码都要填") }
            return
        }
        _login.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val pending = withTimeout(120_000L) {
                    withContext(Dispatchers.IO) {
                        SteamAuth.Pending(
                            SteamAuth.client(WorkshopRepo.http).beginAuthSession(
                                details = top.apricityx.workshop.steam.protocol.SteamAuthSessionDetails(
                                    username = username.trim(),
                                    password = password,
                                    deviceFriendlyName = "DD Launcher",
                                ),
                                debugLogger = { line ->
                                    android.util.Log.d("SteamLogin", line)
                                },
                            )
                        )
                    }
                }
                pendingLogin = pending
                if (pending.needsCode) {
                    _login.update {
                        it.copy(
                            busy = false,
                            stage = LoginStage.Guard,
                            hint = when (pending.preferredType) {
                                top.apricityx.workshop.steam.protocol.SteamGuardChallengeType.DeviceCode ->
                                    "输入 Steam 手机令牌上的验证码"
                                else -> "输入发送到邮箱的验证码"
                            },
                        )
                    }
                } else {
                    _login.update {
                        it.copy(busy = true, stage = LoginStage.Waiting, hint = "请在手机上打开 Steam App 确认登录")
                    }
                    finishLogin(pending)
                }
            } catch (e: Throwable) {
                pendingLogin?.close()
                pendingLogin = null
                _login.update {
                    it.copy(busy = false, error = "登录失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    fun steamLoginGuard(code: String) {
        val pending = pendingLogin ?: run {
            _login.update { it.copy(error = "登录会话已过期，请重新输入密码") }
            return
        }
        if (code.isBlank()) {
            _login.update { it.copy(error = "验证码不能为空") }
            return
        }
        _login.update { it.copy(busy = true, error = null, stage = LoginStage.Waiting, hint = "正在验证…") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    pending.session.submitGuardCode(pending.preferredType, code.trim())
                }
                finishLogin(pending)
            } catch (e: Throwable) {
                _login.update {
                    it.copy(
                        busy = false,
                        stage = LoginStage.Guard,
                        error = "验证码不对或已过期：${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    private suspend fun finishLogin(pending: SteamAuth.Pending) {
        try {
            val result = withTimeout(180_000L) {
                withContext(Dispatchers.IO) { pending.session.awaitResult() }
            }
            val ctx = getApplication<Application>()
            SteamAuth.save(
                ctx,
                SteamAuth.Account(
                    accountName = result.accountName.ifBlank { "steam" },
                    steamId = result.steamId,
                    refreshToken = result.refreshToken,
                    displayName = result.accountName,
                ),
            )
            if (result.accessToken.isNotBlank()) SteamAuth.putAccessToken(ctx, result.accessToken)
            _ws.update {
                it.copy(
                    loggedIn = true,
                    accountName = result.accountName,
                    steamId = result.steamId,
                    showSubs = false,
                )
            }
            _login.update { it.copy(open = false, busy = false, error = null) }
            pendingLogin?.close()
            pendingLogin = null
            wsNote("已登录 ${result.accountName}")
            loadSubscriptions()
        } catch (e: Throwable) {
            _login.update {
                it.copy(busy = false, error = "等待确认超时或被拒绝：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun steamLogout() {
        SteamAuth.logout(getApplication())
        pendingLogin?.close()
        pendingLogin = null
        _ws.update {
            it.copy(
                loggedIn = false, accountName = "", steamId = 0L,
                subscriptions = emptyList(), subsTotal = 0, showSubs = false,
            )
        }
        wsNote("已退出 Steam 账号（本地 token 已删除；要彻底撤销请到 Steam 设置里移除已授权设备）")
    }

    /** 拉我订阅的 mod */
    fun loadSubscriptions() {
        val acc = SteamAuth.load(getApplication())
        if (acc == null) {
            _ws.update { it.copy(error = "先登录 Steam") }
            return
        }
        viewModelScope.launch {
            _ws.update { it.copy(subsLoading = true, error = null) }
            // CM 连接时好时坏（掉线常见报 Socket closed / timeout），失败一次自动重连，
            // 别让用户对着「读取订阅失败」自己点第二遍
            val result = runCatching {
                runCatching {
                    WorkshopRepo.mySubscriptionsViaCm(acc.toSession("DD Launcher"), page = 1, pageSize = 200)
                }.recoverCatching { first ->
                    DdLog.e(getApplication(), "subs", first)
                    kotlinx.coroutines.delay(800)
                    WorkshopRepo.mySubscriptionsViaCm(acc.toSession("DD Launcher"), page = 1, pageSize = 200)
                }.getOrThrow()
            }
            result.onSuccess { page ->
                val entries = page.items.map { it.toEntry() }
                _ws.update {
                    it.copy(
                        subsLoading = false,
                        subscriptions = entries,
                        subsTotal = if (page.total > 0) page.total else entries.size,
                        showSubs = true,
                        // 订阅列表当成数据源，不然列表还停在 670 条内置目录上
                        collectionEntries = entries,
                        onlineSource = false, onlineTotal = 0, onlinePage = 0,
                        query = "",
                        tag = null,
                        collection = null,
                    )
                }
                recompute()
                if (entries.isEmpty()) _ws.update { it.copy(error = "这个账号在 Darkest Dungeon 工坊没有订阅") }
            }.onFailure { e ->
                DdLog.e(getApplication(), "subs", e)
                _ws.update {
                    it.copy(subsLoading = false, error = "读取订阅失败：${FailText.of(e)}")
                }
            }
        }
    }

    fun wsToggleSubs() {
        val s = _ws.value
        if (!s.loggedIn) {
            steamShowLogin(true)
            return
        }
        if (s.showSubs) {
            _ws.update { it.copy(showSubs = false, collectionEntries = emptyList()) }
            recompute()
        } else if (s.subscriptions.isNotEmpty()) {
            // 已经拉过一次就直接切显示，别每次都重连 CM（很慢，会 timeout）
            _ws.update {
                it.copy(showSubs = true, collectionEntries = s.subscriptions, query = "", tag = null, collection = null, error = null)
            }
            recompute()
        } else {
            loadSubscriptions()
        }
    }

    /** 真的向 Steam 订阅 / 取消订阅 */
    fun toggleSubscribed(id: Long, title: String, subscribe: Boolean) {
        if (!_ws.value.loggedIn) {
            steamShowLogin(true)
            return
        }
        viewModelScope.launch {
            _ws.update { it.copy(subscribing = it.subscribing + id) }
            val r = runCatching {
                val acc = SteamAuth.load(getApplication())
                    ?: throw IllegalStateException("登录状态丢失，请重新登录")
                WorkshopRepo.setSubscribedViaCm(acc.toSession("DD Launcher"), id, subscribe)
            }
            _ws.update { it.copy(subscribing = it.subscribing - id) }
            r.onSuccess {
                DdLog.i(getApplication(), "subs", "setSubscribed id=$id subscribe=$subscribe ok")
                wsNote(if (subscribe) "已订阅「${title.take(20)}」" else "已取消订阅")
                if (_ws.value.showSubs) loadSubscriptions()
            }.onFailure {
                DdLog.e(getApplication(), "subs", it)
                _ws.update { st ->
                    st.copy(error = "订阅失败：${FailText.of(it)}")
                }
            }
        }
    }

    /** 正在跑的工坊下载任务，用来支持「取消」 */
    private var wsDownloadJob: kotlinx.coroutines.Job? = null

    fun wsCancelDownload() {
        wsDownloadJob?.cancel()
        wsDownloadJob = null
        _ws.update {
            it.copy(downloading = false, status = "", progress = null, detail = "", error = "已取消下载")
        }
    }

    private fun chunkConcurrency(): Int =
        settings.getInt("chunk_concurrency", 4).coerceIn(1, 12)

    fun wsSetChunkConcurrency(n: Int) {
        settings.edit().putInt("chunk_concurrency", n.coerceIn(1, 12)).apply()
        _ws.update { it.copy(chunkConcurrency = n.coerceIn(1, 12)) }
        wsNote("并发下载线程数已设为 $n（下次下载生效）")
    }

    fun wsDownload() {

        val item = _ws.value.item ?: return
        if (_ws.value.downloading) return
        wsDownloadJob = viewModelScope.launch {
            val ctx = getApplication<Application>()
            val outDir = java.io.File(ctx.cacheDir, "workshop/${item.id}")
            // 不删缓存：引擎会按 chunk 复用（.chunks/*.chunk），删了就等于每次从零下，
            // 网络一抖就白费。成功安装后再清，失败/取消留着下次续传。
            outDir.mkdirs()
            DdLog.i(ctx, "dl", "==== start id=${item.id} title=${item.title} ====")
            DdLog.i(ctx, "dl", "cacheDir=$outDir sdk=${Build.VERSION.SDK_INT} chunks=${chunkConcurrency()} loggedIn=${_ws.value.loggedIn}")
            _ws.update {
                it.copy(
                    downloading = true, status = "查询元数据", detail = "", progress = null,
                    error = null,
                )
            }
            try {
                val account = SteamAuth.load(ctx)?.takeIf { _ws.value.loggedIn }?.toSession("DD Launcher")
                // 进度刷新节流 + 速度采样：每块完成都重组整页太浪费，200ms 一次足够
                var lastUiAt = 0L
                var sampleAt = System.currentTimeMillis()
                var sampleBytes = 0L
                var speed = 0L
                WorkshopRepo.download(
                    item, outDir, account, chunkConcurrency(), SteamIdentity.of(ctx),
                ).collect { ev ->
                    when (ev) {
                        is top.apricityx.workshop.workshop.DownloadEvent.StateChanged ->
                            _ws.update { it.copy(status = WorkshopRepo.stateLabel(ev.state)) }

                        is top.apricityx.workshop.workshop.DownloadEvent.Progress -> {
                            val total = ev.totalBytes
                            val ratio = if (total != null && total > 0) {
                                (ev.writtenBytes.toFloat() / total).coerceIn(0f, 1f)
                            } else null
                            val now = System.currentTimeMillis()
                            val finished = ev.totalChunks != null && ev.completedChunks == ev.totalChunks
                            if (finished || now - lastUiAt >= 200) {
                                lastUiAt = now
                                val dt = now - sampleAt
                                if (dt >= 500) {
                                    speed = ((ev.writtenBytes - sampleBytes) * 1000 / dt).coerceAtLeast(0)
                                    sampleAt = now
                                    sampleBytes = ev.writtenBytes
                                }
                                _ws.update {
                                    it.copy(
                                        progress = ratio,
                                        detail = buildString {
                                            append(DataPack.gb(ev.writtenBytes))
                                            if (total != null && total > 0) append(" / ${DataPack.gb(total)}")
                                            if (speed > 0) append(" · ${DataPack.gb(speed)}/s")
                                        },
                                    )
                                }
                            }
                        }

                        is top.apricityx.workshop.workshop.DownloadEvent.FileCompleted -> Unit

                        // 文件名 / 分块细节只进日志，不往界面上刷（界面上只看进度）
                        is top.apricityx.workshop.workshop.DownloadEvent.LogAppended ->
                            DdLog.i(ctx, "dl", ev.line)

                        is top.apricityx.workshop.workshop.DownloadEvent.Completed ->
                            _ws.update { it.copy(status = "整理文件", detail = "") }

                        is top.apricityx.workshop.workshop.DownloadEvent.Failed ->
                            throw IllegalStateException(ev.message)
                    }
                }

                _ws.update { it.copy(status = "安装中") }
                val prevDir = prefs.getString("dir_${item.id}", null)
                val installed = withContext(Dispatchers.IO) {
                    WorkshopRepo.install(ctx, repo, item, outDir, prevDir)
                }
                DdLog.i(ctx, "dl", "install ok=${installed.ok} msg=${installed.message}")
                if (installed.ok) {
                    prefs.edit()
                        .putLong(item.id.toString(), item.updated)
                        .putString("dir_${item.id}", installed.dirName)
                        .apply()
                    groupNewInstall(installed.dirName)
                    loadWorkshopInstalled()
                }
                // 装成功才清缓存；装了失败也保留，下次还能复用已下好的 chunk
                if (installed.ok) outDir.deleteRecursively()
                _ws.update {
                    it.copy(
                        downloading = false,
                        status = "",
                        progress = null,
                        detail = "",
                        error = if (installed.ok) null else installed.message,
                    )
                }
                wsNote("工坊：${installed.message}")
                refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消当暂停：不清缓存，下次点下载接着下
                DdLog.i(ctx, "dl", "cancelled by user（保留缓存，可续传）")
                throw e
            } catch (e: Throwable) {
                DdLog.e(ctx, "dl", e)
                // 失败也保留缓存：已完成的 chunk 下次直接复用，不用从头再下
                _ws.update {
                    it.copy(
                        downloading = false,
                        status = "",
                        progress = null,
                        error = "下载失败：${FailText.of(e)}",
                    )
                }
            } finally {
                wsDownloadJob = null
            }
        }
    }

    // ---------------- 游戏内设置（persist.options.json） ----------------

    data class Opts(
        val loading: Boolean = true,
        val path: String = "",
        val raw: String = "",
        val modifiedAt: Long = 0L,
        val options: List<GameOptions.Option> = emptyList(),
        /** 读文件失败的说明（文件不存在 / 不是 JSON），只在界面上显示一次，不当弹窗 */
        val error: String? = null,
        /** 保存后的结果一句话，成功/失败都写在这儿 */
        val message: String? = null,
        val saving: Boolean = false,
        val backups: List<File> = emptyList(),
    )

    private val _opts = MutableStateFlow(Opts())
    val opts: StateFlow<Opts> = _opts.asStateFlow()

    fun optsLoad() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val loaded = withContext(Dispatchers.IO) { GameOptions.load(ctx) }
            val backups = withContext(Dispatchers.IO) { GameOptions.listBackups() }
            _opts.update {
                it.copy(
                    loading = false,
                    path = loaded.path,
                    raw = loaded.raw,
                    modifiedAt = loaded.modifiedAt,
                    options = loaded.options,
                    error = loaded.error,
                    backups = backups,
                )
            }
        }
    }

    fun optsMessage(msg: String?) = _opts.update { it.copy(message = msg) }

    /** 保存若干键（值是界面上的文本，按原类型回转） */
    fun optsSave(edits: Map<String, String>) {
        if (_opts.value.saving) return
        viewModelScope.launch {
            _opts.update { it.copy(saving = true, message = null) }
            val ctx = getApplication<Application>()
            val err = withContext(Dispatchers.IO) { GameOptions.save(ctx, edits) }
            _opts.update { it.copy(saving = false) }
            if (err == null) {
                note("游戏设置已保存")
                optsLoad()
                _opts.update { it.copy(message = "已保存，下次启动游戏生效") }
            } else {
                _opts.update { it.copy(message = err) }
            }
        }
    }

    /** 原始 JSON 模式整段写回 */
    fun optsSaveRaw(text: String) {
        if (_opts.value.saving) return
        viewModelScope.launch {
            _opts.update { it.copy(saving = true, message = null) }
            val ctx = getApplication<Application>()
            val err = withContext(Dispatchers.IO) { GameOptions.saveRaw(ctx, text) }
            _opts.update { it.copy(saving = false) }
            if (err == null) {
                note("游戏设置（原始 JSON）已保存")
                optsLoad()
                _opts.update { it.copy(message = "原始 JSON 已写回，下次启动游戏生效") }
            } else {
                _opts.update { it.copy(message = err) }
            }
        }
    }

    /** 把最近一份备份写回去（写之前会再备份当前状态） */
    fun optsRestoreLatest() {
        val latest = _opts.value.backups.firstOrNull() ?: run {
            _opts.update { it.copy(message = "还没有备份") }
            return
        }
        viewModelScope.launch {
            _opts.update { it.copy(saving = true, message = null) }
            val ctx = getApplication<Application>()
            val err = withContext(Dispatchers.IO) { GameOptions.restore(ctx, latest) }
            _opts.update { it.copy(saving = false) }
            if (err == null) {
                note("游戏设置已从备份还原：${latest.name}")
                optsLoad()
                _opts.update { it.copy(message = "已还原 ${latest.name}") }
            } else {
                _opts.update { it.copy(message = err) }
            }
        }
    }

    /** 调试：把当前设置拷到 /sdcard/Documents/DarkestSaves/options/current-options.json */
    fun optsDump() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val path = withContext(Dispatchers.IO) { runCatching { GameOptions.dumpToPublic(ctx) } }
            // 顺手把解析结果写进日志，用来核对键名/类型有没有认错
            val loaded = withContext(Dispatchers.IO) { GameOptions.load(ctx) }
            note("opts parse: ${loaded.options.size} 项 err=${loaded.error ?: "-"}")
            loaded.options.forEach { note("opts  ${it.group} | ${it.key}=${it.text} (${it.kind})") }
            optsLoad()
            _opts.update { it.copy(message = path.getOrElse { "导出失败：${it.message}" }) }
        }
    }

    /** 点「启动」：原来的 Toast 提示改写到日志里 */
    fun launch() {
        note(launchGame().ifBlank { "正在启动游戏…" })
    }

    fun launchGame(): String {
        if (!_state.value.packInstalled) return "还没导入数据包，先点上面那张卡片"
        val ctx = getApplication<Application>()
        val intent = Intent().apply {
            component = ComponentName(ctx.packageName, Game.LAUNCH_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(intent)
            // 顺手在后台留一份存档快照（6 小时内已备份过就跳过），mod 把存档搞坏时能退回去
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    if (!SaveRepo.hasSaves()) return@runCatching
                    val last = SaveRepo.list().firstOrNull()
                    val sixHours = 6 * 60 * 60 * 1000L
                    if (last == null || System.currentTimeMillis() - last.createdAt > sixHours) {
                        SaveRepo.create("before-launch")
                        DdLog.i(getApplication(), "save", "auto snapshot before launch")
                    }
                }.onFailure { DdLog.e(getApplication(), "save", it) }
            }
            ""
        } catch (t: Throwable) {
            "启动失败：${t.message ?: t.javaClass.simpleName}"
        }
    }
}
