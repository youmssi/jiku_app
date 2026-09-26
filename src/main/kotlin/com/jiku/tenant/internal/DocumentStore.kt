package com.jiku.tenant.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Private storage for verification documents (référentiel métier §9). Any
 * S3-compatible store works (Cloudflare R2 in production, MinIO locally); the
 * bucket is never public, and a document is read only through a link that
 * expires after [urlTtl].
 */
@ConfigurationProperties(prefix = "storage.documents")
data class DocumentStorageProperties(
    /** Base URL of the store, e.g. `https://<account>.r2.cloudflarestorage.com`. Empty: storage is off. */
    val endpoint: String = "",
    val region: String = "auto",
    val bucket: String = "",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    /** Lifetime of a link that lets the Jikū team read one document. */
    val urlTtl: Duration = Duration.ofMinutes(5),
) {
    val configured: Boolean
        get() = endpoint.isNotBlank() && bucket.isNotBlank() && accessKeyId.isNotBlank() && secretAccessKey.isNotBlank()
}

/** The document store is not configured in this environment, or did not answer. */
class DocumentStorageUnavailableException(
    message: String,
) : ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message)

/**
 * A minimal S3 client signed with AWS Signature Version 4: enough to put,
 * delete and link to one object, without pulling a full SDK into a small
 * instance. Objects are addressed path-style (`/<bucket>/<key>`), which every
 * S3-compatible store accepts.
 */
@Component
class DocumentStore(
    private val properties: DocumentStorageProperties,
) {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun put(
        key: String,
        bytes: ByteArray,
        contentType: String,
    ) {
        val response = send("PUT", key, bytes, mapOf("content-type" to contentType))
        if (response.statusCode() !in 200..299) {
            throw DocumentStorageUnavailableException("The document store refused the upload (${response.statusCode()})")
        }
    }

    fun delete(key: String) {
        val response = send("DELETE", key, ByteArray(0), emptyMap())
        if (response.statusCode() !in 200..299 && response.statusCode() != 404) {
            throw DocumentStorageUnavailableException("The document store refused the deletion (${response.statusCode()})")
        }
    }

    /** A link that lets its holder read [key] for [DocumentStorageProperties.urlTtl]. */
    fun temporaryLink(
        key: String,
        now: Instant = Instant.now(),
    ): String {
        requireConfigured()
        val stamp = Stamp(now)
        val uri = objectUri(key)
        val query =
            sortedMapOf(
                "X-Amz-Algorithm" to ALGORITHM,
                "X-Amz-Credential" to "${properties.accessKeyId}/${stamp.scope(properties.region)}",
                "X-Amz-Date" to stamp.dateTime,
                "X-Amz-Expires" to properties.urlTtl.seconds.toString(),
                "X-Amz-SignedHeaders" to "host",
            )
        val canonicalQuery = query.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val canonicalRequest =
            listOf("GET", uri.rawPath, canonicalQuery, "host:${host(uri)}\n", "host", UNSIGNED_PAYLOAD).joinToString("\n")
        val signature = sign(stamp, canonicalRequest)
        return "${uri.scheme}://${uri.rawAuthority}${uri.rawPath}?$canonicalQuery&X-Amz-Signature=$signature"
    }

    private fun send(
        method: String,
        key: String,
        body: ByteArray,
        extraHeaders: Map<String, String>,
    ): HttpResponse<String> {
        requireConfigured()
        val stamp = Stamp(Instant.now())
        val uri = objectUri(key)
        val payloadHash = hex(sha256(body))
        val headers =
            (extraHeaders + mapOf("host" to host(uri), "x-amz-content-sha256" to payloadHash, "x-amz-date" to stamp.dateTime))
                .toSortedMap()
        val signedHeaders = headers.keys.joinToString(";")
        val canonicalHeaders = headers.entries.joinToString("") { "${it.key}:${it.value.trim()}\n" }
        val canonicalRequest = listOf(method, uri.rawPath, "", canonicalHeaders, signedHeaders, payloadHash).joinToString("\n")
        val authorization =
            "$ALGORITHM Credential=${properties.accessKeyId}/${stamp.scope(properties.region)}, " +
                "SignedHeaders=$signedHeaders, Signature=${sign(stamp, canonicalRequest)}"
        val request =
            HttpRequest
                .newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                .apply {
                    headers.filterKeys { it != "host" }.forEach { (name, value) -> header(name, value) }
                    header("Authorization", authorization)
                }.build()
        return try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (ex: java.io.IOException) {
            throw DocumentStorageUnavailableException("The document store cannot be reached")
        }
    }

    private fun sign(
        stamp: Stamp,
        canonicalRequest: String,
    ): String {
        val stringToSign =
            listOf(ALGORITHM, stamp.dateTime, stamp.scope(properties.region), hex(sha256(canonicalRequest.toByteArray())))
                .joinToString("\n")
        var key = hmac("AWS4${properties.secretAccessKey}".toByteArray(), stamp.date)
        key = hmac(key, properties.region)
        key = hmac(key, SERVICE)
        key = hmac(key, "aws4_request")
        return hex(hmac(key, stringToSign))
    }

    private fun objectUri(key: String): URI {
        val path = (listOf(properties.bucket) + key.split("/")).joinToString("/") { encode(it) }
        return URI.create("${properties.endpoint.trimEnd('/')}/$path")
    }

    private fun host(uri: URI): String = uri.rawAuthority

    private fun requireConfigured() {
        if (!properties.configured) {
            throw DocumentStorageUnavailableException("Document storage is not configured")
        }
    }

    private class Stamp(
        now: Instant,
    ) {
        val dateTime: String = DATE_TIME.format(now)
        val date: String = dateTime.substring(0, 8)

        fun scope(region: String): String = "$date/$region/$SERVICE/aws4_request"
    }

    private companion object {
        const val ALGORITHM = "AWS4-HMAC-SHA256"
        const val SERVICE = "s3"
        const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        /** RFC 3986 encoding, as Signature Version 4 requires: spaces as %20, `~` left as is. */
        fun encode(value: String): String =
            URLEncoder
                .encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~")

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        fun hmac(
            key: ByteArray,
            data: String,
        ): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data.toByteArray())
            }

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
