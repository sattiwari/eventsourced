package com.stlabs.eventsourced.core

import java.io.File

import akka.actor.ActorSystem
import akka.util.Timeout
import org.apache.commons.io.FileUtils
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, MustMatchers, WordSpec}

import scala.concurrent.duration._

class ReliableOutputChannelSpec extends WordSpec with MustMatchers with BeforeAndAfterEach with BeforeAndAfterAll {
  implicit val system = ActorSystem("test")
  implicit val timeout = Timeout(5 seconds)

  val journalDir = new File("target/journal")

  override protected def afterEach(): Unit = {
    FileUtils.deleteDirectory(journalDir)
  }

  override protected def afterAll() {
    system.shutdown()
  }

  "A reliable output channel" when {
    "just created" must {

    }

    "deliver stored output message on request" in {

    }

    "delivering output messages" must {
      "recover from destination failures" in {
        
      }
    }
  }

}
