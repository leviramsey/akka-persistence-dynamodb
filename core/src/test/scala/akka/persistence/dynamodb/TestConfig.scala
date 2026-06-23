/*
 * Copyright (C) 2024-2026 Lightbend Inc. <https://akka.io>
 */

package akka.persistence.dynamodb

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object TestConfig {
  lazy val config: Config = {
    val defaultConfig = ConfigFactory.load()

    ConfigFactory
      .parseString("""
      akka.loglevel = DEBUG
      akka.persistence.journal.plugin = "akka.persistence.dynamodb.journal"
      akka.persistence.snapshot-store.plugin = "akka.persistence.dynamodb.snapshot"
      akka.persistence.dynamodb.client.local.enabled = true
      akka.actor.testkit.typed.default-timeout = 10s
      akka.persistence.dynamodb.validate-deserialization = on
      """ +
      s"\nakka.actor.serializers.unlucky-string = \"${classOf[akka.persistence.dynamodb.UnluckyStringSerializer].getName}\"" +
      s"\nakka.actor.serialization-bindings.\"${classOf[akka.persistence.dynamodb.UnluckyString].getName}\" = unlucky-string")
      .withFallback(defaultConfig)
  }

  val backtrackingDisabledConfig: Config =
    ConfigFactory.parseString("akka.persistence.dynamodb.query.backtracking.enabled = off")

}
