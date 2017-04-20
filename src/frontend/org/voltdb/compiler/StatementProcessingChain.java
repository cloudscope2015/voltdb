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
import org.voltdb.compiler.VoltCompiler.VoltCompilerException;

public final class StatementProcessingChain {

    protected final VoltCompiler m_compiler;
    protected final ClassLoader m_classLoader;
    protected final VoltDDLElementTracker m_tracker;
    private final ArrayList<StatementProcessor> m_stmtHandlers = new ArrayList<>();

    public StatementProcessingChain(VoltCompiler compiler, ClassLoader classLoader, VoltDDLElementTracker tracker) {
        m_compiler = compiler;
        m_classLoader = classLoader;
        m_tracker = tracker;
    }

    public void addStatementProcessors(StatementProcessor... stmtProcessors) {
        for (StatementProcessor stmtProcessor : stmtProcessors) {
            stmtProcessor.m_chain = this;
            m_stmtHandlers.add(stmtProcessor);
        }
    }

    public boolean process(String statement, Database db, DdlProceduresToLoad whichProcs) throws VoltCompilerException {
        for (StatementProcessor stmtHandler : m_stmtHandlers) {
            Matcher statementMatcher = stmtHandler.match(statement);
            if (statementMatcher.matches()) {
                stmtHandler.m_statementMatcher = statementMatcher;
                stmtHandler.m_statement = statement;
                return stmtHandler.process(db, whichProcs);
            }
        }
        return false;
    }
}
