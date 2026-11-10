plugins {
    id("karandash.native-exports")
    id("karandash.kmp-rule-module")
}

nativeExports {
    packageName.set("io.karandash.validators")
    moduleClass.set("LabelsRuleModule")
    moduleType.set("RULE")
}
