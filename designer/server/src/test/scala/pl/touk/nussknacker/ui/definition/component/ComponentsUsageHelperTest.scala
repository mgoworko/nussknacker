package pl.touk.nussknacker.ui.definition.component

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.{TableDrivenPropertyChecks, TableFor2}
import pl.touk.nussknacker.engine.api.component.ComponentType._
import pl.touk.nussknacker.engine.api.component._
import pl.touk.nussknacker.engine.api.deployment.{ProcessAction, ProcessActionId, ProcessActionState, ProcessActionType}
import pl.touk.nussknacker.engine.api.displayedgraph.DisplayableProcess
import pl.touk.nussknacker.engine.api.process.VersionId
import pl.touk.nussknacker.engine.api.typed.typing.Typed
import pl.touk.nussknacker.engine.api.{FragmentSpecificData, MetaData}
import pl.touk.nussknacker.engine.build.{GraphBuilder, ScenarioBuilder}
import pl.touk.nussknacker.engine.canonicalgraph.canonicalnode.FlatNode
import pl.touk.nussknacker.engine.canonicalgraph.{CanonicalProcess, canonicalnode}
import pl.touk.nussknacker.engine.definition.component.CustomComponentSpecificData
import pl.touk.nussknacker.engine.definition.component.bultin.BuiltInComponentsDefinitionsPreparer
import pl.touk.nussknacker.engine.definition.fragment.FragmentComponentDefinitionExtractor
import pl.touk.nussknacker.engine.graph.node.FragmentInputDefinition.{FragmentClazzRef, FragmentParameter}
import pl.touk.nussknacker.engine.graph.node.{Case, CustomNode, FragmentInputDefinition, FragmentOutputDefinition}
import pl.touk.nussknacker.engine.modelconfig.ComponentsUiConfig
import pl.touk.nussknacker.engine.testing.ModelDefinitionBuilder
import pl.touk.nussknacker.restmodel.component.NodeUsageData.ScenarioUsageData
import pl.touk.nussknacker.restmodel.component.{NodeUsageData, ScenarioComponentsUsages}
import pl.touk.nussknacker.ui.api.helpers.ProcessTestData._
import pl.touk.nussknacker.ui.api.helpers.TestProcessUtil._
import pl.touk.nussknacker.ui.api.helpers.TestProcessingTypes
import pl.touk.nussknacker.ui.api.helpers.TestProcessingTypes._
import pl.touk.nussknacker.ui.definition.AlignedComponentsDefinitionProvider
import pl.touk.nussknacker.ui.process.marshall.ProcessConverter
import pl.touk.nussknacker.ui.process.repository.{ScenarioComponentsUsagesHelper, ScenarioWithDetailsEntity}

import java.time.Instant
import java.util.UUID

class ComponentsUsageHelperTest extends AnyFunSuite with Matchers with TableDrivenPropertyChecks {

  import pl.touk.nussknacker.engine.spel.Implicits._

  private val fragment = CanonicalProcess(
    MetaData("fragment1", FragmentSpecificData()),
    List(
      canonicalnode.FlatNode(
        FragmentInputDefinition("start", List(FragmentParameter("ala", FragmentClazzRef[String])))
      ),
      canonicalnode.FlatNode(CustomNode("f1", None, otherExistingStreamTransformer2, List.empty)),
      FlatNode(FragmentOutputDefinition("out1", "output", List.empty))
    ),
    List.empty
  )

  private val fragmentScenario = displayableToScenarioWithDetailsEntity(
    ProcessConverter.toDisplayable(fragment),
    fragment.name
  )

  private val process1 = ScenarioBuilder
    .streaming("fooProcess1")
    .source("source", existingSourceFactory)
    .customNode("custom", "out1", existingStreamTransformer)
    .customNode("custom2", "out2", otherExistingStreamTransformer)
    .emptySink("sink", existingSinkFactory)

  private val processDetails1 =
    displayableToScenarioWithDetailsEntity(ProcessConverter.toDisplayable(process1), process1.name)

  private val processDetails1ButDeployed = processDetails1.copy(lastAction =
    Option(
      ProcessAction(
        id = ProcessActionId(UUID.randomUUID()),
        processId = processDetails1.processId,
        processVersionId = VersionId.initialVersionId,
        user = "user",
        createdAt = Instant.now(),
        performedAt = Instant.now(),
        actionType = ProcessActionType.Deploy,
        state = ProcessActionState.Finished,
        failureMessage = Option.empty,
        commentId = Option.empty,
        comment = Option.empty,
        buildInfo = Map.empty
      )
    )
  )

  private val process2 = ScenarioBuilder
    .streaming("fooProcess2")
    .source("source", existingSourceFactory)
    .customNode("custom", "out1", otherExistingStreamTransformer)
    .emptySink("sink", existingSinkFactory)

