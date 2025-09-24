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
import java.util.concurrent.atomic.AtomicLong
import com.github.yelog.ideavimbettercmd.cmdline.InterceptBypass

/**
 * 应用级服务：尽早包装 TypedActionHandler，在 IdeaVim 打开底部命令/搜索行前拦截 ':' '/'。
 * 后续可加入 Vim 模式判断（Normal 模式才拦截）。
 */
@Service(Service.Level.APP)
class BetterCmdTypedHandlerInstaller : com.intellij.openapi.Disposable {

    private val log = Logger.getInstance(BetterCmdTypedHandlerInstaller::class.java)
    @Volatile private var installed = false
    private val lastInterceptAt = AtomicLong(0L)

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
            if (InterceptBypass.active()) {
                delegate.execute(editor, charTyped, dataContext)
                return
            }
            if (charTyped == ':' || charTyped == '/') {
                val project = editor.project ?: guessProject()
                if (project != null) {
                    showPopupAndSuppress(project, charTyped)
                    return
                }
            }
            // 避免与 dispatcher 的 KEY_PRESSED 处理重复（两次触发）
            if ((charTyped == ':' || charTyped == '/') &&
                System.currentTimeMillis() - lastInterceptAt.get() < 300
            ) {
                return
            }
            delegate.execute(editor, charTyped, dataContext)
        }

        private fun guessProject(): Project? =
            ProjectManager.getInstance().openProjects.firstOrNull()
    }

    private fun registerIdeQueueDispatcher() {
        IdeEventQueue.getInstance().addDispatcher({ ev ->
            if (InterceptBypass.active()) return@addDispatcher false
            if (ev is KeyEvent && ev.id == KeyEvent.KEY_PRESSED) {
                if (isColon(ev) || isSlash(ev)) {
                    if (!isVimNormalModeSafe()) return@addDispatcher false
                    ev.consume()
                    val project = ProjectManager.getInstance().openProjects.firstOrNull()
                    val ch = if (isSlash(ev)) '/' else ':'
                    project?.let { showPopupAndSuppress(it, ch) }
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
        // 某些情况下第二次按 '/' IdeaVim 可能处于 COMMAND_LINE / SEARCH 过渡态，仍要拦截
        name == null || name in setOf("NORMAL", "COMMAND_LINE", "CMD_LINE", "SEARCH", "SEARCH_IN_PROGRESS")
    } catch (_: Throwable) {
        true
    }

    private fun closeIdeaVimExPanelSafe() {
        try {
            val panelCls = Class.forName("com.maddyhome.idea.vim.ui.ex.ExEntryPanel")
            val instField = panelCls.declaredFields.firstOrNull {
                it.type == panelCls && it.name.lowercase().contains("instance")
            }
            val inst = if (instField != null) {
                instField.isAccessible = true
                instField.get(null)
            } else {
                panelCls.methods.firstOrNull { it.name == "getInstance" && it.parameterCount == 0 }?.invoke(null)
            }
            if (inst != null) {
                val closeNoArg = panelCls.methods.firstOrNull { it.name == "close" && it.parameterCount == 0 }
                val closeBool = panelCls.methods.firstOrNull {
                    it.name == "close" && it.parameterCount == 1 &&
                        (it.parameterTypes[0] == Boolean::class.java || it.parameterTypes[0] == Boolean::class.javaPrimitiveType)
                }
                val deactivate = panelCls.methods.firstOrNull { it.name.lowercase() == "deactivate" && it.parameterCount == 0 }
                when {
                    closeNoArg != null -> closeNoArg.invoke(inst)
                    closeBool != null -> closeBool.invoke(inst, true)
                    deactivate != null -> deactivate.invoke(inst)
                }
                log.debug("Closed IdeaVim ExEntryPanel (attempt).")
            }
        } catch (_: Throwable) {
            // ignore
        }
    }

    /**
     * 为了对抗 IdeaVim 异步创建面板的时机，分多次延迟尝试关闭。
     */
    private fun scheduleCloseNativePanel() {
        // 增加更长尾的关闭尝试，防止 IdeaVim 在我们之后再次异步打开
        val delays = listOf(0L, 16L, 32L, 48L, 64L, 96L, 160L, 240L, 360L, 520L, 700L, 900L, 1200L)
        val executor = AppExecutorUtil.getAppScheduledExecutorService()
        for (d in delays) {
            executor.schedule({
                ApplicationManager.getApplication().invokeLater {
                    closeIdeaVimExPanelSafe()
                }
            }, d, TimeUnit.MILLISECONDS)
        }
    }

    // 额外的密集循环（50ms 间隔 * 20 次 ≈ 1s）再兜底关闭，覆盖更多异步场景
    private fun ensurePanelClosedExtended() {
        val executor = AppExecutorUtil.getAppScheduledExecutorService()
        for (i in 0 until 20) {
            executor.schedule({
                ApplicationManager.getApplication().invokeLater {
                    closeIdeaVimExPanelSafe()
                }
            }, 50L * i, TimeUnit.MILLISECONDS)
        }
    }

    private fun showPopupAndSuppress(project: Project, prefix: Char) {
        lastInterceptAt.set(System.currentTimeMillis())
        project.getService(BetterCmdLineService::class.java).showPopup(prefix)
        // 立即及延迟多次关闭 IdeaVim 原生面板
        closeIdeaVimExPanelSafe()
        scheduleCloseNativePanel()
        ensurePanelClosedExtended()
    }

    override fun dispose() {
        // dispatcher 已随 Disposable 注销
    }
}
