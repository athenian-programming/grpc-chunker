# Chunking Large Messages with gRPC

This implementation was inspired by [this post](https://jbrandhorst.com/post/grpc-binary-blob-stream/).

The server performs checksums on the chunks and sends a handshake to the client after each chunk is read.