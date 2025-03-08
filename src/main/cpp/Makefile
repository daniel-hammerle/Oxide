# Compiler and flags
CXX = g++
CXXFLAGS = -fPIC -shared
INCLUDES = -I./binaryen/src -I/usr/lib/jvm/java-18-openjdk-amd64/include/ -I/usr/lib/jvm/java-18-openjdk-amd64/include/linux
LIBS = -lbinaryen
OUTPUT = ../../../build/libs/bindings.so
SRC = bindings.cpp

# The target rule
all: $(OUTPUT)

# Rule for creating the shared library
$(OUTPUT): $(SRC)
	$(CXX) $(CXXFLAGS) $(SRC) $(INCLUDES) $(LIBS) -o $(OUTPUT)

# Clean rule to remove generated files
clean:
	rm -f $(OUTPUT)
