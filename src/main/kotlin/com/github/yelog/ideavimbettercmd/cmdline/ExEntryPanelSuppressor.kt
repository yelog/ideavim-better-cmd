package com.github.yelog.ideavimbettercmd.cmdline

import com.intellij.openapi.diagnostic.Logger

/**
 * 重复调用 closeOnce() 来尝试关闭 IdeaVim 原生底部 Ex / Search 面板，配合定时器使用。
 */
object ExEntryPanelSuppressor {
    private val log = Logger.getInstance(ExEntryPanelSuppressor::class.java)

    fun closeOnce() {
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
            }
        } catch (_: Throwable) {
            // ignore
        }
    }
}
