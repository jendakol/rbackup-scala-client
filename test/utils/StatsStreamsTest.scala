package utils

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import monix.execution.{Cancelable, Scheduler}
import org.mockito.ArgumentMatchers
import org.mockito.Mockito._
import org.scalatest.FunSuite
import org.scalatest.mockito.MockitoSugar

import scala.io.Source
import scala.util.Random

class StatsStreamsTest extends FunSuite with MockitoSugar {
  test("currentSpeed") {
    assertResult {
      (30 * .4 + 20 * .3 + 10 * .2 + 50 * .1) / 1000.0
    }(StatsStreams.currentSpeed(Array(10, 20, 30, 0, 50), 3))
  }

  test("IS reads all data") {
    val bis = new ByteArrayInputStream("abcdefghijklmnopqrstuvwxyz".getBytes)

    val sch = mock[Scheduler]
    when(sch.scheduleAtFixedRate(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
      .thenReturn(mock[Cancelable])
    val sis = new StatsInputStream(bis, 10)(sch)

    assertResult((0, 0.0))(sis.snapshot)

    assertResult("abcdefghijklmnopqrstuvwxyz")(Source.fromInputStream(sis).mkString)

    assertResult((26, 0.0))(sis.snapshot)
  }

  test("IS calculates rate") {
    val bis = new ByteArrayInputStream(Stream.continually((Random.nextInt(26) + 97).toChar).take(10000).mkString.getBytes)

    val sch = mock[Scheduler]
    when(sch.scheduleAtFixedRate(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
      .thenReturn(mock[Cancelable])
    val sis = new StatsInputStream(bis, 5)(sch)

    for (_ <- 1 to 10) sis.read()
    sis.tick()
    for (_ <- 1 to 15) sis.read()
    sis.tick()
    for (_ <- 1 to 20) sis.read()
    sis.tick()
    for (_ <- 1 to 25) sis.read()
    sis.tick()
    for (_ <- 1 to 30) sis.read()
    sis.tick()
    for (_ <- 1 to 35) sis.read()
    sis.tick()
    for (_ <- 1 to 40) sis.read()
    sis.tick()

    val bytesTotal = 10 + 15 + 20 + 25 + 30 + 35 + 40
    val speed = (40 * .4 + 35 * .3 + 30 * .2 + 25 * .1) / 1000.0

    assertResult((bytesTotal, speed))(sis.snapshot)
  }

  test("IS calculates rate 2") {
    val bis = new ByteArrayInputStream(Stream.continually((Random.nextInt(26) + 97).toChar).take(10000).mkString.getBytes)

    val sch = mock[Scheduler]
    when(sch.scheduleAtFixedRate(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
      .thenReturn(mock[Cancelable])
    val sis = new StatsInputStream(bis, 5)(sch)

    sis.read(Array.fill(10)(0.toByte))
    sis.tick()
    sis.read(Array.fill(15)(0.toByte))
    sis.tick()
    sis.read(Array.fill(20)(0.toByte))
    sis.tick()
    sis.read(Array.fill(25)(0.toByte))
    sis.tick()
    sis.read(Array.fill(30)(0.toByte))
    sis.tick()
    sis.read(Array.fill(35)(0.toByte))
    sis.tick()
    sis.read(Array.fill(40)(0.toByte))
    sis.tick()

    val bytesTotal = 10 + 15 + 20 + 25 + 30 + 35 + 40
    val speed = (40 * .4 + 35 * .3 + 30 * .2 + 25 * .1) / 1000.0

    assertResult((bytesTotal, speed))(sis.snapshot)
  }

  test("OS writes all data") {
    val bos = new ByteArrayOutputStream()

    val sch = mock[Scheduler]
    when(sch.scheduleAtFixedRate(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
      .thenReturn(mock[Cancelable])
    val sos = new StatsOutputStream(bos)(sch)

    assertResult((0, 0.0))(sos.snapshot)

    sos.write("abcdefghijklmnopqrstuvwxyz".getBytes)

    assertResult((26, 0.0))(sos.snapshot)

    assertResult("abcdefghijklmnopqrstuvwxyz")(bos.toString)
  }

  test("OS calculates rate") {
    val bos = new ByteArrayOutputStream()

    val sch = mock[Scheduler]
    when(sch.scheduleAtFixedRate(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
      .thenReturn(mock[Cancelable])
    val sos = new StatsOutputStream(bos)(sch)

    for (_ <- 1 to 10) sos.write(65)
    sos.tick()
    for (_ <- 1 to 15) sos.write(65)
    sos.tick()
    for (_ <- 1 to 20) sos.write(65)
    sos.tick()
    for (_ <- 1 to 25) sos.write(65)
    sos.tick()
    for (_ <- 1 to 30) sos.write(65)
    sos.tick()
    for (_ <- 1 to 35) sos.write(65)
    sos.tick()
    for (_ <- 1 to 40) sos.write(65)
    sos.tick()

    val bytesTotal = 10 + 15 + 20 + 25 + 30 + 35 + 40
    val speed = (40 * .4 + 35 * .3 + 30 * .2 + 25 * .1) / 1000.0

    assertResult((bytesTotal, speed))(sos.snapshot)
  }

  test("OS calculates rate 2") {
    val bos = new ByteArrayOutputStream()

    val sch = mock[Scheduler]
    when(sch.scheduleAtFixedRate(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
      .thenReturn(mock[Cancelable])
    val sos = new StatsOutputStream(bos)(sch)

    sos.write(Array.fill(10)(65.toByte))
    sos.tick()
    sos.write(Array.fill(15)(65.toByte))
    sos.tick()
    sos.write(Array.fill(20)(65.toByte))
    sos.tick()
    sos.write(Array.fill(25)(65.toByte))
    sos.tick()
    sos.write(Array.fill(30)(65.toByte))
    sos.tick()
    sos.write(Array.fill(35)(65.toByte))
    sos.tick()
    sos.write(Array.fill(40)(65.toByte))
    sos.tick()

    val bytesTotal = 10 + 15 + 20 + 25 + 30 + 35 + 40
    val speed = (40 * .4 + 35 * .3 + 30 * .2 + 25 * .1) / 1000.0

    assertResult((bytesTotal, speed))(sos.snapshot)
  }
}
