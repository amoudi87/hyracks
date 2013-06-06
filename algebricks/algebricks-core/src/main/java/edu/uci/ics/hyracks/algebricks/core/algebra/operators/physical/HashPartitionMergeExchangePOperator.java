/*
 * Copyright 2009-2013 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.uci.ics.hyracks.algebricks.core.algebra.operators.physical;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.common.utils.ListSet;
import edu.uci.ics.hyracks.algebricks.common.utils.Pair;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.IHyracksJobBuilder.TargetConstraint;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.LogicalVariable;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.PhysicalOperatorTag;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.IVariableTypeEnvironment;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.IOperatorSchema;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.OrderOperator.IOrder.OrderKind;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.ILocalStructuralProperty;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.ILocalStructuralProperty.PropertyType;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.INodeDomain;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.IPartitioningProperty;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.IPartitioningRequirementsCoordinator;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.IPhysicalPropertiesVector;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.LocalOrderProperty;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.OrderColumn;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.PhysicalRequirements;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.StructuralPropertiesVector;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.UnorderedPartitionedProperty;
import edu.uci.ics.hyracks.algebricks.core.jobgen.impl.JobGenContext;
import edu.uci.ics.hyracks.algebricks.data.IBinaryComparatorFactoryProvider;
import edu.uci.ics.hyracks.algebricks.data.IBinaryHashFunctionFactoryProvider;
import edu.uci.ics.hyracks.api.dataflow.IConnectorDescriptor;
import edu.uci.ics.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import edu.uci.ics.hyracks.api.dataflow.value.IBinaryHashFunctionFactory;
import edu.uci.ics.hyracks.api.dataflow.value.ITuplePartitionComputerFactory;
import edu.uci.ics.hyracks.api.job.IConnectorDescriptorRegistry;
import edu.uci.ics.hyracks.dataflow.common.data.partition.FieldHashPartitionComputerFactory;
import edu.uci.ics.hyracks.dataflow.std.connectors.MToNPartitioningMergingConnectorDescriptor;

public class HashPartitionMergeExchangePOperator extends AbstractExchangePOperator {

    private List<OrderColumn> orderColumns;
    private List<LogicalVariable> partitionFields;
    private INodeDomain domain;

    public HashPartitionMergeExchangePOperator(List<OrderColumn> orderColumns, List<LogicalVariable> partitionFields,
            INodeDomain domain) {
        this.orderColumns = orderColumns;
        this.partitionFields = partitionFields;
        this.domain = domain;
    }

    @Override
    public PhysicalOperatorTag getOperatorTag() {
        return PhysicalOperatorTag.HASH_PARTITION_MERGE_EXCHANGE;
    }

    public List<OrderColumn> getOrderExpressions() {
        return orderColumns;
    }

    @Override
    public void computeDeliveredProperties(ILogicalOperator op, IOptimizationContext context) {
        IPartitioningProperty p = new UnorderedPartitionedProperty(new ListSet<LogicalVariable>(partitionFields),
                domain);
        AbstractLogicalOperator op2 = (AbstractLogicalOperator) op.getInputs().get(0).getValue();
        List<ILocalStructuralProperty> op2Locals = op2.getDeliveredPhysicalProperties().getLocalProperties();
        List<ILocalStructuralProperty> locals = new ArrayList<ILocalStructuralProperty>();
        for (ILocalStructuralProperty prop : op2Locals) {
            if (prop.getPropertyType() == PropertyType.LOCAL_ORDER_PROPERTY) {
                locals.add(prop);
            } else {
                break;
            }
        }

        this.deliveredProperties = new StructuralPropertiesVector(p, locals);
    }

    @Override
    public PhysicalRequirements getRequiredPropertiesForChildren(ILogicalOperator op,
            IPhysicalPropertiesVector reqdByParent) {
        List<ILocalStructuralProperty> orderProps = new LinkedList<ILocalStructuralProperty>();
        for (OrderColumn oc : orderColumns) {
            LogicalVariable var = oc.getColumn();
            switch (oc.getOrder()) {
                case ASC: {
                    orderProps.add(new LocalOrderProperty(new OrderColumn(var, OrderKind.ASC)));
                    break;
                }
                case DESC: {
                    orderProps.add(new LocalOrderProperty(new OrderColumn(var, OrderKind.DESC)));
                    break;
                }
                default: {
                    throw new IllegalStateException();
                }
            }
        }
        StructuralPropertiesVector[] r = new StructuralPropertiesVector[] { new StructuralPropertiesVector(null,
                orderProps) };
        return new PhysicalRequirements(r, IPartitioningRequirementsCoordinator.NO_COORDINATION);
    }

    @Override
    public String toString() {
        return getOperatorTag().toString() + " MERGE:" + orderColumns + " HASH:" + partitionFields;
    }

    @Override
    public Pair<IConnectorDescriptor, TargetConstraint> createConnectorDescriptor(IConnectorDescriptorRegistry spec,
            ILogicalOperator op, IOperatorSchema opSchema, JobGenContext context) throws AlgebricksException {
        int[] keys = new int[partitionFields.size()];
        IBinaryHashFunctionFactory[] hashFunctionFactories = new IBinaryHashFunctionFactory[partitionFields.size()];
        IVariableTypeEnvironment env = context.getTypeEnvironment(op);
        {
            int i = 0;
            IBinaryHashFunctionFactoryProvider hashFunProvider = context.getBinaryHashFunctionFactoryProvider();
            for (LogicalVariable v : partitionFields) {
                keys[i] = opSchema.findVariable(v);
                hashFunctionFactories[i] = hashFunProvider.getBinaryHashFunctionFactory(env.getVarType(v));
                ++i;
            }
        }
        ITuplePartitionComputerFactory tpcf = new FieldHashPartitionComputerFactory(keys, hashFunctionFactories);

        int n = orderColumns.size();
        int[] sortFields = new int[n];
        IBinaryComparatorFactory[] comparatorFactories = new IBinaryComparatorFactory[n];
        {
            int j = 0;
            for (OrderColumn oc : orderColumns) {
                LogicalVariable var = oc.getColumn();
                sortFields[j] = opSchema.findVariable(var);
                Object type = env.getVarType(var);
                IBinaryComparatorFactoryProvider bcfp = context.getBinaryComparatorFactoryProvider();
                comparatorFactories[j] = bcfp.getBinaryComparatorFactory(type, oc.getOrder() == OrderKind.ASC);
                j++;
            }
        }

        IConnectorDescriptor conn = new MToNPartitioningMergingConnectorDescriptor(spec, tpcf, sortFields,
                comparatorFactories);
        return new Pair<IConnectorDescriptor, TargetConstraint>(conn, null);
    }
    
    public List<LogicalVariable> getPartitionFields() {
        return partitionFields;
    }
    
    public List<OrderColumn> getOrderColumns() {
        return orderColumns;
    }

}
