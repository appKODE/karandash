rootProject.name = "standalone-jvm-plugin"

// Builds against the real plugin SDK from this repository (composite build).
// Outside the repo, remove this block and depend on the published SDK artifact instead.
includeBuild("../../..") {
    dependencySubstitution {
        substitute(module("ru.kode.karandash:sdk")).using(project(":plugins:sdk"))
    }
}
