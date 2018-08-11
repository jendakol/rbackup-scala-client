package lib

import java.util

import lib.Sha256._

case class Sha256(bytes: Array[Byte]) {
  require(bytes.length == 32, s"Invalid Sha256: 32 bytes expected but ${bytes.length} provided")

  override def toString: String = bytes2hex(bytes)

  override def hashCode(): Int = util.Arrays.hashCode(bytes)

  override def equals(that: Any): Boolean = that match {
    case that: Sha256 => util.Arrays.equals(bytes, that.bytes)
    case _ => false
  }
}

object Sha256 {
  def apply(hex: String): Sha256 = Sha256(hex2bytes(hex))

  def hex2bytes(hex: String): Array[Byte] = hex.replaceAll("[^0-9A-Fa-f]", "").sliding(2, 2).toArray.map(Integer.parseInt(_, 16).toByte)

  def bytes2hex(bytes: Array[Byte], sep: Option[String] = None): String =
    sep match {
      case None => bytes.map("%02x".format(_)).mkString
      case _ => bytes.map("%02x".format(_)).mkString(sep.get)
    }
}
