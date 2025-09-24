package com.github.yelog.ideavimbettercmd.cmdline

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.application.ApplicationManager
import java.awt.Dimension
import java.awt.Point
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.BorderFactory
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.Timer
import javax.swing.event.DocumentListener
import javax.swing.event.DocumentEvent

class BetterCmdLinePopup(
    private val project: Project,
    private val prefix: Char,
    private val onSubmit: (String) -> Unit,
    private val onChange: ((String) -> Unit)? = null,
) {
    private var popup: JBPopup? = null

    fun show() {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        val label = JLabel(if (prefix == ':') "Command:" else "Search:")
        val field = JTextField()
        field.columns = 50
        field.border = BorderFactory.createEmptyBorder(4, 4, 4, 4)

        var suppressorTimer: Timer? = null

        field.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> {
                        val text = field.text.trim()
                        popup?.cancel()
                        suppressorTimer?.stop()
                        ApplicationManager.getApplication().invokeLater {
                            onSubmit(text)
                            // 回车后直接把焦点放回编辑器
                            val editor = FileEditorManager.getInstance(project).selectedTextEditor
                            editor?.contentComponent?.requestFocusInWindow()
                        }
                    }
                    KeyEvent.VK_ESCAPE -> {
                        popup?.cancel()
                        suppressorTimer?.stop()
                        ApplicationManager.getApplication().invokeLater {
                            val editor = FileEditorManager.getInstance(project).selectedTextEditor
                            editor?.contentComponent?.requestFocusInWindow()
                        }
                    }
                }
            }
        })

        if (prefix == '/') {
            field.document.addDocumentListener(object : DocumentListener {
                private fun changed() {
                    onChange?.invoke(field.text)
                }
                override fun insertUpdate(e: DocumentEvent) = changed()
                override fun removeUpdate(e: DocumentEvent) = changed()
                override fun changedUpdate(e: DocumentEvent) = changed()
            })
        }

        panel.add(label)
        panel.add(field)
        panel.preferredSize = Dimension(panel.preferredSize.width, 70)

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, field)
            .setFocusable(true)
            .setRequestFocus(true)
            .setResizable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setMovable(true)
            .setTitle(if (prefix == ':') "Better Ex Command" else "Better Search")
            .createPopup()

        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val location = if (editor != null) {
            val comp = editor.contentComponent
            val p = comp.locationOnScreen
            val size = comp.size
            Point(
                p.x + (size.width / 2) - 300,
                p.y + (size.height * 0.3).toInt()
            )
        } else {
            null
        }

        if (editor != null && location != null) {
            popup?.showInScreenCoordinates(editor.contentComponent, location)
        } else {
            popup?.showCenteredInCurrentWindow(project)
        }

        // 周期性关闭原生 IdeaVim 面板，防止其在第二次及之后再次出现
        suppressorTimer = Timer(120) {
            ExEntryPanelSuppressor.closeOnce()
        }.also { it.start() }

        ApplicationManager.getApplication().invokeLater {
            field.requestFocusInWindow()
        }
    }
}
