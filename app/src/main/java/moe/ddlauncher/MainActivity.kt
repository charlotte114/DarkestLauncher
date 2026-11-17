package moe.ddlauncher

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.ddlauncher.ui.DDLTheme
import moe.ddlauncher.ui.GameSettingsScreen
import moe.ddlauncher.ui.ModsApp
import moe.ddlauncher.ui.SettingsScreen
import moe.ddlauncher.ui.SteamLoginDialog
import moe.ddlauncher.ui.WorkshopScreen

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDebugIntent(intent)
        setContent {
            DDLTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                val ws by vm.ws.collectAsStateWithLifecycle()
                val login by vm.login.collectAsStateWithLifecycle()
                val opts by vm.opts.collectAsStateWithLifecycle()
                var showWorkshop by remember { mutableStateOf(false) }
                var showSettings by remember { mutableStateOf(false) }
                var showGameSettings by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) { vm.loadWorkshopInstalled() }

                LaunchedEffect(showWorkshop) {
                    if (showWorkshop) {
                        vm.wsInit()
                        vm.steamInit()
                    }
                }

                LaunchedEffect(showGameSettings) {
                    if (showGameSettings) vm.optsLoad()
                }

                LaunchedEffect(showSettings) {
                    // 设置页只看本地状态，不触发联网搜索
                    if (showSettings) vm.settingsInit()
                }

                if (showGameSettings) {
                    BackHandler { showGameSettings = false }
                    GameSettingsScreen(
                        state = opts,
                        onBack = { showGameSettings = false },
                        onReload = { vm.optsLoad() },
                        onSave = { vm.optsSave(it) },
                        onSaveRaw = { vm.optsSaveRaw(it) },
                        onRestoreLatest = { vm.optsRestoreLatest() },
                        onDump = { vm.optsDump() },
                    )
                } else if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(
                        state = state,
                        ws = ws,
                        onBack = { showSettings = false },
                        onLogin = { vm.steamShowLogin(true) },
                        onLogout = { vm.steamLogout() },
                        onOpenWorkshop = {
                            showSettings = false
                            showWorkshop = true
                        },
                        onSetChunks = { vm.wsSetChunkConcurrency(it) },
                        onClearCache = { vm.clearWorkshopCache() },
                        onLoadLauncherLog = { vm.loadLauncherLog() },
                        onLoadGameLog = { vm.loadGameLog() },
                        onClearLogs = { vm.clearLauncherLog() },
                    )
                } else if (showWorkshop) {
                    // 系统返回键：详情 -> 列表 -> 主界面，而不是直接退出 App
                    BackHandler {
                        if (ws.item != null) {
                            vm.wsCloseDetail()
                        } else {
                            showWorkshop = false
                            vm.wsLeave()
                        }
                    }
                    WorkshopScreen(
                        state = ws,
                        onQueryChange = vm::wsSetQuery,
                        onOpen = { vm.wsOpen(it) },
                        onLookup = { vm.wsLookup() },
                        onSetSort = { vm.wsSetSort(it) },
                        onSetTimeWindow = { vm.wsSetTimeWindow(it) },
                        onSetTag = { vm.wsSetTag(it) },
                        onSetCollection = { vm.wsSetCollection(it) },
                        onLoadMore = { vm.wsLoadMore() },
                        onOnlineSearch = { vm.wsSearchOnline() },
                        onResetSource = { vm.wsResetSource() },
                        onDownload = { vm.wsDownload() },
                        onCloseDetail = { vm.wsCloseDetail() },
                        onShowSettings = { vm.wsShowSettings(it) },
                        onSaveKey = { vm.wsSaveKey(it) },
                        onShowLogin = { vm.steamShowLogin(true) },
                        onMySubs = { vm.wsToggleSubs() },
                        onToggleSubscribe = { id, title, on -> vm.toggleSubscribed(id, title, on) },
                        onLogout = { vm.steamLogout() },
                        onSetChunks = { vm.wsSetChunkConcurrency(it) },
                        onSelfCheck = { vm.runWorkshopSelfCheck() },
                        onCloseSelfCheck = { vm.closeSelfCheck() },
                        onCancelDownload = { vm.wsCancelDownload() },
                        updates = state.updates,
                        updateBusy = state.updateBusy,
                        updateStatus = state.updateStatus,
                        onCheckUpdates = { vm.checkModUpdates() },
                        onUpdateAll = { vm.updateAllMods() },
                        onBack = {
                            showWorkshop = false
                            vm.wsLeave()
                        },
                    )
                } else {
                    ModsApp(
                        state = state,
                        onGrant = { askForAccess() },
                        onRefresh = { vm.refresh() },
                        onLaunch = { vm.launch() },
                        onToggle = { mod, on -> vm.toggle(mod, on) },
                        onDelete = { vm.delete(it) },
                        onImport = { vm.importZips(it) },
                        onImportPack = { uri -> vm.importDataPack(uri) },
                        onRemovePack = { vm.removeDataPack() },
                        onOpenWorkshop = { showWorkshop = true },
                        onOpenSettings = { showSettings = true },
                        onUpdateMod = { vm.updateMod(it) },
                        onToggleAllMods = { vm.setAllMods(it) },
                        onLoadSnapshots = { vm.loadSnapshots() },
                        onBackupSaves = { vm.backupSaves() },
                        onImportSave = { vm.importSave(it) },
                        onRestoreSave = { vm.restoreSave(it) },
                        onDeleteSave = { vm.deleteSave(it) },
                        onLoadLauncherLog = { vm.loadLauncherLog() },
                        onLoadGameLog = { vm.loadGameLog() },
                        onCloseLog = { vm.closeLog() },
                        onOpenGameSettings = { showGameSettings = true },
                    )
                }

                if (login.open) {
                    SteamLoginDialog(
                        state = login,
                        onDismiss = { vm.steamShowLogin(false) },
                        onLogin = { u, p -> vm.steamLoginStart(u, p) },
                        onGuard = { vm.steamLoginGuard(it) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }

    /** 已经在运行时再 `am start ... -e dl` 也要认，否则只能杀进程重开 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * adb 调试入口，省得靠点屏幕测下载：
     *
     * ```bash
     * adb shell am start -n com.darkestdungeon.launcher/moe.ddlauncher.MainActivity -e dl 840391233
     * adb shell am start -n com.darkestdungeon.launcher/moe.ddlauncher.MainActivity -e checkupdates 1
     * adb shell am start -n com.darkestdungeon.launcher/moe.ddlauncher.MainActivity -e dumpopts 1
     * adb shell am start -n com.darkestdungeon.launcher/moe.ddlauncher.MainActivity -e optset music_volume=35
     * ```
     *
     * 日志都落在 /sdcard/Android/data/com.darkestdungeon.launcher/files/ddlauncher.log。
     */
    private fun handleDebugIntent(intent: Intent?) {
        val dl = intent?.getStringExtra("dl")?.trim()?.toLongOrNull()
        val check = intent?.getStringExtra("checkupdates") != null
        val backup = intent?.getStringExtra("backup") != null
        val restore = intent?.getStringExtra("restore")?.trim()?.takeIf { it.isNotEmpty() }
        val selftest = intent?.getStringExtra("selftest")
        val selfcheck = intent?.getStringExtra("selfcheck") != null
        val wsq = intent?.getStringExtra("wsq")
        val wsqt = intent?.getStringExtra("wsqt")?.trim()?.toIntOrNull()
        val dumpOpts = intent?.getStringExtra("dumpopts") != null
        // -e optset music_volume=35 ：改一个游戏设置键（值按原类型回转）
        val optSet = intent?.getStringExtra("optset")?.trim()?.takeIf { it.contains('=') }
        if (dl == null && !check && !backup && restore == null && selftest == null && !selfcheck &&
            wsq == null && !dumpOpts && optSet == null
        ) return
        DdLog.i(this, "adb", "intent dl=$dl checkupdates=$check backup=$backup restore=$restore selftest=$selftest")
        // 等 ViewModel 起来再动手，不然沙盒还没准备好
        window.decorView.postDelayed({
            when {
                dl != null -> vm.installFromWorkshop(dl)
                selfcheck -> vm.runWorkshopSelfCheck()
                wsq != null -> vm.debugSearch(wsq, wsqt)
                restore != null -> vm.restoreSave(restore)
                selftest != null -> vm.saveSelfTest()
                dumpOpts -> vm.optsDump()
                optSet != null -> vm.optsSave(
                    mapOf(optSet.substringBefore('=') to optSet.substringAfter('='))
                )
                backup -> vm.backupSaves()
                check -> vm.checkModUpdates()
            }
        }, 1200)
    }

    private fun askForAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val specific = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            val ok = runCatching {
                startActivity(specific)
                true
            }.getOrDefault(false)
            if (!ok) {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else {
            requestPermissions(
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_STORAGE,
            )
        }
    }

    companion object {
        private const val REQ_STORAGE = 1001
    }
}
