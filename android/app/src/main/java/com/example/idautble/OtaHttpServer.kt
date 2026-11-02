package com.example.idautble

import fi.iki.elonen.NanoHTTPD
import java.io.InputStream

class HttpFileServer(
    port: Int,
    private val fileSize: Long,
    private val inputStreamProvider: () -> InputStream?,
) : NanoHTTPD(port), OtaServer {
    override fun startServing() = start(SOCKET_READ_TIMEOUT, false)

    override fun stopServing() = stop()

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/firmware.bin") {
            val inputStream =
                inputStreamProvider()
                    ?: return newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        "text/plain",
                        "File error",
                    )

            // Fixed-length response => Content-Length correcto
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/octet-stream",
                inputStream,
                fileSize,
            )
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
    }
}
