package io.opentelemetry.contrib.generator.telemetry.logs;

import io.opentelemetry.contrib.generator.core.dto.GeneratorEntity;
import io.opentelemetry.contrib.generator.telemetry.EntityModelProvider;
import io.opentelemetry.contrib.generator.telemetry.GeneratorsStateProvider;
import io.opentelemetry.contrib.generator.telemetry.dto.GeneratorState;
import io.opentelemetry.contrib.generator.telemetry.jel.JELProvider;
import io.opentelemetry.contrib.generator.telemetry.logs.dto.LogDefinition;
import io.opentelemetry.contrib.generator.telemetry.misc.GeneratorUtils;
import io.opentelemetry.contrib.generator.telemetry.transport.PayloadHandler;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationLibrary;
import io.opentelemetry.proto.logs.v1.InstrumentationLibraryLogs;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.logs.v1.LogRecord;
import jakarta.el.ELProcessor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Main thread class which generates and posts log packets to the specified destination via PayloadHandler.
 */
@Slf4j
public class LogGeneratorThread implements Runnable {

    private final String requestID;
    @Getter
    private final LogDefinition logDefinition;
    private final PayloadHandler payloadHandler;
    private final GeneratorState<LogGeneratorThread> logGeneratorState;
    private final ELProcessor jelProcessor;
    private int currentPayloadCount;

    public LogGeneratorThread(LogDefinition logDefinition, PayloadHandler payloadHandler, String requestID) {
        this.requestID = requestID;
        this.logDefinition = logDefinition;
        this.payloadHandler = payloadHandler;
        this.logGeneratorState = GeneratorsStateProvider.getLogGeneratorState(requestID);
        jelProcessor = JELProvider.getJelProcessor();
        currentPayloadCount = 0;
    }

    @Override
    public void run() {
        log.debug(requestID + ": Log generator thread invoked for Log Definition type: " + logDefinition.getName());
        if (logGeneratorState.isGenerateData() &&
                logGeneratorState.getThreadPayloadCounts().get(logDefinition.getName()) < logDefinition.getPayloadCount()) {
            List<ResourceLogs> resourceLogsList = new ArrayList<>();
            ResourceLogs resourceLog;
            LogRecord logRecord = getLog(logDefinition);
            List<LogRecord> otelLogs = Collections.nCopies(logDefinition.getCopyCount(), logRecord);
            for (Map.Entry<String, Integer> reportingEntity : logDefinition.getReportingEntitiesCounts().entrySet()) {
                List<Resource> postToEntities = getEntitySubsetByPostCount(reportingEntity.getKey(), reportingEntity.getValue());
                log.debug(requestID + ": Preparing " + postToEntities.size() + " resource logs packets for " + reportingEntity);
                for (Resource eachResource: postToEntities) {
                    resourceLog = ResourceLogs.newBuilder()
                            .setResource(eachResource)
                            .addInstrumentationLibraryLogs(InstrumentationLibraryLogs.newBuilder()
                                    .setInstrumentationLibrary(InstrumentationLibrary.newBuilder()
                                            .setName("@opentelemetry/vodka-exporter")
                                            .setVersion("22.5.0")
                                            .build())
                                    .addAllLogs(otelLogs)
                                    .build())
                            .build();
                    resourceLogsList.add(resourceLog);
                }
                log.info(requestID + ": Sending payload for: " + reportingEntity);
                ExportLogsServiceRequest resourceLogs = ExportLogsServiceRequest.newBuilder().addAllResourceLogs(resourceLogsList).build();
                boolean responseStatus = payloadHandler.postPayload(resourceLogs);
                if (logGeneratorState.getTransportStorage() != null) {
                    logGeneratorState.getTransportStorage().store(logDefinition.getName(), reportingEntity.getKey(), resourceLogs, responseStatus);
                }
                log.debug(requestID + ": Complete payload for entity: " + reportingEntity + " in event Definition" + logDefinition.getName() + ": " + resourceLogs);
                resourceLogsList.clear();
            }
            currentPayloadCount++;
            logGeneratorState.getThreadPayloadCounts().put(logDefinition.getName(), currentPayloadCount);
        }
    }

    private LogRecord getLog(LogDefinition logDefinition) {
        String severity = jelProcessor.eval(logDefinition.getSeverityOrderFunction()).toString();
        return LogRecord.newBuilder()
                .setName(logDefinition.getName())
                .setTimeUnixNano(TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis()))
                .setSeverityText(severity)
                .addAllAttributes(GeneratorUtils.getEvaluatedAttributes(jelProcessor, logDefinition.getAttributes()))
                .setBody(AnyValue.newBuilder().setStringValue(LogMessageProvider.getLogMessage(severity)).build())
                .build();
    }

    private List<Resource> getEntitySubsetByPostCount(String entityName, int entityCount) {
        int entityStartIndex = 0;
        //entityEndIndex is exclusive
        int entityEndIndex;
        List<GeneratorEntity> entitiesInEntityModel = EntityModelProvider.getEntityModel(requestID).get(entityName).stream()
                .filter(GeneratorEntity::isActive).collect(Collectors.toList());
        if (entityCount >= entitiesInEntityModel.size()) {
            entityEndIndex = entitiesInEntityModel.size();
        } else {
            entityStartIndex = currentPayloadCount % (entitiesInEntityModel.size() - entityCount + 1);
            entityEndIndex = entityStartIndex + entityCount;
        }
        return entitiesInEntityModel.subList(entityStartIndex, entityEndIndex)
                .stream().map(GeneratorEntity::getOTelResource).collect(Collectors.toList());
    }
}