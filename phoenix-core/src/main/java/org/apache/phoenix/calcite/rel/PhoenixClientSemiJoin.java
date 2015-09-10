package org.apache.phoenix.calcite.rel;

import java.util.List;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.JoinInfo;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.SemiJoin;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.ImmutableIntList;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.phoenix.calcite.metadata.PhoenixRelMdCollation;
import org.apache.phoenix.compile.ColumnResolver;
import org.apache.phoenix.compile.QueryPlan;
import org.apache.phoenix.compile.SequenceManager;
import org.apache.phoenix.compile.StatementContext;
import org.apache.phoenix.execute.SortMergeJoinPlan;
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.jdbc.PhoenixStatement;
import org.apache.phoenix.parse.JoinTableNode.JoinType;
import org.apache.phoenix.schema.TableRef;

import com.google.common.base.Supplier;
import com.google.common.collect.Lists;

public class PhoenixClientSemiJoin extends PhoenixAbstractSemiJoin implements
        PhoenixRel {
    
    public static PhoenixClientSemiJoin create(
            final RelNode left, final RelNode right, RexNode condition) {
        RelOptCluster cluster = left.getCluster();
        final JoinInfo joinInfo = JoinInfo.of(left, right, condition);
        final RelTraitSet traits =
                cluster.traitSet().replace(PhoenixRel.CLIENT_CONVENTION)
                .replaceIfs(RelCollationTraitDef.INSTANCE,
                        new Supplier<List<RelCollation>>() {
                    public List<RelCollation> get() {
                        return PhoenixRelMdCollation.mergeJoin(left, right, joinInfo.leftKeys, joinInfo.rightKeys);
                    }
                });
        return new PhoenixClientSemiJoin(cluster, traits, left, right, condition, 
                joinInfo.leftKeys, joinInfo.rightKeys);
    }

    private PhoenixClientSemiJoin(RelOptCluster cluster, RelTraitSet traitSet,
            RelNode left, RelNode right, RexNode condition,
            ImmutableIntList leftKeys, ImmutableIntList rightKeys) {
        super(cluster, traitSet, left, right, condition, leftKeys, rightKeys);
    }
    
    @Override
    public SemiJoin copy(RelTraitSet traitSet, RexNode condition,
            RelNode left, RelNode right, JoinRelType joinType, boolean semiJoinDone) {
        assert joinType == JoinRelType.INNER;
        return create(left, right, condition);
    }    

    @Override
    public RelOptCost computeSelfCost(RelOptPlanner planner) {
        if (getLeft().getConvention() != PhoenixRel.CLIENT_CONVENTION 
                || getRight().getConvention() != PhoenixRel.CLIENT_CONVENTION)
            return planner.getCostFactory().makeInfiniteCost();            
        
        if ((!leftKeys.isEmpty() && !RelCollations.contains(RelMetadataQuery.collations(getLeft()), leftKeys))
                || (!rightKeys.isEmpty() && !RelCollations.contains(RelMetadataQuery.collations(getRight()), rightKeys)))
            return planner.getCostFactory().makeInfiniteCost();
        
        double rowCount = RelMetadataQuery.getRowCount(this);        

        double leftRowCount = RelMetadataQuery.getRowCount(getLeft());
        if (Double.isInfinite(leftRowCount)) {
            rowCount = leftRowCount;
        } else {
            rowCount += leftRowCount;
            double rightRowCount = RelMetadataQuery.getRowCount(getRight());
            if (Double.isInfinite(rightRowCount)) {
                rowCount = rightRowCount;
            } else {
                rowCount += rightRowCount;
            }
        }            
        RelOptCost cost = planner.getCostFactory().makeCost(rowCount, 0, 0);

        return cost.multiplyBy(SERVER_FACTOR).multiplyBy(PHOENIX_FACTOR);
    }

    @Override
    public QueryPlan implement(Implementor implementor) {
        List<Expression> leftExprs = Lists.<Expression> newArrayList();
        List<Expression> rightExprs = Lists.<Expression> newArrayList();

        implementor.pushContext(new ImplementorContext(implementor.getCurrentContext().isRetainPKColumns() && getJoinType() != JoinRelType.FULL, true));
        QueryPlan leftPlan = implementInput(implementor, 0, leftExprs);
        TableRef joinedTable = implementor.getTableRef();
        implementor.popContext();

        implementor.pushContext(new ImplementorContext(false, true));
        QueryPlan rightPlan = implementInput(implementor, 1, rightExprs);
        implementor.popContext();
        
        JoinType type = JoinType.Semi;
        implementor.setTableRef(joinedTable);
        PhoenixStatement stmt = leftPlan.getContext().getStatement();
        ColumnResolver resolver = leftPlan.getContext().getResolver();
        StatementContext context = new StatementContext(stmt, resolver, new Scan(), new SequenceManager(stmt));

        return new SortMergeJoinPlan(context, leftPlan.getStatement(), 
                joinedTable, type, leftPlan, rightPlan, leftExprs, rightExprs, 
                joinedTable.getTable(), joinedTable.getTable(), null, 0, false);
    }

}