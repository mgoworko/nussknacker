package pl.touk.nussknacker.engine.benchmarks.serialization.json

import io.circe.JsonObject
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.everit.json.schema.Schema
import org.openjdk.jmh.annotations._
import pl.touk.nussknacker.engine.api.typed.TypedMap
import pl.touk.nussknacker.engine.benchmarks.serialization.SerializationBenchmarkSetup
import pl.touk.nussknacker.engine.json.JsonSchemaBuilder
import pl.touk.nussknacker.engine.json.serde.CirceJsonDeserializer
import pl.touk.nussknacker.engine.json.swagger.extractor.JsonTypedMap
import pl.touk.nussknacker.engine.process.util.Serializers
import pl.touk.nussknacker.engine.schemedkafka.schemaregistry.SchemaId

import java.util.concurrent.TimeUnit
import scala.io.Source

case class RawJsonObjectAndSchemaId(json: String, schemaId: SchemaId)
case class MyJsonObject(jsonObject: JsonObject, schemaId: SchemaId)

@State(Scope.Thread)
class MapSerialization {

  val schemaString                        = Source.fromResource("pgw.schema.json").getLines().mkString
  val schema: Schema                      = JsonSchemaBuilder.parseSchema(schemaString)
  val deserializer                        = new CirceJsonDeserializer(schema)
  val json: String                        = Source.fromResource("pgw.record.json").getLines().mkString
  val lazyMap: JsonTypedMap               = deserializer.deserialize(json).asInstanceOf[JsonTypedMap]
  val typeMap: java.util.Map[String, Any] = lazyMap.materialize

  val rawJsonSchemaId: RawJsonObjectAndSchemaId = RawJsonObjectAndSchemaId(json, SchemaId.fromInt(333))

  val jsonWithSchemaId = MyJsonObject(lazyMap.jsonObject, SchemaId.fromInt(333))

  val lazyMapSetup = new SerializationBenchmarkSetup(
    TypeInformation.of(classOf[JsonTypedMap]),
    lazyMap,
    config => Serializers.CaseClassSerializer.registerIn(config)
  )

  val TypedMapSetup = new SerializationBenchmarkSetup(
    TypeInformation.of(classOf[TypedMap]),
    typeMap
  )

  val rawJsonAndSchemaIdMapSetup = new SerializationBenchmarkSetup(
    TypeInformation.of(classOf[RawJsonObjectAndSchemaId]),
    rawJsonSchemaId,
    config => Serializers.CaseClassSerializer.registerIn(config)
  )

  val jsonWithSchemaIdSetup = new SerializationBenchmarkSetup(
    TypeInformation.of(classOf[MyJsonObject]),
    jsonWithSchemaId,
    config => Serializers.CaseClassSerializer.registerIn(config)
  )

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def rawJsonAndSchemaIdSerialization() =
    rawJsonAndSchemaIdMapSetup.roundTripSerialization()

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def jsonWithSchemaIdSerialization() =
    jsonWithSchemaIdSetup.roundTripSerialization()

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def typeMapSerialization() =
    TypedMapSetup.roundTripSerialization()

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def lazyMapSerialization() =
    lazyMapSetup.roundTripSerialization()

}
