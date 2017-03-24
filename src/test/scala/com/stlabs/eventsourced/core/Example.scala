package com.stlabs.eventsourced.core

import java.io.File
import java.util.concurrent.Exchanger

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import org.apache.commons.io.FileUtils
import org.scalatest._
import org.scalatest.matchers.MustMatchers

import scala.concurrent.Await
import scala.concurrent.duration._

class Example extends WordSpec with MustMatchers with BeforeAndAfterEach with BeforeAndAfterAll {
  implicit val system = ActorSystem("test")
  implicit val timeout = Timeout(5 seconds)

  val journalDir1 = new File("target/journal-1")
  val journalDir2 = new File("target/journal-2")
  val journaler: ActorRef = system.actorOf(Props(new Journaler(journalDir1)))

  override protected def afterEach() {
    FileUtils.deleteDirectory(journalDir1)
    FileUtils.deleteDirectory(journalDir2)
  }

  override protected def afterAll() {
    system.shutdown()
  }

  def createExampleComponent(destination: ActorRef) = {
    ComponentBuilder(0, journaler)
      .addSelfOutputChannel("self")
      .addReliableOutputChannel("dest", destination)
      .setProcessor(outputChannels => system.actorOf(Props(new ExampleProcessor(outputChannels))))
  }

  "An event-sourced component" must {
    "recover state from journaled event messages" in {
      val exchanger = new Exchanger[Message]
      val destination = system.actorOf(Props(new ExampleDestination(exchanger)))
      var component = createExampleComponent(destination)

      // initial run
      var response = component.producer ? "a"
      var result = exchanger.exchange(null)
      result.event must be("a-1-1")
      result.senderMessageId must be(Some("example-2"))
      Await.result(response, timeout.duration) must be("a-1-1")

      // create a fresh component and recover state
      component = createExampleComponent(destination)
      Await.result(component.replay(), timeout.duration)

      // run again (different output as it depends on component state)
      component.producer ! "a"
      result = exchanger.exchange(null)
      result.event must be("a-1-1")
      result.senderMessageId must be(Some("example-4"))
    }
  }
}

class ExampleProcessor(outputChannels: Map[String, ActorRef]) extends Actor {
  var ctr = 0 // state: number of messages received (used to create sender message ids)

  def receive = {
    case msg: Message => {
      ctr = ctr + 1

      val transformedEvt = "%s-1" format msg.event
      val transformedMsg = msg.copy(event = transformedEvt, senderMessageId = Some("example-%s" format ctr))

      if (msg.event.toString.contains("-1")) {
        outputChannels("dest") ! transformedMsg
        msg.sender.foreach(_ ! transformedEvt)
      } else {
        outputChannels("self") ! transformedMsg
      }
    }
  }
}

class ExampleDestination(exchanger: Exchanger[Message]) extends Actor {
  var resultReceiver: Option[ActorRef] = None

  def receive = {
    case msg: Message => { exchanger.exchange(msg); sender ! () }
  }
}