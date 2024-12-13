package org.athenian.chunker

import com.google.protobuf.Empty
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.stub.StreamObserver
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.CRC32

class ChunkerImpl : ChunkerGrpc.ChunkerImplBase() {
  override fun uploadImage(responseObserver: StreamObserver<Empty>): StreamObserver<UploadImageRequest> =
    object : StreamObserver<UploadImageRequest> {
      lateinit var bos: BufferedOutputStream
      var totalChunkCount = 0
      var totalByteCount = 0
      val crcChecksum = CRC32()

      override fun onNext(request: UploadImageRequest) {
        val ooc = request.testOneofCase
        when (ooc.name.lowercase()) {
          "meta" -> {
            logger.info { request.meta }
            val fname = "data/received_file_${request.meta.fileName}.${request.meta.imageFormat}"
            bos = BufferedOutputStream(FileOutputStream(fname))
          }

          "data" -> {
            totalChunkCount++
            totalByteCount += request.data.chunkByteCount

            val data = request.data.chunkBytes.toByteArray()
            crcChecksum.update(data, 0, data.size)

            check(request.data.chunkChecksum == crcChecksum.value)
            logger.info { "Chunk: ${request.data.chunkCount}" }

            bos.apply {
              write(data, 0, request.data.chunkByteCount)
              flush()
            }
          }

          "summary" -> {
            request.summary.apply {
              check(crcChecksum.value == summaryChecksum)
              check(totalChunkCount == summaryChunkCount)
              check(totalByteCount == summaryByteCount)
              logger.info { "Final checksum/chunkCount/byteCount ${crcChecksum.value}/$totalChunkCount/$totalByteCount" }
            }
          }

          else -> throw IllegalStateException("Invalid field name in uploadImage(): ${ooc.name.lowercase()}")
        }
      }

      override fun onError(t: Throwable) {
        logger.info { "Encountered error in uploadImage()" }
        t.printStackTrace()
      }

      override fun onCompleted() {
        try {
          bos.close()
        } catch (e: IOException) {
          e.printStackTrace()
        }

        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted()
      }
    }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}