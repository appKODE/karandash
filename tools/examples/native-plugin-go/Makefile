.PHONY: all clean macos-arm64 linux-x64 linux-arm64

all: macos-arm64

macos-arm64:
	CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -buildmode=c-shared -o libgo-example.dylib .
	cd provider && CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-provider.dylib .
	cd mutator && CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-mutator.dylib .
	cd comment_poster && CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-comment-poster.dylib .
	cd cli_command && CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-cli-command.dylib .
	cd config_format && CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-config-format.dylib .
	cd profile && CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-profile.dylib .
	cd ai_provider && CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-ai-provider.dylib .
	cd ai_interceptor && CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-ai-interceptor.dylib .
	cd ai_analyzer && CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-ai-analyzer.dylib .
	cd composite && CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-composite.dylib .
	cd ai_prompt_processor && CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-ai-prompt-processor.dylib .
	cd ai_response_transformer && CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-ai-response-transformer.dylib .
	cd ai_context_enricher && CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-ai-context-enricher.dylib .
	cd ai_redactor && CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-ai-redactor.dylib .
	cd ai_token_allocator && CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-ai-token-allocator.dylib .
	cd script && CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-script.dylib .
	cd reporter && CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-reporter.dylib .

linux-x64:
	CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared -o libgo-example.so .
	cd provider && CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared -o libgo-example-provider.so .
	cd mutator && CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared -o libgo-example-mutator.so .
	cd comment_poster && CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared -o libgo-example-comment-poster.so .
	cd cli_command && CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared -o libgo-example-cli-command.so .
	cd config_format && CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared -o libgo-example-config-format.so .
	cd profile && CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared -o libgo-example-profile.so .
	cd ai_provider && CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared -o libgo-example-ai-provider.so .
	cd ai_interceptor && CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared -o libgo-example-ai-interceptor.so .
	cd ai_analyzer && CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared -o libgo-example-ai-analyzer.so .
	cd composite && CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared -o libgo-example-composite.so .
	cd ai_prompt_processor && CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared -o libgo-example-ai-prompt-processor.so .
	cd ai_response_transformer && CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared -o libgo-example-ai-response-transformer.so .
	cd ai_context_enricher && CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared -o libgo-example-ai-context-enricher.so .
	cd ai_redactor && CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared -o libgo-example-ai-redactor.so .
	cd ai_token_allocator && CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared -o libgo-example-ai-token-allocator.so .
	cd script && CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared -o libgo-example-script.so .
	cd reporter && CGO_ENABLED=1 GOOS=linux GOARCH=amd64 go build -buildmode=c-shared -o libgo-example-reporter.so .

linux-arm64:
	CGO_ENABLED=1 GOOS=linux GOARCH=arm64 go build -buildmode=c-shared -o libgo-example.so .
	cd provider && CGO_ENABLED=1 GOOS=linux GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-provider.so .
	cd mutator && CGO_ENABLED=1 GOOS=linux GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-mutator.so .
	cd comment_poster && CGO_ENABLED=1 GOOS=linux GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-comment-poster.so .
	cd cli_command && CGO_ENABLED=1 GOOS=linux GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-cli-command.so .
	cd config_format && CGO_ENABLED=1 GOOS=linux GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-config-format.so .
	cd profile && CGO_ENABLED=1 GOOS=linux GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-profile.so .
	cd ai_provider && CGO_ENABLED=1 GOOS=linux GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-ai-provider.so .
	cd ai_interceptor && CGO_ENABLED=1 GOOS=linux GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-ai-interceptor.so .
	cd ai_analyzer && CGO_ENABLED=1 GOOS=linux GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-ai-analyzer.so .
	cd composite && CGO_ENABLED=1 GOOS=linux GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-composite.so .
	cd ai_prompt_processor && CGO_ENABLED=1 GOOS=linux GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-ai-prompt-processor.so .
	cd ai_response_transformer && CGO_ENABLED=1 GOOS=linux GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-ai-response-transformer.so .
	cd ai_context_enricher && CGO_ENABLED=1 GOOS=linux GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-ai-context-enricher.so .
	cd ai_redactor && CGO_ENABLED=1 GOOS=linux GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-ai-redactor.so .
	cd ai_token_allocator && CGO_ENABLED=1 GOOS=linux GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-ai-token-allocator.so .
	cd script && CGO_ENABLED=1 GOOS=linux GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-script.so .
	cd reporter && CGO_ENABLED=1 GOOS=linux GOARCH=arm64 go build -buildmode=c-shared -o libgo-example-reporter.so .

clean:
	rm -f libgo-example.dylib libgo-example.so libgo-example.h
	rm -f provider/libgo-example-provider.dylib provider/libgo-example-provider.so provider/libgo-example-provider.h
	rm -f mutator/libgo-example-mutator.dylib mutator/libgo-example-mutator.so mutator/libgo-example-mutator.h
	rm -f comment_poster/libgo-example-comment-poster.dylib comment_poster/libgo-example-comment-poster.so comment_poster/libgo-example-comment-poster.h
	rm -f cli_command/libgo-example-cli-command.dylib cli_command/libgo-example-cli-command.so cli_command/libgo-example-cli-command.h
	rm -f config_format/libgo-example-config-format.dylib config_format/libgo-example-config-format.so config_format/libgo-example-config-format.h
	rm -f profile/libgo-example-profile.dylib profile/libgo-example-profile.so profile/libgo-example-profile.h
	rm -f ai_provider/libgo-example-ai-provider.dylib ai_provider/libgo-example-ai-provider.so ai_provider/libgo-example-ai-provider.h
	rm -f ai_interceptor/libgo-example-ai-interceptor.dylib ai_interceptor/libgo-example-ai-interceptor.so ai_interceptor/libgo-example-ai-interceptor.h
	rm -f ai_analyzer/libgo-example-ai-analyzer.dylib ai_analyzer/libgo-example-ai-analyzer.so ai_analyzer/libgo-example-ai-analyzer.h
	rm -f composite/libgo-example-composite.dylib composite/libgo-example-composite.so composite/libgo-example-composite.h
	rm -f ai_prompt_processor/libgo-example-ai-prompt-processor.dylib ai_prompt_processor/libgo-example-ai-prompt-processor.so ai_prompt_processor/libgo-example-ai-prompt-processor.h
	rm -f ai_response_transformer/libgo-example-ai-response-transformer.dylib ai_response_transformer/libgo-example-ai-response-transformer.so ai_response_transformer/libgo-example-ai-response-transformer.h
	rm -f ai_context_enricher/libgo-example-ai-context-enricher.dylib ai_context_enricher/libgo-example-ai-context-enricher.so ai_context_enricher/libgo-example-ai-context-enricher.h
	rm -f ai_redactor/libgo-example-ai-redactor.dylib ai_redactor/libgo-example-ai-redactor.so ai_redactor/libgo-example-ai-redactor.h
	rm -f ai_token_allocator/libgo-example-ai-token-allocator.dylib ai_token_allocator/libgo-example-ai-token-allocator.so ai_token_allocator/libgo-example-ai-token-allocator.h
	rm -f script/libgo-example-script.dylib script/libgo-example-script.so script/libgo-example-script.h
	rm -f reporter/libgo-example-reporter.dylib reporter/libgo-example-reporter.so reporter/libgo-example-reporter.h
