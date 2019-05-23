/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.siddhi.core.aggregation;

import io.siddhi.core.config.SiddhiQueryContext;
import io.siddhi.core.event.ComplexEventChunk;
import io.siddhi.core.event.state.MetaStateEvent;
import io.siddhi.core.event.state.StateEvent;
import io.siddhi.core.event.stream.MetaStreamEvent;
import io.siddhi.core.event.stream.StreamEvent;
import io.siddhi.core.exception.SiddhiAppCreationException;
import io.siddhi.core.executor.ConstantExpressionExecutor;
import io.siddhi.core.executor.ExpressionExecutor;
import io.siddhi.core.executor.VariableExpressionExecutor;
import io.siddhi.core.query.input.stream.single.SingleStreamRuntime;
import io.siddhi.core.query.processor.ProcessingMode;
import io.siddhi.core.query.selector.GroupByKeyGenerator;
import io.siddhi.core.table.Table;
import io.siddhi.core.util.collection.operator.CompiledCondition;
import io.siddhi.core.util.collection.operator.IncrementalAggregateCompileCondition;
import io.siddhi.core.util.collection.operator.MatchingMetaInfoHolder;
import io.siddhi.core.util.parser.ExpressionParser;
import io.siddhi.core.util.parser.OperatorParser;
import io.siddhi.core.util.parser.helper.QueryParserHelper;
import io.siddhi.core.util.snapshot.SnapshotService;
import io.siddhi.core.util.statistics.LatencyTracker;
import io.siddhi.core.util.statistics.MemoryCalculable;
import io.siddhi.core.util.statistics.ThroughputTracker;
import io.siddhi.core.util.statistics.metrics.Level;
import io.siddhi.query.api.aggregation.TimePeriod;
import io.siddhi.query.api.aggregation.Within;
import io.siddhi.query.api.definition.AbstractDefinition;
import io.siddhi.query.api.definition.AggregationDefinition;
import io.siddhi.query.api.definition.Attribute;
import io.siddhi.query.api.definition.StreamDefinition;
import io.siddhi.query.api.exception.SiddhiAppValidationException;
import io.siddhi.query.api.expression.AttributeFunction;
import io.siddhi.query.api.expression.Expression;
import io.siddhi.query.api.expression.condition.Compare;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.siddhi.core.util.SiddhiConstants.AGG_EXTERNAL_TIMESTAMP_COL;
import static io.siddhi.core.util.SiddhiConstants.AGG_START_TIMESTAMP_COL;
import static io.siddhi.core.util.SiddhiConstants.UNKNOWN_STATE;
import static io.siddhi.query.api.expression.Expression.Time.normalizeDuration;

/**
 * Aggregation runtime managing aggregation operations for aggregation definition.
 */
public class AggregationRuntime implements MemoryCalculable {
    private final AggregationDefinition aggregationDefinition;
    private final Map<TimePeriod.Duration, IncrementalExecutor> incrementalExecutorMap;
    private final List<ExpressionExecutor> baseExecutorsForFind;
    private ExpressionExecutor shouldUpdateTimestamp;
    private final Map<TimePeriod.Duration, Table> aggregationTables;
    private final MetaStreamEvent tableMetaStreamEvent;
    private final MetaStreamEvent aggregateMetaSteamEvent;
    private final LatencyTracker latencyTrackerFind;
    private final ThroughputTracker throughputTrackerFind;
    private final List<GroupByKeyGenerator> groupByKeyGeneratorList;
    private List<TimePeriod.Duration> incrementalDurations;
    private SingleStreamRuntime singleStreamRuntime;
    private List<ExpressionExecutor> outputExpressionExecutors;
    private RecreateInMemoryData recreateInMemoryData;
    private boolean processingOnExternalTime;
    private boolean isFirstEventArrived;
    private IncrementalDataPurging incrementalDataPurging;
    private List<List<ExpressionExecutor>> aggregateProcessExpressionExecutorsListForFind;
    private List<String> tableAttributesNameList;
    private boolean isDistributed;

