// Declared once at the root so every module's Kotlin plugin is loaded in one classloader scope.
// Applied only in the subprojects, the plugin's shared build services exist once per scope and the
// native link tasks fail at task-graph time.
plugins {
    alias(wip.plugins.kotlinMultiplatform) apply false
}
