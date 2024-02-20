import { SourceSinkCommon } from "./SourceSinkCommon";
import React from "react";
import { NodeType, NodeValidationError, UIParameter } from "../../../types";
import ProcessUtils from "../../../common/ProcessUtils";

interface SourceProps {
    errors: NodeValidationError[];
    findAvailableVariables?: ReturnType<typeof ProcessUtils.findAvailableVariables>;
    parameterDefinitions: UIParameter[];
    showSwitch?: boolean;
    node: NodeType;
    renderFieldLabel: (paramName: string) => JSX.Element;
    setProperty: <K extends keyof NodeType>(property: K, newValue: NodeType[K], defaultValue?: NodeType[K]) => void;
    showValidation?: boolean;
    isEditMode?: boolean;
}

export function Source({
    renderFieldLabel,
    setProperty,
    showSwitch,
    errors,
    findAvailableVariables,
    node,
    parameterDefinitions,
    isEditMode,
    showValidation,
}: SourceProps): JSX.Element {
    return (
        <SourceSinkCommon
            isEditMode={isEditMode}
            showValidation={showValidation}
            showSwitch={showSwitch}
            node={node}
            findAvailableVariables={findAvailableVariables}
            parameterDefinitions={parameterDefinitions}
            errors={errors}
            renderFieldLabel={renderFieldLabel}
            setProperty={setProperty}
        />
    );
}
