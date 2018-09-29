package lib

import java.io.InputStream
import java.security.MessageDigest

import monix.eval.Task

class InputStreamWithSha256(input: InputStream) extends InputStream {

  private val digest: MessageDigest = MessageDigest.getInstance("SHA-256")

  /** Once is this result queried, it is cached.
    */
  val sha256: Task[Sha256] = Task {
    Sha256(digest.digest())
  }.memoize

  override def read(): Int = {
    val byte = input.read()

    digest.update(byte.toByte)
    byte
  }

  override def available(): Int = input.available() + 32

  override def read(b: Array[Byte], off: Int, len: Int): Int = {
    val bytes = input.read(b, off, len)

    digest.update(b.slice(off, off + bytes))
    bytes
  }

  override def close(): Unit = input.close()
}
