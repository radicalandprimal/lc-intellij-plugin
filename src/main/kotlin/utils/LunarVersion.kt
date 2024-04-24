package com.moonsworth.lunar.idea.utils

data class LunarVersion(
    val id: String,
    val name: String,
    val ordinal: Int?,
    val modules: List<Module>
) {
    data class Module(
        val name: String,
        val `private`: Boolean?,
        val modules: List<String>
    )
}