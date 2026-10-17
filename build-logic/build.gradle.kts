plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly("com.android.tools.build:gradle-api:8.11.1")
    compileOnly("org.ow2.asm:asm-tree:9.7.1")
}

gradlePlugin {
    plugins {
        register("removeFakeVersionCheck") {
            id = "alarmeha.remove-fake-version-check"
            implementationClass = "AlarmeHaRemoveFakeVersionCheckPlugin"
        }
    }
}
