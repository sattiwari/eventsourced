package com.stlabs.eventsourced.core

import akka.actor.{Actor, ActorRef}
import akka.util.Timeout
import scala.concurrent.duration._

/**
  * A communication channel used by an event-sourced Component to interact with
  * its environment. A channel is used to communicate via event messages.
  */
trait Channel extends Actor {

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

  def receive = ???

}

private class InputChannelSequencer(val lastSequenceNr: Long) extends Sequencer {

  def receive = ???

}

class InputChannelProducer(inputChannel: ActorRef) extends Actor {
  def receive = ???
}

/**
  * A channel used by a component's processor (actor) to send event messages
  * to it's environment (or even to the component it is owned by).
  */
trait OutputChannel extends Channel {

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
  def receive = ???
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
  def receive = ???
}

class ReliableOutputChannelSequencer(componentId: Int, id: Int, journaler: ActorRef, val lastSequenceNr: Long) extends Sequencer {
  def receive = ???
}
