package com.depromeet.piki.common.event

import com.depromeet.piki.support.IntegrationTestSupport
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.transaction.event.TransactionPhase
import org.springframework.util.ClassUtils
import org.springframework.transaction.event.TransactionalEventListener
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationModuleListenerIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var applicationContext: ApplicationContext

    @Test
    fun `발행 기록에 남는 리스너는 모두 id 를 고정한 커밋 뒤 리스너다`() {
        val methods = recordedListenerMethods()

        assertTrue(methods.isNotEmpty())
        assertEquals(emptyList(), methods.filterNot { it.isCommitListenerWithFixedId() }.map { it.toGenericString() })
    }

    private fun recordedListenerMethods(): List<Method> =
        applicationContext.beanDefinitionNames
            .mapNotNull { applicationContext.getType(it) }
            .map { ClassUtils.getUserClass(it) }
            .filter { it.name.startsWith(BASE_PACKAGE) }
            .flatMap { it.declaredMethods.toList() }
            .filter { AnnotatedElementUtils.hasAnnotation(it, ApplicationModuleListener::class.java) }
            .distinct()

    private fun Method.isCommitListenerWithFixedId(): Boolean {
        val listener = AnnotatedElementUtils.findMergedAnnotation(this, TransactionalEventListener::class.java) ?: return false
        return listener.phase == TransactionPhase.AFTER_COMMIT && listener.id.isNotBlank()
    }

    companion object {
        private const val BASE_PACKAGE = "com.depromeet.piki"
    }
}
