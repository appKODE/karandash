// Maven Central publishing (com.vanniktech.maven.publish) for the plugin SDK and the modules it exposes.
// Coordinates: ru.kode.karandash:<project name>:<version>; POM fields come from the pom* keys in gradle.properties.
//
//   ./gradlew publishToMavenCentral   # needs mavenCentralUsername/mavenCentralPassword + signingInMemoryKey*
//   ./gradlew publishToMavenLocal     # works unsigned: signing is configured only when signing keys are present
plugins {
    id("com.vanniktech.maven.publish")
    signing
}

group = "ru.kode.karandash"

val hasSigningKey =
    providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.keyId").isPresent

fun pomProperty(key: String): String = providers.gradleProperty(key).get()

mavenPublishing {
    publishToMavenCentral()
    if (hasSigningKey) signAllPublications()

    pom {
        name.set("Karandash ${project.name}")
        description.set(pomProperty("pomDescription"))
        url.set(pomProperty("pomUrl"))

        scm {
            url.set(pomProperty("pomScmUrl"))
            connection.set(pomProperty("pomScmConnection"))
            developerConnection.set(pomProperty("pomScmDevConnection"))
        }
        licenses {
            license {
                name.set(pomProperty("pomLicenseName"))
                url.set(pomProperty("pomLicenseUrl"))
                distribution.set(pomProperty("pomLicenseDist"))
            }
        }
        developers {
            developer {
                id.set(pomProperty("pomDeveloperId"))
                name.set(pomProperty("pomDeveloperName"))
            }
        }
    }
}
