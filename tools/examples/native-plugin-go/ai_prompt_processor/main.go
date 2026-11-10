package main

/*
#include <stdlib.h>
*/
import "C"
import "unsafe"

const (
	moduleID      = "go-example-ai-prompt-processor"
	moduleName    = "Go Example AI Prompt Processor Plugin"
	moduleVersion = "1.0.0"
	moduleType    = "AI_PROMPT_PROCESSOR"
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

// karandash_ai_process_prompt receives a user prompt string and a context JSON
// and returns the (possibly modified) prompt string. Return the original prompt
// unchanged to pass it through unmodified.
//
//export karandash_ai_process_prompt
func karandash_ai_process_prompt(prompt *C.char, contextJSON *C.char) *C.char {
	return toCString(C.GoString(prompt))
}

// karandash_ai_process_system_prompt receives a system prompt string and a
// context JSON and returns the (possibly modified) system prompt string.
//
//export karandash_ai_process_system_prompt
func karandash_ai_process_system_prompt(systemPrompt *C.char, contextJSON *C.char) *C.char {
	return toCString(C.GoString(systemPrompt))
}

//export karandash_free_string
func karandash_free_string(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
