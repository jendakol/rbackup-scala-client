package lib

import java.io.InputStream

class CallbackInputStream(input: InputStream)(callback: Int => Unit) extends InputStream {
  override def read(): Int = {
    val b = input.read()
    if (b > -1) callback(1)
    b
  }

  override def read(b: Array[Byte], off: Int, len: Int): Int = {
    val bc = input.read(b, off, len)
    if (bc > -1) callback(bc)
    bc
  }
}
