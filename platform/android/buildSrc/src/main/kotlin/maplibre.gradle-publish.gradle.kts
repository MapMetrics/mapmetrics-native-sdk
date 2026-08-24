import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.Task
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.kotlin.dsl.get
import java.util.Locale

plugins {
    `maven-publish`
    signing
    id("com.android.library")
    id("com.vanniktech.maven.publish.base")
    id("maplibre.artifact-settings")
}

val androidComponents = extensions.getByType<LibraryAndroidComponentsExtension>()
val androidLibrary = extensions.getByType<LibraryExtension>()

// withSourcesJar()/withJavadocJar() are AGP's own mechanism and produce ONE
// correctly-named jar per variant, which the publications then pick up through
// `from(component)`.
//
// WHY NOT `artifacts { add("archives", ...) }`, which is still below: that is the
// legacy maven plugin's mechanism and attaches NOTHING under maven-publish. The
// androidSourcesJar task ran, wrote its jar into build/libs, and every
// publication ignored it -- so 2.0.1 was about to go to Central with no sources
// jar at all, where 1.0.3 shipped one. Central requires it for a release.
//
// Manual `artifact(tasks.named("androidSourcesJar"))` would also be wrong here:
// it is a single task producing a single jar named after the MODULE, so all six
// publications would carry the same mis-named artifact.
androidLibrary.publishing {
    singleVariant("vulkanRelease") {
        withSourcesJar()
        withJavadocJar()
    }
    singleVariant("vulkanDebug") {
        withSourcesJar()
        withJavadocJar()
    }
    singleVariant("openglRelease") {
        withSourcesJar()
        withJavadocJar()
    }
    singleVariant("openglDebug") {
        withSourcesJar()
        withJavadocJar()
    }
}

// Signing is required for Maven Central but must not block a local build.
// `publishToMavenLocal` on a dev machine has no GPG signatory configured, and
// signAllPublications() would otherwise fail the whole build.
val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent ||
    providers.gradleProperty("signing.keyId").isPresent ||
    providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent

afterEvaluate {
    mavenPublishing {
        // automaticRelease = FALSE, deliberately.
        //
        // `true` uploads AND releases in one step. A released version on Maven
        // Central is permanent -- it can be deprecated or superseded, never
        // withdrawn -- so that turns a mistyped version, a wrong artifactId or a
        // bad POM into a public artefact with no way back.
        //
        // With `false` the upload lands as a PENDING deployment: it is validated
        // (signatures, checksums, POM completeness) and then waits in the Portal
        // for a human to inspect the file list and press Publish, or to drop it.
        // The safety costs one click per release.
        publishToMavenCentral(false)
        if (hasSigningKey) {
            signAllPublications()
        } else {
            logger.lifecycle(
                "No signing key configured - publications will NOT be signed. " +
                    "This is fine for publishToMavenLocal, but Maven Central will reject them."
            )
        }
    }
}

// Configure task dependencies after all tasks are created
gradle.projectsEvaluated {
    // Explicitly configure publish tasks to depend on their corresponding signing tasks
    // This fixes Gradle's implicit dependency validation warnings
    // Since some publications may share components (e.g., defaultdebug and opengldebug both use openglDebug),
    // we ensure all signing tasks complete before any publish task
    // EVERY publish task, not just ...ToMavenCentralRepository. Publications here
    // share components (defaultdebug and vulkandebug both come from vulkanDebug),
    // so one publication's publish task consumes another's .asc output. Gradle
    // fails that as an undeclared dependency -- and it fails publishToMavenLocal
    // too, which is the dry run used to check signing before a release. Covering
    // only the Central tasks left the rehearsal broken while the real thing
    // worked, which is the wrong way round.
    tasks.filter { it.name.startsWith("publish") && it.name.contains("Publication") }.forEach { publishTask ->
        tasks.filter { it.name.startsWith("sign") && it.name.endsWith("Publication") }.forEach { signingTask ->
            publishTask.dependsOn(signingTask)
        }
    }
}

tasks.register<Javadoc>("androidJavadocs") {
    source = fileTree("src/main/java")
    classpath = files()
    isFailOnError = false
}

