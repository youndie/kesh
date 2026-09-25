package io.github.youndie.kesh.server.http

/** One answer of the HTTP port. */
class HttpResponse(
    val status: Int,
    val body: String,
    val contentType: String = "text/plain; charset=utf-8",
)
