package main

/*
#include <stdlib.h>
*/
import "C"
import "unsafe"

const (
	moduleID      = "go-example-ai-context-enricher"
	moduleName    = "Go Example AI Context Enricher Plugin"
	moduleVersion = "1.0.0"
	moduleType    = "AI_CONTEXT_ENRICHER"
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

// karandash_ai_enrich_context receives a merge request JSON and an existing
// context JSON. It returns an enriched context JSON with additional fields
// added by this plugin.
//
//export karandash_ai_enrich_context
func karandash_ai_enrich_context(mrJSON *C.char, contextJSON *C.char) *C.char {
	// Pass through unchanged - a real enricher would append extra context fields.
	return toCString(C.GoString(contextJSON))
}

//export karandash_free_string
func karandash_free_string(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
