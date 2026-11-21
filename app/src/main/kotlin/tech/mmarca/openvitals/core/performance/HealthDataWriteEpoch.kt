package tech.mmarca.openvitals.core.performance

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How many times this app has written to Health Connect: an insert, an update
 * or a delete, of any record. A screen that remembers the count it loaded under
 * knows whether the app itself has changed the data since, without asking
 * Health Connect. Other apps' writes do not move it.
 */
@Singleton
class HealthDataWriteEpoch @Inject constructor() {
    private val writes = AtomicLong()

    val value: Long
        get() = writes.get()

    fun bump() {
        writes.incrementAndGet()
    }
}
