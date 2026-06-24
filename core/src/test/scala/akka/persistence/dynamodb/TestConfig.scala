/*
 * Copyright (C) 2024-2026 Lightbend Inc. <https://akka.io>
 */

package akka.persistence.dynamodb

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object TestConfig {
  lazy val config: Config = {
    val defaultConfig = ConfigFactory.load()

    val unluckyStringSerializerFQCN = classOf[akka.persistence.dynamodb.UnluckyStringSerializer].getName
    val unluckyStringFQCN = classOf[akka.persistence.dynamodb.UnluckyString].getName

    ConfigFactory
      .parseString(s"""
      akka.loglevel = DEBUG
      akka.persistence.journal.plugin = "akka.persistence.dynamodb.journal"
      akka.persistence.snapshot-store.plugin = "akka.persistence.dynamodb.snapshot"
      akka.persistence.dynamodb.client.local.enabled = true
      akka.actor.testkit.typed.default-timeout = 10s
      akka.persistence.dynamodb.validate-deserialization = on
      akka.actor.serialization-bindings."$unluckyStringFQCN" = unlucky-string
      akka.actor.serializers.unlucky-string = "$unluckyStringSerializerFQCN"
      """)
      .withFallback(defaultConfig)
  }

  val backtrackingDisabledConfig: Config =
    ConfigFactory.parseString("akka.persistence.dynamodb.query.backtracking.enabled = off")

}
