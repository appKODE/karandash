package main

/*
#include <stdlib.h>
*/
import "C"
import "unsafe"

const (
	moduleID      = "go-example-ai-redactor"
	moduleName    = "Go Example AI Redactor Plugin"
	moduleVersion = "1.0.0"
	moduleType    = "AI_REDACTOR"
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

// karandash_ai_redact receives a text string and a context JSON and returns
// the redacted text with sensitive information removed or masked. Return the
// original text unchanged if no redaction is needed.
//
//export karandash_ai_redact
func karandash_ai_redact(text *C.char, contextJSON *C.char) *C.char {
	// Pass through unchanged - a real redactor would scrub secrets from the text.
	return toCString(C.GoString(text))
}

//export karandash_free_string
func karandash_free_string(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
