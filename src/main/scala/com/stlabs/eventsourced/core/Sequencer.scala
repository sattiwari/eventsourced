package com.stlabs.eventsourced.core

import akka.actor.Actor

/**
  * A (stateful) message sequencer. Senders send (sequenceNr, msg) pairs.
  */
trait Sequencer extends Actor {
  import scala.collection.mutable.Map

  /** Sequence number of last message that has been delivered by this sequencer. */
  def lastSequenceNr: Long

  /** Implemented by subclasses to received sequenced messages. */
  def receiveSequenced: Receive

  val delayed = Map.empty[Long, Any]
  var delivered = lastSequenceNr

  def receive = {
    case (seqnr: Long, msg) => {
      resequence(seqnr, msg)
    }
    case msg => {
      receiveSequenced(msg)
    }
  }

  @scala.annotation.tailrec
  private def resequence(seqnr: Long, msg: Any) {
    if (seqnr == delivered + 1) {
      delivered = seqnr
      receiveSequenced(msg)
    } else {
      delayed += (seqnr -> msg)
    }
    val eo = delayed.remove(delivered + 1)
    if (eo.isDefined) resequence(delivered + 1, eo.get)
  }
}
