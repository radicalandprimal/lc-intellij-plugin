package com.moonsworth.lunar.idea.utils

import com.demonwav.mcdev.platform.mixin.handlers.InjectorAnnotationHandler
import com.demonwav.mcdev.platform.mixin.handlers.MixinAnnotationHandler
import com.demonwav.mcdev.platform.mixin.handlers.ShadowHandler
import com.demonwav.mcdev.platform.mixin.util.MethodTargetMember
import com.demonwav.mcdev.platform.mixin.util.findSourceElement
import com.intellij.openapi.module.ModuleUtil
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope

fun getShadowTarget(element: PsiElement): PsiElement? {
    val psiMember = if (element is PsiMember) element else return null
    val smartPsiElementPointer = ShadowHandler().findFirstShadowTargetForReference(psiMember)
    return smartPsiElementPointer?.element
}

fun getInjectorTargets(member: PsiMethod): List<PsiElement> {
    val project = member.project
    val module = ModuleUtil.findModuleForPsiElement(member) ?: return emptyList()
    val searchScope = GlobalSearchScope.moduleWithLibrariesScope(module)
    for (annotation: PsiAnnotation in member.annotations) {
        val handler = MixinAnnotationHandler.forMixinAnnotation(annotation.qualifiedName ?: continue, member.project)
        if (handler == null || handler !is InjectorAnnotationHandler) {
            continue
        }
        val result = ArrayList<PsiElement>()
        for (target in handler.resolveTarget(annotation)) {
            if (target is MethodTargetMember) {
                val psiElement = target.classAndMethod.method.findSourceElement(
                    target.classAndMethod.clazz,
                    project,
                    searchScope
                )
                psiElement?.let { p0 -> result.add(p0) }
            }
        }
        return result
    }
    return emptyList()
}