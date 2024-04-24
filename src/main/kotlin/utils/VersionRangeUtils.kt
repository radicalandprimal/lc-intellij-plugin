package com.moonsworth.lunar.idea.utils

import com.google.common.collect.BiMap
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.search.GlobalSearchScope
import com.moonsworth.lunar.idea.VERSION_CONSTANTS

private val versionMappingCache: MutableMap<Project, BiMap<String, Int>> = mutableMapOf()

private fun implicitMin(context: PsiElement): Int {
    val mapping = getVersionMapping(context.project)
    val module = ModuleUtil.findModuleForPsiElement(context) ?: return 0
    if (module.name.contains(".modern.")) {
        return mapping.get("1.16.1")!!.toInt()
    }
    return 0
}

private fun implicitMax(context: PsiElement): Int {
    val mapping = getVersionMapping(context.project)
    val module = ModuleUtil.findModuleForPsiElement(context)
        ?: return mapping.values.max().toInt()
    if (module.name.contains(".legacy.")) {
        return mapping["1.12.2"]!!.toInt()
    }
    return mapping.values.max()
}

fun getAvailableRange(element: PsiElement): Set<Int> {
    TODO("Not yet implemented")
}

private fun getAvailableRanges(element: PsiElement): Sequence<Set<Int>> {
    TODO("Not yet implemented")
}

private fun Project.getInt(element: PsiAnnotationMemberValue): Int? {
    TODO("Not yet implemented")
}

fun PsiField.getIntConstant(): Int? {
    return (initializer as? PsiLiteralExpression)?.value as? Int
}

fun getVersionName(project: Project, version: Int): String {
    return getVersionMapping(project).inverse()[version] ?: "unknown"
}

private fun getVersions(ann: PsiAnnotation): Set<Int> {
    TODO("Not yet implemented")
}

private fun getVersionMapping(project: Project): BiMap<String, Int> {
    TODO("Not yet implemented")
}

private fun getBridgeConstant(project: Project, version: Int): String? {
    val clazz = JavaPsiFacade.getInstance(project).findClass(VERSION_CONSTANTS, GlobalSearchScope.projectScope(project)) ?: return null

    val psiField = clazz.fields.firstOrNull {
        val n: Int? = it.getIntConstant()
        n != null && n == version
    }

    return psiField?.name
}

private fun Set<Int>.filterEnabled(project: Project): Set<Int> {
    val mapping: BiMap<String, Int> = getVersionMapping(project)
    val destination = HashSet<Int>()
    filterTo(destination) { mapping.containsValue(it) }
    return destination
}

interface VersionCheck {
    fun getConditionText(project: Project, versionConstant: String): String

    fun getAnnotationText(project: Project, annotationName: String): String

    fun mergeWith(project: Project, annotation: PsiAnnotation): PsiAnnotation = replaceAnnotation(project, annotation)

    companion object {
        fun of(project: Project, reachable: Set<Int>, available: Set<Int>): VersionCheck {
            val problems = reachable.minus(available)
            val toCheck = reachable.minus(problems)
            return when(toCheck.size) {
                0 -> Remove()
                1 -> {
                    val version = toCheck.first()
                    val v1 = getVersionMapping(project).values
                    val latest = v1.maxOrNull()
                    if (latest != null && version == latest) {
                        Range(version, null)
                    } else {
                        Single(version)
                    }
                }
                else -> {
                    // TODO?
                    val v3 = ofRange(project, toCheck, reachable, available)
                    v3 as? VersionCheck ?: Individual(toCheck)
                }
            }
        }

        private fun ofRange(project: Project, toCheck: Set<Int>, reachable: Set<Int>, available: Set<Int>) {
            TODO("Not yet implemented")
        }

        private fun VersionCheck.replaceAnnotation(project: Project, annotation: PsiAnnotation): PsiAnnotation {
            val string = annotation.qualifiedName ?: return annotation
            return JavaPsiFacade.getElementFactory(project)
                .createAnnotationFromText(getAnnotationText(project, string), null)
        }
    }

    data class Individual(val versions: Set<Int>) : VersionCheck {
        override fun getConditionText(project: Project, versionConstant: String): String {
            return versions.joinToString(" || ") { "$versionConstant == ${getBridgeConstant(project, it)}" }
        }

        override fun getAnnotationText(project: Project, annotationName: String): String {
            return versions.joinToString(
                prefix = "@$annotationName({",
                postfix = "})"
            ) {
                getBridgeConstant(project, it).toString()
            }
        }

        override fun mergeWith(project: Project, annotation: PsiAnnotation): PsiAnnotation {
            val existing = getVersions(annotation) ?: return replaceAnnotation(project, annotation)
            return Individual(existing.intersect(versions)).replaceAnnotation(project, annotation)
        }
    }

    data class Range(
        val min: Int?,
        val max: Int?
    ) : VersionCheck {
        override fun getConditionText(project: Project, versionConstant: String): String {
            val lower = min?.let { "$versionConstant >= ${getBridgeConstant(project, it)}" }
            val upper = max?.let { "$versionConstant <= ${getBridgeConstant(project, it)}" }
            return listOfNotNull(lower, upper).joinToString(" && ")
        }

        override fun getAnnotationText(project: Project, annotationName: String): String {
            val lower = min?.let { "min = ${getBridgeConstant(project, it)}" }
            val upper = max?.let { "max = ${getBridgeConstant(project, it)}" }
            return "@$annotationName(${listOfNotNull(lower, upper).joinToString(")")}"
        }

        override fun mergeWith(project: Project, annotation: PsiAnnotation): PsiAnnotation {
            if (annotation.hasAttribute("value")) {
                return Individual(IntRange(
                    min ?: implicitMin(annotation as PsiElement),
                    max ?: implicitMax(annotation as PsiElement)
                ).toSet().filterEnabled(project)).mergeWith(project, annotation)
            }
            val min = listOfNotNull(annotation.findAttributeValue("min")?.let { project.getInt(it) }).maxOrNull() as Int
            val max = listOfNotNull(annotation.findAttributeValue("max")?.let { project.getInt(it) }).minOrNull() as Int
            return Range(min, max).replaceAnnotation(project, annotation)
        }
    }

    class Remove : VersionCheck {
        override fun getConditionText(project: Project, versionConstant: String): String {
            return "false"
        }

        override fun getAnnotationText(project: Project, annotationName: String): String {
            return "@$annotationName({})"
        }

        override fun mergeWith(project: Project, annotation: PsiAnnotation): PsiAnnotation {
            return mergeWith(project, annotation)
        }
    }

    data class Single(val version: Int) : VersionCheck {
        override fun getConditionText(project: Project, versionConstant: String): String {
            return "$versionConstant == ${getBridgeConstant(project, version)}"
        }

        override fun getAnnotationText(project: Project, annotationName: String): String {
            return "@$annotationName(${getBridgeConstant(project, version)})"
        }

        override fun mergeWith(project: Project, annotation: PsiAnnotation): PsiAnnotation {
            return mergeWith(project, annotation)
        }
    }
}