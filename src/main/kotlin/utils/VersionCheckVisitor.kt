package com.moonsworth.lunar.idea.utils

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiPackage
import com.intellij.psi.util.PsiTypesUtil
import com.moonsworth.lunar.idea.MC_VERSION
import org.jetbrains.uast.*
import org.jetbrains.uast.internal.acceptList
import org.jetbrains.uast.visitor.AbstractUastVisitor
import org.jetbrains.uast.visitor.UastVisitor

interface UsageType {
    object Generic : UsageType
    object SuperCtorCall : UsageType
}

data class Usage(
    val resolved: PsiElement,
    val element: PsiElement,
    val versions: Set<Int>,
    val type: UsageType = UsageType.Generic
)

typealias UsageProcessor = (usage: Usage) -> Unit

data class UsageProcessorHolder(val processor: UsageProcessor)

internal class ExitFinderVisitor(
    private val versions: Set<Int>,
    private val exitFound: (Set<Int>) -> Unit,
) : AbstractUastVisitor() {
    private var done: Boolean = false // TODO: this doesnt init w/ false

    override fun visitBlockExpression(node: UBlockExpression): Boolean {
        return done
    }

    override fun visitLabeledExpression(node: ULabeledExpression): Boolean {
        return done
    }

    override fun visitIfExpression(node: UIfExpression): Boolean {
        node.uAnnotations.acceptList(this)
        node.thenExpression?.accept(ExitFinderVisitor(getDefinitePassingVersions(node.condition, this.versions), exitFound))
        node.elseExpression?.accept(ExitFinderVisitor(versions - getPossiblePassingVersions(node.condition, versions), exitFound))
        return true
    }

    override fun visitReturnExpression(node: UReturnExpression): Boolean {
        return handleExit()
    }

    override fun visitThrowExpression(node: UThrowExpression): Boolean {
        return handleExit()
    }

    override fun visitBreakExpression(node: UBreakExpression): Boolean {
        return handleExit()
    }

    override fun visitContinueExpression(node: UContinueExpression): Boolean {
        return handleExit()
    }

    private fun handleExit(): Boolean {
        if (!done) {
            exitFound.invoke(versions)
            done = true
        }
        return true
    }
}

// TODO
fun makeVersionCheckVisitor(versions: Set<Int>, processor: UsageProcessor): UastVisitor {
    with (UsageProcessorHolder(processor)) {
        return VersionCheckVisitor(versions)
    }
}

// TODO: object cast here
private fun skipParentheses(node: UExpression): UExpression {
    var result = node
    while (result is UParenthesizedExpression) {
        result = result.expression
    }
    return result
}

// TODO: casting
context(UsageProcessorHolder)
private fun visitCondition(condition: UExpression, versions: Set<Int>) {
    val node = skipParentheses(condition)
    if (node is UPolyadicExpression && (node.operator == UastBinaryOperator.LOGICAL_AND || node.operator == UastBinaryOperator.LOGICAL_OR)) {
        val isAnd = node.operator == UastBinaryOperator.LOGICAL_AND
        val retained = versions.toMutableSet()
        for (operand in node.operands) {
            operand.visitWith(retained)
            if (isAnd) {
                retained.retainAll(getPossiblePassingVersions(operand, retained))
                continue
            }
            retained.removeAll(getDefinitePassingVersions(operand, retained))
        }
    } else {
        node.visitWith(versions)
    }
}

private fun getPossiblePassingVersions(condition: UExpression, versions: Set<Int>): Set<Int> {
    val node = skipParentheses(condition)

    if (node is UBinaryExpression) {
        getPassingVersionsForCheck(node, versions)?.let { return it }
    }

    if (node is UUnaryExpression && node.operator == UastPrefixOperator.LOGICAL_NOT) {
        return versions - getDefinitePassingVersions(node.operand, versions)
    }

    if (node is UPolyadicExpression && (node.operator == UastBinaryOperator.LOGICAL_AND || node.operator == UastBinaryOperator.LOGICAL_OR)) {
        val isAnd = node.operator == UastBinaryOperator.LOGICAL_AND
        val passing = if (isAnd) versions.toMutableSet() else mutableSetOf()
        for (operand in node.operands) {
            val individual = getPossiblePassingVersions(operand, versions)
            if (isAnd) {
                passing.retainAll(individual)
                continue
            }
            passing.addAll(individual)
        }
        return passing
    }

    return versions
}

private fun getDefinitePassingVersions(condition: UExpression, versions: Set<Int>): Set<Int> {
    val node = skipParentheses(condition)

    if (node is UBinaryExpression) {
        getPassingVersionsForCheck(node, versions)?.let { return it }
    }

    if (node is UUnaryExpression && node.operator == UastPrefixOperator.LOGICAL_NOT) {
        return versions - getPossiblePassingVersions(node.operand, versions)
    }

    if (node is UPolyadicExpression && (node.operator == UastBinaryOperator.LOGICAL_AND || node.operator == UastBinaryOperator.LOGICAL_OR)) {
        val isAnd = node.operator == UastBinaryOperator.LOGICAL_AND
        val passing = if (isAnd) versions.toMutableSet() else mutableSetOf()
        for (operand in node.operands) {
            val individual = getDefinitePassingVersions(operand, versions)
            if (isAnd) {
                passing.retainAll(individual)
                continue
            }
            passing.addAll(individual)
        }
        return passing
    }

    return emptySet()
}

