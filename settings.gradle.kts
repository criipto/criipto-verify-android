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
  }

  versionCatalogs {
    create("exampleLibs") {
      from(files("./gradle/example.libs.versions.toml"))
    }
    create("libraryLibs") {
      from(files("./gradle/verify.libs.versions.toml"))
    }
  }
}

rootProject.name = "Idura Verify Android"
include(":verify")
include(":example")
