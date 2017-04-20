package org.voltdb.compiler;

import java.util.regex.Matcher;

import org.voltdb.catalog.CatalogMap;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Function;
import org.voltdb.compiler.VoltCompiler.DdlProceduresToLoad;
import org.voltdb.compiler.VoltCompiler.VoltCompilerException;
import org.voltdb.parser.SQLParser;

public class CreateFunctionProcessor extends StatementProcessor {

    @Override
    public Matcher match(String statement) {
        // Matches if it is CREATE FUNCTION <name> FROM METHOD <class-name>.<method-name>
        return SQLParser.matchCreateFunctionFromMethod(statement);
    }

    @Override
    public boolean process(Database db, DdlProceduresToLoad whichProcs) throws VoltCompilerException {
        String functionName = checkIdentifierStart(m_statementMatcher.group(1), m_statement);
        String className = checkIdentifierStart(m_statementMatcher.group(2), m_statement);
        String methodName = checkIdentifierStart(m_statementMatcher.group(3), m_statement);
        CatalogMap<Function> functions = db.getFunctions();
        if (functions.get(functionName) != null) {
            throw m_chain.m_compiler.new VoltCompilerException(String.format(
                    "Function name \"%s\" in CREATE FUNCTION statement already exists.",
                    functionName));
        }
        Function func = functions.add(functionName);
        func.setFunctionname(functionName);
        func.setClassname(className);
        func.setMethodname(methodName);
        return true;
    }
}
