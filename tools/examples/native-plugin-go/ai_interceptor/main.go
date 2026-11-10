package main

/*
#include <stdlib.h>
*/
import "C"
import "unsafe"

const (
	moduleID      = "go-example-ai-interceptor"
	moduleName    = "Go Example AI Interceptor Plugin"
	moduleVersion = "1.0.0"
	moduleType    = "AI_INTERCEPTOR"
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

// karandash_ai_intercept receives an AI request JSON and a context JSON and
// returns a (possibly modified) request JSON. Return the original requestJSON
// unchanged to pass it through unmodified.
//
//export karandash_ai_intercept
func karandash_ai_intercept(requestJSON *C.char, contextJSON *C.char) *C.char {
	// Pass through unchanged - a real interceptor would inspect/modify the request.
	return toCString(C.GoString(requestJSON))
}

//export karandash_free_string
func karandash_free_string(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
