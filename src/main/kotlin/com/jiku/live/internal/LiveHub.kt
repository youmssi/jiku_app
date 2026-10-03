package com.jiku.live.internal

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The open streams of this instance, by topic (JIKU-214). A change only marks
 * its topic; every [LiveProperties.coalesce] the marked topics are sent once
 * to their streams, so 300 check-ins in a second reach a screen as one signal.
 * A heartbeat keeps idle streams open through proxies.
 *
 * In memory: with several API instances, a change on one would not reach
 * streams held by another; PostgreSQL LISTEN/NOTIFY would then carry the
 * signal between them.
 */
@Component
class LiveHub(
    private val properties: LiveProperties,
) {
    private val log = LoggerFactory.getLogger(LiveHub::class.java)
    private val streams = ConcurrentHashMap<String, CopyOnWriteArraySet<SseEmitter>>()
    private val changed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val open = AtomicInteger()
    private val clock =
        Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "jiku-live").apply { isDaemon = true } }

    init {
        val coalesce = properties.coalesce.toMillis()
        clock.scheduleWithFixedDelay(::flush, coalesce, coalesce, TimeUnit.MILLISECONDS)
        val heartbeat = properties.heartbeat.toMillis()
        clock.scheduleWithFixedDelay(::heartbeat, heartbeat, heartbeat, TimeUnit.MILLISECONDS)
    }

    fun open(topic: String): SseEmitter {
        if (open.incrementAndGet() > properties.maxStreams) {
            open.decrementAndGet()
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Too many live screens open; this one keeps refreshing on its own",
            )
        }
        val emitter = SseEmitter(properties.streamTimeout.toMillis())
        val subscribers = streams.computeIfAbsent(topic) { CopyOnWriteArraySet() }
        subscribers += emitter
        val close = Runnable { drop(topic, emitter) }
        emitter.onCompletion(close)
        emitter.onTimeout(close)
        emitter.onError { close.run() }
        send(
            topic,
            emitter,
            SseEmitter
                .event()
                .name(READY)
                .reconnectTime(RECONNECT_MS)
                .data(topic),
        )
        return emitter
    }

    /** Marks [topic] as changed; its screens hear about it at the next flush. */
    fun changed(topic: String) {
        if (streams.containsKey(topic)) changed += topic
    }

    fun openStreams(): Int = open.get()

    internal fun flush() {
        val topics = changed.toList()
        topics.forEach { topic ->
            changed -= topic
            streams[topic]?.forEach { send(topic, it, SseEmitter.event().name(CHANGE).data(topic)) }
        }
    }

    private fun heartbeat() {
        streams.forEach { (topic, emitters) -> emitters.forEach { send(topic, it, SseEmitter.event().comment("hb")) } }
    }

    private fun send(
        topic: String,
        emitter: SseEmitter,
        event: SseEmitter.SseEventBuilder,
    ) {
        try {
            emitter.send(event)
        } catch (ex: IOException) {
            drop(topic, emitter)
        } catch (ex: IllegalStateException) {
            drop(topic, emitter)
        }
    }

    private fun drop(
        topic: String,
        emitter: SseEmitter,
    ) {
        val subscribers = streams[topic] ?: return
        if (subscribers.remove(emitter)) {
            open.decrementAndGet()
            streams.computeIfPresent(topic) { _, set -> set.takeIf { it.isNotEmpty() } }
        }
    }

    @PreDestroy
    fun close() {
        clock.shutdownNow()
        streams.values.flatten().forEach {
            try {
                it.complete()
            } catch (ex: IllegalStateException) {
                log.debug("Live stream already closed", ex)
            }
        }
    }

    companion object {
        const val READY = "ready"
        const val CHANGE = "change"
        const val RECONNECT_MS = 5_000L
    }
}
