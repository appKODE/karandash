plugins {
    id("karandash.native-exports")
    id("karandash.kmp-rule-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.io.core)
        }
    }
}

nativeExports {
    packageName.set("io.karandash.validators")
    moduleClass.set("CodeownersRuleModule")
    moduleType.set("RULE")
}