  private val processDetails2 =
    displayableToScenarioWithDetailsEntity(ProcessConverter.toDisplayable(process2), process2.name)

  private val processWithSomeBasesStreaming = ScenarioBuilder
    .streaming("processWithSomeBasesStreaming")
    .source("source", existingSourceFactory)
    .filter("checkId", "#input.id != null")
    .filter("checkId2", "#input.id != null")
    .switch(
      "switchStreaming",
      "#input.id != null",
      "output",
      Case("'1'", GraphBuilder.emptySink("out1", existingSinkFactory)),
      Case("'2'", GraphBuilder.emptySink("out2", existingSinkFactory2))
    )

  private val processDetailsWithSomeBasesStreaming = displayableToScenarioWithDetailsEntity(
    ProcessConverter.toDisplayable(processWithSomeBasesStreaming),
    processWithSomeBasesStreaming.name
  )

  private val processWithSomeBasesFraud = ScenarioBuilder
    .streaming("processWithSomeBases")
    .source("source", existingSourceFactory)
    .filter("checkId", "#input.id != null")
    .switch(
      "switchFraud",
      "#input.id != null",
      "output",
      Case("'1'", GraphBuilder.emptySink("out1", existingSinkFactory)),
      Case("'2'", GraphBuilder.emptySink("out2", existingSinkFactory2))
    )

  private val processDetailsWithSomeBasesFraud = displayableToScenarioWithDetailsEntity(
    ProcessConverter.toDisplayable(processWithSomeBasesFraud),
    processWithSomeBasesFraud.name,
    TestProcessingTypes.Fraud
  )

  private val processWithFragment = ScenarioBuilder
    .streaming("processWithSomeBases")
    .source("source", existingSourceFactory)
    .customNode("custom", "outCustom", otherExistingStreamTransformer2)
    .fragment(
      fragment.name.value,
      fragment.name.value,
      Nil,
      Map.empty,
      Map(
        "sink" -> GraphBuilder.emptySink("sink", existingSinkFactory)
      )
    )

  private val processDetailsWithFragment =
    displayableToScenarioWithDetailsEntity(
      ProcessConverter.toDisplayable(processWithFragment),
      processWithFragment.name
    )

  private def nonFragmentComponents(determineDesignerWideId: ComponentId => DesignerWideComponentId) = {
    val modelDefinition = ModelDefinitionBuilder
      .empty(Map.empty)
      .withDesignerWideComponentIdDeterminingStrategy(determineDesignerWideId)
      .withSink(existingSinkFactory)
      .withSink(existingSinkFactory2)
      .withSource(existingSourceFactory)
      .withCustom(
        otherExistingStreamTransformer,
        Some(Typed[String]),
        CustomComponentSpecificData(manyInputs = false, canBeEnding = false),
        componentGroupName = None,
        designerWideComponentId = Some(DesignerWideComponentId(overriddenOtherExistingStreamTransformer))
      )
      .withCustom(
        otherExistingStreamTransformer2,
        Some(Typed[String]),
        CustomComponentSpecificData(manyInputs = false, canBeEnding = false),
      )
      .build

    val alignedComponentsDefinitionProvider = new AlignedComponentsDefinitionProvider(
      new BuiltInComponentsDefinitionsPreparer(new ComponentsUiConfig(Map.empty, Map.empty)),
      new FragmentComponentDefinitionExtractor(getClass.getClassLoader, Some(_), determineDesignerWideId),
      modelDefinition
    )

    alignedComponentsDefinitionProvider
      .getAlignedComponentsWithBuiltInComponentsAndFragments(
        forFragment = false,
        fragments = List.empty
      )
  }

  private val processingTypeAndInfoToNonFragmentDesignerWideId =
    (nonFragmentComponents(DesignerWideComponentId.default(TestProcessingTypes.Streaming, _)).map { component =>
      (TestProcessingTypes.Streaming, component.id) -> component.designerWideId
    } ::: nonFragmentComponents(DesignerWideComponentId.default(TestProcessingTypes.Fraud, _)).map { component =>
      (TestProcessingTypes.Fraud, component.id) -> component.designerWideId
    }).toMap

