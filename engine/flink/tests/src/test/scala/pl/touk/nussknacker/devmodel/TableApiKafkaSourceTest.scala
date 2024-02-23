package pl.touk.nussknacker.devmodel

import com.typesafe.config.{Config, ConfigFactory}
import io.circe.Json
import io.confluent.kafka.schemaregistry.json.JsonSchema
import pl.touk.nussknacker.defaultmodel.FlinkWithKafkaSuite
import pl.touk.nussknacker.defaultmodel.MockSchemaRegistry.schemaRegistryMockClient
import pl.touk.nussknacker.engine.api.component.ComponentDefinition
import pl.touk.nussknacker.engine.api.process.ProcessObjectDependencies
import pl.touk.nussknacker.engine.build.ScenarioBuilder
import pl.touk.nussknacker.engine.flink.table.SourceTableComponentProvider
import pl.touk.nussknacker.engine.kafka.KafkaTestUtils.richConsumer
import pl.touk.nussknacker.engine.schemedkafka.KafkaUniversalComponentTransformer
import pl.touk.nussknacker.engine.schemedkafka.schemaregistry.SchemaVersionOption
import pl.touk.nussknacker.engine.schemedkafka.schemaregistry.confluent.ConfluentUtils
import pl.touk.nussknacker.engine.spel

class TableApiKafkaSourceTest extends FlinkWithKafkaSuite {

  import spel.Implicits._

  private val inputTopic: String  = "table-api.source.input"
  private val outputTopic: String = "table-api.source.output"

  private val schema = new JsonSchema("""{
                                        |  "type": "object",
                                        |  "properties": {
                                        |    "someInt" : { "type": "integer" },
                                        |    "someString" : { "type": "string" }
                                        |  }
                                        |}
                                        |""".stripMargin)

  private val record1 =
    """{
      |  "someInt": 1,
      |  "someString": "AAA"
      |}""".stripMargin

  private val record2 =
    """{
      |  "someInt": 2,
      |  "someString": "BBB"
      |}""".stripMargin

  private lazy val kafkaTableConfig =
    s"""
       | connector: "kafka"
       | format: "json"
       | options {
       |   "properties.bootstrap.servers": "${kafkaServer.kafkaAddress}"
       |   "properties.group.id": "someConsumerGroupId"
       |   "scan.startup.mode": "earliest-offset"
       |   "topic": "$inputTopic"
       | }
       |""".stripMargin

  private lazy val tableKafkaComponentsConfig: Config = ConfigFactory.parseString(kafkaTableConfig)

  override lazy val additionalComponents: List[ComponentDefinition] = new SourceTableComponentProvider().create(
    tableKafkaComponentsConfig,
    ProcessObjectDependencies.withConfig(tableKafkaComponentsConfig)
  )

  test("should ping-pong with table kafka source and filter") {
    val inputSubject  = ConfluentUtils.topicSubject(inputTopic, isKey = false)
    val outputSubject = ConfluentUtils.topicSubject(outputTopic, isKey = false)
    schemaRegistryMockClient.register(inputSubject, schema)
    schemaRegistryMockClient.register(outputSubject, schema)

    sendAsJson(record1, inputTopic)
    sendAsJson(record2, inputTopic)

    val scenarioId = "scenarioId"
    val sourceId   = "input"
    val process = ScenarioBuilder
      .streaming(scenarioId)
      .parallelism(1)
      .source(sourceId, "configuredSource-kafka-tableApi")
      .filter("filterId", "#input.someInt != 1")
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
      val result = kafkaClient
        .createConsumer()
        .consumeWithJson[Json](outputTopic)
        .take(1)
        .map(_.message())

      result.head shouldBe parseJson(record2)
    }
  }

}
