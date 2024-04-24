package com.moonsworth.lunar.idea.utils

import com.intellij.psi.PsiBlockStatement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiParameterList
import org.jetbrains.uast.*
import kotlin.math.min

private fun getMethodName(call: UCallExpression): String {
    val methodIdentifier = call.methodIdentifier ?: call.methodName
    return methodIdentifier as String
}

private fun skipParentheses(element: UElement?): UElement? {
    var current = element
    while (current is UParenthesizedExpression) {
        current = current.uastParent
    }
    return current
}

/**
 * Computes an argument mapping between the arguments of a UCallExpression and the parameters of a PsiMethod.
 *
 * @param call The UCallExpression for which to compute the argument mapping.
 * @param method The PsiMethod containing the parameters.
 * @return A Map mapping UExpression arguments to their corresponding PsiParameter.
 */
private fun computeArgumentMapping(call: UCallExpression, method: PsiMethod): Map<UExpression, PsiParameter> {
    // get the parameter list of the PsiMethod
    val parameterList: PsiParameterList = method.parameterList

    // If the method has no parameters, return an empty map
    if (parameterList.parametersCount == 0) {
        return emptyMap()
    }

    val arguments: List<UExpression> = call.valueArguments // get the list of arguments from the UCallExpression
    val parameters = parameterList.parameters // get the array of parameters from the PsiParameterList
    var argIndex = 0
    val mappingCount = min(parameters.size, arguments.size) // determine the number of mappings to compute, limited to the minimum of parameters and arguments
    val map: HashMap<UExpression, PsiParameter> = HashMap(2 * mappingCount) // store the argument-to-parameter mappings

    var paramIndex = 0

    // iterate over arguments and parameters to create mappings
    while (paramIndex < mappingCount) {
        val argument = arguments[argIndex]
        val parameter = parameters[paramIndex]
        map[argument] = parameter
        argIndex++
        paramIndex++
    }

    // if there are remaining arguments and parameters, create additional mappings
    if (argIndex < arguments.size && paramIndex > 0) {
        paramIndex--
        while (argIndex < arguments.size) {
            val argument = arguments[argIndex]
            val parameter = parameters[paramIndex]
            map[argument] = parameter
            argIndex++
        }
    }

    // return the computed argument-to-parameter mappings as a map
    return map
}

private fun UElement?.isEqualTo(other: UElement?): Boolean {
    if (this == null || other == null) { // TODO
        return this == other
    }
    var e1 = this
    var e2 = other
    if (e1 is UBlockExpression && e1.sourcePsi is PsiBlockStatement) {
        e1 = (e1.sourcePsi as PsiBlockStatement).codeBlock.toUElement()!!
    }
    if (e2 is UBlockExpression && e2.sourcePsi is PsiBlockStatement) {
        e2 = (e1.sourcePsi as PsiBlockStatement).codeBlock.toUElement()!!
    }
    return e1 == e2
}

private typealias ApiLevelLookup = (UElement) -> Int
