plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.hilt.android) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.kover) apply false
  id("opensab.unit-tests")
}