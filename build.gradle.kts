plugins {
  id("org.jetbrains.kotlin.jvm") version "2.2.21"
}

dependencies {
  implementation(libs.jimfs)
  implementation(variantOf(libs.tomlj) {
    classifier("all")
  }) {
    because("Use the tomlj fatjar which bundles the matching antlr4-runtime version and avoids version skew in consumers")
  }
}
