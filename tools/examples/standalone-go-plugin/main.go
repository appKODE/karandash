package main

/*
#include <stdlib.h>
*/
import "C"
import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"unsafe"
)

const (
	moduleID      = "go-standalone-plugin"
	moduleName    = "Go Standalone Rule Plugin"
	moduleVersion = "1.0.0"
	moduleType    = "RULE"
)

var conventionalPrefixes = []string{
	"feat:", "fix:", "chore:", "refactor:", "docs:", "test:", "ci:",
}

func hasConventionalPrefix(title string) bool {
	for _, p := range conventionalPrefixes {
		if strings.HasPrefix(title, p) {
			return true
		}
	}
	return false
}

func toCString(s string) *C.char {
	return C.CString(s)
}

//export karandash_abi_version
func karandash_abi_version() C.int { return 1 }

//export karandash_module_id
func karandash_module_id() *C.char { return toCString(moduleID) }

//export karandash_module_name
func karandash_module_name() *C.char { return toCString(moduleName) }

//export karandash_module_version
func karandash_module_version() *C.char { return toCString(moduleVersion) }

//export karandash_module_type
func karandash_module_type() *C.char { return toCString(moduleType) }

//export karandash_initialize
func karandash_initialize(_ *C.char) C.int { return 1 }

//export karandash_shutdown
func karandash_shutdown() {}

//export karandash_contributed_rules
func karandash_contributed_rules() *C.char {
	data, _ := json.Marshal([]string{"go-title-check"})
	return toCString(string(data))
}

//export karandash_validate
func karandash_validate(ruleID *C.char, mrJSON *C.char, _ *C.char) *C.char {
	rule := C.GoString(ruleID)
	if rule != "go-title-check" {
		return toCString("[]")
	}

	var mr MergeRequest
	if err := json.Unmarshal([]byte(C.GoString(mrJSON)), &mr); err != nil {
		fmt.Fprintf(os.Stderr, "go-standalone-plugin: failed to parse MR JSON: %v\n", err)
		return toCString("[]")
	}

	if !hasConventionalPrefix(mr.Title) {
		findings := []Finding{{
			Severity: "ERROR",
			Message:  "Title must start with feat:, fix:, chore:, refactor:, docs:, test:, or ci:",
			Rule:     "go-title-check",
		}}
		data, _ := json.Marshal(findings)
		return toCString(string(data))
	}
	return toCString("[]")
}

//export karandash_format_id
func karandash_format_id() *C.char { return toCString("") }

//export karandash_report
func karandash_report(findingsJSON *C.char, passed C.int, configJSON *C.char) *C.char {
	return toCString("")
}

//export karandash_free_string
func karandash_free_string(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
