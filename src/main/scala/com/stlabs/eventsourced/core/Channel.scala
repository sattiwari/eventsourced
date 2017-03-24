package com.stlabs.eventsourced.core

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.stlabs.eventsourced.core.Message._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
  * A communication channel used by an event-sourced Component to interact with
  * its environment. A channel is used to communicate via event messages.
  */
trait Channel extends Actor {
  import Journaler._

  def id: Int
  def componentId: Int

  implicit val executionContext = ExecutionContext.fromExecutor(context.dispatcher)

  def journaler: ActorRef
  val journalerTimeout = Timeout(10 seconds)

  var counter = 0L

  def journal(cmd: Any): Future[Any] =
    journaler.ask(cmd)(journalerTimeout)

  def lastSequenceNr: Long = {
    val future = journaler.ask(GetLastSequenceNr(componentId, id))(journalerTimeout).mapTo[Long]
    Await.result(future, journalerTimeout.duration)
  }
}

object Channel {
  val inputChannelId = 0
  val destinationTimeout = Timeout(5 seconds)

  case class SetProcessor(processor: ActorRef)
  case class SetDestination(processor: ActorRef)
}

/**
  * An input channel is used by application to send event messages to an event-sourced
  * component. This channel writes event messages to a journal before sending it to the
  * component's processor.
  *
  * @param componentId id of the input channel owner
  * @param journaler
  */
class InputChannel(val componentId: Int, val journaler: ActorRef) extends Channel {
  import Channel._
  import Journaler._

  val id = inputChannelId

  var sequencer: Option[ActorRef] = None
  var processor: Option[ActorRef] = None

  def receive = {
    case Message(evt, sdr, sdrmid, _, _, _, false) => {
      val msg = Message(evt, sdr, sdrmid, counter, Nil, Nil)
      val key = Key(componentId, id, msg.sequenceNr, 0)

      val future = journal(WriteMsg(key, msg))

      val s = sender

      future.onSuccess {
        case _ => { sequencer.foreach(_ ! (msg.sequenceNr, msg)); s ! key }
      }

      future.onFailure {
        case e => { context.stop(self); println("journaling failure: %s caused by %s" format (e, msg)) }
        // TODO: inform cluster manager to fail-over
      }

      counter = counter + 1
    }
    case msg @ Message(_, _, _, _, _, _, true) => {
      processor.foreach(_.!(msg.copy(sender = None))(null))
    }
    case cmd @ SetProcessor(p) => {
      sequencer.foreach(_ forward cmd)
      processor = Some(p)
    }
  }

  override def preStart() {
    val lsn = lastSequenceNr
    val seq = context.actorOf(Props(new InputChannelSequencer(lsn)))
    sequencer = Some(seq)
    counter = lsn + 1
  }
}

private class InputChannelSequencer(val lastSequenceNr: Long) extends Sequencer {
  import Channel._

  var processor: Option[ActorRef] = None

  def receiveSequenced = {
    case msg: Message => {
      processor.foreach(_ ! msg)
    }
    case SetProcessor(p) => {
      processor = Some(p)
    }
  }
}

class InputChannelProducer(inputChannel: ActorRef) extends Actor {
  def receive = {
    case msg: Message => inputChannel.!(msg.copy(sender = Some(sender)))(null)
    case evt          => inputChannel.!(Message(evt, Some(sender)))(null)
  }
}

/**
  * A channel used by a component's processor (actor) to send event messages
  * to it's environment (or even to the component it is owned by).
  */
trait OutputChannel extends Channel {
  var destination: Option[ActorRef] = None
}

/**
  * An output channel that sends event messages to a destination. If the destination responds
  * with a successful result, a send confirmation is written to the journal.
  *
  * @param componentId id of the input channel owner
  * @param id output channel id (used internally)
  * @param journaler
  */
class DefaultOutputChannel(val componentId: Int, val id: Int, val journaler: ActorRef) extends OutputChannel {
  import Channel._
  import Journaler._
  import Message._

  assert(id > 0)

  def receive = {
    case Message(evt, sdr, sdrmid, seqnr, acks, _, replicated) if (!acks.contains(id) && !replicated) => {
      val msg = Message(evt, sdr, sdrmid, counter, Nil, Nil)

      destination.foreach(_.ask(msg)(destinationTimeout) onSuccess {
        case r => journaler.!(WriteAck(Key(componentId, inputChannelId, seqnr, id)))(null)
      })

      counter = counter + 1
    }
    case SetDestination(d) => {
      destination = Some(d)
    }
  }

  override def preStart() {
    counter = 1L
  }
}

/**
  * An output channel that stores output messages in the journal before sending it to its
  * destination. If the destination responds with a successful result the stored output
  * message is removed from the journal, otherwise a re-send is attempted.
  *
  * @param componentId id of the input channel owner
  * @param id output channel id (used internally)
  * @param journaler
  */
class ReliableOutputChannel(val componentId: Int, val id: Int, val journaler: ActorRef) extends OutputChannel {
  import Channel._
  import Journaler._
  import Message._

  assert(id > 0)

  var sequencer: Option[ActorRef] = None

  def receive = {
    case Message(evt, sdr, sdrmid, seqnr, acks, _, replicated)  if (!acks.contains(id) && !replicated) => {
      val msg = Message(evt, sdr, sdrmid, counter, Nil, Nil)
      val msgKey = Key(componentId, id, msg.sequenceNr, 0)
      val ackKey = Key(componentId, inputChannelId, seqnr, id)

      val future = journal(WriteAckAndMsg(ackKey, msgKey, msg))

      future.onSuccess {
        case _ => sequencer.foreach(_ ! (msg.sequenceNr, msg))
      }

      future.onFailure {
        case e => { context.stop(self); println("journaling failure: %s caused by %s" format (e, msg)) }
        // TODO: inform cluster manager to fail-over
      }

      counter = counter + 1
    }
    case cmd @ SetDestination(d) => {
      sequencer.foreach(_ forward cmd)
      destination = Some(d)
    }
  }

  override def preStart() {
    val lsn = lastSequenceNr
    val seq = context.actorOf(Props(new ReliableOutputChannelSequencer(componentId, id, journaler, lsn)))
    sequencer = Some(seq)
    counter = lsn + 1
  }
}

class ReliableOutputChannelSequencer(componentId: Int, id: Int, journaler: ActorRef, val lastSequenceNr: Long) extends Sequencer {
  import Channel._
  import Journaler._

  implicit val executionContext = ExecutionContext.fromExecutor(context.dispatcher)

  var destination: Option[ActorRef] = None

  def receiveSequenced = {
    case msg: Message => {
      destination.foreach { d =>
        val future = d.ask(msg)(destinationTimeout)

        future.onSuccess {
          case _ => journaler.!(DeleteMsg(Key(componentId, id, msg.sequenceNr, 0)))(null)
        }

        future.onFailure {
          case _ => // TODO: stop self and schedule retry
        }
      }
    }
    case SetDestination(d) => {
      destination = Some(d)
    }
  }
}