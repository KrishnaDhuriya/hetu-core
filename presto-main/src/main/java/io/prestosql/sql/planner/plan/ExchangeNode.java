/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.sql.planner.plan;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.prestosql.spi.plan.AggregationNode;
import io.prestosql.spi.plan.CTEScanNode;
import io.prestosql.spi.plan.OrderingScheme;
import io.prestosql.spi.plan.PlanNode;
import io.prestosql.spi.plan.PlanNodeId;
import io.prestosql.spi.plan.Symbol;
import io.prestosql.sql.planner.Partitioning;
import io.prestosql.sql.planner.Partitioning.ArgumentBinding;
import io.prestosql.sql.planner.PartitioningHandle;
import io.prestosql.sql.planner.PartitioningScheme;

import javax.annotation.concurrent.Immutable;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static io.prestosql.sql.planner.SystemPartitioningHandle.FIXED_ARBITRARY_DISTRIBUTION;
import static io.prestosql.sql.planner.SystemPartitioningHandle.FIXED_BROADCAST_DISTRIBUTION;
import static io.prestosql.sql.planner.SystemPartitioningHandle.FIXED_HASH_DISTRIBUTION;
import static io.prestosql.sql.planner.SystemPartitioningHandle.FIXED_PASSTHROUGH_DISTRIBUTION;
import static io.prestosql.sql.planner.SystemPartitioningHandle.SINGLE_DISTRIBUTION;
import static io.prestosql.sql.planner.plan.ExchangeNode.Scope.LOCAL;
import static io.prestosql.sql.planner.plan.ExchangeNode.Scope.REMOTE;
import static io.prestosql.util.MoreLists.listOfListsCopy;
import static java.util.Objects.requireNonNull;

