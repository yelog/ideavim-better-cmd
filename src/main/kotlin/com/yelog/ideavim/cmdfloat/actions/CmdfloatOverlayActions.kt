package com.yelog.ideavim.cmdfloat.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.yelog.ideavim.cmdfloat.overlay.OverlayMode
import com.yelog.ideavim.cmdfloat.services.CmdlineOverlayService

abstract class CmdfloatOverlayAction(private val mode: OverlayMode) : AnAction(), DumbAware {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: event.getData(CommonDataKeys.PROJECT) ?: return
        val service = project.getService(CmdlineOverlayService::class.java) ?: return
        service.triggerOverlay(mode)
    }

    override fun update(event: AnActionEvent) {
        val project = event.project ?: event.getData(CommonDataKeys.PROJECT)
        event.presentation.isEnabled = project != null && !project.isDisposed
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

class CmdfloatCommandOverlayAction : CmdfloatOverlayAction(OverlayMode.COMMAND)

class CmdfloatSearchOverlayAction : CmdfloatOverlayAction(OverlayMode.SEARCH_FORWARD)

class CmdfloatSearchBackwardOverlayAction : CmdfloatOverlayAction(OverlayMode.SEARCH_BACKWARD)
