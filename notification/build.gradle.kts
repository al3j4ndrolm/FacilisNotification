plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "io.github.al3j4ndrolm.facilis.notification"
    compileSdk {
        version = release(37)
    }
    resourcePrefix = "facilis_notification_"

    defaultConfig {
        // Notification channels and java.time both start at Android 8.
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(libs.facilis.permission)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.startup)

    testImplementation(libs.junit)
    // Real org.json for unit tests (Android's is a stub off-device).
    testImplementation(libs.org.json)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "notification"
            version = project.version.toString()

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
