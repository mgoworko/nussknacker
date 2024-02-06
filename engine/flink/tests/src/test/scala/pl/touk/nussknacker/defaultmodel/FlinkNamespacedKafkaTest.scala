package pl.touk.nussknacker.defaultmodel

import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.config.ConfigValueFactory.fromAnyRef
import io.circe.Json
import io.confluent.kafka.schemaregistry.json.JsonSchema
import org.apache.kafka.clients.admin.ListTopicsOptions
import pl.touk.nussknacker.defaultmodel.MockSchemaRegistry.schemaRegistryMockClient
import pl.touk.nussknacker.engine.api.namespaces.ObjectNaming
import pl.touk.nussknacker.engine.build.ScenarioBuilder
import pl.touk.nussknacker.engine.kafka.KafkaTestUtils.richConsumer
import pl.touk.nussknacker.engine.schemedkafka.KafkaUniversalComponentTransformer
import pl.touk.nussknacker.engine.schemedkafka.schemaregistry.SchemaVersionOption
import pl.touk.nussknacker.engine.schemedkafka.schemaregistry.confluent.ConfluentUtils
import pl.touk.nussknacker.engine.spel
import pl.touk.nussknacker.engine.util.namespaces.DefaultNamespacedObjectNaming

class FlinkNamespacedKafkaTest extends FlinkWithKafkaSuite {

  import spel.Implicits._

  private val namespaceName: String            = "ns"
  private val inputTopic: String               = "input"
  private val outputTopic: String              = "output"
  private def namespaced(name: String): String = s"${namespaceName}_$name"

  override lazy val objectNaming: ObjectNaming = DefaultNamespacedObjectNaming

  override lazy val config: Config = ConfigFactory
    .load()
    .withValue("namespace", fromAnyRef(namespaceName))

  private val schema = new JsonSchema("""{
                                                  |  "type": "object",
                                                  |  "properties": {
                                                  |    "value" : { "type": "string" }
                                                  |  }
                                                  |}
                                                  |""".stripMargin)

  private val record =
    """{
      |  "value": "Jan"
      |}""".stripMargin

  test("should send message to topic with appended namespace") {
    val inputSubject  = ConfluentUtils.topicSubject(namespaced(inputTopic), isKey = false)
    val outputSubject = ConfluentUtils.topicSubject(namespaced(outputTopic), isKey = false)
    schemaRegistryMockClient.register(inputSubject, schema)
    schemaRegistryMockClient.register(outputSubject, schema)

    sendAsJson(record, namespaced(inputTopic))

    val scenarioId = "scenarioId"
    val sourceId   = "input"
    val process = ScenarioBuilder
      .streaming(scenarioId)
      .parallelism(1)
      .source(
        sourceId,
        "kafka",
        KafkaUniversalComponentTransformer.TopicParamName         -> s"'$inputTopic'",
        KafkaUniversalComponentTransformer.SchemaVersionParamName -> s"'${SchemaVersionOption.LatestOptionName}'"
      )
      .emptySink(
        "output",
        "kafka",
        KafkaUniversalComponentTransformer.SinkKeyParamName       -> "",
        KafkaUniversalComponentTransformer.SinkValueParamName     -> "#input",
        KafkaUniversalComponentTransformer.TopicParamName         -> s"'$outputTopic'",
        KafkaUniversalComponentTransformer.SchemaVersionParamName -> s"'${SchemaVersionOption.LatestOptionName}'",
        KafkaUniversalComponentTransformer.SinkRawEditorParamName -> s"true",
      )

    run(process) {
      val processed =
        kafkaClient
          .createConsumer()
          .consumeWithJson[Json](namespaced(outputTopic))
          .take(1)
          .map(_.message())
          .toList
      processed.size shouldBe 1
      processed.head shouldBe parseJson(record)
      print(kafkaServer.kafkaAddress)
      // TODO local: need to wait for consumer to commit offsets find a way to commit offsets or live with it?
      //  could lead to flaky tests - increase sleep time if is flaky
      Thread.sleep(10000)
      kafkaClient.listConsumerGroups().map(_.groupId()).head shouldBe s"${namespaceName}_$scenarioId-$sourceId"
    }
  }

}
