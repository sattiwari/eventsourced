package com.stlabs.eventsourced.core

import java.io.File

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.stlabs.eventsourced.util.JavaSerializer
import org.fusesource.leveldbjni.JniDBFactory._
import org.iq80.leveldb._

import scala.concurrent.duration._
import Message._

import scala.concurrent.ExecutionContext.Implicits.global

class Journaler(dir: File) extends Actor {
  import Journaler._

  // TODO: make configurable
  val serializer = new JavaSerializer[Message]

  val levelDbReadOptions = new ReadOptions
  val levelDbWriteOptions = new WriteOptions().sync(false)
  val leveldb = factory.open(dir, new Options().createIfMissing(true))

  def receive = {
    case WriteAck(key) => {
      println("write ack: key=%s" format key)
      write(key)
      sender ! ()
    }
    case WriteMsg(key, msg) => {
      println("write msg: key=%s msg=%s" format (key, msg))
      write(key, msg.copy(sender = None))
      sender ! ()
    }
    case WriteAckAndMsg(ackKey, msgKey, msg) => {
      println("write ack and msg: ack-key=%s msg-key=%s msg=%s" format (ackKey, msgKey, msg))
      write(ackKey, msgKey, msg.copy(sender = None))
      sender ! ()
    }
    case DeleteMsg(key) => {
      println("delete msg: key=%s" format key)
      delete(key)
      sender ! ()
    }
    case Replay(compId, chanId, fromNr, target) => {
      replay(compId, chanId, fromNr, msg => target ! msg.copy(sender = None))
      sender ! ()
    }
    case GetLastSequenceNr(compId, chanId) => {
      sender ! lastSequenceNr(compId, chanId)
    }
  }

  def write(key: Key): Unit =
    leveldb.put(key.bytes, Array.empty[Byte], levelDbWriteOptions)

  def write(key: Key, message: Message): Unit =
    leveldb.put(key.bytes, serializer.toBytes(message), levelDbWriteOptions)

  def write(ackKey: Key, msgKey: Key, message: Message): Unit = {
    val batch = leveldb.createWriteBatch()
    try {
      batch.put(ackKey.bytes, Array.empty[Byte])
      batch.put(msgKey.bytes, serializer.toBytes(message))
      leveldb.write(batch, levelDbWriteOptions)
    } finally {
      batch.close()
    }
  }

  def delete(key: Key): Unit =
    leveldb.delete(key.bytes, levelDbWriteOptions)

  override def postStop() {
    leveldb.close()
  }

  private def replay(componentId: Int, channelId: Int, fromSequenceNr: Long, p: Message => Unit): Unit = {
    val iter = leveldb.iterator(levelDbReadOptions.snapshot(leveldb.getSnapshot))
    try {
      val startKey = Key(componentId, channelId, fromSequenceNr, 0)
      iter.seek(startKey.bytes)
      replay(iter, startKey, p)
    } finally {
      iter.close()
    }
  }

  @scala.annotation.tailrec
  private def replay(iter: DBIterator, key: Key, p: Message => Unit): Unit = {
    if (iter.hasNext) {
      val nextEntry = iter.next()
      val nextKey = Key(nextEntry.getKey)
      assert(nextKey.confirmingChannelId == 0)
      if (key.componentId         == nextKey.componentId &&
        key.initiatingChannelId == nextKey.initiatingChannelId) {
        val msg = serializer.fromBytes(nextEntry.getValue)
        val channelIds = confirmingChannelIds(iter, nextKey, Nil)
        p(msg.copy(acks = channelIds))
        replay(iter, nextKey, p)
      }
    }
  }

  @scala.annotation.tailrec
  private def confirmingChannelIds(iter: DBIterator, key: Key, channelIds: List[Int]): List[Int] = {
    if (iter.hasNext) {
      val nextEntry = iter.peekNext()
      val nextKey = Key(nextEntry.getKey)
      if (key.componentId         == nextKey.componentId &&
        key.initiatingChannelId == nextKey.initiatingChannelId &&
        key.sequenceNr          == nextKey.sequenceNr) {
        iter.next()
        confirmingChannelIds(iter, nextKey, nextKey.confirmingChannelId :: channelIds)
      } else channelIds
    } else channelIds
  }

