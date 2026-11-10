package main

/*
#include <stdlib.h>
*/
import "C"
import (
	"encoding/json"
	"fmt"
	"os"
	"unsafe"
)

const (
	moduleID      = "go-example-plugin"
	moduleName    = "Go Example Plugin"
	moduleVersion = "1.0.0"
	moduleType    = "RULE"
)

// Helper: allocate C string
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

//export karandash_contributed_rules
func karandash_contributed_rules() *C.char {
	rules := []string{"go-title-length"}
	data, _ := json.Marshal(rules)
	return toCString(string(data))
}

//export karandash_validate
func karandash_validate(ruleID *C.char, mrJSON *C.char, configJSON *C.char) *C.char {
	rule := C.GoString(ruleID)
	if rule != "go-title-length" {
		return toCString("[]")
	}

	mrStr := C.GoString(mrJSON)
	var mr MergeRequest
	if err := json.Unmarshal([]byte(mrStr), &mr); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to parse MR JSON: %v\n", err)
		return toCString("[]")
	}

	var findings []Finding
	if len(mr.Title) < 10 {
		findings = append(findings, Finding{
			Severity: "WARNING",
			Message:  fmt.Sprintf("Title is too short (%d chars, minimum 10)", len(mr.Title)),
			Rule:     "go-title-length",
		})
	}

	data, _ := json.Marshal(findings)
	return toCString(string(data))
}

//export karandash_format_id
func karandash_format_id() *C.char {
	return toCString("")
}

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
