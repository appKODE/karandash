UNAME_S := $(shell uname -s)
CXX      = c++
# One shared header: the generated docs/native-sdk/karandash_plugin.h (override KARANDASH_SDK outside the repo).
KARANDASH_SDK ?= ../../../docs/native-sdk
CXXFLAGS = -std=c++17 -Wall -Wextra -fPIC -O2 -I$(KARANDASH_SDK)

ifeq ($(UNAME_S),Darwin)
    TARGET  = libcpp-standalone.dylib
    LDFLAGS = -dynamiclib
else ifeq ($(UNAME_S),Linux)
    TARGET  = libcpp-standalone.so
    LDFLAGS = -shared
else
    TARGET  = cpp-standalone.dll
    LDFLAGS = -shared
endif

all: $(TARGET)

$(TARGET): plugin.cpp $(KARANDASH_SDK)/karandash_plugin.h
	$(CXX) $(CXXFLAGS) $(LDFLAGS) -o $@ plugin.cpp

clean:
	rm -f libcpp-standalone.dylib libcpp-standalone.so cpp-standalone.dll

.PHONY: all clean