@Immutable
public class ExchangeNode
        extends InternalPlanNode
{
    public enum Type
    {
        GATHER,
        REPARTITION,
        REPLICATE
    }

    public enum Scope
    {
        LOCAL,
        REMOTE
    }

    private final Type type;
    private final Scope scope;

    private final List<PlanNode> sources;

    private final PartitioningScheme partitioningScheme;

    // for each source, the list of inputs corresponding to each output
    private final List<List<Symbol>> inputs;

    private final Optional<OrderingScheme> orderingScheme;
    private final AggregationNode.AggregationType aggregationType;

    @JsonCreator
    public ExchangeNode(
            @JsonProperty("id") PlanNodeId id,
            @JsonProperty("type") Type type,
            @JsonProperty("scope") Scope scope,
            @JsonProperty("partitioningScheme") PartitioningScheme partitioningScheme,
            @JsonProperty("sources") List<PlanNode> sources,
            @JsonProperty("inputs") List<List<Symbol>> inputs,
            @JsonProperty("orderingScheme") Optional<OrderingScheme> orderingScheme,
            @JsonProperty("aggregationType") AggregationNode.AggregationType aggregationType)
    {
        super(id);

        // CTEScanNode adds one exchange node on top of it,
        // so if upper node going to have another ExchangeNode then we should omit previous one.
        // In order to find this, we check if child node is already exchange node and it has only one source
        // and that source CTE node.
        if (sources.size() == 1) {
            PlanNode child = sources.get(0);
            if (scope == REMOTE && child instanceof ExchangeNode && child.getSources().size() == 1 && child.getSources().get(0) instanceof CTEScanNode) {
                sources = ImmutableList.of(child.getSources().get(0));
            }
        }

        requireNonNull(type, "type is null");
        requireNonNull(scope, "scope is null");
        requireNonNull(sources, "sources is null");
        requireNonNull(partitioningScheme, "partitioningScheme is null");
        requireNonNull(inputs, "inputs is null");
        requireNonNull(orderingScheme, "orderingScheme is null");

        checkArgument(!inputs.isEmpty(), "inputs is empty");
        checkArgument(inputs.stream().allMatch(inputSymbols -> inputSymbols.size() == partitioningScheme.getOutputLayout().size()), "Input symbols do not match output symbols");
        checkArgument(inputs.size() == sources.size(), "Must have same number of input lists as sources");
        for (int i = 0; i < inputs.size(); i++) {
            checkArgument(ImmutableSet.copyOf(sources.get(i).getOutputSymbols()).containsAll(inputs.get(i)), "Source does not supply all required input symbols");
        }

        checkArgument(scope != LOCAL || partitioningScheme.getPartitioning().getArguments().stream().allMatch(ArgumentBinding::isVariable),
                "local exchanges do not support constant partition function arguments");

        checkArgument(scope != REMOTE || type == Type.REPARTITION || !partitioningScheme.isReplicateNullsAndAny(), "Only REPARTITION can replicate remotely");

        orderingScheme.ifPresent(ordering -> {
            PartitioningHandle partitioningHandle = partitioningScheme.getPartitioning().getHandle();
            checkArgument(scope != REMOTE || partitioningHandle.equals(SINGLE_DISTRIBUTION), "remote merging exchange requires single distribution");
            checkArgument(scope != LOCAL || partitioningHandle.equals(FIXED_PASSTHROUGH_DISTRIBUTION), "local merging exchange requires passthrough distribution");
            checkArgument(partitioningScheme.getOutputLayout().containsAll(ordering.getOrderBy()), "Partitioning scheme does not supply all required ordering symbols");
        });
        this.type = type;
        this.sources = sources;
        this.scope = scope;
        this.partitioningScheme = partitioningScheme;
        this.inputs = listOfListsCopy(inputs);
        this.orderingScheme = orderingScheme;
        this.aggregationType = aggregationType;
    }

    public static ExchangeNode partitionedExchange(PlanNodeId id, Scope scope, PlanNode child, List<Symbol> partitioningColumns, Optional<Symbol> hashColumns)
    {
        return partitionedExchange(id, scope, child, partitioningColumns, hashColumns, false, AggregationNode.AggregationType.HASH);
    }

    public static ExchangeNode partitionedExchange(PlanNodeId id, Scope scope, PlanNode child, List<Symbol> partitioningColumns, Optional<Symbol> hashColumns, boolean replicateNullsAndAny)
    {
        return partitionedExchange(id, scope, child, partitioningColumns, hashColumns, replicateNullsAndAny, AggregationNode.AggregationType.HASH);
    }

    public static ExchangeNode partitionedExchange(PlanNodeId id, Scope scope, PlanNode child, List<Symbol> partitioningColumns, Optional<Symbol> hashColumns, boolean replicateNullsAndAny, AggregationNode.AggregationType aggregationType)
    {
        return partitionedExchange(
                id,
                scope,
                child,
                new PartitioningScheme(
                        Partitioning.create(FIXED_HASH_DISTRIBUTION, partitioningColumns),
                        child.getOutputSymbols(),
                        hashColumns,
                        replicateNullsAndAny,
                        Optional.empty()),
                aggregationType);
    }

    public static ExchangeNode partitionedExchange(PlanNodeId id, Scope scope, PlanNode child, PartitioningScheme partitioningScheme)
    {
        return partitionedExchange(id, scope, child, partitioningScheme, AggregationNode.AggregationType.HASH);
    }

    public static ExchangeNode partitionedExchange(PlanNodeId id, Scope scope, PlanNode child, PartitioningScheme partitioningScheme, AggregationNode.AggregationType aggregationType)
    {
        if (partitioningScheme.getPartitioning().getHandle().isSingleNode()) {
            return gatheringExchange(id, scope, child);
        }

        // CTEScanNode adds one exchange node on top of it,
        // so if upper node going to have another ExchangeNode then we should omit previous one.
        // In order to find this, we check if child node is already exchange node and it has only one source
        // and that source CTE node.
        if (scope == REMOTE && child instanceof ExchangeNode && child.getSources().size() == 1 && child.getSources().get(0) instanceof CTEScanNode) {
            child = child.getSources().get(0);
        }

        return new ExchangeNode(
                id,
                ExchangeNode.Type.REPARTITION,
                scope,
                partitioningScheme,
                ImmutableList.of(child),
                ImmutableList.of(partitioningScheme.getOutputLayout()).asList(),
                Optional.empty(),
                aggregationType);
    }

    public static ExchangeNode replicatedExchange(PlanNodeId id, Scope scope, PlanNode child)
    {
        // CTEScanNode adds one exchange node on top of it,
        // so if upper node going to have another ExchangeNode then we should omit previous one.
        if (scope == REMOTE && child instanceof ExchangeNode && child.getSources().size() == 1 && child.getSources().get(0) instanceof CTEScanNode) {
            child = child.getSources().get(0);
        }

        return new ExchangeNode(
                id,
                ExchangeNode.Type.REPLICATE,
                scope,
                new PartitioningScheme(Partitioning.create(FIXED_BROADCAST_DISTRIBUTION, ImmutableList.of()), child.getOutputSymbols()),
                ImmutableList.of(child),
                ImmutableList.of(child.getOutputSymbols()),
                Optional.empty(),
                AggregationNode.AggregationType.HASH);
    }

    public static ExchangeNode gatheringExchange(PlanNodeId id, Scope scope, PlanNode child)
    {
        // CTEScanNode adds one exchange node on top of it,
        // so if upper node going to have another ExchangeNode then we should omit previous one.
        if (scope == REMOTE && child instanceof ExchangeNode && child.getSources().size() == 1 && child.getSources().get(0) instanceof CTEScanNode) {
            child = child.getSources().get(0);
        }

        return new ExchangeNode(
                id,
                ExchangeNode.Type.GATHER,
                scope,
                new PartitioningScheme(Partitioning.create(SINGLE_DISTRIBUTION, ImmutableList.of()), child.getOutputSymbols()),
                ImmutableList.of(child),
                ImmutableList.of(child.getOutputSymbols()),
                Optional.empty(),
                AggregationNode.AggregationType.HASH);
    }

    public static ExchangeNode roundRobinExchange(PlanNodeId id, Scope scope, PlanNode child)
    {
        return partitionedExchange(
                id,
                scope,
                child,
                new PartitioningScheme(Partitioning.create(FIXED_ARBITRARY_DISTRIBUTION, ImmutableList.of()), child.getOutputSymbols()));
    }

    public static ExchangeNode mergingExchange(PlanNodeId id, Scope scope, PlanNode child, OrderingScheme orderingScheme)
    {
        // CTEScanNode adds one exchange node on top of it,
        // so if upper node going to have another ExchangeNode then we should omit previous one.
        if (scope == REMOTE && child instanceof ExchangeNode && child.getSources().size() == 1 && child.getSources().get(0) instanceof CTEScanNode) {
            child = child.getSources().get(0);
        }

        PartitioningHandle partitioningHandle = scope == LOCAL ? FIXED_PASSTHROUGH_DISTRIBUTION : SINGLE_DISTRIBUTION;
        return new ExchangeNode(
                id,
                Type.GATHER,
                scope,
                new PartitioningScheme(Partitioning.create(partitioningHandle, ImmutableList.of()), child.getOutputSymbols()),
                ImmutableList.of(child),
                ImmutableList.of(child.getOutputSymbols()),
                Optional.of(orderingScheme),
                AggregationNode.AggregationType.HASH);
    }

    @JsonProperty
    public Type getType()
    {
        return type;
    }

    @JsonProperty
    public Scope getScope()
    {
        return scope;
    }

    @Override
    @JsonProperty
    public List<PlanNode> getSources()
    {
        return sources;
    }

    @Override
    public List<Symbol> getOutputSymbols()
    {
        return partitioningScheme.getOutputLayout();
    }

    @JsonProperty
    public PartitioningScheme getPartitioningScheme()
    {
        return partitioningScheme;
    }

    @JsonProperty
    public Optional<OrderingScheme> getOrderingScheme()
    {
        return orderingScheme;
    }

    @JsonProperty
    public List<List<Symbol>> getInputs()
    {
        return inputs;
    }

    @JsonProperty("aggregationType")
    public AggregationNode.AggregationType getAggregationType()
    {
        return aggregationType;
    }

    @Override
    public <R, C> R accept(InternalPlanVisitor<R, C> visitor, C context)
    {
        return visitor.visitExchange(this, context);
    }

    @Override
    public PlanNode replaceChildren(List<PlanNode> newChildren)
    {
        return new ExchangeNode(getId(), type, scope, partitioningScheme, newChildren, inputs, orderingScheme, aggregationType);
    }
}
