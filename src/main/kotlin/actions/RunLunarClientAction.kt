package com.moonsworth.lunar.idea.actions

import com.intellij.execution.RunManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.moonsworth.lunar.idea.utils.LunarVersion
import com.moonsworth.lunar.idea.utils.getLunarVersions
import com.moonsworth.lunar.idea.utils.isLunar
import icons.LunarClientDevIcons
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.jetbrains.plugins.gradle.util.cmd.node.GradleCommandLine
import java.nio.file.Path
import java.nio.file.Paths

private fun runGradleTask(project: Project, dir: Path, func: (ExternalSystemTaskExecutionSettings) -> Unit) {
    val settings = ExternalSystemTaskExecutionSettings().apply {
        externalSystemIdString = GradleConstants.SYSTEM_ID.id
        externalProjectPath = dir.toString().replace('\\', '/')
        func.invoke(this@apply)
    }
    ExternalSystemUtil.runTask(settings, "Run", project, GradleConstants.SYSTEM_ID)
}

class RunLunarClientAction : DumbAwareAction(LunarClientDevIcons.LUNAR) {
    // TODO: weird block appears
    override fun actionPerformed(e: AnActionEvent) {
        if (e.project == null) return

        val popup = JBPopupFactory.getInstance().createListPopup(VersionPopupStep(e))

        e.inputEvent?.let {
            popup.showUnderneathOf(it.component)
        } ?: run(popup::showInFocusCenter)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.isLunar() ?: false
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}

private class ModulesPopupStep(
    private val e: AnActionEvent,
    private val version: LunarVersion
) : BaseListPopupStep<LunarVersion.Module>("Modules", version.modules) {
    override fun getTextFor(value: LunarVersion.Module): String {
        return value.name
    }

    override fun onChosen(selectedValue: LunarVersion.Module, finalChoice: Boolean): PopupStep<*>? {
        val project = e.project ?: return null
        val workingDir = project.basePath ?: return null
        val modules = selectedValue.modules.joinToString(",")
        val commandLine = GradleCommandLine.parse("runGenesis --dumpClasses --modules=\"$modules\" -Pmc=\"${version.id}\"")
        runGradleTask(project, Paths.get(workingDir)) {
            it.externalProjectPath = workingDir
            it.taskNames = commandLine.tasks.tokens
            it.scriptParameters = commandLine.options.text
            it.externalSystemIdString = GradleConstants.SYSTEM_ID.toString()
            it.executionName = "Lunar Client ${version.name} ${selectedValue.name}"
            makeTemporaryTask(project, it)
        }
        return null
    }

    private fun makeTemporaryTask(project: Project, settings: ExternalSystemTaskExecutionSettings) {
        val configuration = ExternalSystemUtil.createExternalSystemRunnerAndConfigurationSettings(settings, project, GradleConstants.SYSTEM_ID) ?: return
        val runManager = RunManager.getInstance(project)
        val existingConfiguration = runManager.findConfigurationByTypeAndName(configuration.type, configuration.name)
        if (existingConfiguration == null) {
            runManager.setTemporaryConfiguration(configuration)
        } else {
            runManager.selectedConfiguration = existingConfiguration
        }
    }
}

private class VersionPopupStep(private val e: AnActionEvent)
    : BaseListPopupStep<LunarVersion>("Versions", e.project!!.getLunarVersions())
{
    override fun getTextFor(value: LunarVersion): String {
        return value.name
    }

    override fun onChosen(selectedValue: LunarVersion, finalChoice: Boolean): ModulesPopupStep {
        return ModulesPopupStep(e, selectedValue)
    }
}