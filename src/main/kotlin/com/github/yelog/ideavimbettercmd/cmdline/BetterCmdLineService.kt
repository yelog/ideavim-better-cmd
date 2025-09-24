package com.github.yelog.ideavimbettercmd.cmdline

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.application.ApplicationManager
import java.awt.event.KeyEvent

@Service(Level.PROJECT)
class BetterCmdLineService(private val project: Project) {

    private val log = Logger.getInstance(BetterCmdLineService::class.java)

    fun showPopup(prefix: Char) {
        BetterCmdLinePopup(
            project,
            prefix,
            onSubmit = { text -> execute(prefix, text) },
            onChange = { changed ->
                if (prefix == '/') {
                    incrementalSearch(changed)
                }
            }
        ).show()
    }

    private fun execute(prefix: Char, text: String) {
        val cmd = text.trim()
        if (cmd.isEmpty()) return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null) {
            log.warn("No active editor for command/search: '$cmd'")
            return
        }

        try {
            val vimPluginClass = Class.forName("com.maddyhome.idea.vim.VimPlugin")
            val vim = vimPluginClass.getMethod("getInstance").invoke(null)

            val ok = when (prefix) {
                ':' -> runExCommand(vim, editor, cmd).also {
                    if (!it) fallbackSimulate(editor, ":$cmd\n")
                }
                '/' -> {
                    val success = runSearch(vim, editor, cmd)
                    if (!success) {
                        // Fallback: 模拟原生 /pattern<CR>
                        fallbackSimulate(editor, "/$cmd\n")
                    }
                    success
                }
                else -> false
            }
            if (!ok) {
                log.warn("Command/search NOT executed (prefix=$prefix, text='$cmd') (may have fallback simulated)")
            }
        } catch (t: Throwable) {
            log.warn("IdeaVim integration failed: ${t.message} ; fallback simulate.", t)
            fallbackSimulate(editor, (if (prefix == ':') ":" else "/") + cmd + "\n")
        }
    }
    
    fun incrementalSearch(pattern: String) {
        val pat = pattern.trim()
        if (pat.isEmpty()) return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        try {
            val vimPluginClass = Class.forName("com.maddyhome.idea.vim.VimPlugin")
            val vim = vimPluginClass.getMethod("getInstance").invoke(null)
            val ok = runSearch(vim, editor, pat)
            if (!ok) {
                log.debug("Incremental search (runSearch failed) pattern='$pat'")
            }
        } catch (t: Throwable) {
            log.debug("Incremental search reflection failed: ${t.message}")
        }
    }
    
    /**
     * 通过反射尝试调用 IdeaVim 的 Ex 命令执行方法。
     * 逻辑：获取 *commandGroup* (getCommandGroup / commandGroup)，再在其上寻找包含 runEx / execute / process
     * 且最后一个参数是 String 的方法，并尝试注入 (project/editor/命令)。
     */
    private fun runExCommand(vim: Any, editor: Editor, command: String): Boolean {
        val cmdGroup = findGroup(vim, "command") ?: run {
            log.warn("Cannot locate IdeaVim commandGroup for Ex command.")
            return false
        }

        val methods = cmdGroup.javaClass.methods.filter {
            val n = it.name.lowercase()
            (n.contains("runex") || n.contains("execute") || n.contains("process") || n.contains("command"))
        }

        if (methods.isEmpty()) {
            log.debug("Ex group has methods: ${cmdGroup.javaClass.methods.joinToString { it.name }}")
        }

        for (m in methods) {
            val args = buildArgsForInvocation(m.parameterTypes, editor, command) ?: continue
            try {
                m.isAccessible = true
                m.invoke(cmdGroup, *args)
                log.debug("Executed Ex command via ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): $command")
                editor.contentComponent.requestFocusInWindow()
                return true
            } catch (t: Throwable) {
                log.debug("Ex method ${m.name} failed: ${t.message}")
            }
        }
        log.warn("No suitable IdeaVim method executed for Ex command: $command (candidates tried=${methods.map { it.name }})")
        return false
    }

    /**
     * 通过反射执行搜索；思路与 Ex 命令类似，寻找 searchGroup。
     * 可能的方法名：search / find / startSearch / doSearch。
     */
    private fun runSearch(vim: Any, editor: Editor, pattern: String): Boolean {
        val searchGroup = findGroup(vim, "search") ?: run {
            log.warn("Cannot locate IdeaVim searchGroup for search.")
            return false
        }

        // 先尝试设置 last search pattern（如果存在）
        try {
            val setPatternMethod = searchGroup.javaClass.methods.firstOrNull {
                it.name.lowercase().let { n -> n.contains("set") && n.contains("pattern") } &&
                    it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
            }
            setPatternMethod?.invoke(searchGroup, pattern)
        } catch (_: Throwable) {
        }

        val methods = searchGroup.javaClass.methods.filter {
            val n = it.name.lowercase()
            (n.contains("search") || n.contains("find") || n.contains("dosearch") || n.contains("startsearch"))
        }

        if (methods.isEmpty()) {
            log.debug("Search group has methods: ${searchGroup.javaClass.methods.joinToString { it.name }}")
        }

        for (m in methods) {
            val args = buildArgsForInvocation(m.parameterTypes, editor, pattern) ?: continue
            try {
                m.isAccessible = true
                m.invoke(searchGroup, *args)
                log.debug("Executed search via ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): /$pattern")
                editor.contentComponent.requestFocusInWindow()
                return true
            } catch (t: Throwable) {
                log.debug("Search method ${m.name} failed: ${t.message}")
            }
        }
        log.warn("No suitable IdeaVim method executed for search: /$pattern (candidates tried=${methods.map { it.name }})")
        return false
    }

    /**
     * 根据 groupName 关键字 (command / search) 定位 group 对象。
     */
    private fun findGroup(vim: Any, groupName: String): Any? {
        val lower = groupName.lowercase()
        val method = vim.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.name.lowercase().contains(lower) && it.name.lowercase().endsWith("group")
        } ?: return null
        return try {
            method.invoke(vim)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 为反射调用按参数类型构造参数数组。
     * 支持: Project / Editor / String / Boolean(默认true) ；否则返回 null 放弃该方法。
     */
    private fun buildArgsForInvocation(
        paramTypes: Array<Class<*>>,
        editor: Editor,
        tailString: String
    ): Array<Any>? {
        val list = mutableListOf<Any>()
        for (i in paramTypes.indices) {
            val p = paramTypes[i]
            val v: Any? = when {
                p.isAssignableFrom(Project::class.java) -> project
                p.name.endsWith(".VimEditor") || p.simpleName == "VimEditor" -> adaptVimEditor(editor) ?: return null
                p.isAssignableFrom(editor.javaClass) || p.simpleName == "Editor" -> editor
                p == String::class.java -> tailString // 统一直接给命令字符串
                p == Boolean::class.javaPrimitiveType || p == java.lang.Boolean::class.java -> true
                p == Int::class.javaPrimitiveType || p == Integer::class.java -> 0
                p == Long::class.javaPrimitiveType || p == java.lang.Long::class.java -> 0L
                else -> return null // 有无法支持的参数类型则放弃该方法
            }
            list += v!!
        }
        return list.toTypedArray()
    }

    private fun adaptVimEditor(editor: Editor): Any? {
        val candidates = listOf(
            "com.maddyhome.idea.vim.newapi.IjVimEditor", // 新 API
            "com.maddyhome.idea.vim.helper.IjVimEditor"  // 旧或备用
        )
        for (c in candidates) {
            try {
                val cls = Class.forName(c)
                val ctor = cls.constructors.firstOrNull { it.parameterCount == 1 }
                if (ctor != null) {
                    return ctor.newInstance(editor)
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun fallbackSimulate(editor: Editor, seq: String) {
        try {
            InterceptBypass.runWithoutIntercept {
                val comp = editor.contentComponent
                ApplicationManager.getApplication().invokeLater {
                    for (ch in seq) {
                        val now = System.currentTimeMillis()
                        val code = KeyEvent.getExtendedKeyCodeForChar(ch.code)
                        val pressed = KeyEvent(comp, KeyEvent.KEY_PRESSED, now, 0, code, ch)
                        val typed = KeyEvent(comp, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, ch)
                        val released = KeyEvent(comp, KeyEvent.KEY_RELEASED, now, 0, code, ch)
                        comp.dispatchEvent(pressed)
                        comp.dispatchEvent(typed)
                        comp.dispatchEvent(released)
                    }
                }
            }
            log.debug("Fallback simulated keys: '$seq'")
        } catch (t: Throwable) {
            log.warn("Fallback simulate failed: ${t.message}")
        }
    }
}