    public AggregationRuntime(AggregationDefinition aggregationDefinition,
                              Map<TimePeriod.Duration, IncrementalExecutor> incrementalExecutorMap,
                              Map<TimePeriod.Duration, Table> aggregationTables,
                              SingleStreamRuntime singleStreamRuntime,
                              List<TimePeriod.Duration> incrementalDurations,
                              MetaStreamEvent tableMetaStreamEvent,
                              List<ExpressionExecutor> outputExpressionExecutors,
                              LatencyTracker latencyTrackerFind, ThroughputTracker throughputTrackerFind,
                              RecreateInMemoryData recreateInMemoryData, boolean processingOnExternalTime,
                              List<GroupByKeyGenerator> groupByKeyGeneratorList,
                              IncrementalDataPurging incrementalDataPurging,
                              ExpressionExecutor shouldUpdateTimestamp, boolean isDistributed,
                              List<List<ExpressionExecutor>> aggregateProcessExpressionExecutorsListForFind) {
        this.aggregationDefinition = aggregationDefinition;
        this.incrementalExecutorMap = incrementalExecutorMap;
        this.aggregationTables = aggregationTables;
        this.incrementalDurations = incrementalDurations;
        this.singleStreamRuntime = singleStreamRuntime;
        this.tableMetaStreamEvent = tableMetaStreamEvent;
        this.outputExpressionExecutors = outputExpressionExecutors;
        this.latencyTrackerFind = latencyTrackerFind;
        this.throughputTrackerFind = throughputTrackerFind;
        this.recreateInMemoryData = recreateInMemoryData;
        this.processingOnExternalTime = processingOnExternalTime;
        this.groupByKeyGeneratorList = groupByKeyGeneratorList;
        this.incrementalDataPurging = incrementalDataPurging;
        this.shouldUpdateTimestamp = shouldUpdateTimestamp;
        this.aggregateProcessExpressionExecutorsListForFind = aggregateProcessExpressionExecutorsListForFind;
        this.aggregateMetaSteamEvent = new MetaStreamEvent();
        //Without timestamp executor
        this.baseExecutorsForFind = aggregateProcessExpressionExecutorsListForFind.get(0).subList(1,
                aggregateProcessExpressionExecutorsListForFind.get(0).size());
        aggregationDefinition.getAttributeList().forEach(aggregateMetaSteamEvent::addOutputData);
        this.tableAttributesNameList = tableMetaStreamEvent.getInputDefinitions().get(0).getAttributeList()
                .stream().map(Attribute::getName).collect(Collectors.toList());
        this.isDistributed = isDistributed;

    }

    private static void initMetaStreamEvent(MetaStreamEvent metaStreamEvent, AbstractDefinition inputDefinition,
                                            String inputReferenceId) {
        metaStreamEvent.addInputDefinition(inputDefinition);
        metaStreamEvent.setInputReferenceId(inputReferenceId);
        metaStreamEvent.initializeAfterWindowData();
        inputDefinition.getAttributeList().forEach(metaStreamEvent::addData);
    }

    private static void cloneStreamDefinition(StreamDefinition originalStreamDefinition,
                                              StreamDefinition newStreamDefinition) {
        for (Attribute attribute : originalStreamDefinition.getAttributeList()) {
            newStreamDefinition.attribute(attribute.getName(), attribute.getType());
        }
    }

    private static MetaStreamEvent createNewMetaStreamEventWithStartEnd(MatchingMetaInfoHolder matchingMetaInfoHolder,
                                                                        List<Attribute> additionalAttributes) {
        MetaStreamEvent metaStreamEventWithStartEnd;
        StreamDefinition streamDefinitionWithStartEnd = new StreamDefinition();

        if (matchingMetaInfoHolder.getMetaStateEvent().getMetaStreamEvents().length == 1) {
            metaStreamEventWithStartEnd = new MetaStreamEvent();
        } else {
            metaStreamEventWithStartEnd = matchingMetaInfoHolder.getMetaStateEvent()
                    .getMetaStreamEvent(matchingMetaInfoHolder.getMatchingStreamEventIndex());
            cloneStreamDefinition((StreamDefinition) metaStreamEventWithStartEnd.getLastInputDefinition(),
                    streamDefinitionWithStartEnd);
        }

        additionalAttributes.forEach(attribute ->
        streamDefinitionWithStartEnd.attribute(attribute.getName(), attribute.getType())
        );
        initMetaStreamEvent(metaStreamEventWithStartEnd, streamDefinitionWithStartEnd,
                matchingMetaInfoHolder.getMetaStateEvent().getMetaStreamEvent(0).getInputReferenceId());
        return metaStreamEventWithStartEnd;
    }

    private static MatchingMetaInfoHolder alterMetaInfoHolderForStoreQuery(
            MetaStreamEvent newMetaStreamEventWithStartEnd, MatchingMetaInfoHolder matchingMetaInfoHolder) {
        MetaStateEvent metaStateEvent = new MetaStateEvent(2);
        MetaStreamEvent incomingMetaStreamEvent = matchingMetaInfoHolder.getMetaStateEvent().getMetaStreamEvent(0);
        metaStateEvent.addEvent(newMetaStreamEventWithStartEnd);
        metaStateEvent.addEvent(incomingMetaStreamEvent);

        return new MatchingMetaInfoHolder(metaStateEvent, 0, 1,
                newMetaStreamEventWithStartEnd.getLastInputDefinition(),
                incomingMetaStreamEvent.getLastInputDefinition(), UNKNOWN_STATE);
    }

