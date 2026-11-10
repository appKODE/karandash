cmake_minimum_required(VERSION 3.10)
project(native-example-plugin C)

set(CMAKE_C_STANDARD 11)

# One shared header: the generated docs/native-sdk/karandash_plugin.h (override KARANDASH_SDK outside the repo).
set(KARANDASH_SDK "${CMAKE_CURRENT_SOURCE_DIR}/../../../docs/native-sdk" CACHE PATH "Directory holding karandash_plugin.h")
include_directories(${KARANDASH_SDK})

# ---------------------------------------------------------------------------
# Helper macro: configure shared library output name per platform
# ---------------------------------------------------------------------------
macro(configure_shared_lib target output_name)
    if(APPLE)
        set_target_properties(${target} PROPERTIES
            OUTPUT_NAME "${output_name}"
            SUFFIX ".dylib"
            PREFIX "lib"
        )
    elseif(UNIX)
        set_target_properties(${target} PROPERTIES
            OUTPUT_NAME "${output_name}"
            SUFFIX ".so"
            PREFIX "lib"
        )
    elseif(WIN32)
        set_target_properties(${target} PROPERTIES
            OUTPUT_NAME "${output_name}"
            SUFFIX ".dll"
            PREFIX ""
        )
    endif()
endmacro()

# ---------------------------------------------------------------------------
# Rule plugin
# ---------------------------------------------------------------------------
add_library(native-example-rule SHARED src/example-rule.c)
target_compile_options(native-example-rule PRIVATE -Wall -Wextra -O2)
configure_shared_lib(native-example-rule "native-example-rule")

# ---------------------------------------------------------------------------
# Reporter plugin
# ---------------------------------------------------------------------------
add_library(native-example-reporter SHARED src/example-reporter.c)
target_compile_options(native-example-reporter PRIVATE -Wall -Wextra -O2)
configure_shared_lib(native-example-reporter "native-example-reporter")

# ---------------------------------------------------------------------------
# MR Provider plugin
# ---------------------------------------------------------------------------
add_library(native-example-provider SHARED src/example-provider.c)
target_compile_options(native-example-provider PRIVATE -Wall -Wextra -O2)
configure_shared_lib(native-example-provider "native-example-provider")

# ---------------------------------------------------------------------------
# MR Mutator plugin
# ---------------------------------------------------------------------------
add_library(native-example-mutator SHARED src/example-mutator.c)
target_compile_options(native-example-mutator PRIVATE -Wall -Wextra -O2)
configure_shared_lib(native-example-mutator "native-example-mutator")

# ---------------------------------------------------------------------------
# Comment Poster plugin
# ---------------------------------------------------------------------------
add_library(native-example-comment-poster SHARED src/example-comment-poster.c)
target_compile_options(native-example-comment-poster PRIVATE -Wall -Wextra -O2)
configure_shared_lib(native-example-comment-poster "native-example-comment-poster")

# ---------------------------------------------------------------------------
# CLI Command plugin
# ---------------------------------------------------------------------------
add_library(native-example-cli-command SHARED src/example-cli-command.c)
target_compile_options(native-example-cli-command PRIVATE -Wall -Wextra -O2)
configure_shared_lib(native-example-cli-command "native-example-cli-command")

# ---------------------------------------------------------------------------
# Config Format plugin
# ---------------------------------------------------------------------------
add_library(native-example-config-format SHARED src/example-config-format.c)
target_compile_options(native-example-config-format PRIVATE -Wall -Wextra -O2)
configure_shared_lib(native-example-config-format "native-example-config-format")

# ---------------------------------------------------------------------------
# Profile plugin
# ---------------------------------------------------------------------------
add_library(native-example-profile SHARED src/example-profile.c)
target_compile_options(native-example-profile PRIVATE -Wall -Wextra -O2)
configure_shared_lib(native-example-profile "native-example-profile")

# ---------------------------------------------------------------------------
# AI Provider plugin
# ---------------------------------------------------------------------------
add_library(native-example-ai-provider SHARED src/example-ai-provider.c)
target_compile_options(native-example-ai-provider PRIVATE -Wall -Wextra -O2)
configure_shared_lib(native-example-ai-provider "native-example-ai-provider")

# ---------------------------------------------------------------------------
# AI Interceptor plugin
# ---------------------------------------------------------------------------
add_library(native-example-ai-interceptor SHARED src/example-ai-interceptor.c)
target_compile_options(native-example-ai-interceptor PRIVATE -Wall -Wextra -O2)
configure_shared_lib(native-example-ai-interceptor "native-example-ai-interceptor")

# ---------------------------------------------------------------------------
# AI Analyzer plugin
# ---------------------------------------------------------------------------
add_library(native-example-ai-analyzer SHARED src/example-ai-analyzer.c)
target_compile_options(native-example-ai-analyzer PRIVATE -Wall -Wextra -O2)
configure_shared_lib(native-example-ai-analyzer "native-example-ai-analyzer")

# ---------------------------------------------------------------------------
# AI Prompt Processor plugin
# ---------------------------------------------------------------------------
add_library(native-example-ai-prompt-processor SHARED src/example-ai-prompt-processor.c)
target_compile_options(native-example-ai-prompt-processor PRIVATE -Wall -Wextra -O2)
configure_shared_lib(native-example-ai-prompt-processor "native-example-ai-prompt-processor")

# ---------------------------------------------------------------------------
# AI Response Transformer plugin
# ---------------------------------------------------------------------------
add_library(native-example-ai-response-transformer SHARED src/example-ai-response-transformer.c)
target_compile_options(native-example-ai-response-transformer PRIVATE -Wall -Wextra -O2)
configure_shared_lib(native-example-ai-response-transformer "native-example-ai-response-transformer")

# ---------------------------------------------------------------------------
# AI Context Enricher plugin
# ---------------------------------------------------------------------------
add_library(native-example-ai-context-enricher SHARED src/example-ai-context-enricher.c)
target_compile_options(native-example-ai-context-enricher PRIVATE -Wall -Wextra -O2)
configure_shared_lib(native-example-ai-context-enricher "native-example-ai-context-enricher")

# ---------------------------------------------------------------------------
# AI Redactor plugin
# ---------------------------------------------------------------------------
add_library(native-example-ai-redactor SHARED src/example-ai-redactor.c)
target_compile_options(native-example-ai-redactor PRIVATE -Wall -Wextra -O2)
configure_shared_lib(native-example-ai-redactor "native-example-ai-redactor")

# ---------------------------------------------------------------------------
# AI Token Allocator plugin
# ---------------------------------------------------------------------------
add_library(native-example-ai-token-allocator SHARED src/example-ai-token-allocator.c)
target_compile_options(native-example-ai-token-allocator PRIVATE -Wall -Wextra -O2)
configure_shared_lib(native-example-ai-token-allocator "native-example-ai-token-allocator")

# ---------------------------------------------------------------------------
# Installation target (optional)
# ---------------------------------------------------------------------------
install(TARGETS
    native-example-rule
    native-example-reporter
    native-example-provider
    native-example-mutator
    native-example-comment-poster
    native-example-cli-command
    native-example-config-format
    native-example-profile
    native-example-ai-provider
    native-example-ai-interceptor
    native-example-ai-analyzer
    native-example-ai-prompt-processor
    native-example-ai-response-transformer
    native-example-ai-context-enricher
    native-example-ai-redactor
    native-example-ai-token-allocator
    LIBRARY DESTINATION lib
    RUNTIME DESTINATION bin
)
