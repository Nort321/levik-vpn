import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component
import org.gradle.api.artifacts.dsl.LockMode

plugins {
    id("org.cyclonedx.bom") version "3.4.1"
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
}

group = "com.leviknet"
version = "2.6.3"

allprojects {
    group = rootProject.group
    version = rootProject.version

    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }

    configurations.configureEach {
        if (name == "androidApis") {
            // AGP creates this SDK-provided synthetic configuration after the
            // lock-writing task has enumerated project dependency graphs.
            resolutionStrategy.deactivateDependencyLocking()
        }
    }

    tasks.register("resolveAndLockAll") {
        group = "dependency locking"
        description = "Resolves every resolvable configuration and writes dependency locks."
        notCompatibleWithConfigurationCache("Resolves configurations selected at execution time")

        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) {
                "$path must be run with --write-locks."
            }
        }
        doLast {
            configurations
                .filter { configuration -> configuration.isCanBeResolved }
                .sortedBy { configuration -> configuration.name }
                .forEach { configuration ->
                    logger.lifecycle("Locking ${project.path}:${configuration.name}")
                    configuration.incoming.resolutionResult.allComponents
                }
        }
    }

    tasks.register("verifyDependencyLocks") {
        group = "verification"
        description = "Resolves every configuration under strict dependency locking."
        notCompatibleWithConfigurationCache("Resolves configurations selected at execution time")

        doLast {
            configurations
                .filter { configuration -> configuration.isCanBeResolved }
                .sortedBy { configuration -> configuration.name }
                .forEach { configuration ->
                    logger.lifecycle("Verifying lock for ${project.path}:${configuration.name}")
                    configuration.incoming.resolutionResult.allComponents
                }
        }
    }

    tasks.withType<CyclonedxDirectTask>().configureEach {
        includeConfigs = listOf(
            "^directReleaseRuntimeClasspath$",
            "^playReleaseRuntimeClasspath$",
        )
        testConfigs = emptyList()
        includeMetadataResolution = true
        includeBuildEnvironment = false
        includeBuildSystem = true
        includeLicenseText = true
        projectType.set(Component.Type.APPLICATION)
    }
}

tasks.register("resolveAndLockAllDependencies") {
    group = "dependency locking"
    description = "Resolves and locks every resolvable configuration in every project."
    dependsOn(allprojects.map { project -> project.tasks.named("resolveAndLockAll") })
}

tasks.register("verifyAllDependencyLocks") {
    group = "verification"
    description = "Verifies strict dependency locks for every project."
    dependsOn(allprojects.map { project -> project.tasks.named("verifyDependencyLocks") })
}
