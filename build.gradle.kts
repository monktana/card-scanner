plugins {
    id("com.android.application") version "9.5.0" apply false
    // AGP 9 bringt Kotlin selbst mit; das Compose-Plugin hebt die Kotlin-Version auf 2.4.20.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("com.google.devtools.ksp") version "2.3.13" apply false
}
