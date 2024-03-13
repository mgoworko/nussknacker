package pl.touk.nussknacker.ui.api

import com.typesafe.config.Config
import io.circe.Json
import io.circe.syntax.EncoderOps
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers
import pl.touk.nussknacker.engine.api.typed.typing.{Typed, TypedTaggedValue}
import pl.touk.nussknacker.test.WithTestHttpClient
import pl.touk.nussknacker.test.base.it.NuItTest
import pl.touk.nussknacker.test.config.WithSimplifiedDesignerConfig.TestProcessingType.Streaming
import pl.touk.nussknacker.test.config.{ConfigWithScalaVersion, WithDesignerConfig}
import pl.touk.nussknacker.ui.api.DictResources.{DictListElement, DictListRequest, TypingResultJson}
import sttp.client3.{UriContext, quickRequest}
import sttp.model.{MediaType, StatusCode}

class DictsResourcesTest
    extends AnyFunSuiteLike
    with NuItTest
    with WithDesignerConfig
    with WithTestHttpClient
    with Matchers {

  override def designerConfig: Config = ConfigWithScalaVersion.TestsConfigWithEmbeddedEngine

  test("return list of available dictionaries for DictParameterEditor - String") {
    val response = httpClient.send(
      quickRequest
        .post(
          uri"$nuDesignerHttpAddress/api/dicts/${Streaming.stringify}"
        )
        .contentType(MediaType.ApplicationJson)
        .body(
          DictListRequest(TypingResultJson(Typed[String].asJson)).asJson.spaces2
        )
        .auth
        .basic("admin", "admin")
    )

    response.code shouldEqual StatusCode.Ok
    response.bodyAsJson shouldEqual Json.arr(
      DictListElement("rgb", TypedTaggedValue(underlying = Typed.typedClass[String], tag = "dictValue:rgb")).asJson,
      DictListElement("bc", TypedTaggedValue(underlying = Typed.typedClass[String], tag = "dictValue:bc")).asJson,
      DictListElement("dict", TypedTaggedValue(underlying = Typed.typedClass[String], tag = "dictValue:dict")).asJson
    )
  }

  test("return list of available dictionaries for DictParameterEditor - Long") {
    val response = httpClient.send(
      quickRequest
        .post(
          uri"$nuDesignerHttpAddress/api/dicts/${Streaming.stringify}"
        )
        .contentType(MediaType.ApplicationJson)
        .body(
          DictListRequest(TypingResultJson(Typed[Long].asJson)).asJson.spaces2
        )
        .auth
        .basic("admin", "admin")
    )

    response.code shouldEqual StatusCode.Ok
    response.bodyAsJson shouldEqual Json.arr(
      DictListElement("long_dict", Typed[java.lang.Long]).asJson,
    )

  }

  test("fail for bad request") {
    val response = httpClient.send(
      quickRequest
        .post(
          uri"$nuDesignerHttpAddress/api/dicts/${Streaming.stringify}"
        )
        .contentType(MediaType.ApplicationJson)
        .body(
          DictListRequest(TypingResultJson(Json.fromString("qwerty"))).asJson.spaces2
        )
        .auth
        .basic("admin", "admin")
    )

    response.code shouldEqual StatusCode.BadRequest
  }

  test("return suggestions for existing prefix") {
    val DictId = "rgb"

    val response1 = httpClient.send(
      quickRequest
        .get(
          uri"$nuDesignerHttpAddress/api/dicts/${Streaming.stringify}/$DictId/entry?label=${"Black".take(2)}"
        )
        .auth
        .basic("admin", "admin")
    )
    response1.code shouldEqual StatusCode.Ok
    response1.bodyAsJson shouldEqual Json.arr(
      Json.obj(
        "key"   -> Json.fromString("H000000"),
        "label" -> Json.fromString("Black")
      ),
      Json.obj(
        "key"   -> Json.fromString("H0000ff"),
        "label" -> Json.fromString("Blue")
      )
    )
  }

  test("return 0 suggestions for non-existing prefix") {
    val DictId = "rgb"

    val response1 = httpClient.send(
      quickRequest
        .get(
          uri"$nuDesignerHttpAddress/api/dicts/${Streaming.stringify}/$DictId/entry?label=thisPrefixDoesntExist"
        )
        .auth
        .basic("admin", "admin")
    )
    response1.code shouldEqual StatusCode.Ok
    response1.bodyAsJson shouldEqual Json.arr()
  }

}
