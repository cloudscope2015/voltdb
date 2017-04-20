package org.voltdb.compiler;

import java.util.regex.Matcher;

import org.voltdb.catalog.CatalogMap;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Function;
import org.voltdb.compiler.VoltCompiler.DdlProceduresToLoad;
import org.voltdb.compiler.VoltCompiler.VoltCompilerException;
import org.voltdb.parser.SQLParser;

public class DropFunctionProcessor extends StatementProcessor {

    @Override
    public Matcher match(String statement) {
        // Matches if it is DROP FUNCTION <name>
        return SQLParser.matchDropFunction(statement);
    }

    @Override
    public boolean process(Database db, DdlProceduresToLoad whichProcs) throws VoltCompilerException {
        String functionName = checkIdentifierStart(m_statementMatcher.group(1), m_statement);
        boolean ifExists = m_statementMatcher.group(2) != null;
        CatalogMap<Function> functions = db.getFunctions();
        if (functions.get(functionName) != null) {
            functions.delete(functionName);
        }
        else {
            if (! ifExists) {
                throw m_chain.m_compiler.new VoltCompilerException(String.format(
                        "Function name \"%s\" in DROP FUNCTION statement does not exist.",
                        functionName));
            }
        }
        return true;
    }

}
