package lib

import java.io.InputStream
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import monix.execution.Scheduler

import scala.concurrent.duration._

class StatsInputStream(input: InputStream)(sch: Scheduler) extends InputStream {

  private val stats = new AtomicReference[(Long, Double)]((0, 0))

  // TODO floating window
  private val prevSecondBytes = new AtomicInteger(0)
  private val lastSecondBytes = new AtomicInteger(0)

  private val scheduled = sch.scheduleAtFixedRate(1.second, 1.second) {
    // use prev for report, set current as prev, reset current
    val secondFinal = prevSecondBytes.getAndSet(lastSecondBytes.getAndSet(0)) / 1000.0

    stats.accumulateAndGet((0, secondFinal), (oldData: (Long, Double), newData: (Long, Double)) => {
      (oldData._1, newData._2)
    })
  }

  override def read(): Int = {
    val b = input.read()
    if (b > -1) updateStats(b)
    b
  }

  override def read(b: Array[Byte], off: Int, len: Int): Int = {
    val bc = input.read(b, off, len)
    if (bc > -1) updateStats(bc)
    bc
  }

  private def updateStats(uploadedBytes: Int): Unit = {
    stats.accumulateAndGet((uploadedBytes, 0), (oldData: (Long, Double), newData: (Long, Double)) => {
      (oldData._1 + newData._1, oldData._2)
    })
    lastSecondBytes.addAndGet(uploadedBytes)
  }

  def snapshot: (Long, Double) = stats.get()

  override def close(): Unit = {
    input.close()
    scheduled.cancel()
  }
}
