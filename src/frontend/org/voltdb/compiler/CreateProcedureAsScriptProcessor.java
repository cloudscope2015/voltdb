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

import java.util.regex.Matcher;

import org.voltdb.catalog.Database;
import org.voltdb.compiler.VoltCompiler.DdlProceduresToLoad;
import org.voltdb.compiler.VoltCompiler.VoltCompilerException;
import org.voltdb.parser.SQLParser;

public class CreateProcedureAsScriptProcessor extends CreateProcedureProcessorBase {

    @Override
    public Matcher match(String statement) {
        // Matches if it is CREATE PROCEDURE <proc-name> [ALLOW <role> ...] [PARTITION ON ...] AS
        // ### <code-block> ### LANGUAGE <language-name>
        // We used to support Groovy in pre-5.x, but now we don't
        return SQLParser.matchCreateProcedureAsScript(statement);
    }

    @Override
    public boolean process(Database db, DdlProceduresToLoad whichProcs) throws VoltCompilerException {
        throw m_chain.m_compiler.new VoltCompilerException("VoltDB doesn't support inline proceudre creation..");
    }
}
