plugins {
  alias(libs.plugins.kotlin)
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
