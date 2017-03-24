package com.stlabs.eventsourced.core

import java.io.File

import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.util.Timeout
import org.apache.commons.io.FileUtils

import scala.concurrent.duration._
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, MustMatchers, WordSpec}

import java.util.concurrent.Exchanger

class Example extends WordSpec with MustMatchers with BeforeAndAfterEach with BeforeAndAfterAll {
  implicit val system = ActorSystem("test")
  implicit val timeout = Timeout(5 seconds)

  val journalDir1 = new File("target/journal-1")
  val journalDir2 = new File("target/journal-2")

  override protected def afterEach() {
    FileUtils.deleteDirectory(journalDir1)
    FileUtils.deleteDirectory(journalDir2)
  }

  override protected def afterAll() {
    system.shutdown()
  }

  "An event-sourced component" must {
    "recover state from journaled event mesages" in {

    }

    "replicate journaled events and in-memory state" in {

    }
  }

}

class ExampleProcessor(outputChannels: Map[String, ActorRef]) extends Actor {
  var ctr = 0

  def receive = ???
}

class ExampleDestination(exchanger: Exchanger[Message]) extends Actor {

  def receive = ???
}
