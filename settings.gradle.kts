pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "running"

include("core")
include("runner")

rootProject.children.forEach { subproject ->
    val moduleName = subproject.name
    subproject.buildFileName = "$moduleName.gradle.kts"
    require(subproject.projectDir.isDirectory) {
        "Project directory ${subproject.projectDir} does not exist"
    }
}
