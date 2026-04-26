plugins {
    `java-library`
    `maven-publish`
    signing
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17

    withSourcesJar()
    withJavadocJar()
}

dependencies {
    compileOnly(libs.lint.api)
    compileOnly(libs.lint.checks)
}

tasks.jar {
    manifest {
        attributes("Lint-Registry-v2" to "io.github.libxposed.lint.LibXposedIssueRegistry")
    }
}

publishing {
    publications {
        register<MavenPublication>("lint") {
            artifactId = "lint"
            group = "io.github.libxposed"
            version = "1.0.0"
            from(components["java"])
            pom {
                name.set("lint")
                description.set("Lint checks for libxposed APIs")
                url.set("https://github.com/libxposed/lint")
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        name.set("libxposed")
                        url.set("https://libxposed.github.io")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/libxposed/lint.git")
                    url.set("https://github.com/libxposed/lint")
                }
            }
        }
    }
    repositories {
        maven {
            name = "ossrh"
            url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
            credentials(PasswordCredentials::class)
        }
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/libxposed/lint")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

signing {
    val signingKey = findProperty("signingKey") as String?
    val signingPassword = findProperty("signingPassword") as String?
    if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}
