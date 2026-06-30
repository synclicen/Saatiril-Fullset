pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            credentials {
                // JitPack authentication — uses GitHub token when available
                // This is needed for CI/CD where JitPack may require auth
                username = System.getenv("GITHUB_USER") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
        maven {
            url = uri("https://repo.gradle.org/gradle/libs-releases/")
        }
    }
}

rootProject.name = "SaatirilOperator"
include(":app")
