default: stubs bin

all: clean stubs compile bin

clean:
	./gradlew clean

stubs:
	./gradlew generateProto

compile: build

build:
	./gradlew assemble build -xtest

bin:
	./gradlew install

client:
	build/install/grpc-chunker/bin/chunker-client

server:
	build/install/grpc-chunker/bin/chunker-server

versioncheck:
	./gradlew dependencyUpdates
