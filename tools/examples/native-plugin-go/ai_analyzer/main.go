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
	moduleID      = "go-example-ai-analyzer"
	moduleName    = "Go Example AI Analyzer Plugin"
	moduleVersion = "1.0.0"
	moduleType    = "AI_ANALYZER"
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

// karandash_ai_analyze receives a merge request JSON, a token budget JSON, a
// model identifier, and a config JSON. It returns a JSON array of Finding
// objects (empty when nothing is found).
//
//export karandash_ai_analyze
func karandash_ai_analyze(mrJSON *C.char, budgetJSON *C.char, model *C.char, configJSON *C.char) *C.char {
	findings := []interface{}{}
	data, _ := json.Marshal(findings)
	return toCString(string(data))
}

//export karandash_free_string
func karandash_free_string(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
