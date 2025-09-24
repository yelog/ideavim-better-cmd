package com.github.yelog.ideavimbettercmd.cmdline

import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.text.JTextComponent

/**
 * 拦截 Normal 模式下输入的 ':' '/' ，弹出自定义命令/搜索输入框。
 * 当前版本未真正检测 Vim 模式，后续可通过 IdeaVim API 判断是否在 Normal。
 */
class BetterCmdLineKeyInterceptor(
    private val project: Project,
    private val service: BetterCmdLineService
) : KeyEventDispatcher, Disposable {

    private val log = Logger.getInstance(BetterCmdLineKeyInterceptor::class.java)

    /**
     * 记录最近一次由 KEY_PRESSED 触发弹窗的时间，避免紧接着的 KEY_TYPED 再次触发
     */
    private var lastTriggerAt: Long = 0L

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        when (e.id) {
            KeyEvent.KEY_PRESSED -> {
                if (!shouldIntercept(e)) return false
                if (e.isControlDown || e.isAltDown || e.isMetaDown) return false

                when (e.keyCode) {
                    KeyEvent.VK_SLASH -> {
                        e.consume()
                        trigger('/')
                        return true
                    }
                    KeyEvent.VK_SEMICOLON -> {
                        if (e.isShiftDown) {
                            e.consume()
                            trigger(':')
                            return true
                        }
                    }
                }
            }
            KeyEvent.KEY_TYPED -> {
                // 某些情况下（或由于 KEY_PRESSED 未能阻止 IdeaVim）再兜底拦截
                val ch = e.keyChar
                if (ch == ':' || ch == '/') {
                    if (!shouldIntercept(e)) return false
                    // 避免与刚刚 KEY_PRESSED 重复
                    if (System.currentTimeMillis() - lastTriggerAt < 120) {
                        // 已由 KEY_PRESSED 处理，不再弹第二个
                        e.consume()
                        return true
                    }
                    e.consume()
                    trigger(ch)
                    return true
                }
            }
        }
        return false
    }

    private fun trigger(prefix: Char) {
        lastTriggerAt = System.currentTimeMillis()
        log.debug("Trigger popup for prefix '$prefix'")
        service.showPopup(prefix)
    }

    private fun shouldIntercept(e: KeyEvent): Boolean {
        val comp = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        // 只在焦点不在文本输入控件时拦截（避免用户在普通输入框里想输入冒号）
        if (comp is JTextComponent) return false
        // TODO: 通过 IdeaVim 判断是否处于 Normal 模式
        return true
    }

    override fun dispose() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this)
    }

    companion object {
        fun register(project: Project, service: BetterCmdLineService) {
            val dispatcher = BetterCmdLineKeyInterceptor(project, service)
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
            Disposer.register(project, dispatcher)
        }
    }
}
