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
	moduleID      = "go-example-config-format"
	moduleName    = "Go Example Config Format Plugin"
	moduleVersion = "1.0.0"
	moduleType    = "CONFIG_FORMAT"
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

// karandash_config_format_id returns the unique identifier for this config format.
//
//export karandash_config_format_id
func karandash_config_format_id() *C.char {
	return toCString("go-example")
}

// karandash_config_format_extensions returns a JSON array of file extensions
// this format handles (e.g. [".goconf"]).
//
//export karandash_config_format_extensions
func karandash_config_format_extensions() *C.char {
	exts := []string{".goconf"}
	data, _ := json.Marshal(exts)
	return toCString(string(data))
}

// karandash_config_parse parses config content and returns a JSON map of
// the parsed configuration.
//
//export karandash_config_parse
func karandash_config_parse(content *C.char) *C.char {
	result := map[string]interface{}{
		"parsed": true,
		"source": C.GoString(content),
	}
	data, _ := json.Marshal(result)
	return toCString(string(data))
}

// karandash_config_parse_extends parses an extends field from config content
// and returns a JSON string with the resolved base config path or URL.
//
//export karandash_config_parse_extends
func karandash_config_parse_extends(content *C.char) *C.char {
	return toCString("")
}

//export karandash_free_string
func karandash_free_string(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
