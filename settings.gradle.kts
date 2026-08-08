pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Local fallback for io.hammerhead:karoo-ext — lets the app build without
        // GitHub Packages auth: clone hammerheadnav/karoo-ext at the pinned tag and
        // run `./gradlew :lib:publishToMavenLocal` there.
        mavenLocal {
            content {
                includeGroup("io.hammerhead")
            }
        }
        maven {
            name = "GitHubPackagesKarooExt"
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orElse("")
                    .get()
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orElse("")
                    .get()
            }
        }
    }
}

rootProject.name = "karoo-garage"
include(":app")