private fun getExitVersions(versions: Set<Int>, node: UIfExpression): Set<Int> {
    val result = mutableSetOf<Int>()
    node.accept(ExitFinderVisitor(versions) { result.addAll(it) })
    return result
}

context(UsageProcessorHolder)
private fun UExpression.visitWith(versions: Set<Int>) {
    accept(VersionCheckVisitor(versions))
}

private fun getPassingVersionsForCheck(versionCheck: UBinaryExpression, versions: Set<Int>): Set<Int>? {
    if (!isMcVersionInt(versionCheck.leftOperand)) return null

    val version = getVersionInt(versionCheck.rightOperand) ?: return null

    val check: (Int) -> Boolean = when(versionCheck.operator) {
        UastBinaryOperator.GREATER_OR_EQUALS -> { it -> it >= version }
        UastBinaryOperator.GREATER -> { it -> it > version }
        UastBinaryOperator.LESS_OR_EQUALS -> { it -> it <= version }
        UastBinaryOperator.LESS -> { it -> it < version }
        UastBinaryOperator.EQUALS, UastBinaryOperator.IDENTITY_EQUALS -> { it -> it == version }
        UastBinaryOperator.NOT_EQUALS, UastBinaryOperator.IDENTITY_NOT_EQUALS -> { it -> it != version}
        else -> return null
    }

    // TODO
    val destination = linkedSetOf<Int>()
    for (element in versions) {
        if (check(element)) destination.add(element)
    }

    return destination
}

private fun isMcVersionInt(element: UExpression): Boolean {
    val node = skipParentheses(element)
    return node is UReferenceExpression && node.resolvedName == MC_VERSION
}

// TODO: weird `Object` casting
private fun getVersionInt(element: UExpression): Int? {
    val node = skipParentheses(element)
    var level: Int? = null
    if (node is UReferenceExpression) {
        val field = node.resolve() as? PsiField
        val literal = field?.initializer as? PsiLiteralExpression
        (literal?.value as? Int)?.let { level = it }
    } else if (node is ULiteralExpression) {
        val value = node.value
        if (value is Int) level = value
    }
    return level
}

context(UsageProcessorHolder)
internal class VersionCheckVisitor(private val versions: Set<Int>) : AbstractUastVisitor() {
    override fun visitIfExpression(node: UIfExpression): Boolean {
        node.uAnnotations.acceptList(this)
        visitCondition(node.condition, versions)
        node.thenExpression?.visitWith(getPossiblePassingVersions(node.condition, versions))
        node.elseExpression?.visitWith(versions.minus(getDefinitePassingVersions(node.condition, versions)))
        return true
    }

    override fun visitBlockExpression(node: UBlockExpression): Boolean {
        node.uAnnotations.acceptList(this)
        val retained = versions.toMutableSet()
        for (expression in node.expressions) {
            expression.visitWith(retained)
            if (expression is UIfExpression) {
                val exitVersions = getExitVersions(versions, expression)
                retained.removeAll(exitVersions)
            }
        }
        return true
    }

    override fun visitPolyadicExpression(node: UPolyadicExpression): Boolean {
        if (node.operator == UastBinaryOperator.LOGICAL_AND) {
            visitCondition(node, versions)
            return true
        }
        return false
    }

    override fun visitBinaryExpression(node: UBinaryExpression): Boolean {
        return visitPolyadicExpression(node)
    }

    override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
        visitSimpleElement(node)
        return false
    }

    override fun visitCallableReferenceExpression(node: UCallableReferenceExpression): Boolean {
        visitSimpleElement(node)
        return false
    }

    override fun visitTypeReferenceExpression(node: UTypeReferenceExpression): Boolean {
        val resolved = PsiTypesUtil.getPsiClass(node.type) ?: return false
        processor(Usage(resolved, node.sourcePsi ?: return false, versions))
        return false
    }

    // TODO: make same
    override fun visitCallExpression(node: UCallExpression): Boolean {
        val resolved = node.resolve() ?: return false
        if (resolved.isConstructor && node.methodName == "super") {
            processor(Usage(
                resolved,
                (node.methodIdentifier)?.sourcePsi ?: return false,
                versions,
                UsageType.SuperCtorCall
            ))
            return false
        }

        val source = (if (resolved.isConstructor) node.classReference
            else node.methodIdentifier)?.sourcePsi ?: return false
        processor(Usage(
            resolved,
            source,
            versions
        ))
        return false
    }

    // TODO: make same
    private fun <T> visitSimpleElement(node: T) where T : UResolvable, T : UElement {
        val resolved = node.resolve() ?: return
        if (resolved is PsiPackage) return
        processor(Usage(
            resolved,
            (node as UElement).sourcePsi ?: return,
            versions
        ))
    }
}