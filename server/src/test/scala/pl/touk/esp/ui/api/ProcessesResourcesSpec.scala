package pl.touk.esp.ui.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import argonaut.Argonaut._
import cats.data.Validated
import org.scalatest._
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Millis, Seconds, Span}
import pl.touk.esp.engine.api.deployment._
import pl.touk.esp.engine.canonicalgraph.CanonicalProcess
import pl.touk.esp.engine.canonize.ProcessCanonizer
import pl.touk.esp.engine.graph.exceptionhandler.ExceptionHandlerRef
import pl.touk.esp.engine.graph.param.Parameter
import pl.touk.esp.engine.marshall.ProcessMarshaller
import pl.touk.esp.engine.marshall.ProcessUnmarshallError.ProcessJsonDecodeError
import pl.touk.esp.ui.api.helpers.EspItTest
import pl.touk.esp.ui.api.helpers.TestFactory._
import pl.touk.esp.ui.process.displayedgraph.{DisplayableProcess, ProcessProperties}
import pl.touk.esp.ui.process.marshall.{DisplayableProcessCodec, UiProcessMarshaller}
import pl.touk.esp.ui.process.repository.ProcessRepository
import pl.touk.esp.ui.process.repository.ProcessRepository.ProcessDetails
import pl.touk.esp.ui.sample
import pl.touk.esp.ui.sample.SampleProcess
import pl.touk.esp.ui.security.{LoggedUser, Permission}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.higherKinds

