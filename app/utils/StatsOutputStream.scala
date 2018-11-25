package utils

import java.io.OutputStream
import java.time.Clock
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import monix.execution.Scheduler

import scala.concurrent.duration._

class StatsOutputStream(output: OutputStream, clock: Clock = Clock.systemDefaultZone())(sch: Scheduler) extends OutputStream {

  private val stats = new AtomicReference[(Long, Double)]((0, 0))

  private val currentSecondIndex = new AtomicInteger(0)
  private val secondsStats = Array.fill(5)(0)

  private val scheduled = sch.scheduleAtFixedRate(1.second, 1.second) {
    val secondIndex = currentSecondIndex.getAndUpdate(i => (i + 1) % 5)

    val unrolled = secondsStats.slice(secondIndex, 5) ++ secondsStats.slice(0, secondIndex - 1)

    val speed = unrolled.zipWithIndex.map { case (b, i) => b * ((i + 1) / 10.0) }.sum / 1000.0

    stats.accumulateAndGet((0, speed), (oldData: (Long, Double), newData: (Long, Double)) => {
      (oldData._1, newData._2)
    })
  }

  override def write(b: Int): Unit = {
    output.write(b)
    updateStats(1)
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    super.write(b, off, len)

    updateStats(len)
  }

  private def updateStats(uploadedBytes: Int): Unit = {
    stats.accumulateAndGet((uploadedBytes, 0), (oldData: (Long, Double), newData: (Long, Double)) => {
      (oldData._1 + newData._1, oldData._2)
    })

    secondsStats(currentSecondIndex.get()) += uploadedBytes
  }

  def snapshot: (Long, Double) = stats.get()

  override def close(): Unit = {
    output.close()
    scheduled.cancel()
  }
}
