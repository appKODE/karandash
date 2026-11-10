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
	moduleID      = "go-example-ai-token-allocator"
	moduleName    = "Go Example AI Token Allocator Plugin"
	moduleVersion = "1.0.0"
	moduleType    = "AI_TOKEN_ALLOCATOR"
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

// karandash_ai_allocate_tokens receives a JSON array of rule IDs, a total
// token budget as an integer, and a JSON map of rule priorities. It returns a
// JSON map of rule ID to allocated token count.
//
//export karandash_ai_allocate_tokens
func karandash_ai_allocate_tokens(rulesJSON *C.char, totalBudget C.int, prioritiesJSON *C.char) *C.char {
	var rules []string
	if err := json.Unmarshal([]byte(C.GoString(rulesJSON)), &rules); err != nil || len(rules) == 0 {
		data, _ := json.Marshal(map[string]int{})
		return toCString(string(data))
	}

	budget := int(totalBudget)
	perRule := budget / len(rules)
	allocation := make(map[string]int, len(rules))
	for _, rule := range rules {
		allocation[rule] = perRule
	}

	data, _ := json.Marshal(allocation)
	return toCString(string(data))
}

//export karandash_free_string
func karandash_free_string(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
