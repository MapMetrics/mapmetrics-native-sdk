import com.android.build.api.dsl.Publishing

plugins {
    alias(libs.plugins.nexusPublishPlugin)
    alias(libs.plugins.kotlinter) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    id("com.jaredsburrows.license") version "0.9.8" apply false
    id("maplibre.dependencies")
    id("maplibre.publish-root")
    `maven-publish`
}

//Replaced the nexusPublishing with only publishing
//Did this because this is what is used for new Maven Central Repositories
publishing {
    repositories {
        maven {
            name = "MavenCentral"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = "EGiLiBy0"
                password = "ymdh85m+x49Li4tYvry0+kne7099RwGLMdYr/wJDzwmo"
            }
        }
    }
}
