package org.athenian.chunker

import io.grpc.stub.StreamObserver
import mu.KLogging
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.CRC32


class ChunkerImpl : ChunkerGrpc.ChunkerImplBase() {

    override fun uploadImage(responseObserver: StreamObserver<UploadImageResponse>): StreamObserver<UploadImageRequest> =
        object : StreamObserver<UploadImageRequest> {
            var bos: BufferedOutputStream? = null
            var serverChunckCount = 0
            var serverByteCount = 0
            val serverChecksum = CRC32()

            override fun onNext(request: UploadImageRequest) {
                val ooc = request.testOneofCase
                when (ooc.name.toLowerCase()) {
                    "meta" -> {
                        logger.info { request.meta }
                        val fname = "data/received_file_${request.meta.fileName}.${request.meta.imageFormat}"
                        bos = BufferedOutputStream(FileOutputStream(fname))
                    }
                    "data" -> {
                        serverChunckCount++
                        val data = request.data.chunkBytes.toByteArray()
                        serverChecksum.update(data, 0, data.size)

                        logger.info { "Incremental checksums for ${request.data.chunkCount} are equal ${request.data.chunkChecksum == serverChecksum.value}" }

                        bos?.apply {
                            write(data, 0, request.data.chunkByteCount)
                            flush()
                        }

                        serverByteCount += request.data.chunkByteCount

                        val msg =
                            UploadImageResponse.newBuilder()
                                .run {
                                    status = 1
                                    chunkCount = serverChunckCount
                                    byteCount = serverByteCount
                                    checksum = serverChecksum.value
                                    build()
                                }

                        responseObserver.onNext(msg)

                        // Introduce a delay for testing
                        // Thread.sleep(Random.nextLong(1000))
                    }
                    "summary" -> {
                        val clientChunks = request.summary.summaryChunkCount
                        val clientBytes = request.summary.summaryByteCount
                        val clientChecksum = request.summary.summaryChecksum

                        // Check for checksum and length match
                        logger.info { "Final checksums ${clientChecksum} and ${serverChecksum.value} are equal ${clientChecksum == serverChecksum.value}" }
                        logger.info { "Final chunk counts $serverChunckCount and ${clientChunks} are equal ${serverChunckCount == clientChunks}" }
                        logger.info { "Final byte counts $serverByteCount and ${clientBytes} are equal ${serverByteCount == clientBytes}" }
                    }
                    else -> throw IOException("Invalid field name in uploadImage()")
                }
            }

            override fun onError(t: Throwable) {
                logger.info { "Encountered error in uploadImage()" }
                t.printStackTrace()
            }

            override fun onCompleted() {
                try {
                    bos?.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                } finally {
                    bos = null
                }

                responseObserver.onCompleted()
            }
        }

    companion object : KLogging()
}