    private static MatchingMetaInfoHolder createNewStreamTableMetaInfoHolder(
            MetaStreamEvent metaStreamEventWithStartEnd, AbstractDefinition tableDefinition, String referenceId) {
        MetaStateEvent metaStateEvent = new MetaStateEvent(2);
        MetaStreamEvent metaStreamEventForTable = new MetaStreamEvent();

        metaStreamEventForTable.setEventType(MetaStreamEvent.EventType.TABLE);
        initMetaStreamEvent(metaStreamEventForTable, tableDefinition, referenceId);

        metaStateEvent.addEvent(metaStreamEventWithStartEnd);
        metaStateEvent.addEvent(metaStreamEventForTable);
        return new MatchingMetaInfoHolder(metaStateEvent, 0, 1,
                metaStreamEventWithStartEnd.getLastInputDefinition(),
                tableDefinition, UNKNOWN_STATE);
    }

    public AggregationDefinition getAggregationDefinition() {
        return aggregationDefinition;
    }

    public SingleStreamRuntime getSingleStreamRuntime() {
        return singleStreamRuntime;
    }

    public StreamEvent find(StateEvent matchingEvent, CompiledCondition compiledCondition,
                            SiddhiQueryContext siddhiQueryContext) {
        try {
            SnapshotService.getSkipStateStorageThreadLocal().set(true);
            if (latencyTrackerFind != null &&
                    Level.BASIC.compareTo(siddhiQueryContext.getSiddhiAppContext().getRootMetricsLevel()) <= 0) {
                latencyTrackerFind.markIn();
                throughputTrackerFind.eventIn();
            }
            if (!isFirstEventArrived) {
                this.recreateInMemoryData.recreateInMemoryData();
            }
            return ((IncrementalAggregateCompileCondition) compiledCondition).find(matchingEvent,
                    aggregationDefinition, incrementalExecutorMap, aggregationTables, incrementalDurations,
                    baseExecutorsForFind, outputExpressionExecutors, siddhiQueryContext,
                    aggregateProcessExpressionExecutorsListForFind,
                    groupByKeyGeneratorList, shouldUpdateTimestamp,
                    isDistributed);
        } finally {
            SnapshotService.getSkipStateStorageThreadLocal().set(null);
            if (latencyTrackerFind != null &&
                    Level.BASIC.compareTo(siddhiQueryContext.getSiddhiAppContext().getRootMetricsLevel()) <= 0) {
                latencyTrackerFind.markOut();
            }
        }
    }