  test("should compute components usage count") {
    val table = Table(
      ("processesDetails", "expectedData"),
      (List.empty, Map.empty),
      (
        List(processDetails2, processDetailsWithSomeBasesStreaming),
        Map(
          sid(Sink, existingSinkFactory)                -> 2,
          sid(Sink, existingSinkFactory2)               -> 1,
          sid(Source, existingSourceFactory)            -> 2,
          oid(overriddenOtherExistingStreamTransformer) -> 1,
          bid(BuiltInComponentId.Choice)                -> 1,
          bid(BuiltInComponentId.Filter)                -> 2
        )
      ),
      (
        List(processDetails2, fragmentScenario),
        Map(
          sid(Sink, existingSinkFactory)                        -> 1,
          sid(Source, existingSourceFactory)                    -> 1,
          oid(overriddenOtherExistingStreamTransformer)         -> 1,
          sid(CustomComponent, otherExistingStreamTransformer2) -> 1,
          bid(BuiltInComponentId.FragmentInputDefinition)       -> 1,
          bid(BuiltInComponentId.FragmentOutputDefinition)      -> 1
        )
      ),
      (
        List(processDetails2, processDetailsWithSomeBasesStreaming, fragmentScenario),
        Map(
          sid(Sink, existingSinkFactory)                        -> 2,
          sid(Sink, existingSinkFactory2)                       -> 1,
          sid(Source, existingSourceFactory)                    -> 2,
          oid(overriddenOtherExistingStreamTransformer)         -> 1,
          sid(CustomComponent, otherExistingStreamTransformer2) -> 1,
          bid(BuiltInComponentId.Choice)                        -> 1,
          bid(BuiltInComponentId.Filter)                        -> 2,
          bid(BuiltInComponentId.FragmentInputDefinition)       -> 1,
          bid(BuiltInComponentId.FragmentOutputDefinition)      -> 1
        )
      ),
      (
        List(processDetailsWithSomeBasesFraud, processDetailsWithSomeBasesStreaming),
        Map(
          sid(Sink, existingSinkFactory)     -> 1,
          sid(Sink, existingSinkFactory2)    -> 1,
          sid(Source, existingSourceFactory) -> 1,
          fid(Sink, existingSinkFactory)     -> 1,
          fid(Sink, existingSinkFactory2)    -> 1,
          fid(Source, existingSourceFactory) -> 1,
          bid(BuiltInComponentId.Choice)     -> 2,
          bid(BuiltInComponentId.Filter)     -> 3
        )
      ),
      (
        List(processDetailsWithFragment, fragmentScenario),
        Map(
          sid(Source, existingSourceFactory)                    -> 1,
          sid(Sink, existingSinkFactory)                        -> 1,
          sid(Fragment, fragment.name.value)                    -> 1,
          sid(CustomComponent, otherExistingStreamTransformer2) -> 2,
          bid(BuiltInComponentId.FragmentInputDefinition)       -> 1,
          bid(BuiltInComponentId.FragmentOutputDefinition)      -> 1
        )
      ),
      (
        List(fragmentScenario, fragmentScenario),
        Map(
          sid(CustomComponent, otherExistingStreamTransformer2) -> 2,
          bid(BuiltInComponentId.FragmentInputDefinition)       -> 2,
          bid(BuiltInComponentId.FragmentOutputDefinition)      -> 2
        )
      )
    )

    forAll(table) { (processesDetails, expectedData) =>
      val result = ComponentsUsageHelper.computeComponentsUsageCount(
        withComponentsUsages(processesDetails),
        processingTypeAndInfoToNonFragmentDesignerWideId
      )
      result shouldBe expectedData
    }
  }

