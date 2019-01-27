package utils

import java.io.{InputStream, OutputStream}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import monix.execution.{Cancelable, Scheduler}
import utils.StatsStreams._

import scala.concurrent.duration._

trait StatsStream extends AutoCloseable {
  protected def buckets: Int

  protected def sch: Scheduler

  protected val stats = new AtomicReference[(Long, Double)]((0, 0))

  protected val currentSecondIndex = new AtomicInteger(0)
  protected val secondsStats: Array[Int] = Array.fill(buckets)(0)

  protected val scheduled: Cancelable = sch.scheduleAtFixedRate(1.second, 1.second) {
    tick()
  }

  protected[utils] def tick(): Unit = {
    val secondIndex = currentSecondIndex.updateAndGet { i =>
      val ni = (i + 1) % buckets
      secondsStats(ni) = 0 // reset the counter
      ni
    }

    val speed: Double = currentSpeed(secondsStats.clone(), secondIndex)

    stats.accumulateAndGet((0 /*not used*/, speed), (oldData: (Long, Double), newData: (Long, Double)) => {
      (oldData._1, newData._2)
    })
  }

  protected def updateStats(transferredBytes: Int): Unit = {
    stats.accumulateAndGet((transferredBytes, 0), (oldData: (Long, Double), newData: (Long, Double)) => {
      (oldData._1 + newData._1, oldData._2)
    })

    secondsStats(currentSecondIndex.get()) += transferredBytes
  }

  def snapshot: (Long, Double) = stats.get()

  override def close(): Unit = {
    scheduled.cancel()
  }
}

class StatsInputStream(input: InputStream, override protected val buckets: Int = 5)(override protected val sch: Scheduler)
    extends InputStream
    with StatsStream {

  override def read(): Int = {
    val b = input.read()
    if (b > -1) updateStats(1)
    b
  }

  override def read(b: Array[Byte], off: Int, len: Int): Int = {
    val bc = input.read(b, off, len)
    if (bc > -1) updateStats(bc)
    bc
  }

  override def close(): Unit = {
    super.close()
    input.close()
  }
}

class StatsOutputStream(output: OutputStream, override protected val buckets: Int = 5)(override protected val sch: Scheduler)
    extends OutputStream
    with StatsStream {

  override def write(b: Int): Unit = {
    output.write(b)
    updateStats(1)
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    output.write(b, off, len)
    updateStats(len)
  }

  override def close(): Unit = {
    super.close()
    output.close()
  }
}

object StatsStreams {
  private[utils] def currentSpeed(data: Array[Int], secondIndex: Int): Double = {
    val unrolled = data.slice(secondIndex + 1, data.length) ++ data.slice(0, secondIndex)
    unrolled.zipWithIndex.map { case (b, i) => b * ((i + 1) / 10.0) }.sum / 1000.0 // in kBps
  }
}
