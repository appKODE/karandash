package main

/*
#include <stdlib.h>
*/
import "C"
import "unsafe"

const (
	moduleID      = "go-example-ai-response-transformer"
	moduleName    = "Go Example AI Response Transformer Plugin"
	moduleVersion = "1.0.0"
	moduleType    = "AI_RESPONSE_TRANSFORMER"
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

// karandash_ai_transform_response receives an AI response JSON, the original
// request JSON, and a context JSON. It returns a (possibly modified) response
// JSON. Return the original responseJSON unchanged to pass it through.
//
//export karandash_ai_transform_response
func karandash_ai_transform_response(responseJSON *C.char, requestJSON *C.char, contextJSON *C.char) *C.char {
	return toCString(C.GoString(responseJSON))
}

//export karandash_free_string
func karandash_free_string(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
