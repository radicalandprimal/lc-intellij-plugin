package com.moonsworth.lunar.idea.inspections

import com.demonwav.mcdev.util.addAnnotation
import com.intellij.codeInsight.generation.surroundWith.JavaWithIfSurrounder
import com.intellij.codeInspection.*
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import com.moonsworth.lunar.idea.AVAILABLE
import com.moonsworth.lunar.idea.OMNI_MIXIN
import com.moonsworth.lunar.idea.VERSION_CONSTANTS
import com.moonsworth.lunar.idea.utils.*
import org.jetbrains.uast.UClass

private val PsiElement.name: String
    get() {
        return when (this) {
            is PsiMethod -> name
            is PsiClass -> name
            else -> null
        } ?: "member"
    }

private fun addStaticVersionImports(project: Project, element: PsiElement) {
    val targets = mutableListOf<PsiReferenceExpression>()

    val visitor = ReferenceFinderVisitor {
        if (it.qualifiedName.startsWith("v1_")) {
            targets.add(it)
        }
    }
    element.accept(visitor)

    val versionConstants = JavaPsiFacade.getInstance(project)
        .findClass(VERSION_CONSTANTS, GlobalSearchScope.projectScope(project))
        ?: return

    targets.forEach { it.bindToElementViaStaticImport(versionConstants) }
}

class ReferenceFinderVisitor(private val onFound: (PsiReferenceExpression) -> Unit) : JavaRecursiveElementVisitor() {
    override fun visitExpression(expression: PsiExpression) {
        if (expression is PsiReferenceExpression) {
            onFound.invoke(expression)
        }
        super.visitExpression(expression)
    }
}

internal class AddMcVersionCheckQuickFix(
    private val check: VersionCheck,
    private val project: Project
) : LocalQuickFix {
    override fun getFamilyName(): String {
        return "Add MC version check"
    }

    override fun getName(): String {
        return "Surround with if (${check.getConditionText(project, "MC")}) { ... }"
    }

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement
        val file = element.containingFile ?: return
        val expression = PsiTreeUtil.getParentOfType(element, PsiExpression::class.java, false) ?: return
        val editor = EditorFactory.getInstance().createEditor(PsiDocumentManager.getInstance(project).getDocument(element.containingFile) ?: return)
        val anchorStatement = PsiTreeUtil.getParentOfType(expression, PsiStatement::class.java) ?: return
        val document = editor.document
        val owner = PsiTreeUtil.getParentOfType(element, PsiModifierListOwner::class.java, false)
        var elements = arrayOf<PsiElement>(anchorStatement)
        val prev = PsiTreeUtil.skipWhitespacesBackward(anchorStatement)
        if (prev is PsiComment && JavaSuppressionUtil.getSuppressedInspectionIdsIn(prev) != null) {
            elements = arrayOf(prev, anchorStatement)
        }
        val textRange = JavaWithIfSurrounder().surroundElements(project, editor, elements) ?: return
        // TODO
    }
}

internal class AddAvailableAnnotationQuickFix(
    private val check: VersionCheck,
    private val project: Project,
    owner: PsiModifierListOwner
) : LocalQuickFixOnPsiElement(owner) {
    override fun getFamilyName(): String {
        return "Annotate with @Available"
    }

    override fun getText(): String {
        return "Annotate ${startElement.name} with ${check.getAnnotationText(project, "Available")}"
    }

    override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
        val annotation = getFinalAnnotation(startElement as PsiModifierListOwner) ?: return
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(annotation)
        addStaticVersionImports(project, annotation)
    }

    private fun getFinalAnnotation(member: PsiModifierListOwner): PsiAnnotation? {
        val annotation = member.getAnnotation(AVAILABLE)
            ?: return member.addAnnotation(check.getAnnotationText(project, AVAILABLE))
        val psiAnnotation2 = check.mergeWith(project, annotation)
        annotation.delete()
        return member.addAnnotation(psiAnnotation2)
    }
}

class McVersionInspection : AbstractBaseUastLocalInspectionTool() {
    override fun checkClass(
        aClass: UClass,
        manager: InspectionManager,
        isOnTheFly: Boolean
    ): Array<ProblemDescriptor> {
        val project = manager.project

        val result = mutableListOf<ProblemDescriptor>()

        val psiElement = aClass.sourcePsi
        val psiClass = (if (psiElement is PsiClass) psiElement else null) ?: return emptyArray()

        if (!psiClass.hasAnnotation(OMNI_MIXIN)) {
            return emptyArray()
        }

        // TODO: why did this inline when compiled?
        aClass.accept(makeVersionCheckVisitor(getAvailableRange(psiClass)) { usage ->
            val refElement = usage.resolved
            val elementToHighlight = usage.element
            val versions = usage.versions

            val reachable = getAvailableRange(elementToHighlight).intersect(versions)
            val available = getAvailableRange(refElement)

            val problemVersions = reachable.minus(available)

            if (problemVersions.isEmpty()) {
                val versionText = problemVersions.sorted().joinToString { getVersionName(project, it) }
                val problemDescriptor = manager.createProblemDescriptor(
                    elementToHighlight,
                    "Reachable but not available on: $versionText",
                    isOnTheFly,
                    getQuickFixes(elementToHighlight, reachable, available), ProblemHighlightType.GENERIC_ERROR
                )
                result.add(problemDescriptor)
            }
        })

        return result.toTypedArray()
    }

    private fun getQuickFixes(context: PsiElement, reachable: Set<Int>, available: Set<Int>): Array<LocalQuickFix> {
        val collection = mutableListOf<LocalQuickFix>() // TODO: createListBuilder
        val project = context.project
        val check = VersionCheck.of(project, reachable, available)

        collection.add(AddMcVersionCheckQuickFix(check, project))

        context.parentOfType<PsiModifierListOwner>(true)?.let {
            collection.add(AddAvailableAnnotationQuickFix(check, project, it))
        }

        return collection.toTypedArray()
    }
}