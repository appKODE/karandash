// One native library, several module types: karandash_describe lists every
// type it serves and the host registers one part per type under the same id.
// karandash_module_type is the primary (first) one.
package main

/*
#include <stdlib.h>
*/
import "C"
import (
	"encoding/json"
	"fmt"
	"unsafe"
)

const (
	moduleID      = "go-composite-example"
	moduleName    = "Go Composite Plugin Example"
	moduleVersion = "1.0.0"
	moduleType    = "RULE"
	ruleID        = "go-composite-title"
)

func toCString(s string) *C.char { return C.CString(s) }

func toJSON(v interface{}) *C.char {
	data, _ := json.Marshal(v)
	return toCString(string(data))
}

//export karandash_abi_version
func karandash_abi_version() C.int { return 1 }

//export karandash_module_id
func karandash_module_id() *C.char { return toCString(moduleID) }

//export karandash_module_name
func karandash_module_name() *C.char { return toCString(moduleName) }

//export karandash_module_version
func karandash_module_version() *C.char { return toCString(moduleVersion) }

//export karandash_module_type
func karandash_module_type() *C.char { return toCString(moduleType) }

//export karandash_describe
func karandash_describe() *C.char {
	return toJSON(map[string]interface{}{
		"id":         moduleID,
		"types":      []string{"RULE", "REPORTER", "ENRICHMENT_PROVIDER"},
		"properties": map[string]interface{}{},
	})
}

//export karandash_initialize
func karandash_initialize(contextJSON *C.char) C.int { return 1 }

//export karandash_shutdown
func karandash_shutdown() {}

// RULE

//export karandash_contributed_rules
func karandash_contributed_rules() *C.char { return toJSON([]string{ruleID}) }

//export karandash_validate
func karandash_validate(rule *C.char, mrJSON *C.char, configJSON *C.char) *C.char {
	var mr struct {
		Title string `json:"title"`
	}
	if C.GoString(rule) != ruleID || json.Unmarshal([]byte(C.GoString(mrJSON)), &mr) != nil || mr.Title != "" {
		return toCString("[]")
	}
	return toJSON([]map[string]interface{}{
		{"rule": ruleID, "message": "Title is empty", "severity": "ERROR"},
	})
}

// REPORTER

//export karandash_format_id
func karandash_format_id() *C.char { return toCString("go-composite-report") }

//export karandash_report
func karandash_report(findingsJSON *C.char, passed C.int, configJSON *C.char) *C.char {
	var findings []interface{}
	_ = json.Unmarshal([]byte(C.GoString(findingsJSON)), &findings)
	return toCString(fmt.Sprintf("GO-COMPOSITE-REPORT passed=%t findings=%d", passed != 0, len(findings)))
}

// ENRICHMENT_PROVIDER

//export karandash_enrichment_ids
func karandash_enrichment_ids() *C.char { return toJSON([]string{"go-composite-title-length"}) }

//export karandash_enrichment_enrich
func karandash_enrichment_enrich(mrJSON *C.char, configJSON *C.char) *C.char {
	var mr struct {
		Title string `json:"title"`
	}
	_ = json.Unmarshal([]byte(C.GoString(mrJSON)), &mr)
	return toJSON(map[string]interface{}{"go-composite-title-length": len(mr.Title)})
}

//export karandash_free_string
func karandash_free_string(ptr *C.char) {
	if ptr != nil {
		C.free(unsafe.Pointer(ptr))
	}
}

func main() {}
