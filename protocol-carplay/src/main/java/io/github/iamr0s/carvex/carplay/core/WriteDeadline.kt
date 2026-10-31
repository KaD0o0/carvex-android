package io.github.iamr0s.carvex.carplay.core
import java.util.concurrent.atomic.AtomicReference

/** Tracks only an outstanding write; completion cannot disarm the next write. */
class WriteDeadline(private val timeoutNanos:Long) {
    class Ticket internal constructor(internal val began:Long)
    private val active=AtomicReference<Ticket?>(null)
    init{require(timeoutNanos>0)}
    fun begin(now:Long):Ticket {
        val ticket=Ticket(now)
        check(active.compareAndSet(null,ticket)){"Overlapping video writes"}
        return ticket
    }
    fun complete(ticket:Ticket){active.compareAndSet(ticket,null)}
    /** Age of the outstanding write, or null when no write is in flight. */
    fun ageNanos(now:Long):Long? {
        val ticket=active.get()?:return null
        return now-ticket.began
    }
    fun expire(now:Long):Boolean {
        val ticket=active.get()?:return false
        return now-ticket.began>=timeoutNanos&&active.compareAndSet(ticket,null)
    }
}
