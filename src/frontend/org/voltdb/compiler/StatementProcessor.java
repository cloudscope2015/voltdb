package org.voltdb.compiler;

import java.util.regex.Matcher;

import org.voltdb.catalog.Database;
import org.voltdb.compiler.VoltCompiler.DdlProceduresToLoad;
import org.voltdb.compiler.VoltCompiler.VoltCompilerException;

public abstract class StatementProcessor {

    protected StatementProcessingChain m_chain;
    protected Matcher m_statementMatcher;
    protected String m_statement;

    public abstract Matcher match(String statement);

    public abstract boolean process(Database db, DdlProceduresToLoad whichProcs) throws VoltCompilerException;

    /**
     * Checks whether or not the start of the given identifier is java (and
     * thus DDL) compliant. An identifier may start with: _ [a-zA-Z] $
     * @param identifier the identifier to check
     * @param statement the statement where the identifier is
     * @return the given identifier unmodified
     * @throws VoltCompilerException when it is not compliant
     */
    protected String checkIdentifierStart(
            final String identifier, final String statement
            ) throws VoltCompilerException {

        assert identifier != null && ! identifier.trim().isEmpty();
        assert statement != null && ! statement.trim().isEmpty();

        int loc = 0;
        do {
            if ( ! Character.isJavaIdentifierStart(identifier.charAt(loc))) {
                String msg = "Unknown indentifier in DDL: \"" +
                        statement.substring(0,statement.length()-1) +
                        "\" contains invalid identifier \"" + identifier + "\"";
                throw m_chain.m_compiler.new VoltCompilerException(msg);
            }
            loc = identifier.indexOf('.', loc) + 1;
        }
        while( loc > 0 && loc < identifier.length());

        return identifier;
    }
}
