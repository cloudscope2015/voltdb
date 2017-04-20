/* This file is part of VoltDB.
 * Copyright (C) 2008-2017 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.compiler;

import java.util.ArrayList;
import java.util.regex.Matcher;

import org.voltdb.catalog.Database;
import org.voltdb.compiler.VoltCompiler.DdlProceduresToLoad;
import org.voltdb.compiler.VoltCompiler.ProcedureDescriptor;
import org.voltdb.compiler.VoltCompiler.VoltCompilerException;
import org.voltdb.parser.SQLParser;

public class CreateProcedureAsSQLProcessor extends CreateProcedureProcessorBase {

    @Override
    public Matcher match(String statement) {
        // Matches if it is CREATE PROCEDURE <proc-name> [ALLOW <role> ...] [PARTITION ON ...] AS <select-or-dml-statement>
        return SQLParser.matchCreateProcedureAsSQL(statement);
    }

    @Override
    public boolean process(Database db, DdlProceduresToLoad whichProcs) throws VoltCompilerException {
        String clazz = checkProcedureIdentifier(m_statementMatcher.group(1), m_statement);
        String sqlStatement = m_statementMatcher.group(3) + ";";

        ProcedureDescriptor descriptor = m_chain.m_compiler.new ProcedureDescriptor(
                new ArrayList<String>(), clazz, sqlStatement, null, null, false, null);

        // Parse the ALLOW and PARTITION clauses.
        // Populate descriptor roles and returned partition data as needed.
        CreateProcedurePartitionData partitionData =
                parseCreateProcedureClauses(descriptor, m_statementMatcher.group(2));

        m_chain.m_tracker.add(descriptor);

        // add partitioning if specified
        addProcedurePartitionInfo(clazz, partitionData, m_statement);

        return true;
    }

    /**
     * Check whether or not a procedure name is acceptible.
     * @param identifier the identifier to check
     * @param statement the statement where the identifier is
     * @return the given identifier unmodified
     * @throws VoltCompilerException
     */
    private String checkProcedureIdentifier(
            final String identifier, final String statement
            ) throws VoltCompilerException {
        String retIdent = checkIdentifierStart(identifier, statement);
        if (retIdent.contains(".")) {
            String msg = String.format(
                "Invalid procedure name containing dots \"%s\" in DDL: \"%s\"",
                identifier, statement.substring(0,statement.length()-1));
            throw m_chain.m_compiler.new VoltCompilerException(msg);
        }
        return retIdent;
    }
}
