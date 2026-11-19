package moe.ddlauncher

import android.content.Context

/** 自定义名称只影响启动器列表，不碰 mod 文件夹、project.xml 或存档里的 mod 标识。 */
class ModAliases(context: Context) {

    private val prefs = context.getSharedPreferences("mod_aliases", Context.MODE_PRIVATE)

    fun load(): Map<String, String> = prefs.all.mapNotNull { (folder, value) ->
        val name = (value as? String)?.trim().orEmpty()
        if (name.isBlank()) null else folder to name
    }.toMap()

    /** 空名称会删除自定义项，恢复 project.xml 里的原名。 */
    fun set(folder: String, name: String): Boolean {
        val clean = normalize(name)
        val edit = prefs.edit()
        if (clean.isBlank()) edit.remove(folder) else edit.putString(folder, clean)
        return edit.commit()
    }

    fun remove(folder: String): Boolean = prefs.edit().remove(folder).commit()

    companion object {
        const val MAX_LENGTH = 80

        fun normalize(name: String): String =
            name.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH)
    }
}
