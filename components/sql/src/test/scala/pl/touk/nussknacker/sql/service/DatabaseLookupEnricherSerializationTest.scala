package pl.touk.nussknacker.sql.service

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory.fromAnyRef
import org.apache.flink.api.common.typeinfo.TypeInformation
import pl.touk.nussknacker.engine.api.component.ComponentDefinition
import pl.touk.nussknacker.engine.api.process.SourceFactory
import pl.touk.nussknacker.engine.build.ScenarioBuilder
import pl.touk.nussknacker.engine.canonicalgraph.CanonicalProcess
import pl.touk.nussknacker.engine.flink.test.FlinkSpec
import pl.touk.nussknacker.engine.flink.util.source.EmitWatermarkAfterEachElementCollectionSource
import pl.touk.nussknacker.engine.flink.util.transformer.FlinkBaseComponentProvider
import pl.touk.nussknacker.engine.graph.expression.Expression
import pl.touk.nussknacker.engine.process.helpers.ConfigCreatorWithCollectingListener
import pl.touk.nussknacker.engine.process.runner.UnitTestsFlinkRunner
import pl.touk.nussknacker.engine.testing.LocalModelData
import pl.touk.nussknacker.engine.testmode.{ResultsCollectingListener, ResultsCollectingListenerHolder, TestProcess}
import pl.touk.nussknacker.sql.db.pool.DBPoolConfig
import pl.touk.nussknacker.sql.db.schema.{JdbcMetaDataProvider, MetaDataProviderFactory}
import pl.touk.nussknacker.sql.utils.BaseHsqlQueryEnricherTest
import pl.touk.nussknacker.engine.spel.Implicits._

import java.io.{ByteArrayOutputStream, ObjectOutputStream}

case class TestRecord(id: Int = 1, timeHours: Int = 0) {
  def timestamp: Long = timeHours * 3600L * 1000
}

class DatabaseLookupEnricherSerializationTest extends BaseHsqlQueryEnricherTest with FlinkSpec {

  override val handleOpenAndClose: Boolean = false

  override val prepareHsqlDDLs: List[String] = List(
    "CREATE TABLE persons (id INT, name VARCHAR(40));",
    "INSERT INTO persons (id, name) VALUES (1, 'John')"
  )

  private def provider: DBPoolConfig => JdbcMetaDataProvider = (conf: DBPoolConfig) =>
    new MetaDataProviderFactory().create(conf)

  override val service = new DatabaseLookupEnricher(hsqlDbPoolConfig, provider(hsqlDbPoolConfig))

  private val sinkId                 = "end"
  private val resultVariableName     = "resultVar"
  private val dbLookupEnricherOutput = "dbVar"
  private val dbLookupNodeResultId   = "db-result"

  private def aProcessWithDbLookupNode(): CanonicalProcess = {
    val resultExpression: String = s"#$dbLookupEnricherOutput.NAME"
    return ScenarioBuilder
      .streaming("forEachProcess")
      .parallelism(1)
      .stateOnDisk(true)
      .source("start", "start")
      .enricher(
        id = "personEnricher",
        output = dbLookupEnricherOutput,
        svcId = "dbLookup",
        params = "Table" -> Expression.spel("'PERSONS'"),
        "Cache TTL"  -> Expression.spel("T(java.time.Duration).ofDays(1L)"),
        "Key column" -> Expression.spel("'ID'"),
        "Key value"  -> Expression.spel("#input.id")
      )
      .buildSimpleVariable(dbLookupNodeResultId, resultVariableName, resultExpression)
      .emptySink(sinkId, "dead-end")
  }

  test("Database lookup enricher should be serializable") {
    val fos = new ByteArrayOutputStream()
    val oos = new ObjectOutputStream(fos);
    oos.writeObject(service)
    oos.flush()
    oos.close()
  }

  private def initializeListener = ResultsCollectingListenerHolder.registerRun

  private def modelData(
      list: List[TestRecord] = List(),
      collectingListener: ResultsCollectingListener
  ): LocalModelData = {
    val modelConfig = ConfigFactory
      .empty()
      .withValue("useTypingResultTypeInformation", fromAnyRef(true))
    val sourceComponent = ComponentDefinition(
      "start",
      SourceFactory.noParam[TestRecord](
        EmitWatermarkAfterEachElementCollectionSource
          .create[TestRecord](list, _.timestamp, java.time.Duration.ofHours(1))(TypeInformation.of(classOf[TestRecord]))
      )
    )
    LocalModelData(
      modelConfig,
      (sourceComponent :: FlinkBaseComponentProvider.Components) ::: List(ComponentDefinition("dbLookup", service)),
      configCreator = new ConfigCreatorWithCollectingListener(collectingListener),
    )
  }

  private def collectTestResults[T](
      model: LocalModelData,
      testProcess: CanonicalProcess,
      collectingListener: ResultsCollectingListener
  ): TestProcess.TestResults = {
    runProcess(model, testProcess)
    collectingListener.results
  }

  private def runProcess(model: LocalModelData, testProcess: CanonicalProcess): Unit = {
    val stoppableEnv = flinkMiniCluster.createExecutionEnvironment()
    UnitTestsFlinkRunner.registerInEnvironmentWithModel(stoppableEnv, model)(testProcess)
    stoppableEnv.executeAndWaitForFinished(testProcess.name.value)()
  }

  test("should produce results for each element in list") {
    val collectingListener = initializeListener
    val model              = modelData(List(TestRecord(id = 1)), collectingListener)

    val testProcess = aProcessWithDbLookupNode()

    val results = collectTestResults[String](model, testProcess, collectingListener)
    extractResultValues(results) shouldBe List("John")
  }

  private def extractResultValues(results: TestProcess.TestResults): List[String] = results
    .nodeResults(sinkId)
    .map(_.get[String](resultVariableName).get)

}
