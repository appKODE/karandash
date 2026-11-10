package main

/*
#include <stdlib.h>
*/
import "C"
import (
	"fmt"
	"unsafe"
)

const (
	moduleID      = "go-reporter-example"
	moduleName    = "Go Reporter Plugin Example"
	moduleVersion = "1.0.0"
	moduleType    = "REPORTER"
)

func toCString(s string) *C.char { return C.CString(s) }

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
func karandash_initialize(contextJSON *C.char) C.int { return 1 }

//export karandash_shutdown
func karandash_shutdown() {}

//export karandash_format_id
func karandash_format_id() *C.char { return toCString("go-markdown") }

//export karandash_report
func karandash_report(findingsJSON *C.char, passed C.int, configJSON *C.char) *C.char {
	passedStr := "FAILED"
	if int(passed) != 0 {
		passedStr = "PASSED"
	}
	report := fmt.Sprintf("# Karandash Report\n\n**Status:** %s\n", passedStr)
	return toCString(report)
}

//export karandash_free_string
func karandash_free_string(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
