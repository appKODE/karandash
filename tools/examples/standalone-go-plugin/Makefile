UNAME_S := $(shell uname -s)

ifeq ($(UNAME_S),Darwin)
    TARGET = libgo-standalone.dylib
else ifeq ($(UNAME_S),Linux)
    TARGET = libgo-standalone.so
else
    TARGET = go-standalone.dll
endif

all: $(TARGET)

$(TARGET): main.go models.go go.mod
	CGO_ENABLED=1 go build -buildmode=c-shared -o $(TARGET) .

clean:
	rm -f libgo-standalone.dylib libgo-standalone.so go-standalone.dll libgo-standalone.h

.PHONY: all clean
