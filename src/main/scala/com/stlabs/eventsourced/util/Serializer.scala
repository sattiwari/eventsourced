package com.stlabs.eventsourced.util

import java.io._

import akka.util.ClassLoaderObjectInputStream

trait Serializer[A] {
  def toBytes(obj: A): Array[Byte]
  def fromBytes(bytes: Array[Byte]): A
}

class JavaSerializer[A] extends Serializer[A] {
  def toBytes(obj: A) = {
    val bos = new ByteArrayOutputStream
    val oos = new ObjectOutputStream(bos)
    oos.writeObject(obj)
    oos.close()
    bos.toByteArray
  }

  def fromBytes(bytes: Array[Byte]) = {
    val bis = new ByteArrayInputStream(bytes)
    val ois = new ClassLoaderObjectInputStream(getClass.getClassLoader, bis)
    val obj = ois.readObject()
    ois.close()
    obj.asInstanceOf[A]
  }
}
