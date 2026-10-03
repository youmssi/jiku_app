package com.jiku.live.internal

import com.jiku.shared.LiveChanged
import com.jiku.shared.LiveScoped
import jakarta.annotation.PostConstruct
import jakarta.persistence.EntityManagerFactory
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.event.service.spi.EventListenerRegistry
import org.hibernate.event.spi.EventType
import org.hibernate.event.spi.PostCommitDeleteEventListener
import org.hibernate.event.spi.PostCommitInsertEventListener
import org.hibernate.event.spi.PostCommitUpdateEventListener
import org.hibernate.event.spi.PostDeleteEvent
import org.hibernate.event.spi.PostInsertEvent
import org.hibernate.event.spi.PostUpdateEvent
import org.hibernate.persister.entity.EntityPersister
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Hears every committed write of a [LiveScoped] entity and marks its topics
 * changed (JIKU-214). Whatever wrote it, a counter, a WhatsApp answer, an
 * import, a background job, the screens following it reload. Bulk updates are
 * not seen by Hibernate: their callers publish [LiveChanged] instead.
 */
@Component
class LiveEntityListener(
    private val entityManagerFactory: EntityManagerFactory,
    private val hub: LiveHub,
) : PostCommitInsertEventListener,
    PostCommitUpdateEventListener,
    PostCommitDeleteEventListener {
    @PostConstruct
    fun register() {
        val registry =
            entityManagerFactory
                .unwrap(SessionFactoryImplementor::class.java)
                .serviceRegistry
                .requireService(EventListenerRegistry::class.java)
        registry.appendListeners(EventType.POST_COMMIT_INSERT, this)
        registry.appendListeners(EventType.POST_COMMIT_UPDATE, this)
        registry.appendListeners(EventType.POST_COMMIT_DELETE, this)
    }

    override fun onPostInsert(event: PostInsertEvent) = touch(event.entity)

    override fun onPostUpdate(event: PostUpdateEvent) = touch(event.entity)

    override fun onPostDelete(event: PostDeleteEvent) = touch(event.entity)

    override fun onPostInsertCommitFailed(event: PostInsertEvent) = Unit

    override fun onPostUpdateCommitFailed(event: PostUpdateEvent) = Unit

    override fun onPostDeleteCommitFailed(event: PostDeleteEvent) = Unit

    override fun requiresPostCommitHandling(persister: EntityPersister): Boolean =
        LiveScoped::class.java.isAssignableFrom(persister.mappedClass)

    @TransactionalEventListener(fallbackExecution = true)
    fun on(change: LiveChanged) = change.topics.forEach(hub::changed)

    private fun touch(entity: Any?) {
        (entity as? LiveScoped)?.liveTopics()?.forEach(hub::changed)
    }
}
