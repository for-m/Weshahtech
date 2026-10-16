rootProject.name = "weshah-jvm-harness"
// A mirror can be forced for networks that rate-limit Maven Central (MAVEN_CENTRAL_MIRROR).
val central = System.getenv("MAVEN_CENTRAL_MIRROR") ?: "https://repo.maven.apache.org/maven2"
pluginManagement {
    repositories {
        maven(url = System.getenv("MAVEN_CENTRAL_MIRROR") ?: "https://repo.maven.apache.org/maven2")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement { repositories { maven(url = central) } }
