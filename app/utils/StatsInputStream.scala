package utils

import java.io.InputStream
import java.time.Clock
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import monix.execution.Scheduler

import scala.concurrent.duration._

class StatsInputStream(input: InputStream, clock: Clock = Clock.systemDefaultZone())(sch: Scheduler) extends InputStream {

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

    secondsStats(currentSecondIndex.get()) += uploadedBytes
  }

  def snapshot: (Long, Double) = stats.get()

  override def close(): Unit = {
    input.close()
    scheduled.cancel()
  }
}
