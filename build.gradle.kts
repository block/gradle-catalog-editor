plugins {
  alias(libs.plugins.kotlin)
  alias(libs.plugins.mavenPublish)
}

dependencies {
  implementation(libs.jimfs)
  implementation(variantOf(libs.tomlj) {
    classifier("all")
  }) {
    because("Use the tomlj fatjar which bundles the matching antlr4-runtime version and avoids version skew in consumers")
  }

  testImplementation(libs.assertj)
  testImplementation(libs.junitJupiter)
  testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks.test {
  useJUnitPlatform()
}

mavenPublishing {
  coordinates(group.toString(), rootProject.name, version.toString())
  publishToMavenCentral(automaticRelease = true)
  signAllPublications()

  pom {
    name.set("gradle-catalog-editor")
    description.set("A library for parsing, editing, and serializing Gradle version catalog files.")
    inceptionYear.set("2026")
    url.set("https://github.com/block/gradle-catalog-editor")

    licenses {
      license {
        name.set("The Apache Software License, Version 2.0")
        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
        distribution.set("repo")
      }
    }

    developers {
      developer {
        id.set("block")
        name.set("Block")
        url.set("https://github.com/block")
      }
    }

    scm {
      url.set("https://github.com/block/gradle-catalog-editor")
      connection.set("scm:git:git://github.com/block/gradle-catalog-editor.git")
      developerConnection.set("scm:git:ssh://github.com/block/gradle-catalog-editor.git")
    }
  }
}
