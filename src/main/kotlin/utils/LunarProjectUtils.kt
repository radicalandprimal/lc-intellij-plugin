package com.moonsworth.lunar.idea.utils

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.intellij.openapi.project.Project
import com.intellij.util.io.readText
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val lunarMarker = """
####################################################################################
# Minecraft Version Configuration
#
# This config describes the Minecraft Versions compatible with Lunar Client.
# It is written in HOCON (download the IntelliJ plugin for syntax highlighting).
####################################################################################
"""

private var isLunarCache: Cache<Project, Boolean> = Caffeine.newBuilder()
    .weakKeys()
    .build()

fun Project.isLunar(): Boolean {
    if (basePath == null) return false
    return isLunarCache.get(this) {
        val path = versionsFile ?: return@get false
        val text = path.readText()
        text.startsWith(lunarMarker)
    }
}

val Project.lunarConfig: Config?
    get () {
        val path = versionsFile ?: return null
        val text = path.readText()
        return ConfigFactory.parseString(text).resolve()
    }

private val Project.versionsFile: Path?
    get() {
        val path = Paths.get(basePath ?: return null)
        val versionFilePath = path.resolve("version/src/main/resources/versions.conf") ?: return null
        if (!Files.exists(versionFilePath)) {
            return null
        }
        return versionFilePath
    }
