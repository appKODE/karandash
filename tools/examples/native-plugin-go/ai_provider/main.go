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
	moduleID      = "go-example-ai-provider"
	moduleName    = "Go Example AI Provider Plugin"
	moduleVersion = "1.0.0"
	moduleType    = "AI_PROVIDER"
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

// karandash_ai_complete receives a JSON completion request and returns a JSON
// completion response. The request follows the AI provider contract defined in
// the Karandash SDK.
//
//export karandash_ai_complete
func karandash_ai_complete(requestJSON *C.char) *C.char {
	response := map[string]interface{}{
		"content": "This is a stub response from the Go example AI provider.",
		"model":   "go-stub-model",
		"usage": map[string]int{
			"inputTokens":  0,
			"outputTokens": 0,
		},
	}
	data, _ := json.Marshal(response)
	return toCString(string(data))
}

//export karandash_free_string
func karandash_free_string(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
