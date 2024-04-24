package com.moonsworth.lunar.idea.utils

import com.intellij.openapi.project.Project
import com.typesafe.config.Config

fun Project.getLunarVersions(): List<LunarVersion> {
    return buildList {
        val config: Config? = lunarConfig

        if (config != null) {
            for (key in config.root().keys.filter { it.startsWith("v1_") }) {
                if (config.hasPath("$key.disabled") && config.getBoolean("$key.disabled")) {
                    continue
                }

                val version = config.getString("${key!!}.exact-version")!!
                val ordinal = config.getInt("$key.ordinal")
                val moduleList = config.getObjectList("$key.modules").map {
                    val moduleConfig = it.toConfig()
                    val moduleName = moduleConfig.getString("name")
                    val isPrivate = moduleConfig.hasPath("private") && moduleConfig.getBoolean("private")
                    val modules = moduleConfig.getStringList("modules")
                    LunarVersion.Module(moduleName, isPrivate, modules)
                }

                add(LunarVersion(key, version, ordinal, moduleList))
            }
        }
    }.sortedWith(compareBy { it.ordinal })
}