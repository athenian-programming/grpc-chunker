package org.athenian.chunker

import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.stub.StreamObserver
import mu.KLogging
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32


class ChunkerClient internal constructor(private val channel: ManagedChannel) : Closeable {
  private val blockingStub: ChunkerGrpc.ChunkerBlockingStub = ChunkerGrpc.newBlockingStub(channel)
  private val asyncStub: ChunkerGrpc.ChunkerStub = ChunkerGrpc.newStub(channel)
  private val semaphore = Semaphore(1)

  constructor(host: String, port: Int = 50051) :
      this(ManagedChannelBuilder.forAddress(host, port).usePlaintext().build())

  fun uploadImage(filepath: String) {
    require(filepath.contains(".")) { "File name missing type suffix: $filepath" }

    val finishLatch = CountDownLatch(1)
    var totalByteCount = 0
    var totalChunkCount = 0
    val crcChecksum = CRC32()

    val responseObserver =
      object : StreamObserver<UploadImageResponse> {
        override fun onNext(response: UploadImageResponse) {
          logger.debug { "Response:\n$response" }

          check(response.checksum == crcChecksum.value)
          check(response.byteCount == totalByteCount)
          logger.info { "Checksum ${response.checksum}" }
          logger.info { "Byte counts $totalByteCount" }

          logger.info { "Releasing semaphore" }
          // Release semaphore to allow the next msg to be sent
          semaphore.release()
        }

        override fun onError(t: Throwable) {
          val status = Status.fromThrowable(t)
          logger.info { "uploadImage() failed: $status" }
          finishLatch.countDown()
        }

        override fun onCompleted() {
          finishLatch.countDown()
        }
      }

    val requestObserver = asyncStub.uploadImage(responseObserver)

    try {
      val file = File(filepath)
      if (!file.exists()) {
        logger.info { "File does not exist" }
        return
      }

      val filename = filepath.split("/").lastOrNull()!!

      val metaMsg =
        UploadImageRequest.newBuilder()
          .run {
            meta =
              MetaData.newBuilder()
                .run {
                  auth = "secret"
                  fileName = filename.split(".")[0]
                  imageFormat = filename.split(".")[1]
                  build()
                }
            build()
          }
      requestObserver.onNext(metaMsg)

      // Grab the semaphone to start
      semaphore.acquire()

      FileInputStream(file)
        .use { fis ->
          val bis = BufferedInputStream(fis)
          val buffer = ByteArray(bufferSize)
          var readByteCount: Int

          while (bis.read(buffer).also { bytesRead -> readByteCount = bytesRead } > 0) {
            totalByteCount += readByteCount
            totalChunkCount++
            crcChecksum.update(buffer, 0, buffer.size);
            val byteString: ByteString = ByteString.copyFrom(buffer)

            val req =
              UploadImageRequest.newBuilder()
                .let { builder ->
                  builder.data =
                    ChunkData.newBuilder()
                      .run {
                        chunkCount = totalChunkCount
                        chunkByteCount = readByteCount
                        chunkChecksum = crcChecksum.value
                        chunkBytes = byteString
                        build()
                      }
                  builder.build()
                }

            logger.info { "Writing chunk $totalChunkCount ($readByteCount bytes)" }
            requestObserver.onNext(req)

            logger.info { "Waiting for semaphore" }
            try {
              semaphore.tryAcquire(1, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
              e.printStackTrace()
              // Deal with timeout situation
              // For now, just return
              return
            }

            logger.info { "Acquired semaphore" }

            if (finishLatch.count == 0L) {
              // RPC completed or errored before we finished sending.
              // Sending further requests won't error, but they will just be thrown away.
              return
            }
          }
        }

      val summaryMsg =
        UploadImageRequest.newBuilder()
          .run {
            summary =
              SummaryData.newBuilder()
                .run {
                  summaryChunkCount = totalChunkCount
                  summaryByteCount = totalByteCount
                  summaryChecksum = crcChecksum.value
                  build()
                }
            build()
          }
      requestObserver.onNext(summaryMsg)

      // Mark the end of requests
      requestObserver.onCompleted()

    } catch (e: RuntimeException) {
      // Cancel RPC
      requestObserver.onError(e)
      throw e
    }

    logger.info { "Client streaming is complete" }

    // Receiving happens asynchronously
    try {
      finishLatch.await(10, TimeUnit.MINUTES)
    } catch (e: InterruptedException) {
      e.printStackTrace()
    }

    logger.info { "Client exiting" }

  }

  override fun close() {
    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
  }

  companion object : KLogging() {
    // See: https://github.com/grpc/grpc.github.io/issues/371
    const val bufferSize = 32 * 1024

    @JvmStatic
    fun main(args: Array<String>) {
      ChunkerClient("localhost")
        .use { client ->
          client.uploadImage("data/AutumScene.jpg")
        }
    }
  }
}