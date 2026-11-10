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
	moduleID      = "go-example-cli-command"
	moduleName    = "Go Example CLI Command Plugin"
	moduleVersion = "1.0.0"
	moduleType    = "CLI_COMMAND"
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

// karandash_cli_commands returns a JSON array of CLI command descriptors
// contributed by this plugin.
//
//export karandash_cli_commands
func karandash_cli_commands() *C.char {
	commands := []map[string]interface{}{
		{
			"name":        "go-example",
			"description": "An example CLI command contributed by the Go plugin",
			"args":        []string{},
		},
	}
	data, _ := json.Marshal(commands)
	return toCString(string(data))
}

//export karandash_free_string
func karandash_free_string(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
