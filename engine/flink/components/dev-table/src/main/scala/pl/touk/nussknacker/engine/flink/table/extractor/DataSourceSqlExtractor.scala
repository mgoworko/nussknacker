package pl.touk.nussknacker.engine.flink.table.extractor

import com.typesafe.scalalogging.LazyLogging
import org.apache.flink.table.api.{EnvironmentSettings, Table, TableEnvironment, TableResult}
import org.apache.flink.table.catalog.{CatalogBaseTable, ObjectPath}
import org.apache.flink.table.types.DataType
import pl.touk.nussknacker.engine.api.typed.typing.{Typed, TypingResult}
import pl.touk.nussknacker.engine.flink.table.extractor.SqlDataSourceConfig.{Connector, Format}
import pl.touk.nussknacker.engine.flink.table.extractor.SqlStatementReader.SqlStatement

import scala.util.Try

object DataSourceSqlExtractor extends LazyLogging {

  import scala.jdk.CollectionConverters._
  import scala.jdk.OptionConverters.RichOptional

  private val connectorKey        = "connector"
  private val formatKey           = "format"
  private val builtInCatalogName  = "defaultCatalog"
  private val buildInDatabaseName = "defaultDatabase"

  // TODO: validate duplicate table names
  def extractTablesFromFlinkRuntime(
      createTableStatements: List[SqlStatement]
  ): List[Either[SqlExtractorError, SqlDataSourceConfig]] = {
    val settings = EnvironmentSettings
      .newInstance()
      .withBuiltInCatalogName(builtInCatalogName)
      .withBuiltInDatabaseName(buildInDatabaseName)
      .build()
    val tableEnv = TableEnvironment.create(settings)

    /**
     * Access to `CatalogBaseTable` (carrying schema details such as connector, format, and options) requires the name
     * of the catalog/database. This code uses the default catalog and database.
     * Assumptions:
     *  - The executed sql statements create tables in the default catalog and database
     *
     * @see <a href="https://nightlies.apache.org/flink/flink-docs-release-1.18/docs/dev/table/catalogs/#registering-a-catalog">Flink docs source </a>
     */
    val catalog = tableEnv.getCatalog(tableEnv.getCurrentCatalog).toScala match {
      case Some(value) => value
      case None =>
        throw new IllegalStateException(
          "Default catalog was not found during parsing of sql for generic table components."
        )
    }

    /**
     *  1. Execution of arbitrary sql statement in temp context
     *  1. Extraction of schema and metadata from the first (only) table in the env
     *  1. Dropping the table so we have guarantee that there are is only one table in the env
     *
     *  Assumptions:
     *   - There are no tables registered by default
     **/
    def extractOne(
        statement: SqlStatement
    ): Either[SqlExtractorError, SqlDataSourceConfig] = {
      implicit val statementImplicit: SqlStatement = statement
      for {
        _ <- tryExecuteStatement(statement, tableEnv)
        // Need to grab table from env because table from result has wrong schema
        tableName <- tableEnv
          .listTables()
          .headOption
          .toRight(SqlExtractorError(TableNotCreatedOrCreatedOutsideOfContext, None))

        // CatalogBaseTable - contains metadata
        tablePath                 = new ObjectPath(tableEnv.getCurrentDatabase, tableName)
        tableWithUnresolvedSchema = catalog.getTable(tablePath)
        metaData <- extractMetaData(tableWithUnresolvedSchema)

        // Table with resolved schema - contains resolved column types
        tableFromEnv            = tableEnv.from(tableName)
        (columns, typingResult) = extractSchema(tableFromEnv)

        _ <- tryExecuteStatement(s"DROP TABLE $tableName", tableEnv)
      } yield SqlDataSourceConfig(
        tableName,
        metaData.connector,
        DataSourceSchema(columns),
        typingResult,
        statement
      )
    }

    val tablesResults = createTableStatements.map(s => extractOne(s))
    tablesResults
  }

  private def tryExecuteStatement(
      sqlStatement: SqlStatement,
      env: TableEnvironment
  ): Either[SqlExtractorError, TableResult] =
    Try(env.executeSql(sqlStatement)).toEither.left.map(e =>
      SqlExtractorError(StatementNotExecuted, Some(e))(sqlStatement)
    )

  private final case class TableMetaData(connector: Connector, format: Format)

  // TODO: extract format - have to fail on formats that are not on classpath
  private def extractMetaData(
      tableWithMetadata: CatalogBaseTable
  )(implicit statement: SqlStatement): Either[SqlExtractorError, TableMetaData] = {
    for {
      connector <- tableWithMetadata.getOptions.asScala.get(connectorKey).toRight(SqlExtractorError(ConnectorMissing))
      format    <- tableWithMetadata.getOptions.asScala.get(formatKey).toRight(SqlExtractorError(FormatMissing))
    } yield TableMetaData(connector, format)
  }

  private def extractSchema(tableFromEnv: Table) = {
    val (columns, columnsTypingData) = tableFromEnv.getResolvedSchema.getColumns.asScala
      .map { column =>
        val name     = column.getName
        val dataType = column.getDataType
        (Column(name, dataType), name -> columnClassToTypingData(dataType))
      }
      .toList
      .unzip
    columns -> Typed.record(columnsTypingData.toMap)
  }

  // TODO: handle complex data types - Maps, Arrays, Rows, Raw
  private def columnClassToTypingData(dataType: DataType) =
    Typed.typedClass(dataType.getLogicalType.getDefaultConversion)

}

final case class SqlDataSourceConfig(
    tableName: String,
    connector: Connector,
    schema: DataSourceSchema,
    typingResult: TypingResult,
    sqlCreateTableStatement: SqlStatement
)

object SqlDataSourceConfig {
  type Format    = String
  type Connector = String
}

// TODO: flatten this?
final case class DataSourceSchema(columns: List[Column])
final case class Column(name: String, dataType: DataType)
