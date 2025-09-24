package com.github.yelog.ideavimbettercmd.startup

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.github.yelog.ideavimbettercmd.cmdline.BetterCmdLineService
import com.intellij.openapi.application.ApplicationManager

class MyProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        thisLogger().warn("Don't forget to remove all non-needed sample code files with their corresponding registration entries in `plugin.xml`.")
        // 初始化 BetterCmdLineService
        project.getService(BetterCmdLineService::class.java)
        // 强制初始化应用级按键处理安装器（确保 IdeEventQueue dispatcher 注册）
        ApplicationManager.getApplication()
            .getService(com.github.yelog.ideavimbettercmd.startup.BetterCmdTypedHandlerInstaller::class.java)
    }
}
