default: versioncheck

all: build bin

clean:
	./gradlew clean

stubs:
	./gradlew generateProto

build: clean stubs
	./gradlew assemble build -xtest

bin:
	./gradlew install

client:
	build/install/grpc-chunker/bin/chunker-client

server:
	build/install/grpc-chunker/bin/chunker-server

versioncheck:
	./gradlew dependencyUpdates

upgrade-wrapper:
	./gradlew wrapper --gradle-version=8.11.1 --distribution-type=bin
