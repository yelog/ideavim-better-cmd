package com.github.yelog.ideavimbettercmd.startup

import com.github.yelog.ideavimbettercmd.cmdline.BetterCmdLineService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.ide.IdeEventQueue
import java.awt.event.KeyEvent
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

/**
 * 应用级服务：尽早包装 TypedActionHandler，在 IdeaVim 打开底部命令/搜索行前拦截 ':' '/'。
 * 后续可加入 Vim 模式判断（Normal 模式才拦截）。
 */
@Service(Service.Level.APP)
class BetterCmdTypedHandlerInstaller : com.intellij.openapi.Disposable {

    private val log = Logger.getInstance(BetterCmdTypedHandlerInstaller::class.java)
    @Volatile private var installed = false

    init {
        installOrReinstall()
        ApplicationManager.getApplication().invokeLater {
            installOrReinstall()
        }
        registerIdeQueueDispatcher()
    }

    private fun installOrReinstall() {
        val actionManager = EditorActionManager.getInstance()
        val current = actionManager.typedAction.handler

        // 如果我们已经包裹过且未被替换，则不重复
        if (installed && current is WrapperHandler) {
            return
        }

        val wrapper = WrapperHandler(current)
        actionManager.typedAction.setupHandler(wrapper)
        installed = true
        log.debug("BetterCmdLine TypedActionHandler installed (previous=${current.javaClass.name}).")
    }

    private inner class WrapperHandler(
        private val delegate: TypedActionHandler
    ) : TypedActionHandler {

        override fun execute(editor: Editor, charTyped: Char, dataContext: DataContext) {
            if (charTyped == ':' || charTyped == '/') {
                val project = editor.project ?: guessProject()
                if (project != null) {
                    project.getService(BetterCmdLineService::class.java)
                        .showPopup(charTyped)
                    return
                }
            }
            delegate.execute(editor, charTyped, dataContext)
        }

        private fun guessProject(): Project? =
            ProjectManager.getInstance().openProjects.firstOrNull()
    }

    private fun registerIdeQueueDispatcher() {
        IdeEventQueue.getInstance().addDispatcher({ ev ->
            if (ev is KeyEvent && ev.id == KeyEvent.KEY_PRESSED) {
                if (isColon(ev) || isSlash(ev)) {
                    if (!isVimNormalModeSafe()) return@addDispatcher false
                    ev.consume()
                    val project = ProjectManager.getInstance().openProjects.firstOrNull()
                    project?.getService(BetterCmdLineService::class.java)?.showPopup(
                        if (isSlash(ev)) '/' else ':'
                    )
                    // 多次调度关闭原生面板，防止 IdeaVim 在我们之后仍创建 UI
                    scheduleCloseNativePanel()
                    closeIdeaVimExPanelSafe()
                    return@addDispatcher true
                }
            }
            false
        }, this)
        log.debug("BetterCmdLine IdeEventQueue dispatcher registered.")
    }

    private fun isColon(e: KeyEvent): Boolean =
        e.keyCode == KeyEvent.VK_SEMICOLON && e.isShiftDown

    private fun isSlash(e: KeyEvent): Boolean =
        e.keyCode == KeyEvent.VK_SLASH && !e.isControlDown && !e.isAltDown && !e.isMetaDown

    private fun isVimNormalModeSafe(): Boolean = try {
        val vimPluginCls = Class.forName("com.maddyhome.idea.vim.VimPlugin")
        val inst = vimPluginCls.getMethod("getInstance").invoke(null)
        val mode = inst.javaClass.methods.firstOrNull { it.name == "getMode" }?.invoke(inst)
        val name = mode?.toString()?.uppercase()
        name == null || name == "NORMAL"
    } catch (_: Throwable) {
        true
    }

    private fun closeIdeaVimExPanelSafe() {
        try {
            val panelCls = Class.forName("com.maddyhome.idea.vim.ui.ex.ExEntryPanel")
            val instField = panelCls.declaredFields.firstOrNull { it.type == panelCls && it.name.lowercase().contains("instance") }
            val inst = if (instField != null) {
                instField.isAccessible = true
                instField.get(null)
            } else null
            if (inst != null) {
                val closeMethod = panelCls.methods.firstOrNull { it.name == "close" }
                closeMethod?.invoke(inst)
                log.debug("Closed IdeaVim ExEntryPanel (if visible).")
            }
        } catch (_: Throwable) {
            // ignore
        }
    }

    /**
     * 为了对抗 IdeaVim 异步创建面板的时机，分多次延迟尝试关闭。
     */
    private fun scheduleCloseNativePanel() {
        val delays = listOf(0L, 40L, 100L, 180L, 260L)
        val executor = AppExecutorUtil.getAppScheduledExecutorService()
        for (d in delays) {
            executor.schedule({
                ApplicationManager.getApplication().invokeLater {
                    closeIdeaVimExPanelSafe()
                }
            }, d, TimeUnit.MILLISECONDS)
        }
    }

    override fun dispose() {
        // dispatcher 已随 Disposable 注销
    }
}
