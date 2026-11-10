plugins {
    id("karandash.native-exports")
    id("karandash.kmp-rule-module")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

nativeExports {
    packageName.set("io.karandash.validators")
    moduleClass.set("CommitBodyRuleModule")
    moduleType.set("RULE")
}
