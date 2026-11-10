plugins {
    id("karandash.native-exports")
    id("karandash.kmp-rule-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.datetime)
        }
    }
}

nativeExports {
    packageName.set("io.karandash.validators")
    moduleClass.set("ApprovalRuleModule")
    moduleType.set("RULE")
}
