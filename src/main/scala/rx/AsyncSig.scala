package rx

import concurrent.{ExecutionContext, Future}
import java.util.concurrent.atomic.{AtomicLong, AtomicInteger}
import util.Try
import concurrent.duration.{FiniteDuration, Deadline, Duration}
import akka.actor.{Actor, Cancellable, ActorSystem}
import rx.Flow.Reactor


abstract class Target[T](default: T){
  def handleSend(id: Long): Unit
  def handleReceive(id: Long, value: Try[T], callback: Try[T] => Unit): Unit
}

object AsyncCombinators{
  implicit class pimpedAsyncSig[T](source: AsyncSig[T]){
    def discardLate = DiscardLate(source.currentValue)
  }
  case class BaseTarget[T](default: T) extends Target[T](default){
    def handleSend(id: Long) = ()
    def handleReceive(id: Long, value: Try[T], callback: Try[T] => Unit) = {
      callback(value)
    }
  }

  case class DiscardLate[T](default: T) extends Target[T](default){
    val sendIndex = new AtomicLong(0)
    val receiveIndex = new AtomicLong(0)

    def handleSend(id: Long) = {
      sendIndex.set(id)
    }
    def handleReceive(id: Long, value: Try[T], callback: Try[T] => Unit) = {
      if (id >= receiveIndex.get()){
        receiveIndex.set(id)
        callback(value)
      }
    }
  }
}

class DebouncedSig[+T](source: Signal[T], interval: FiniteDuration)
                      (implicit system: ActorSystem, ex: ExecutionContext)
extends Settable[T](source.currentValue){

  def name = "debounced " + source.name

  private[this] var nextTime = Deadline.now
  private[this] var lastOutput: Option[(Try[T], Cancellable)] = None

  private[this] val listener = Obs(source){

    def updateRecurse(value: Try[T]): Unit = {
      if (Deadline.now > nextTime){
        this() = value
        nextTime = Deadline.now + interval
      }else{
        for ((value, cancellable) <- lastOutput) cancellable.cancel()
        lastOutput = Some(source.toTry -> system.scheduler.scheduleOnce(interval)(updateRecurse(value)))
      }
    }
    updateRecurse(source.toTry)
  }
}

class AsyncSig[+T](default: T, source: Signal[Future[T]], targetC: T => Target[T])
                  (implicit executor: ExecutionContext)
extends Settable[T](default){
  def name = "async " + source.name
  private[this] lazy val count = new AtomicLong(0)
  private[this] lazy val target = targetC(default)

  private[this] val listener = Obs(source){
    val future = source()
    val id = count.getAndIncrement
    target.handleSend(id)
    future.onComplete{ x =>
      target.handleReceive(id, x, this() = _)
    }
  }
  listener.trigger()
}