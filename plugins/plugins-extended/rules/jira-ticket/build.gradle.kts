plugins {
    id("karandash.native-exports")
    id("karandash.kmp-rule-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":core:config"))
            implementation(project(":core:config-pipeline"))
        }
    }
}

nativeExports {
    packageName.set("io.karandash.validators")
    moduleClass.set("JiraTicketRuleModule")
    moduleType.set("RULE")
}
