package moe.ddlauncher

/**
 * 「关于」里显示的作者信息。
 *
 * 这是这个启动器自己的信息，跟游戏本体无关。想让设置页显示什么，改这里就行；
 * 留空的话界面上会显示「未填写」。
 */
object About {
    /** 作者名（你） */
    const val AUTHOR = "小猫你可以开猫拖车"

    /** B 站 ID */
    const val BILIBILI = "小猫你可以开猫猫拖车"

    /** 源码仓库：设置页里显示成一行可点的链接 */
    const val REPO = "https://github.com/charlotte114/DarkestLauncher"

    /** 仓库在界面上显示的名字 */
    const val REPO_NAME = "charlotte114/DarkestLauncher"

    /** 一句话简介 / 联系方式，可留空 */
    const val NOTE = ""

    /** 其它主页（博客等），留空就不显示 */
    const val LINK = ""
}