class ProcessesResourcesSpec extends FlatSpec with ScalatestRouteTest with Matchers with Inside
  with ScalaFutures with OptionValues with Eventually with BeforeAndAfterEach with BeforeAndAfterAll with EspItTest  {

  implicit override val patienceConfig = PatienceConfig(timeout = scaled(Span(1, Seconds)), interval = scaled(Span(100, Millis)))
  implicit val testtimeout = RouteTestTimeout(2.seconds)

  val routeWithRead = withPermissions(processesRoute, Permission.Read)
  val routeWithWrite = withPermissions(processesRoute, Permission.Write)
  val routWithAllPermissions = withAllPermissions(processesRoute)
  val routWithAdminPermission = withPermissions(processesRoute, Permission.Admin)

  implicit val loggedUser = LoggedUser("lu", "", List(), List(testCategory))

  private val processId: String = SampleProcess.process.id

  it should "return list of process details" in {
    saveProcess(processId, ProcessTestData.validProcess) {
      Get("/processes") ~> routWithAllPermissions ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] should include (processId)
      }
    }
  }

  it should "return 404 when no process" in {
    Get("/processes/123") ~> routWithAllPermissions ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "return sample process details" in {
    saveProcess(processId, ProcessTestData.validProcess) {
      Get(s"/processes/$processId") ~> routWithAllPermissions ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] should include (processId)
      }
    }
  }

  it should "return 400 when trying to update json of custom process" in {
    whenReady(processRepository.saveProcess("customProcess", CustomProcess(""))) { _ =>
      saveProcess("customProcess", SampleProcess.process) {
        status shouldEqual StatusCodes.BadRequest
      }
    }
  }

  it should "save correct process json with ok status" in {
    saveProcess(SampleProcess.process.id, ProcessTestData.validProcess) {
      status shouldEqual StatusCodes.OK
      checkSampleProcessRootIdEquals(ProcessTestData.validProcess.root.id)
      val json = entityAs[String].parseOption.value
      json.field("invalidNodes").flatMap(_.obj).value.isEmpty shouldBe true
    }
  }

  it should "save invalid process json with ok status but with non empty invalid nodes" in {
    saveProcess(SampleProcess.process.id, ProcessTestData.invalidProcess) {
      status shouldEqual StatusCodes.OK
      checkSampleProcessRootIdEquals(ProcessTestData.invalidProcess.root.id)
      val json = entityAs[String].parseOption.value
      json.field("invalidNodes").flatMap(_.obj).value.isEmpty shouldBe false
    }
  }

  it should "return one latest version for process" in {
    saveProcess(SampleProcess.process.id, ProcessTestData.validProcess) { status shouldEqual StatusCodes.OK}
    saveProcess(SampleProcess.process.id, ProcessTestData.invalidProcess) { status shouldEqual StatusCodes.OK}

    Get("/processes") ~> routWithAllPermissions ~> check {
      status shouldEqual StatusCodes.OK
      val resp = responseAs[String].decodeOption[List[ProcessDetails]].get
      withClue(resp) {
        resp.count(_.id == SampleProcess.process.id) shouldBe 1
      }
    }
  }


  it should "save new process with default category" in {
    saveProcess(SampleProcess.process.id, ProcessTestData.validProcess) { status shouldEqual StatusCodes.OK}
    Get(s"/processes/${SampleProcess.process.id}") ~> routWithAllPermissions ~> check {
      val processDetails = responseAs[String].decodeOption[ProcessDetails].get
      processDetails.processCategory shouldBe ProcessRepository.defaultCategory
    }

  }

  it should "return process if user has category" in {
    saveProcess(SampleProcess.process.id, ProcessTestData.validProcess) { status shouldEqual StatusCodes.OK}
    processRepository.updateCategory(SampleProcess.process.id, testCategory)

    Get(s"/processes/${SampleProcess.process.id}") ~> routWithAllPermissions ~> check {
      val processDetails = responseAs[String].decodeOption[ProcessDetails].get
      processDetails.processCategory shouldBe testCategory
    }

    Get(s"/processes") ~> routeWithRead ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] should include (SampleProcess.process.id)
    }

  }

  it should "not return processes not in user categories" in {
    saveProcess(SampleProcess.process.id, ProcessTestData.validProcess) { status shouldEqual StatusCodes.OK}
    processRepository.updateCategory(SampleProcess.process.id, "newCategory")
    Get(s"/processes/${SampleProcess.process.id}") ~> routeWithRead ~> check {
      status shouldEqual StatusCodes.NotFound
    }

    Get(s"/processes") ~> routeWithRead ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldBe "[]"
    }
  }

  it should "return all processes for admin user" in {
    saveProcess(SampleProcess.process.id, ProcessTestData.validProcess) { status shouldEqual StatusCodes.OK}
    processRepository.updateCategory(SampleProcess.process.id, "newCategory")

    Get(s"/processes/${SampleProcess.process.id}") ~> routWithAdminPermission ~> check {
      val processDetails = responseAs[String].decodeOption[ProcessDetails].get
      processDetails.processCategory shouldBe "newCategory"
    }

    Get(s"/processes") ~> routWithAdminPermission ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] should include (SampleProcess.process.id)
    }
  }

  it should "save process history" in {
    saveProcess(SampleProcess.process.id, ProcessTestData.validProcess) {
      status shouldEqual StatusCodes.OK
    }
    saveProcess(SampleProcess.process.id, ProcessTestData.validProcess.copy(root = ProcessTestData.validProcess.root.copy(data = ProcessTestData.validProcess.root.data.copy(id = "AARGH")))) {
      status shouldEqual StatusCodes.OK
    }
    Get(s"/processes/${SampleProcess.process.id}") ~> routWithAllPermissions ~> check {
      val processDetails = responseAs[String].decodeOption[ProcessDetails].get
      processDetails.name shouldBe SampleProcess.process.id
      processDetails.history.length shouldBe 2
      processDetails.history.forall(_.processName == SampleProcess.process.id) shouldBe true
    }
  }

  it should "perform idempotent process save" in {
    saveSampleProcess()
    Get(s"/processes/${SampleProcess.process.id}") ~> routWithAllPermissions ~> check {
      val processHistoryBeforeDuplicatedWrite = responseAs[String].decodeOption[ProcessDetails].get.history
      saveSampleProcess()
      Get(s"/processes/${SampleProcess.process.id}") ~> routWithAllPermissions ~> check {
        val processHistoryAfterDuplicatedWrite = responseAs[String].decodeOption[ProcessDetails].get.history
        processHistoryAfterDuplicatedWrite shouldBe processHistoryBeforeDuplicatedWrite
      }
    }
  }

  it should "not authorize user with read permissions to modify node" in {
    Put(s"/processes/$testCategory/$processId/json", posting.toEntity(ProcessTestData.validProcess)) ~> routeWithRead ~> check {
      rejection shouldBe server.AuthorizationFailedRejection
    }

    val modifiedParallelism = 123
    val modifiedName = "fooBarName"
    val props = ProcessProperties(Some(modifiedParallelism), ExceptionHandlerRef(List(Parameter(modifiedName, modifiedName))), None)
    Put(s"/processes/$testCategory/$processId/json/properties", posting.toEntity(props)) ~> routeWithRead ~> check {
      rejection shouldBe server.AuthorizationFailedRejection
    }

  }

  it should "save displayable process" in {
    val processToSave = ProcessTestData.sampleDisplayableProcess
    saveProcess(processToSave) {
      status shouldEqual StatusCodes.OK
    }

    Get(s"/processes/${processToSave.id}") ~> routWithAllPermissions ~> check {
      val processDetails = responseAs[String].decodeOption[ProcessDetails].get
      processDetails.json.get shouldBe processToSave
    }
  }

  def saveSampleProcess() = {
    saveProcess(SampleProcess.process.id, ProcessTestData.validProcess) { status shouldEqual StatusCodes.OK}
  }

  def checkSampleProcessRootIdEquals(expected: String) = {
    fetchSampleProcess()
      .map(_.nodes.head.id)
      .futureValue shouldEqual expected
  }

  def fetchSampleProcess(): Future[CanonicalProcess] = {
    processRepository
      .fetchLatestProcessVersion(SampleProcess.process.id)
      .map(_.getOrElse(sys.error("Sample process missing")))
      .map { version =>
        val parsed = UiProcessMarshaller().fromJson(version.json.get)
        parsed.valueOr(_ => sys.error("Invalid process json"))
      }
  }
}