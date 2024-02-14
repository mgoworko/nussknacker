import { css, cx } from "@emotion/css";
import React, { useCallback } from "react";
import { ChangeableValue } from "./ChangeableValue";
import ValidationLabels from "./modals/ValidationLabels";
import { NodeTable } from "./graph/node-modal/NodeDetailsContent/NodeTable";
import { NodeInput, SelectNodeWithFocus } from "./withFocus";
import { getValidationErrorsForField } from "./graph/node-modal/editors/Validators";
import { FormControl, FormGroup, FormHelperText, FormLabel, Link, Typography } from "@mui/material";
import { Trans, useTranslation } from "react-i18next";
import StreamingIcon from "../assets/img/streaming.svg";
import RequestResponseIcon from "../assets/img/request-response.svg";
import BatchIcon from "../assets/img/batch.svg";
import { CustomRadio } from "./customRadio/CustomRadio";
import { ProcessingMode } from "../http/HttpService";
import { NodeValidationError } from "../types";
import NodeTip from "./graph/node-modal/NodeTip";
import InfoIcon from "@mui/icons-material/Info";

export type FormValue = { processName: string; processCategory: string; processingMode: string; processEngine: string };

interface AddProcessFormProps extends ChangeableValue<FormValue> {
    validationErrors: NodeValidationError[];
    categories: { value: string; disabled: boolean }[];
    processingModes: ProcessingMode[];
    engines: string[] | undefined;
}

export function AddProcessForm({
    value,
    onChange,
    validationErrors,
    categories,
    engines,
    processingModes,
}: AddProcessFormProps): JSX.Element {
    const { t } = useTranslation();
    const onFieldChange = useCallback((field: keyof FormValue, next: string) => onChange({ ...value, [field]: next }), [onChange, value]);

    return (
        <div
            className={cx(
                css({
                    paddingTop: 10,
                    paddingBottom: 20,
                }),
            )}
        >
            <NodeTable>
                <FormControl>
                    <FormLabel required>{t("addProcessForm.label.processingMode", "Processing mode")}</FormLabel>
                    <span className="node-value">
                        <FormGroup
                            row
                            sx={(theme) => ({ flexWrap: "nowrap", gap: theme.spacing(1.5) })}
                            onChange={(event) => {
                                const target = event.target as HTMLInputElement;
                                if (!target.checked) {
                                    onFieldChange("processingMode", "");
                                    return;
                                }

                                onFieldChange("processingMode", target.value);
                            }}
                        >
                            <CustomRadio
                                disabled={processingModes.every((processingMode) => processingMode !== ProcessingMode.streaming)}
                                label={t("addProcessForm.label.streaming", "Streaming")}
                                value={ProcessingMode.streaming}
                                Icon={StreamingIcon}
                                active={value.processingMode === ProcessingMode.streaming}
                            />
                            <CustomRadio
                                disabled={processingModes.every((processingMode) => processingMode !== ProcessingMode.requestResponse)}
                                label={t("addProcessForm.label.requestResponse", "Request-response")}
                                value={ProcessingMode.requestResponse}
                                Icon={RequestResponseIcon}
                                active={value.processingMode === ProcessingMode.requestResponse}
                            />
                            <CustomRadio
                                disabled={processingModes.every((processingMode) => processingMode !== ProcessingMode.batch)}
                                label={t("addProcessForm.label.batch", "Batch")}
                                value={ProcessingMode.batch}
                                Icon={BatchIcon}
                                active={value.processingMode === ProcessingMode.batch}
                            />
                        </FormGroup>
                        <ValidationLabels fieldErrors={getValidationErrorsForField(validationErrors, "processingMode")} />
                        <Typography component={"div"} variant={"overline"} mt={1}>
                            <Trans i18nKey={"addProcessForm.helperText.processingMode"}>
                                Processing mode defines how scenario deployed on an engine interacts with the outside world. Click here to
                                <Link
                                    sx={{ cursor: "pointer", ml: 0.5 }}
                                    href="https://nussknacker.io/documentation/about/ProcessingModes"
                                    target="_blank"
                                    rel="noopener noreferrer"
                                >
                                    learn more.
                                </Link>
                            </Trans>
                        </Typography>
                    </span>
                </FormControl>
                <FormControl>
                    <FormLabel required>{t("addProcessForm.label.name", "Name")}</FormLabel>
                    <div className="node-value">
                        <NodeInput
                            type="text"
                            id="newProcessName"
                            value={value.processName}
                            onChange={(e) => onFieldChange("processName", e.target.value)}
                        />
                        <ValidationLabels fieldErrors={getValidationErrorsForField(validationErrors, "processName")} />
                    </div>
                </FormControl>
                <FormControl>
                    <FormLabel required>{t("addProcessForm.label.category", "Category")}</FormLabel>
                    <div className="node-value">
                        <SelectNodeWithFocus
                            id="processCategory"
                            value={value.processCategory}
                            onChange={(e) => {
                                onFieldChange("processCategory", e.target.value);
                            }}
                        >
                            <>
                                <option value={""}></option>
                                {categories.map(({ value, disabled }, index) => (
                                    <option key={index} value={value} disabled={disabled}>
                                        {value}
                                    </option>
                                ))}
                            </>
                        </SelectNodeWithFocus>
                        <ValidationLabels fieldErrors={getValidationErrorsForField(validationErrors, "processCategory")} />

                        <Typography component={"div"} variant={"overline"} mt={1}>
                            <Trans i18nKey={"addProcessForm.helperText.category"}>
                                To read more about categories,
                                <Link
                                    sx={{ cursor: "pointer", ml: 0.5 }}
                                    href="https://nussknacker.io/documentation/docs/1.10/installation_configuration_guide/DesignerConfiguration/#scenario-type-categories"
                                    target="_blank"
                                    rel="noopener noreferrer"
                                >
                                    click here.
                                </Link>
                            </Trans>
                        </Typography>
                    </div>
                </FormControl>
                {engines && (
                    <FormControl>
                        <FormLabel required>{t("addProcessForm.label.engine", "Engine")}</FormLabel>
                        <div className="node-value">
                            <SelectNodeWithFocus
                                id="processEngine"
                                value={value.processEngine}
                                onChange={(e) => {
                                    onFieldChange("processEngine", e.target.value);
                                }}
                            >
                                <>
                                    <option value={""}></option>
                                    {engines.map((engine, index) => (
                                        <option key={index} value={engine}>
                                            {engine}
                                        </option>
                                    ))}
                                </>
                            </SelectNodeWithFocus>
                            {getValidationErrorsForField(validationErrors, "processEngine").map((engineError, index) => (
                                <FormHelperText key={index} error>
                                    {engineError.message}
                                </FormHelperText>
                            ))}
                            <Typography component={"div"} variant={"overline"} mt={1}>
                                <Trans i18nKey={"addProcessForm.helperText.engine"}>
                                    To read more about engines,
                                    <Link
                                        sx={{ cursor: "pointer", ml: 0.5 }}
                                        href="https://nussknacker.io/documentation/about/engines"
                                        target="_blank"
                                        rel="noopener noreferrer"
                                    >
                                        click here.
                                    </Link>
                                </Trans>
                            </Typography>
                        </div>
                    </FormControl>
                )}
            </NodeTable>
        </div>
    );
}
