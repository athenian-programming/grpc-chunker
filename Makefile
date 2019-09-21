default: stubs bin

all: clean stubs kbuild bin

clean:
	./gradlew clean

stubs:
	./gradlew generateProto

kbuild:
	./gradlew assemble build

bin:
	./gradlew install

client:
	build/install/grpc-chunker/bin/chunker-client

server:
	build/install/grpc-chunker/bin/chunker-server

versioncheck:
	./gradlew dependencyUpdates
