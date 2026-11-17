package top.apricityx.workshop.workshop

import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 开发用探针：在桌面上跑一遍真实工坊下载，验证 CM/CDN 链路是否可用。
 * 用法: gradle :workshop-core:probe -Pid=3756060734
 */
fun main(args: Array<String>) = runBlocking {
    val id = (System.getProperty("id") ?: args.firstOrNull() ?: "3756060734").toLong()
    val appId = (System.getProperty("appId") ?: "262060").toUInt()
    val out = File(System.getProperty("outDir") ?: "build/probe/$id")
    out.deleteRecursively()
    out.mkdirs()
    println("probing app=$appId id=$id -> $out")

    val engine = WorkshopDownloadEngine.createDefault()
    engine.download(
        WorkshopDownloadRequest(appId = appId, publishedFileId = id.toULong(), outputDir = out)
    ).collect { ev ->
        when (ev) {
            is DownloadEvent.StateChanged -> println("STATE ${ev.state}")
            is DownloadEvent.LogAppended -> println("LOG   ${ev.line}")
            is DownloadEvent.Progress -> println(
                "PROG  ${ev.writtenBytes}/${ev.totalBytes ?: "?"} chunks=${ev.completedChunks ?: "-"}/${ev.totalChunks ?: "-"} files=${ev.completedFiles ?: "-"}/${ev.totalFiles ?: "-"}"
            )
            is DownloadEvent.FileCompleted -> println("FILE  ${ev.file.relativePath} ${ev.file.sizeBytes}")
            is DownloadEvent.Completed -> println("DONE  ${ev.files.size} files")
            is DownloadEvent.Failed -> println("FAIL  ${ev.message}")
        }
    }
    println("--- result tree ---")
    out.walkTopDown().filter { it.isFile }.forEach { println("  ${it.length()}\t${it.relativeTo(out).path}") }
}