    public CompiledCondition compileExpression(Expression expression, Within within, Expression per,
                                               MatchingMetaInfoHolder matchingMetaInfoHolder,
                                               List<VariableExpressionExecutor> variableExpressionExecutors,
                                               Map<String, Table> tableMap, SiddhiQueryContext siddhiQueryContext) {

        Map<TimePeriod.Duration, CompiledCondition> withinTableCompiledConditions = new HashMap<>();
        CompiledCondition withinInMemoryCompileCondition;
        CompiledCondition onCompiledCondition;
        List<Attribute> additionalAttributes = new ArrayList<>();

        // Define additional attribute list
        additionalAttributes.add(new Attribute("_START", Attribute.Type.LONG));
        additionalAttributes.add(new Attribute("_END", Attribute.Type.LONG));

        int durationsSize = this.incrementalDurations.size() - 1;
        if (isDistributed) {
            //Add additional attributes to get base aggregation timestamps based on current timestamps
            // for values calculated in inmemory in the shards
            for (int i = 0; i < durationsSize - 1; i++) {
                Attribute attribute = new Attribute("_AGG_TIMESTAMP_FILTER_" + i, Attribute.Type.LONG);
                additionalAttributes.add(attribute);
            }
        }

        // Get table definition. Table definitions for all the tables used to persist aggregates are similar.
        // Therefore it's enough to get the definition from one table.
        AbstractDefinition tableDefinition = ((Table) aggregationTables.values().toArray()[0]).getTableDefinition();

        // Alter existing meta stream event or create new one if a meta stream doesn't exist
        // After calling this method the original MatchingMetaInfoHolder's meta stream event would be altered
        MetaStreamEvent newMetaStreamEventWithStartEnd = createNewMetaStreamEventWithStartEnd(matchingMetaInfoHolder,
                additionalAttributes);
        MatchingMetaInfoHolder alteredMatchingMetaInfoHolder = null;

        // Alter meta info holder to contain stream event and aggregate both when it's a store query
        if (matchingMetaInfoHolder.getMetaStateEvent().getMetaStreamEvents().length == 1) {
            matchingMetaInfoHolder = alterMetaInfoHolderForStoreQuery(newMetaStreamEventWithStartEnd,
                    matchingMetaInfoHolder);
            alteredMatchingMetaInfoHolder = matchingMetaInfoHolder;
        }

        // Create new MatchingMetaInfoHolder containing newMetaStreamEventWithStartEnd and table meta event
        MatchingMetaInfoHolder streamTableMetaInfoHolderWithStartEnd = createNewStreamTableMetaInfoHolder(
                newMetaStreamEventWithStartEnd, tableDefinition,
                matchingMetaInfoHolder.getMetaStateEvent().getMetaStreamEvent(1).getInputReferenceId());

        // Create per expression executor
        ExpressionExecutor perExpressionExecutor;
        if (per != null) {
            perExpressionExecutor = ExpressionParser.parseExpression(per,
                    matchingMetaInfoHolder.getMetaStateEvent(),
                    matchingMetaInfoHolder.getCurrentState(), tableMap, variableExpressionExecutors,
                    false, 0, ProcessingMode.BATCH, false, siddhiQueryContext);
            if (perExpressionExecutor.getReturnType() != Attribute.Type.STRING) {
                throw new SiddhiAppCreationException(
                        "Query " + siddhiQueryContext.getName() + "'s per value expected a string but found "
                                + perExpressionExecutor.getReturnType(),
                        per.getQueryContextStartIndex(), per.getQueryContextEndIndex());
            }
            // Additional Per time function verification at compile time if it is a constant
            if (perExpressionExecutor instanceof ConstantExpressionExecutor) {
                String perValue = ((ConstantExpressionExecutor) perExpressionExecutor).getValue().toString();
                try {
                    normalizeDuration(perValue);
                } catch (SiddhiAppValidationException e) {
                    throw new SiddhiAppValidationException(
                            "Aggregation Query's per value is expected to be of a valid time function of the " +
                                    "following " + TimePeriod.Duration.SECONDS + ", " + TimePeriod.Duration.MINUTES
                                    + ", " + TimePeriod.Duration.HOURS + ", " + TimePeriod.Duration.DAYS + ", "
                                    + TimePeriod.Duration.MONTHS + ", " + TimePeriod.Duration.YEARS + ".");
                }
            }
        } else {
            throw new SiddhiAppCreationException("Syntax Error: Aggregation join query must contain a `per` " +
                    "definition for granularity");
        }

        // Create within expression
        Expression timeFilterExpression;
        if (processingOnExternalTime) {
            timeFilterExpression = Expression.variable(AGG_EXTERNAL_TIMESTAMP_COL);
        } else {
            timeFilterExpression = Expression.variable(AGG_START_TIMESTAMP_COL);
        }
        Expression withinExpression;
        Expression start = Expression.variable(additionalAttributes.get(0).getName());
        Expression end = Expression.variable(additionalAttributes.get(1).getName());
        Expression compareWithStartTime = Compare.compare(start, Compare.Operator.LESS_THAN_EQUAL,
                timeFilterExpression);
        Expression compareWithEndTime = Compare.compare(timeFilterExpression, Compare.Operator.LESS_THAN, end);
        withinExpression = Expression.and(compareWithStartTime, compareWithEndTime);

        // Create start and end time expression
        Expression startEndTimeExpression;
        ExpressionExecutor startTimeEndTimeExpressionExecutor;
        if (within != null) {
            if (within.getTimeRange().size() == 1) {
                startEndTimeExpression = new AttributeFunction("incrementalAggregator",
                        "startTimeEndTime", within.getTimeRange().get(0));
            } else { // within.getTimeRange().size() == 2
                startEndTimeExpression = new AttributeFunction("incrementalAggregator",
                        "startTimeEndTime", within.getTimeRange().get(0), within.getTimeRange().get(1));
            }
            startTimeEndTimeExpressionExecutor = ExpressionParser.parseExpression(startEndTimeExpression,
                    matchingMetaInfoHolder.getMetaStateEvent(), matchingMetaInfoHolder.getCurrentState(), tableMap,
                    variableExpressionExecutors, false, 0,
                    ProcessingMode.BATCH, false, siddhiQueryContext);
        } else {
            throw new SiddhiAppCreationException("Syntax Error : Aggregation read query must contain a `within` " +
                    "definition for filtering of aggregation data.");
        }

        List<ExpressionExecutor> timestampFilterExecutors = new ArrayList<>();
        if (isDistributed) {
            for (int i = 0; i < durationsSize - 1; i++) {
                Expression[] expressionArray = new Expression[]{
                        new AttributeFunction("", "currentTimeMillis", null),
                        Expression.value(this.incrementalDurations.get(i).toString())};
                Expression filterExpression = new AttributeFunction("incrementalAggregator",
                        "getAggregationStartTime", expressionArray);
                timestampFilterExecutors.add(ExpressionParser.parseExpression(filterExpression,
                        matchingMetaInfoHolder.getMetaStateEvent(), matchingMetaInfoHolder.getCurrentState(), tableMap,
                        variableExpressionExecutors, false, 0,
                        ProcessingMode.BATCH, false, siddhiQueryContext));
            }
        }

        // Create compile condition per each table used to persist aggregates.
        // These compile conditions are used to check whether the aggregates in tables are within the given duration.
        // Combine with and on condition for table query
        AggregationExpressionBuilder aggregationExpressionBuilder = new AggregationExpressionBuilder(expression);
        AggregationExpressionVisitor expressionVisitor = new AggregationExpressionVisitor(
                newMetaStreamEventWithStartEnd.getInputReferenceId(),
                newMetaStreamEventWithStartEnd.getLastInputDefinition().getAttributeList(),
                this.tableAttributesNameList
        );
        aggregationExpressionBuilder.build(expressionVisitor);

        Expression withinExpressionTable = Expression.and(withinExpression, expressionVisitor.getReducedExpression());
        List<VariableExpressionExecutor> startEndExpressionExecutorList = new ArrayList<>();
        for (Map.Entry<TimePeriod.Duration, Table> entry : aggregationTables.entrySet()) {
            CompiledCondition withinTableCompileCondition = entry.getValue().compileCondition(withinExpressionTable,
                    streamTableMetaInfoHolderWithStartEnd, startEndExpressionExecutorList, tableMap,
                    siddhiQueryContext);
            withinTableCompiledConditions.put(entry.getKey(), withinTableCompileCondition);
        }

        // Create compile condition for in-memory data.
        // This compile condition is used to check whether the running aggregates (in-memory data)
        // are within given duration
        withinInMemoryCompileCondition = OperatorParser.constructOperator(new ComplexEventChunk<>(true),
                withinExpression, streamTableMetaInfoHolderWithStartEnd, startEndExpressionExecutorList,
                tableMap, siddhiQueryContext);

        QueryParserHelper.reduceMetaComplexEvent(streamTableMetaInfoHolderWithStartEnd.getMetaStateEvent());
        QueryParserHelper.updateVariablePosition(streamTableMetaInfoHolderWithStartEnd.getMetaStateEvent(),
                startEndExpressionExecutorList);

        // On compile condition.
        // After finding all the aggregates belonging to within duration, the final on condition (given as
        // "on stream1.name == aggregator.nickName ..." in the join query) must be executed on that data.
        // This condition is used for that purpose.
        onCompiledCondition = OperatorParser.constructOperator(new ComplexEventChunk<>(true), expression,
                matchingMetaInfoHolder, variableExpressionExecutors, tableMap, siddhiQueryContext);

        return new IncrementalAggregateCompileCondition(withinTableCompiledConditions, withinInMemoryCompileCondition,
                onCompiledCondition, tableMetaStreamEvent, aggregateMetaSteamEvent, additionalAttributes,
                alteredMatchingMetaInfoHolder, perExpressionExecutor, startTimeEndTimeExpressionExecutor,
                timestampFilterExecutors, processingOnExternalTime);
    }

    public void startPurging() {
        incrementalDataPurging.executeIncrementalDataPurging();
    }

    public void recreateInMemoryData() {
        // State only updated when first event arrives to IncrementalAggregationProcessor
        this.isFirstEventArrived = true;
        for (Map.Entry<TimePeriod.Duration, IncrementalExecutor> durationIncrementalExecutorEntry :
                this.incrementalExecutorMap.entrySet()) {
            durationIncrementalExecutorEntry.getValue().setProcessingExecutor(true);
        }
        this.recreateInMemoryData.recreateInMemoryData();
    }

    public void processEvents(ComplexEventChunk<StreamEvent> streamEventComplexEventChunk) {
        incrementalExecutorMap.get(incrementalDurations.get(0)).execute(streamEventComplexEventChunk);
    }
}
