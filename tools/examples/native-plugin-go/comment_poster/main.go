package main

/*
#include <stdlib.h>
*/
import "C"
import "unsafe"

const (
	moduleID      = "go-example-comment-poster"
	moduleName    = "Go Example Comment Poster"
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

//export karandash_post_comment
func karandash_post_comment(projectID *C.char, mrID *C.char, body *C.char) *C.char {
	return toCString("comment-123")
}

//export karandash_update_comment
func karandash_update_comment(projectID *C.char, mrID *C.char, commentID *C.char, body *C.char) C.int {
	return 1
}

//export karandash_find_existing_comment
func karandash_find_existing_comment(projectID *C.char, mrID *C.char, marker *C.char) *C.char {
	return nil
}

//export karandash_post_inline_comment
func karandash_post_inline_comment(projectID *C.char, mrID *C.char, body *C.char, filePath *C.char, line C.int, diffRefsJSON *C.char) *C.char {
	return toCString("comment-124")
}

//export karandash_free_string
func karandash_free_string(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