  // --------------------------------------------------------
  //  Slow search of highest sequence number. This is
  //  because iterator.prev() doesn't work properly in
  //  leveldbjbi. Not on critical path, only needed on
  //  application start.
  // --------------------------------------------------------

  private def lastSequenceNr(componentId: Int, channelId: Int): Long = {
    val iter = leveldb.iterator()
    try {
      val startKey = Key(componentId, channelId, 0L, 0)
      iter.seek(startKey.bytes)
      lastSequenceNr(iter, startKey).sequenceNr
    } finally {
      iter.close()
    }
  }

  @scala.annotation.tailrec
  private def lastSequenceNr(iter: DBIterator, key: Key): Key = {
    if (iter.hasNext) {
      val nextKey = Key(iter.next().getKey)
      if (key.componentId         == nextKey.componentId &&
        key.initiatingChannelId == nextKey.initiatingChannelId) lastSequenceNr(iter, nextKey) else key
    } else key
  }
}

object Journaler {
  case class DeleteMsg(key: Key)
  case class WriteMsg(key: Key, msg: Message)
  case class WriteAck(key: Key)
  case class WriteAckAndMsg(ackKey: Key, msgKey: Key, msg: Message)
  case class Replay(componentId: Int, channelId: Int, fromSequenceNr: Long, target: ActorRef)

  case class GetLastSequenceNr(componentId: Int, channelId: Int)
}

class ReplicatingJournaler(dir: File) extends Actor {
  import Journaler._
  import Replicator._

  implicit val executor = context.dispatcher

  var replicator: Option[ActorRef] = None
  var journaler: ActorRef = context.actorOf(Props(new Journaler(dir)))

  def receive = {
    case SetReplicator(r) => {
      replicator = r
    }
    case cmd: Replay => {
      journaler forward cmd
    }
    case cmd: GetLastSequenceNr => {
      journaler forward cmd
    }
    case cmd => {
//      val f1 = journaler.ask(cmd)(journalerTimeout)
//      val f2 = if (replicator.isDefined) replicator.get.ask(cmd)(replicatorTimeout) else Promise.successful(())
//      val f3 = Future.sequence(List(f1, f2))
//
//      val s = sender
//
//      f3 onSuccess {
//        case r => s ! r
//      }
//
//      f3 onFailure {
//        case e => {
//          val f1Success = f1.value.map(_.isRight).getOrElse(false)
//          val f2Success = f2.value.map(_.isRight).getOrElse(false)
//          (f1Success, f2Success) match {
//            case (true, false) => {
//              // continue at risk without replication
//              self ! SetReplicator(None)
//
//              // inform sender about journaling result
//              s ! f1.value.get.right.get
//
//              // TODO: inform cluster manager to re-attach slave
//              // ...
//            }
//            case other => {
//              sender ! Status.Failure(e)
//            }
//          }
//        }
//      }
      ???
    }
  }
}

class Replicator(journaler: ActorRef) extends Actor {
  import Journaler._
  import Replicator._

  var inputChannels = Map.empty[Int, ActorRef] // componentId -> inputChannel

  def receive = {
    case RegisterInputChannel(componentId, inputChannel) => {
      inputChannels = inputChannels + (componentId -> inputChannel)
    }
    case cmd @ WriteMsg(key, msg) => {
      journal(cmd, sender) { inputChannels.get(key.componentId).foreach(_ ! msg.copy(replicated = true)) }
    }
    case cmd => {
      journal(cmd, sender)(())
    }
  }

  def journal(cmd: Any, sender: ActorRef)(success: => Unit) {
    val future = journaler.ask(cmd)(journalerTimeout)

    future onSuccess {
      case r => { sender ! r; success }
    }

    future onFailure {
      case e => sender ! Status.Failure(e)
    }
  }
}

object Replicator {
  val replicatorTimeout = Timeout(5 seconds)
  val journalerTimeout = Timeout(5 seconds)

  case class SetReplicator(replicator: Option[ActorRef])
  case class RegisterInputChannel(componentId: Int, inputChannel: ActorRef)
}