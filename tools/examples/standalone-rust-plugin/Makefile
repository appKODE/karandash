UNAME_S := $(shell uname -s)

ifeq ($(UNAME_S),Darwin)
    TARGET     = librust-standalone.dylib
    CARGO_OUT  = target/release/libkarandash_standalone_rust.dylib
else ifeq ($(UNAME_S),Linux)
    TARGET     = librust-standalone.so
    CARGO_OUT  = target/release/libkarandash_standalone_rust.so
else
    TARGET     = rust-standalone.dll
    CARGO_OUT  = target/release/karandash_standalone_rust.dll
endif

all: $(TARGET)

$(TARGET): src/lib.rs src/models.rs Cargo.toml
	cargo build --release
	cp $(CARGO_OUT) $(TARGET)

clean:
	cargo clean
	rm -f librust-standalone.dylib librust-standalone.so rust-standalone.dll

.PHONY: all clean
