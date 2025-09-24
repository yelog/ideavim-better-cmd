package com.github.yelog.ideavimbettercmd.cmdline

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.editor.Editor

@Service(Level.PROJECT)
class BetterCmdLineService(private val project: Project) {

    private val log = Logger.getInstance(BetterCmdLineService::class.java)

    fun showPopup(prefix: Char) {
        BetterCmdLinePopup(project, prefix) { text ->
            execute(prefix, text)
        }.show()
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

            when (prefix) {
                ':' -> runExCommand(vim, editor, cmd)
                '/' -> runSearch(vim, editor, cmd)
            }
        } catch (t: Throwable) {
            log.warn("IdeaVim integration failed (fallback not implemented yet): ${t.message}", t)
        }
    }

    /**
     * 通过反射尝试调用 IdeaVim 的 Ex 命令执行方法。
     * 逻辑：获取 *commandGroup* (getCommandGroup / commandGroup)，再在其上寻找包含 runEx / execute / process
     * 且最后一个参数是 String 的方法，并尝试注入 (project/editor/命令)。
     */
    private fun runExCommand(vim: Any, editor: Editor, command: String) {
        val cmdGroup = findGroup(vim, "command") ?: run {
            log.warn("Cannot locate IdeaVim commandGroup for Ex command.")
            return
        }

        val methods = cmdGroup.javaClass.methods.filter {
            val n = it.name.lowercase()
            (n.contains("runex") || n.contains("execute") || n.contains("process")) &&
                it.parameterTypes.lastOrNull() == String::class.java
        }

        for (m in methods) {
            val args = buildArgsForInvocation(m.parameterTypes, editor, command) ?: continue
            try {
                m.invoke(cmdGroup, *args)
                log.debug("Executed Ex command via ${m.name}: $command")
                return
            } catch (ignored: Throwable) {
                // 尝试下一个候选
            }
        }
        log.warn("No suitable IdeaVim method executed for Ex command: $command (candidates tried=${methods.map { it.name }})")
    }

    /**
     * 通过反射执行搜索；思路与 Ex 命令类似，寻找 searchGroup。
     * 可能的方法名：search / find / startSearch / doSearch。
     */
    private fun runSearch(vim: Any, editor: Editor, pattern: String) {
        val searchGroup = findGroup(vim, "search") ?: run {
            log.warn("Cannot locate IdeaVim searchGroup for search.")
            return
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
            (n.contains("search") || n.contains("find")) && it.parameterTypes.lastOrNull() == String::class.java
        }

        for (m in methods) {
            val args = buildArgsForInvocation(m.parameterTypes, editor, pattern) ?: continue
            try {
                m.invoke(searchGroup, *args)
                log.debug("Executed search via ${m.name}: /$pattern")
                return
            } catch (ignored: Throwable) {
            }
        }
        log.warn("No suitable IdeaVim method executed for search: /$pattern (candidates tried=${methods.map { it.name }})")
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
                p.isAssignableFrom(editor.javaClass) || p.simpleName == "Editor" -> editor
                p == String::class.java -> if (i == paramTypes.lastIndex) tailString else ""
                p == Boolean::class.javaPrimitiveType || p == java.lang.Boolean::class.java -> true
                else -> return null // 不支持的参数类型，放弃该方法
            }
            list += v!!
        }
        return list.toTypedArray()
    }
}
