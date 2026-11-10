package main

/*
#include <stdlib.h>
*/
import "C"
import "unsafe"

const (
	moduleID      = "go-example-provider"
	moduleName    = "Go Example Provider"
	moduleVersion = "1.0.0"
	moduleType    = "PLATFORM_SERVICE"

	mockMRJSON = `{"title":"Mock MR from Go","description":"Example","state":"opened","author":{"username":"mock"},"labels":[],"commits":[],"changedFiles":[],"comments":[],"approvals":[]}`
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

//export karandash_fetch_merge_request
func karandash_fetch_merge_request(projectID *C.char, mrID *C.char) *C.char {
	return toCString(mockMRJSON)
}

//export karandash_fetch_merge_request_by_url
func karandash_fetch_merge_request_by_url(url *C.char) *C.char {
	return toCString(mockMRJSON)
}

//export karandash_free_string
func karandash_free_string(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
