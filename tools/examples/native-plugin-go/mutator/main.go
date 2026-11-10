package main

/*
#include <stdlib.h>
*/
import "C"
import "unsafe"

const (
	moduleID      = "go-example-mutator"
	moduleName    = "Go Example Mutator"
	moduleVersion = "1.0.0"
	moduleType    = "PLATFORM_SERVICE"
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

//export karandash_add_labels
func karandash_add_labels(projectID *C.char, mrID *C.char, labelsJSON *C.char) C.int {
	return 1
}

//export karandash_remove_labels
func karandash_remove_labels(projectID *C.char, mrID *C.char, labelsJSON *C.char) C.int {
	return 1
}

//export karandash_set_draft
func karandash_set_draft(projectID *C.char, mrID *C.char, draft C.int) C.int {
	return 1
}

//export karandash_update_title
func karandash_update_title(projectID *C.char, mrID *C.char, title *C.char) C.int {
	return 1
}

//export karandash_update_description
func karandash_update_description(projectID *C.char, mrID *C.char, description *C.char) C.int {
	return 1
}

//export karandash_free_string
func karandash_free_string(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
