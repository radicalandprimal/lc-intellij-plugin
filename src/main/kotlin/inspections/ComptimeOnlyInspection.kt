package com.moonsworth.lunar.idea.inspections

import com.demonwav.mcdev.util.constantStringValue
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.util.parentOfType
import com.moonsworth.lunar.idea.COMPTIME_ONLY
import com.moonsworth.lunar.idea.MIXIN
import com.moonsworth.lunar.idea.utils.UsageType

class ComptimeOnlyInspection : AbstractBaseJavaLocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return UsageVisitor { refElement, elementToHighlight, usage ->
            val comptimeOnly = (if (refElement is PsiModifierListOwner) refElement else null)
                ?.getAnnotation(COMPTIME_ONLY)
                ?: return@UsageVisitor

            val reason = comptimeOnly.findAttributeValue("value")?.constantStringValue

            val parent = elementToHighlight.parentOfType<PsiClass>()
            if (usage is UsageType.SuperCtorCall && parent?.hasAnnotation(MIXIN) == true) {
                return@UsageVisitor
            }

            holder.registerProblem(elementToHighlight, "Member is compile-time only: $reason")
        }
    }
}