  test("should compute components usage") {
    val table: TableFor2[
      List[ScenarioWithDetailsEntity[DisplayableProcess]],
      Map[DesignerWideComponentId, List[
        (ScenarioWithDetailsEntity[DisplayableProcess], List[NodeUsageData])
      ]]
    ] = Table(
      ("processesDetails", "expected"),
      (List.empty, Map.empty),
      (
        List(processDetails1ButDeployed),
        Map(
          sid(Source, existingSourceFactory) -> List((processDetails1ButDeployed, List(ScenarioUsageData("source")))),
          sid(CustomComponent, existingStreamTransformer) -> List(
            (processDetails1ButDeployed, List(ScenarioUsageData("custom")))
          ),
          oid(overriddenOtherExistingStreamTransformer) -> List(
            (processDetails1ButDeployed, List(ScenarioUsageData("custom2")))
          ),
          sid(Sink, existingSinkFactory) -> List((processDetails1ButDeployed, List(ScenarioUsageData("sink")))),
        )
      ),
      (
        List(processDetails1ButDeployed, processDetails2),
        Map(
          sid(Source, existingSourceFactory) -> List(
            (processDetails1ButDeployed, List(ScenarioUsageData("source"))),
            (processDetails2, List(ScenarioUsageData("source")))
          ),
          sid(CustomComponent, existingStreamTransformer) -> List(
            (processDetails1ButDeployed, List(ScenarioUsageData("custom")))
          ),
          oid(overriddenOtherExistingStreamTransformer) -> List(
            (processDetails1ButDeployed, List(ScenarioUsageData("custom2"))),
            (processDetails2, List(ScenarioUsageData("custom")))
          ),
          sid(Sink, existingSinkFactory) -> List(
            (processDetails1ButDeployed, List(ScenarioUsageData("sink"))),
            (processDetails2, List(ScenarioUsageData("sink")))
          ),
        )
      ),
      (
        List(processDetailsWithSomeBasesStreaming, processDetailsWithSomeBasesFraud),
        Map(
          sid(Source, existingSourceFactory) -> List(
            (processDetailsWithSomeBasesStreaming, List(ScenarioUsageData("source")))
          ),
          sid(Sink, existingSinkFactory) -> List(
            (processDetailsWithSomeBasesStreaming, List(ScenarioUsageData("out1")))
          ),
          sid(Sink, existingSinkFactory2) -> List(
            (processDetailsWithSomeBasesStreaming, List(ScenarioUsageData("out2")))
          ),
          bid(BuiltInComponentId.Filter) -> List(
            (processDetailsWithSomeBasesFraud, List(ScenarioUsageData("checkId"))),
            (processDetailsWithSomeBasesStreaming, List(ScenarioUsageData("checkId"), ScenarioUsageData("checkId2")))
          ),
          bid(BuiltInComponentId.Choice) -> List(
            (processDetailsWithSomeBasesFraud, List(ScenarioUsageData("switchFraud"))),
            (processDetailsWithSomeBasesStreaming, List(ScenarioUsageData("switchStreaming")))
          ),
          fid(Source, existingSourceFactory) -> List(
            (processDetailsWithSomeBasesFraud, List(ScenarioUsageData("source")))
          ),
          fid(Sink, existingSinkFactory)  -> List((processDetailsWithSomeBasesFraud, List(ScenarioUsageData("out1")))),
          fid(Sink, existingSinkFactory2) -> List((processDetailsWithSomeBasesFraud, List(ScenarioUsageData("out2")))),
        )
      ),
      (
        List(processDetailsWithFragment, fragmentScenario),
        Map(
          sid(Source, existingSourceFactory) -> List((processDetailsWithFragment, List(ScenarioUsageData("source")))),
          sid(CustomComponent, otherExistingStreamTransformer2) -> List(
            (processDetailsWithFragment, List(ScenarioUsageData("custom"))),
            (fragmentScenario, List(ScenarioUsageData("f1")))
          ),
          sid(Sink, existingSinkFactory) -> List((processDetailsWithFragment, List(ScenarioUsageData("sink")))),
          sid(Fragment, fragment.name.value) -> List(
            (processDetailsWithFragment, List(ScenarioUsageData(fragment.name.value)))
          ),
          bid(BuiltInComponentId.FragmentInputDefinition) -> List(
            (fragmentScenario, List(ScenarioUsageData("start")))
          ),
          bid(BuiltInComponentId.FragmentOutputDefinition) -> List(
            (fragmentScenario, List(ScenarioUsageData("out1")))
          ),
        )
      )
    )

    forAll(table) { (processesDetails, expected) =>
      import pl.touk.nussknacker.engine.util.Implicits._

      val result = ComponentsUsageHelper
        .computeComponentsUsage(
          withComponentsUsages(processesDetails),
          processingTypeAndInfoToNonFragmentDesignerWideId
        )
        .mapValuesNow(_.map { case (baseProcessDetails, nodeIds) =>
          (baseProcessDetails.mapScenario(_ => ()), nodeIds)
        })

      result should have size expected.size

      withEmptyProcess(expected).foreach { case (componentId, usages) =>
        withClue(s"componentId: $componentId") {
          result(componentId) should contain theSameElementsAs usages
        }
      }
    }
  }

  private def sid(componentType: ComponentType, id: String) =
    DesignerWideComponentId.default(Streaming, ComponentId(componentType, id))

  private def fid(componentType: ComponentType, id: String) =
    DesignerWideComponentId.default(Fraud, ComponentId(componentType, id))

  private def bid(componentId: ComponentId) = DesignerWideComponentId.forBuiltInComponent(componentId)

  private def oid(overriddenName: String) = DesignerWideComponentId(overriddenName)

  private def withComponentsUsages(
      processesDetails: List[ScenarioWithDetailsEntity[DisplayableProcess]]
  ): List[ScenarioWithDetailsEntity[ScenarioComponentsUsages]] = {
    processesDetails.map { details =>
      details.mapScenario(p => ScenarioComponentsUsagesHelper.compute(toCanonical(p, details.name)))
    }
  }

  private def withEmptyProcess(
      usagesMap: Map[DesignerWideComponentId, List[(ScenarioWithDetailsEntity[_], List[NodeUsageData])]]
  ): Map[DesignerWideComponentId, List[(ScenarioWithDetailsEntity[Unit], List[NodeUsageData])]] = {
    usagesMap.transform { case (_, usages) =>
      usages.map { case (processDetails, nodeIds) => (processDetails.mapScenario(_ => ()), nodeIds) }
    }
  }

}
