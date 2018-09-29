package lib

import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

class StatsInputStream(input: InputStream) extends InputStream {

  private val stats = new AtomicReference[(Long, Double)]((0, 0))

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
    stats.accumulateAndGet((uploadedBytes, 19500), (oldData: (Long, Double), newData: (Long, Double)) => {
      (oldData._1 + newData._1, newData._2)
    })
  }

  def snapshot: (Long, Double) = stats.get()
}
