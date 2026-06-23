/*
 * Copyright (C) 2024-2026 Lightbend Inc. <https://akka.io>
 */

package akka.persistence.dynamodb

import java.nio.charset.StandardCharsets

import scala.annotation.nowarn

import akka.serialization.SerializerWithStringManifest

case class UnluckyString(string: String)

// A pathological serializer: it will serialize objects with any string length, but
// will fail to deserialize strings of length 7
class UnluckyStringSerializer extends SerializerWithStringManifest {
  val Manifest = "UnluckyString"
  val Utf8 = StandardCharsets.UTF_8.name

  def identifier = 0xee1e777b

  def manifest(obj: AnyRef) = Manifest

  @nowarn("msg=may not be exhaustive")
  def toBinary(obj: AnyRef) =
    obj match {
      case UnluckyString(string) => string.getBytes(Utf8)
    }

  def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    manifest match {
      case Manifest =>
        if (bytes.length != 7) UnluckyString(new String(bytes, Utf8))
        else throw new RuntimeException("Unlucky Number 7!")

      case _ => throw new IllegalArgumentException(s"Unknown manifest $manifest")
    }
}
