import React, { useState } from "react";
import { useAutocomplete } from "@mui/material";
import httpService from "../../../../../../http/HttpService";
import { getScenario } from "../../../../../../reducers/selectors/graph";
import { useSelector } from "react-redux";
import { debounce } from "lodash";

export const DictParameterEditor = (props) => {
    const scenario = useSelector(getScenario);

    const [options, setOptions] = useState([]);
    const [value, setValue] = React.useState(JSON.parse(JSON.stringify({ label: "label", key: "key" })));
    const [inputValue, setInputValue] = React.useState("");
    const { groupedOptions, getInputProps, getOptionProps, getListboxProps } = useAutocomplete({
        options,
        onOpen: async (event) => {
            const response = await httpService.fetchProcessDefinitionDataDict(
                scenario.processingType,
                props.param.editor.dictId,
                inputValue,
            );
            setOptions(response.data);
        },
        isOptionEqualToValue: (option, value) => true,
        getOptionLabel: (option) => option.label,
        onChange: (event, value, reason, details) => {
            console.log("value", value);
            props.onValueChange(value?.key || "");
            setValue(value);
        },
        value,
        inputValue,
        onInputChange: async (event, value, reason) => {
            const test = debounce(async () => {
                const response = await httpService.fetchProcessDefinitionDataDict(
                    scenario.processingType,
                    props.param.editor.dictId,
                    value,
                );
                setOptions(response.data);
            }, 500);
            test();
            setInputValue(value);
        },
    });

    return (
        <div>
            <input {...getInputProps()} />
            <ul {...getListboxProps()}>
                {groupedOptions.map((option, index) => (
                    <li {...getOptionProps({ option, index })}>{option.label}</li>
                ))}
            </ul>
        </div>
    );
};
