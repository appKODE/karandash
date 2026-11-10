package main

/*
#include <stdlib.h>
*/
import "C"
import (
	"encoding/json"
	"unsafe"
)

const (
	moduleID      = "go-example-profile"
	moduleName    = "Go Example Profile Plugin"
	moduleVersion = "1.0.0"
	moduleType    = "PROFILE"
)

func toCString(s string) *C.char {
	return C.CString(s)
}

//export karandash_abi_version
func karandash_abi_version() C.int {
	return 1
}

//export karandash_module_id
func karandash_module_id() *C.char {
	return toCString(moduleID)
}

//export karandash_module_name
func karandash_module_name() *C.char {
	return toCString(moduleName)
}

//export karandash_module_version
func karandash_module_version() *C.char {
	return toCString(moduleVersion)
}

//export karandash_module_type
func karandash_module_type() *C.char {
	return toCString(moduleType)
}

//export karandash_initialize
func karandash_initialize(contextJSON *C.char) C.int {
	return 1
}

//export karandash_shutdown
func karandash_shutdown() {}

// karandash_profile_id returns the unique identifier for this profile.
//
//export karandash_profile_id
func karandash_profile_id() *C.char {
	return toCString("go-example-profile")
}

// karandash_profile_summary returns a human-readable description of the profile.
//
//export karandash_profile_summary
func karandash_profile_summary() *C.char {
	return toCString("An example validation profile contributed by the Go plugin")
}

// karandash_profile_tags returns a JSON array of tags categorising this profile.
//
//export karandash_profile_tags
func karandash_profile_tags() *C.char {
	tags := []string{"example", "go"}
	data, _ := json.Marshal(tags)
	return toCString(string(data))
}

// karandash_profile_rule_configs returns a JSON map of rule ID to rule config
// that this profile enables.
//
//export karandash_profile_rule_configs
func karandash_profile_rule_configs() *C.char {
	rules := map[string]interface{}{
		"title-format": map[string]interface{}{
			"pattern": "^(feat|fix|chore|docs|refactor|test|style|perf|ci):",
		},
	}
	data, _ := json.Marshal(rules)
	return toCString(string(data))
}

// karandash_profile_reporter_configs returns a JSON map of reporter ID to
// reporter config that this profile enables.
//
//export karandash_profile_reporter_configs
func karandash_profile_reporter_configs() *C.char {
	reporters := map[string]interface{}{
		"console": true,
	}
	data, _ := json.Marshal(reporters)
	return toCString(string(data))
}

//export karandash_free_string
func karandash_free_string(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
