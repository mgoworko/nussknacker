import React from "react";
import { SettingLabelStyled } from "./StyledSettingsComponnets";
import { useTranslation } from "react-i18next";
import { FixedValuesType, onChangeType, FixedValuesOption, FixedListParameterVariant } from "../../../item";
import { ListItems } from "./ListItems";
import { Option, TypeSelect } from "../../../TypeSelect";
import { FixedValuesPresets, NodeValidationError, ReturnedType, VariableTypes } from "../../../../../../../types";
import { UserDefinedListInput } from "./UserDefinedListInput";
import { FieldsControl } from "../../../../node-row-fields-provider/FieldsControl";
import { Box, FormControl } from "@mui/material";

interface FixedValuesSetting extends Pick<FixedListParameterVariant, "presetSelection"> {
    onChange: (path: string, value: onChangeType) => void;
    path: string;
    fixedValuesType: FixedValuesType;
    fixedValuesList: FixedValuesOption[];
    fixedValuesPresets: FixedValuesPresets;
    fixedValuesListPresetId: string;
    readOnly: boolean;
    variableTypes: VariableTypes;
    errors: NodeValidationError[];
    typ: ReturnedType;
    name: string;
    initialValue: FixedValuesOption;
}

export function FixedValuesSetting({
    path,
    fixedValuesType,
    onChange,
    fixedValuesListPresetId,
    fixedValuesPresets,
    fixedValuesList,
    readOnly,
    variableTypes,
    errors,
    typ,
    name,
    initialValue,
}: FixedValuesSetting) {
    const { t } = useTranslation();

    const presetListOptions: Option[] = Object.keys(fixedValuesPresets ?? {}).map((key) => ({ label: key, value: key }));

    const selectedPresetValueExpressions: Option[] = (fixedValuesPresets?.[fixedValuesListPresetId]?.values ?? []).map(
        (selectedPresetValueExpression) => ({ label: selectedPresetValueExpression.label, value: selectedPresetValueExpression.label }),
    );

    console.log(presetListOptions);
    console.log(errors);
    return (
        <>
            {fixedValuesType === FixedValuesType.ValueInputWithFixedValuesPreset && (
                <FormControl>
                    <SettingLabelStyled required>{t("fragment.presetSelection", "Preset selection:")}</SettingLabelStyled>
                    <Box width={"100%"} flex={1}>
                        <TypeSelect
                            readOnly={readOnly}
                            onChange={(value) => {
                                onChange(`${path}.valueEditor.fixedValuesPresetId`, value);
                                onChange(`${path}.initialValue`, null);
                            }}
                            value={presetListOptions.find((presetListOption) => presetListOption.value === fixedValuesListPresetId)}
                            options={presetListOptions}
                            fieldErrors={[]}
                        />
                        {selectedPresetValueExpressions?.length > 0 && (
                            <ListItems
                                items={selectedPresetValueExpressions}
                                errors={errors}
                                fieldName={`$param.${name}.$fixedValuesPresets`}
                            />
                        )}
                    </Box>
                </FormControl>
            )}
            {fixedValuesType === FixedValuesType.ValueInputWithFixedValuesProvided && (
                <UserDefinedListInput
                    fixedValuesList={fixedValuesList}
                    variableTypes={variableTypes}
                    readOnly={readOnly}
                    onChange={onChange}
                    path={path}
                    errors={errors}
                    typ={typ}
                    name={name}
                    initialValue={initialValue}
                />
            )}
        </>
    );
}
