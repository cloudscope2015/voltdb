package org.voltdb.compiler;

import java.util.ArrayList;
import java.util.regex.Matcher;

import org.voltcore.utils.CoreUtils;
import org.voltdb.catalog.Database;
import org.voltdb.compiler.VoltCompiler.DdlProceduresToLoad;
import org.voltdb.compiler.VoltCompiler.ProcedureDescriptor;
import org.voltdb.compiler.VoltCompiler.VoltCompilerException;
import org.voltdb.parser.SQLParser;

public class CreateProcedureFromClassProcessor extends CreateProcedureProcessorBase {

    @Override
    public Matcher match(String statement) {
        // Matches if it is CREATE PROCEDURE [ALLOW <role> ...] [PARTITION ON ...] FROM CLASS <class-name>;
        return SQLParser.matchCreateProcedureFromClass(statement);
    }

    @Override
    public boolean process(Database db, DdlProceduresToLoad whichProcs) throws VoltCompilerException {
        if (whichProcs != DdlProceduresToLoad.ALL_DDL_PROCEDURES) {
            return true;
        }
        String className = checkIdentifierStart(m_statementMatcher.group(2), m_statement);
        Class<?> clazz;
        try {
            clazz = Class.forName(className, true, m_chain.m_classLoader);
        }
        catch (Throwable cause) {
            // We are here because either the class was not found or the class was found and
            // the initializer of the class threw an error we can't anticipate. So we will
            // wrap the error with a runtime exception that we can trap in our code.
            if (CoreUtils.isStoredProcThrowableFatalToServer(cause)) {
                throw (Error)cause;
            }
            else {
                throw m_chain.m_compiler.new VoltCompilerException(String.format(
                        "Cannot load class for procedure: %s",
                        className), cause);
            }
        }

        ProcedureDescriptor descriptor = m_chain.m_compiler.new ProcedureDescriptor(
                new ArrayList<String>(), null, clazz);

        // Parse the ALLOW and PARTITION clauses.
        // Populate descriptor roles and returned partition data as needed.
        CreateProcedurePartitionData partitionData = parseCreateProcedureClauses(descriptor, m_statementMatcher.group(1));

        // track the defined procedure
        String procName = m_chain.m_tracker.add(descriptor);

        // add partitioning if specified
        addProcedurePartitionInfo(procName, partitionData, m_statement);

        return true;
    }
}
