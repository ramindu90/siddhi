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
    private AggregationDefinition aggregationDefinition;
    private boolean isProcessingOnExternalTime;
    private boolean isDistributed;
    private List<TimePeriod.Duration> incrementalDurations;
    private Map<TimePeriod.Duration, IncrementalExecutor> incrementalExecutorMap;
    private Map<TimePeriod.Duration, Table> aggregationTables;
    private List<String> tableAttributesNameList;
    private MetaStreamEvent aggregateMetaSteamEvent;
    private List<ExpressionExecutor> outputExpressionExecutors;
    private Map<TimePeriod.Duration, List<ExpressionExecutor>> aggregateProcessingExecutorsMap;
    private ExpressionExecutor shouldUpdateTimestamp;
    private Map<TimePeriod.Duration, GroupByKeyGenerator> groupByKeyGeneratorMap;

    private IncrementalDataPurger incrementalDataPurger;
    private IncrementalExecutorsInitialiser incrementalExecutorsInitialiser;

    private SingleStreamRuntime singleStreamRuntime;

    private LatencyTracker latencyTrackerFind;
    private ThroughputTracker throughputTrackerFind;

    private boolean isFirstEventArrived;

    public AggregationRuntime(AggregationDefinition aggregationDefinition, boolean isProcessingOnExternalTime,
                              boolean isDistributed, List<TimePeriod.Duration> incrementalDurations,
                              Map<TimePeriod.Duration, IncrementalExecutor> incrementalExecutorMap,
                              Map<TimePeriod.Duration, Table> aggregationTables,
                              List<ExpressionExecutor> outputExpressionExecutors,
                              Map<TimePeriod.Duration, List<ExpressionExecutor>> aggregateProcessingExecutorsMap,
                              ExpressionExecutor shouldUpdateTimestamp,
                              Map<TimePeriod.Duration, GroupByKeyGenerator> groupByKeyGeneratorMap,
                              IncrementalDataPurger incrementalDataPurger,
                              IncrementalExecutorsInitialiser incrementalExecutorInitialiser,
                              SingleStreamRuntime singleStreamRuntime, MetaStreamEvent tableMetaStreamEvent,
                              LatencyTracker latencyTrackerFind, ThroughputTracker throughputTrackerFind) {

        this.aggregationDefinition = aggregationDefinition;
        this.isProcessingOnExternalTime = isProcessingOnExternalTime;
        this.isDistributed = isDistributed;
        this.incrementalDurations = incrementalDurations;
        this.incrementalExecutorMap = incrementalExecutorMap;
        this.aggregationTables = aggregationTables;
        this.tableAttributesNameList = tableMetaStreamEvent.getInputDefinitions().get(0).getAttributeList()
                .stream().map(Attribute::getName).collect(Collectors.toList());
        this.outputExpressionExecutors = outputExpressionExecutors;
        this.aggregateProcessingExecutorsMap = aggregateProcessingExecutorsMap;
        this.shouldUpdateTimestamp = shouldUpdateTimestamp;
        this.groupByKeyGeneratorMap = groupByKeyGeneratorMap;

        this.incrementalDataPurger = incrementalDataPurger;
        this.incrementalExecutorsInitialiser = incrementalExecutorInitialiser;

        this.singleStreamRuntime = singleStreamRuntime;
        this.aggregateMetaSteamEvent = new MetaStreamEvent();
        aggregationDefinition.getAttributeList().forEach(this.aggregateMetaSteamEvent::addOutputData);

        this.latencyTrackerFind = latencyTrackerFind;
        this.throughputTrackerFind = throughputTrackerFind;

    }

    private static void initMetaStreamEvent(MetaStreamEvent metaStreamEvent, AbstractDefinition inputDefinition,
                                            String inputReferenceId) {
        metaStreamEvent.addInputDefinition(inputDefinition);
        metaStreamEvent.setInputReferenceId(inputReferenceId);
        metaStreamEvent.initializeAfterWindowData();
        inputDefinition.getAttributeList().forEach(metaStreamEvent::addData);
    }
    private static MetaStreamEvent alterMetaStreamEvent(boolean isStoreQuery, MetaStreamEvent originalMetaStreamEvent,
                                                        List<Attribute> additionalAttributes) {

        StreamDefinition alteredStreamDef = new StreamDefinition();

        if (!isStoreQuery) {
            for (Attribute attribute : originalMetaStreamEvent.getLastInputDefinition().getAttributeList()) {
                alteredStreamDef.attribute(attribute.getName(), attribute.getType());
            }
        }

        additionalAttributes.forEach(attribute -> alteredStreamDef.attribute(attribute.getName(), attribute.getType()));

        initMetaStreamEvent(originalMetaStreamEvent, alteredStreamDef, originalMetaStreamEvent.getInputReferenceId());
        return originalMetaStreamEvent;
    }

    private static MetaStreamEvent createMetaStoreEvent(AbstractDefinition tableDefinition, String referenceId) {
        MetaStreamEvent metaStreamEventForTable = new MetaStreamEvent();
        metaStreamEventForTable.setEventType(MetaStreamEvent.EventType.TABLE);
        initMetaStreamEvent(metaStreamEventForTable, tableDefinition, referenceId);
        return metaStreamEventForTable;
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

    private static MatchingMetaInfoHolder createNewStreamTableMetaInfoHolder(MetaStreamEvent metaStreamEvent,
                                                                             MetaStreamEvent metaStoreEvent) {

        MetaStateEvent metaStateEvent = new MetaStateEvent(2);
        metaStateEvent.addEvent(metaStreamEvent);
        metaStateEvent.addEvent(metaStoreEvent);
        return new MatchingMetaInfoHolder(metaStateEvent, 0, 1,
                metaStreamEvent.getLastInputDefinition(), metaStoreEvent.getLastInputDefinition(), UNKNOWN_STATE);
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
            if (!isDistributed && !isFirstEventArrived) {
                // No need to initialise executors if it is distributed
                initialiseExecutors(false);
            }
            return ((IncrementalAggregateCompileCondition) compiledCondition).find(matchingEvent,
                    incrementalExecutorMap, aggregateProcessingExecutorsMap, groupByKeyGeneratorMap,
                    shouldUpdateTimestamp);

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

        int lowerGranularitySize = this.incrementalDurations.size() - 1;
        List<String> lowerGranularityAttributes = new ArrayList<>();
        if (isDistributed) {
            //Add additional attributes to get base aggregation timestamps based on current timestamps
            // for values calculated in in-memory in the shards
            for (int i = 0; i < lowerGranularitySize; i++) {
                String attributeName = "_AGG_TIMESTAMP_FILTER_" + i;
                additionalAttributes.add(new Attribute(attributeName, Attribute.Type.LONG));
                lowerGranularityAttributes.add(attributeName);
            }
        }

        // Get table definition. Table definitions for all the tables used to persist aggregates are similar.
        // Therefore it's enough to get the definition from one table.
        AbstractDefinition tableDefinition = aggregationTables.get(incrementalDurations.get(0)).getTableDefinition();

        boolean isStoreQuery = matchingMetaInfoHolder.getMetaStateEvent().getMetaStreamEvents().length == 1;

        // Alter existing meta stream event or create new one if a meta stream doesn't exist
        // After calling this method the original MatchingMetaInfoHolder's meta stream event would be altered
        // Alter meta info holder to contain stream event and aggregate both when it's a store query
        MetaStreamEvent metaStreamEventForTableLookups;
        if (isStoreQuery) {
            metaStreamEventForTableLookups = alterMetaStreamEvent(true, new MetaStreamEvent(), additionalAttributes);
            matchingMetaInfoHolder = alterMetaInfoHolderForStoreQuery(metaStreamEventForTableLookups,
                    matchingMetaInfoHolder);
        } else {
            metaStreamEventForTableLookups = alterMetaStreamEvent(false,
                    matchingMetaInfoHolder.getMetaStateEvent().getMetaStreamEvent(0), additionalAttributes);
        }


        // Create new MatchingMetaInfoHolder containing newMetaStreamEventWithStartEnd and table meta event
        MetaStreamEvent metaStoreEventForTableLookups = createMetaStoreEvent(tableDefinition,
                matchingMetaInfoHolder.getMetaStateEvent().getMetaStreamEvent(1).getInputReferenceId());

        // Create new MatchingMetaInfoHolder containing metaStreamEventForTableLookups and table meta event
        MatchingMetaInfoHolder metaInfoHolderForTableLookups = createNewStreamTableMetaInfoHolder(
                metaStreamEventForTableLookups, metaStoreEventForTableLookups);

        // Create per expression executor
        ExpressionExecutor perExpressionExecutor;
        if (per != null) {
            perExpressionExecutor = ExpressionParser.parseExpression(per, matchingMetaInfoHolder.getMetaStateEvent(),
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

        // Create within expression
        Expression timeFilterExpression;
        if (isProcessingOnExternalTime) {
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

        List<ExpressionExecutor> timestampFilterExecutors = new ArrayList<>();
        if (isDistributed) {
            for (int i = 0; i < lowerGranularitySize; i++) {
                Expression[] expressionArray = new Expression[]{
                        new AttributeFunction("", "currentTimeMillis", null),
                        Expression.value(this.incrementalDurations.get(i + 1).toString())};
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
                metaStreamEventForTableLookups.getInputReferenceId(),
                metaStreamEventForTableLookups.getLastInputDefinition().getAttributeList(),
                this.tableAttributesNameList
        );
        aggregationExpressionBuilder.build(expressionVisitor);
        Expression reducedExpression = expressionVisitor.getReducedExpression();
        Expression withinExpressionTable = Expression.and(withinExpression, reducedExpression);

        List<VariableExpressionExecutor> variableExpExecutorsForTableLookups = new ArrayList<>();
        for (Map.Entry<TimePeriod.Duration, Table> entry : aggregationTables.entrySet()) {
            CompiledCondition withinTableCompileCondition = entry.getValue().compileCondition(withinExpressionTable,
                    metaInfoHolderForTableLookups, variableExpExecutorsForTableLookups, tableMap,
                    siddhiQueryContext);
            withinTableCompiledConditions.put(entry.getKey(), withinTableCompileCondition);
        }

        // Create compile condition for in-memory data.
        // This compile condition is used to check whether the running aggregates (in-memory data)
        // are within given duration
        withinInMemoryCompileCondition = OperatorParser.constructOperator(new ComplexEventChunk<>(true),
                withinExpression, metaInfoHolderForTableLookups, variableExpExecutorsForTableLookups, tableMap,
                siddhiQueryContext);

        // Create compile condition for in-memory data, in case of distributed
        // Look at the lower level granularities
        Map<TimePeriod.Duration, CompiledCondition> withinTableLowerGranularityCompileCondition = new HashMap<>();
        String aggregationName = aggregationDefinition.getId();
        Expression lowerGranularity;
        if (isDistributed) {
            for (int i = 0; i < lowerGranularitySize; i++) {
                if (isProcessingOnExternalTime) {
                    lowerGranularity = Expression.and(
                            Expression.compare(
                                    Expression.variable("AGG_TIMESTAMP"),
                                    Compare.Operator.GREATER_THAN_EQUAL,
                                    Expression.variable(lowerGranularityAttributes.get(i))),
                            withinExpressionTable
                    );
                } else {
                    lowerGranularity = Expression.and(
                            Expression.compare(
                                    Expression.variable("AGG_TIMESTAMP"),
                                    Compare.Operator.GREATER_THAN_EQUAL,
                                    Expression.variable(lowerGranularityAttributes.get(i))),
                            reducedExpression
                    );
                }
                TimePeriod.Duration duration = this.incrementalDurations.get(i);
                String tableName = aggregationName + "_" + duration.toString();
                CompiledCondition compiledCondition = tableMap.get(tableName).compileCondition(lowerGranularity,
                        metaInfoHolderForTableLookups, variableExpExecutorsForTableLookups, tableMap,
                        siddhiQueryContext);
                withinTableLowerGranularityCompileCondition.put(duration, compiledCondition);
            }
        }

        QueryParserHelper.reduceMetaComplexEvent(metaInfoHolderForTableLookups.getMetaStateEvent());

        // On compile condition.
        // After finding all the aggregates belonging to within duration, the final on condition (given as
        // "on stream1.name == aggregator.nickName ..." in the join query) must be executed on that data.
        // This condition is used for that purpose.
        onCompiledCondition = OperatorParser.constructOperator(new ComplexEventChunk<>(true), expression,
                matchingMetaInfoHolder, variableExpressionExecutors, tableMap, siddhiQueryContext);

        return new IncrementalAggregateCompileCondition(aggregationName, isProcessingOnExternalTime, isDistributed,
                incrementalDurations, aggregationTables, outputExpressionExecutors,
                withinTableCompiledConditions, withinInMemoryCompileCondition,
                withinTableLowerGranularityCompileCondition, onCompiledCondition, additionalAttributes,
                perExpressionExecutor, startTimeEndTimeExpressionExecutor, timestampFilterExecutors,
                aggregateMetaSteamEvent, matchingMetaInfoHolder, metaInfoHolderForTableLookups,
                variableExpExecutorsForTableLookups);

    }

    public void startPurging() {
        incrementalDataPurger.executeIncrementalDataPurging();
    }

    public void initialiseExecutors(boolean isFirstEventArrived) {
        // State only updated when first event arrives to IncrementalAggregationProcessor
        if (isFirstEventArrived) {
            this.isFirstEventArrived = true;
            for (Map.Entry<TimePeriod.Duration, IncrementalExecutor> durationIncrementalExecutorEntry :
                    this.incrementalExecutorMap.entrySet()) {
                durationIncrementalExecutorEntry.getValue().setProcessingExecutor(true);
            }
        }
        this.incrementalExecutorsInitialiser.initialiseExecutors();
    }

    public void processEvents(ComplexEventChunk<StreamEvent> streamEventComplexEventChunk) {
        incrementalExecutorMap.get(incrementalDurations.get(0)).execute(streamEventComplexEventChunk);
    }
}
