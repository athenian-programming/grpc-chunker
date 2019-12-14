package org.athenian.chunker

import io.grpc.Server
import io.grpc.ServerBuilder
import mu.KLogging
import java.io.IOException

class ChunkerServer {

  private var server: Server? = null

  @Throws(IOException::class)
  private fun start() {
    server = ServerBuilder.forPort(port).addService(ChunkerImpl()).build().start()
    logger.info { "Server started, listening on $port" }
    Runtime.getRuntime().addShutdownHook(
      Thread {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down")
        server?.shutdown()
        System.err.println("*** server shut down")
      })
  }

  companion object : KLogging() {

    const val port = 50051

    @Throws(IOException::class, InterruptedException::class)
    @JvmStatic
    fun main(args: Array<String>) {
      ChunkerServer()
        .apply {
          start()
          server?.awaitTermination()
        }
    }
  }
}
