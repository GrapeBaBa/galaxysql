/*
 * Copyright [2013-2021], Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.polardbx.executor.ddl.job.task;

import com.alibaba.polardbx.common.ddl.newengine.DdlConstants;
import com.alibaba.polardbx.common.ddl.newengine.DdlState;
import com.alibaba.polardbx.common.ddl.newengine.DdlTaskState;
import com.alibaba.polardbx.common.ddl.newengine.DdlType;
import com.alibaba.polardbx.common.exception.TddlRuntimeException;
import com.alibaba.polardbx.common.exception.code.ErrorCode;
import com.alibaba.polardbx.common.properties.ConnectionParams;
import com.alibaba.polardbx.common.utils.TStringUtil;
import com.alibaba.polardbx.common.utils.logger.Logger;
import com.alibaba.polardbx.executor.cursor.Cursor;
import com.alibaba.polardbx.executor.ddl.job.converter.DdlJobDataConverter;
import com.alibaba.polardbx.executor.ddl.job.converter.PhysicalPlanData;
import com.alibaba.polardbx.executor.ddl.newengine.utils.DdlJobManagerUtils;
import com.alibaba.polardbx.executor.utils.failpoint.FailPoint;
import com.alibaba.polardbx.optimizer.context.DdlContext;
import com.alibaba.polardbx.optimizer.context.ExecutionContext;
import com.alibaba.polardbx.optimizer.context.PhyDdlExecutionRecord;
import com.alibaba.polardbx.optimizer.core.rel.PhyDdlTableOperation;
import com.alibaba.polardbx.optimizer.core.row.Row;
import com.alibaba.polardbx.optimizer.utils.QueryConcurrencyPolicy;
import com.alibaba.polardbx.statistics.SQLRecorderLogger;
import lombok.Getter;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.sql.SqlDropTable;
import org.apache.commons.collections.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Getter
public abstract class BasePhyDdlTask extends BaseDdlTask {

    private final static Logger LOG = SQLRecorderLogger.ddlEngineLogger;

    protected PhysicalPlanData physicalPlanData;

    public BasePhyDdlTask(String schemaName, PhysicalPlanData physicalPlanData) {
        super(schemaName);
        this.physicalPlanData = physicalPlanData;
    }

    @Override
    protected void beforeTransaction(ExecutionContext executionContext) {
        //may need to clean up physical tables if failed
        updateTaskStateInNewTxn(DdlTaskState.DIRTY);
        executeImpl(executionContext);
    }

    public void executeImpl(ExecutionContext executionContext) {
        List<RelNode> physicalPlans = getPhysicalPlans(executionContext);
        executePhyDdl(physicalPlans, executionContext);
    }

    @Override
    protected void beforeRollbackTransaction(ExecutionContext executionContext) {
        rollbackImpl(executionContext);
    }

    public void rollbackImpl(ExecutionContext executionContext) {
        List<RelNode> rollbackPhysicalPlans = genRollbackPhysicalPlans(executionContext);
        executePhyDdl(rollbackPhysicalPlans, executionContext);
    }

    /**
     * Get physical plans to execute.
     */
    protected List<RelNode> getPhysicalPlans(ExecutionContext executionContext) {
        return DdlJobDataConverter.convertToPhysicalPlans(physicalPlanData, executionContext);
    }

    /**
     * Generate physical plans to roll the DDL operation back.
     */
    protected List<RelNode> genRollbackPhysicalPlans(ExecutionContext executionContext) {
        // Not all DDL operations can be rolled back for now.
        return null;
    }

    protected List<RelNode> convertToRelNodes(List<PhyDdlTableOperation> inputs) {
        List<RelNode> relNodes = new ArrayList<>();
        for (PhyDdlTableOperation phyDdlTableOperation : inputs) {
            relNodes.add(phyDdlTableOperation);
        }
        return relNodes;
    }

    protected void executePhyDdl(List<RelNode> inputs, ExecutionContext ec) {
        if (CollectionUtils.isEmpty(inputs)) {
            return;
        }

        LOG.info(String.format("[Job:%d Task:%d] Execute physical ddl: %s",
            this.jobId, this.taskId,
            TStringUtil.quoteString(this.physicalPlanData.toString())));

        ExecutionContext executionContext = ec.copy();
        PhyDdlExecutionRecord phyDdlExecutionRecord = new PhyDdlExecutionRecord(jobId, taskId, inputs.size());
        executionContext.setPhyDdlExecutionRecord(phyDdlExecutionRecord);
        executionContext.setExtraDatas(new HashMap<>());
        try {

            DdlJobManagerUtils.reloadPhyTablesDone(phyDdlExecutionRecord);

            List<Cursor> inputCursors = new ArrayList<>();
            List<Throwable> exceptions = new ArrayList<>();
            List<Throwable> closeExceptions = null;
            FailPoint.injectRandomExceptionFromHint(executionContext);
            FailPoint.injectRandomSuspendFromHint(executionContext);

            executeConcurrently(inputs, inputCursors, exceptions, executionContext);

            for (Cursor affectRowCursor : inputCursors) {
                Row row;
                while ((row = affectRowCursor.next()) != null) {
                    row.getInteger(0);
                }
                closeExceptions = affectRowCursor.close(exceptions);
            }

            if (closeExceptions != null && !closeExceptions.isEmpty()) {
                exceptions.addAll(closeExceptions);
            }

            verifyResult((PhyDdlTableOperation) inputs.get(0), exceptions, executionContext);
        } finally {
            DdlJobManagerUtils.clearPhyTablesDone(phyDdlExecutionRecord);
        }
    }

    protected void verifyResult(PhyDdlTableOperation ddl, List<Throwable> exceptions,
                                ExecutionContext executionContext) {
        DdlContext ddlContext = executionContext.getDdlContext();
        PhyDdlExecutionRecord phyDdlExecutionRecord = executionContext.getPhyDdlExecutionRecord();

        int inputCount = phyDdlExecutionRecord.getNumPhyObjectsTotal();

        if (ddlContext.getState() == DdlState.ROLLBACK_RUNNING || !executionContext.needToRenamePhyTables()) {
            inputCount = 0;
        }

        int objectDoneCount = phyDdlExecutionRecord.getNumPhyObjectsDone();

        if (inputCount != objectDoneCount && checkIfHandleError(ddl, executionContext)) {
            int countError = 0;
            StringBuilder causedMsg = new StringBuilder();

            // Errors/Warnings from physical DDLs.
            List<ExecutionContext.ErrorMessage> failedMsgs =
                (List<ExecutionContext.ErrorMessage>) executionContext.getExtraDatas()
                    .get(ExecutionContext.FailedMessage);
            if (failedMsgs != null && !failedMsgs.isEmpty()) {
                int countUnknownTables = 0;
                for (ExecutionContext.ErrorMessage errMsg : failedMsgs) {
                    if (errMsg != null) {
                        causedMsg.append(DdlConstants.SEMICOLON).append(errMsg.getCode());
                        causedMsg.append(DdlConstants.COLON).append(errMsg.getGroupName());
                        causedMsg.append(DdlConstants.COLON).append(errMsg.getMessage());
                        if (errMsg.getCode() == DdlConstants.ERROR_UNKNOWN_TABLE) {
                            countUnknownTables++;
                        }
                        countError++;
                    }
                }
                if (countUnknownTables == failedMsgs.size()) {
                    return;
                }
            }

            // Exceptions from executor/cursor.
            if (exceptions != null) {
                for (Throwable e : exceptions) {
                    causedMsg.append(DdlConstants.SEMICOLON).append(e.getMessage());
                    LOGGER.error(e);
                    countError++;
                }
            }

            if (countError == 0) {
                // No any error actually.
                return;
            }

            if (objectDoneCount == 0) {
                onExceptionTryRollback();
            }

            // Put various errors together.
            StringBuilder errMsg = new StringBuilder();
            errMsg.append("Not all physical DDLs have been executed successfully: ");
            errMsg.append(inputCount).append(" expected, ").append(objectDoneCount).append(" done, ");
            errMsg.append(inputCount - objectDoneCount).append(" failed. Caused by: ");
            if (causedMsg.length() > 0) {
                // The job failed due to some errors.
                errMsg.append(causedMsg.deleteCharAt(0));
            } else {
                // The job failed probably due to user's cancellation.
                errMsg.append("The job '").append(ddlContext.getJobId()).append("' has been interrupted");
            }

            throw new TddlRuntimeException(ErrorCode.ERR_DDL_JOB_ERROR, errMsg.toString());
        }
    }

    private boolean checkIfHandleError(PhyDdlTableOperation physicalPlan, ExecutionContext executionContext) {
        if (physicalPlan.getNativeSqlNode() instanceof SqlDropTable) {
            SqlDropTable dropTable = (SqlDropTable) physicalPlan.getNativeSqlNode();
            DdlType ddlType = executionContext.getDdlContext().getDdlType();
            if ((ddlType == DdlType.DROP_TABLE || ddlType == DdlType.DROP_GLOBAL_INDEX) && dropTable.isIfExists()) {
                // DROP TABLE IF EXISTS is allowed to proceed with warning instead of immediate failure.
                return false;
            }
        }
        return true;
    }

    protected void executeConcurrently(List<RelNode> inputs, List<Cursor> inputCursors, List<Throwable> exceptions,
                                       ExecutionContext executionContext) {
        QueryConcurrencyPolicy concurrencyPolicy = getConcurrencyPolicy(executionContext);
        // Execute with the specified policy for sharding table
        if (concurrencyPolicy == QueryConcurrencyPolicy.INSTANCE_CONCURRENT) {
            executeInstanceConcurrent(inputs, inputCursors, executionContext, schemaName, exceptions);
        } else {
            executeWithConcurrentPolicy(executionContext, inputs, concurrencyPolicy, inputCursors, schemaName);
        }
    }

    private QueryConcurrencyPolicy getConcurrencyPolicy(ExecutionContext executionContext) {
        boolean mergeConcurrent = executionContext.getParamManager().getBoolean(ConnectionParams.MERGE_CONCURRENT);

        boolean mergeDdlConcurrent =
            executionContext.getParamManager().getBoolean(ConnectionParams.MERGE_DDL_CONCURRENT);

        boolean sequential =
            executionContext.getParamManager().getBoolean(ConnectionParams.SEQUENTIAL_CONCURRENT_POLICY);

        if (mergeConcurrent && mergeDdlConcurrent) {
            return QueryConcurrencyPolicy.CONCURRENT;
        } else if (mergeConcurrent) {
            return QueryConcurrencyPolicy.GROUP_CONCURRENT_BLOCK;
        } else if (sequential) {
            return QueryConcurrencyPolicy.SEQUENTIAL;
        }

        return QueryConcurrencyPolicy.INSTANCE_CONCURRENT;
    }

    @Override
    public String remark() {
//        if (FailPoint.isAssertEnable()) {
        return "";
//        }
//        if (physicalPlanData == null) {
//            return "|empty";
//        }
//        String sql = physicalPlanData.getSqlTemplate();
//        if (TStringUtil.isNotBlank(sql)) {
//            sql = sql.replace("\n", "");
//        }
//        return String.format("|physical plan(%s)", sql);
    }
}