import React, { useCallback, useMemo } from "react";
import { FixedValuesPresets, Parameter, VariableTypes } from "../../../../types";
import { mandatoryValueValidator, uniqueListValueValidator, Validator } from "../editors/Validators";
import { DndItems } from "../../../common/dndItems/DndItems";
import { NodeRowFieldsProvider } from "../node-row-fields-provider";
import { Item, onChangeType, FragmentInputParameter } from "./item";

export interface Option {
    value: string;
    label: string;
}

interface FieldsSelectProps {
    addField: () => void;
    label: string;
    fields: Parameter[];
    namespace: string;
    onChange: (path: string, value: onChangeType) => void;
    options: Option[];
    removeField: (path: string, uuid: string) => void;
    readOnly?: boolean;
    showValidation?: boolean;
    variableTypes: VariableTypes;
    fixedValuesPresets: FixedValuesPresets;
}

export function FieldsSelect(props: FieldsSelectProps): JSX.Element {
    const {
        fields,
        label,
        namespace,
        options,
        onChange,
        variableTypes,
        removeField,
        addField,
        readOnly,
        showValidation,
        fixedValuesPresets,
    } = props;

    const ItemElement = useCallback(
        ({ index, item, validators }: { index: number; item: FragmentInputParameter; validators: Validator[] }) => {
            return (
                <Item
                    index={index}
                    item={item}
                    validators={validators}
                    namespace={namespace}
                    onChange={onChange}
                    options={options}
                    readOnly={readOnly}
                    variableTypes={variableTypes}
                    showValidation={showValidation}
                    fixedValuesPresets={fixedValuesPresets}
                />
            );
        },
        [namespace, onChange, options, readOnly, variableTypes, showValidation, fixedValuesPresets],
    );

    const changeOrder = useCallback((value) => onChange(namespace, value), [namespace, onChange]);

    const items = useMemo(
        () =>
            fields.map((item: any, index, list) => {
                const validators = [
                    mandatoryValueValidator,
                    uniqueListValueValidator(
                        list.map((v) => v.name),
                        index,
                    ),
                ];

                return { item, el: <ItemElement key={index} index={index} item={item} validators={validators} /> };
            }),
        [ItemElement, fields],
    );

    return (
        <NodeRowFieldsProvider label={label} path={namespace} onFieldAdd={addField} onFieldRemove={removeField} readOnly={readOnly}>
            <DndItems disabled={readOnly} items={items} onChange={changeOrder} />
        </NodeRowFieldsProvider>
    );
}