tasks.register<Jar>("androidJavadocsJar") {
    archiveClassifier.set("javadoc")
    from(tasks.named("androidJavadocs", Javadoc::class.java).map { it.destinationDir!! })
    dependsOn(tasks.named("androidJavadocs"))
}

tasks.register<Jar>("androidSourcesJar") {
    archiveClassifier.set("sources")
    from("src/main/java")
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).apply {
        charSet = "UTF-8"
        docEncoding = "UTF-8"
    }
}

artifacts {
    add("archives", tasks.named("androidSourcesJar"))
    add("archives", tasks.named("androidJavadocsJar"))
}

project.logger.lifecycle(project.extra["versionName"].toString())

version = project.extra["versionName"] as String
group = project.extra["mapLibreArtifactGroupId"] as String

fun configureMavenPublication(
    renderer: String,
    publicationName: String,
    artifactIdPostfix: String,
    descriptionPostfix: String,
    buildType: String = "Release"
) {
    publishing {
        publications {
            create<MavenPublication>(publicationName) {
                groupId = project.group.toString()
                artifactId = "${project.extra["mapLibreArtifactId"]}$artifactIdPostfix"
                version = project.version.toString()

                val componentName = "${renderer}${buildType}"
                val component = components.findByName(componentName)
                    ?: components.find { it.name.equals(componentName, ignoreCase = true) }
                if (component != null) {
                    from(component)
                } else {
                    project.logger.warn(
                        "Skipping publication '$publicationName' because component '$componentName' was not found. " +
                            "Available components: ${components.map { it.name }.sorted()}"
                    )
                }

                pom {
                    name.set("${project.extra["mapLibreArtifactTitle"]}$descriptionPostfix")
                    description.set("${project.extra["mapLibreArtifactTitle"]}$descriptionPostfix")
                    url.set(project.extra["mapLibreArtifactUrl"].toString())
                    licenses {
                        license {
                            name.set(project.extra["mapLibreArtifactLicenseName"].toString())
                            url.set(project.extra["mapLibreArtifactLicenseUrl"].toString())
                        }
                    }
                    developers {
                        developer {
                            id.set(project.extra["mapLibreDeveloperId"].toString())
                            name.set(project.extra["mapLibreDeveloperName"].toString())
                            email.set(project.extra["mapLibreDeveloperEmail"].toString())
                        }
                    }
                    scm {
                        connection.set(project.extra["mapLibreArtifactScmUrl"].toString())
                        developerConnection.set(project.extra["mapLibreArtifactScmUrl"].toString())
                        url.set(project.extra["mapLibreArtifactUrl"].toString())
                    }
                }
            }
        }
    }
}

afterEvaluate {
    configureMavenPublication("vulkan", "defaultrelease", "", "")
    configureMavenPublication("vulkan", "defaultdebug", "-debug", " (Debug)", "Debug")
    configureMavenPublication("vulkan", "vulkanrelease", "-vulkan", "(Vulkan)")
    configureMavenPublication("vulkan", "vulkandebug", "-vulkan-debug", "(Vulkan, Debug)", "Debug")
    // Right now this is the same as the first, but in the future we might release a major version
    // which defaults to Vulkan (or has support for multiple backends). We will keep using only
    // OpenGL ES with this artifact ID if that happens.
    configureMavenPublication("opengl", "openglrelease", "-opengl", " (OpenGL ES)")
    configureMavenPublication("opengl", "opengldebug", "-opengl-debug", " (OpenGL ES, Debug)", "Debug")
}

// Wire per-variant compile classpaths into the Javadoc task.
// Replaces the removed `android.libraryVariants` API with the new AndroidComponents API,
// looking up the generated JavaCompile task by its conventional name.
androidComponents.onVariants { variant ->
    val capitalizedName = variant.name.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
    }
    val javaCompileTaskName = "compile${capitalizedName}JavaWithJavac"

    tasks.named("androidJavadocs", Javadoc::class.java).configure {
        val javaCompile = tasks.named(javaCompileTaskName, JavaCompile::class.java)
        dependsOn(javaCompile)
        doFirst {
            classpath = classpath.plus(files(javaCompile.get().classpath))
        }
    }
}
