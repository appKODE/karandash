plugins {
    id("karandash.native-exports")
    id("karandash.kmp-rule-module")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":test-fixtures"))
        }
    }
}

nativeExports {
    packageName.set("io.karandash.validators")
    moduleClass.set("TitleFormatRuleModule")
    moduleType.set("RULE")
}
