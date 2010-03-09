/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb.regressionsuites;

import java.io.IOException;
import java.util.ArrayList;

import org.voltdb.BackendTarget;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;
import org.voltdb.VoltTable.ColumnInfo;
import org.voltdb.client.Client;
import org.voltdb.client.NoConnectionsException;
import org.voltdb.client.ProcCallException;
import org.voltdb.compiler.VoltProjectBuilder;
import org.voltdb.elt.LocalSingleProcessServerELT;
import org.voltdb.elt.SingleTableVerifier;

/**
 *  End to end ELT tests using the RawProcessor and the ELSinkServer.
 *
 *  Note, this test reuses the TestSQLTypesSuite schema and procedures.
 *  Each table in that schema, to the extent the DDL is supported by the
 *  DB, really needs an ELT round trip test.
 */

public class TestELTSuite extends RegressionSuite {

    /** Shove a table name and pkey in front of row data */
    private Object[] convertValsToParams(final int i, final Object[] rowdata) {
        final Object[] params = new Object[rowdata.length + 2];
        params[0] = "ALLOW_NULLS";
        params[1] = i;
        for (int ii=0; ii < rowdata.length; ++ii)
            params[ii+2] = rowdata[ii];
        return params;
    }

    /** Push pkey into expected row data */
    private Object[] convertValsToRow(final int i, final Object[] rowdata) {
        final Object[] row = new Object[rowdata.length + 1];
        row[0] = i;
        for (int ii=0; ii < rowdata.length; ++ii)
            row[ii+1] = rowdata[ii];
        return row;
    }


    /**
     * The verifier; the LocalSingleProcessServerELT will invoke this
     * constructor for each test. (Therefore must be public.)
     */
    public static class ELTSuiteTupleVerifier extends SingleTableVerifier {
        public ELTSuiteTupleVerifier() {
            final ArrayList<VoltType> schema = new ArrayList<VoltType>();
            schema.add(VoltType.INTEGER);
            schema.add(VoltType.TINYINT);
            schema.add(VoltType.SMALLINT);
            schema.add(VoltType.INTEGER);
            schema.add(VoltType.BIGINT);
            schema.add(VoltType.FLOAT);
            schema.add(VoltType.TIMESTAMP);
            schema.add(VoltType.STRING); // varchar(4)
            schema.add(VoltType.STRING); // varchar(10)
            schema.add(VoltType.STRING); // varchar(1024)
            schema.add(VoltType.STRING); // varchar(32000)
            schema.add(VoltType.DECIMAL);
            addTable("ALLOW_NULLS", schema);
        }
    }

    private void quiesce(final Client client) throws ProcCallException,
        NoConnectionsException {
        // quiesce and then spin, polling for all idle.
        boolean allIdle = false;
        client.callProcedure("@Quiesce");
        while (!allIdle) {
            allIdle = true;
            VoltTable[] vt = client.callProcedure("@SystemInformation");
            while (vt[0].advanceRow()) {
                if (vt[0].getString("key").equals("operStatus")) {
                    if (vt[0].getString("value").equals("running")) {
                        allIdle = false;
                        break;
                    }
                }
            }
        }
    }

    private void quiesceAndVerify(final Client client)
        throws ProcCallException, NoConnectionsException {

        final LocalSingleProcessServerELT config =
            (LocalSingleProcessServerELT) getServerConfig();

        quiesce(client);

        // and read and close the el sink server
        config.flushSinkServer();
        assertTrue(config.allRowsVerified());
    }

    private void quiesceAndVerifyFalse(final Client client)
        throws ProcCallException, NoConnectionsException {

        final LocalSingleProcessServerELT config =
            (LocalSingleProcessServerELT) getServerConfig();

        quiesce(client);

        // and read and close the el sink server
        config.flushSinkServer();
        assertFalse(config.allRowsVerified());
    }

    /** Verify test infrastructure fails a test that sends too many rows (0 rows registered) */
    public void testELTLocalServerTooMany() throws IOException, ProcCallException, InterruptedException
    {
        final Client client = getClient();

        for (int i=0; i < 10; i++) {
            final Object[] rowdata = TestSQLTypesSuite.m_midValues;
            // don't register any rows with TupleVerifier
            final Object[] params = convertValsToParams(i, rowdata);
            client.callProcedure("Insert", params);
        }
        quiesceAndVerifyFalse(client);
    }

    /** Verify test infrastructure fails a test that sends too many rows (Some rows registered) */
    public void testELTLocalServerTooMany2() throws IOException, ProcCallException, InterruptedException
    {
        final Client client = getClient();

        final LocalSingleProcessServerELT config =
            (LocalSingleProcessServerELT) getServerConfig();

        for (int i=0; i < 10; i++) {
            final Object[] rowdata = TestSQLTypesSuite.m_midValues;
            // don't register half of the rows!
            if (i % 2 == 0)
                config.addRow("ALLOW_NULLS", convertValsToRow(i, rowdata));
            final Object[] params = convertValsToParams(i, rowdata);
            client.callProcedure("Insert", params);
        }
        quiesceAndVerifyFalse(client);
    }

    /** Verify test infrastructure fails a test that sends too few rows */
    public void testELTLocalServerTooFew() throws IOException, ProcCallException, InterruptedException
    {
        final Client client = getClient();

        final LocalSingleProcessServerELT config =
            (LocalSingleProcessServerELT) getServerConfig();

        for (int i=0; i < 10; i++) {
            final Object[] rowdata = TestSQLTypesSuite.m_midValues;
            // add each row twice - verify the test infra. catches a failure
            config.addRow("ALLOW_NULLS", convertValsToRow(i, rowdata));
            config.addRow("ALLOW_NULLS", convertValsToRow(i, rowdata));
            final Object[] params = convertValsToParams(i, rowdata);
            client.callProcedure("Insert", params);
        }
        quiesceAndVerifyFalse(client);
    }

    /** Verify test infrastructure fails a test that sends mismatched data */
    public void testELTLocalServerBadData() throws IOException, ProcCallException, InterruptedException
    {
        final Client client = getClient();

        final LocalSingleProcessServerELT config =
            (LocalSingleProcessServerELT) getServerConfig();

        for (int i=0; i < 10; i++) {
            final Object[] rowdata = TestSQLTypesSuite.m_midValues;
            // add wrong pkeys on purpose!
            config.addRow("ALLOW_NULLS", convertValsToRow(i+10, rowdata));
            final Object[] params = convertValsToParams(i, rowdata);
            client.callProcedure("Insert", params);
        }
        quiesceAndVerifyFalse(client);
    }

    /**
     * Sends ten tuples to an EL enabled VoltServer and verifies the receipt
     * of those tuples after a quiesce (shutdown). Base case.
     */
    public void testELTRoundTripPersistentTable() throws IOException, ProcCallException, InterruptedException
    {
        final Client client = getClient();

        final LocalSingleProcessServerELT config =
            (LocalSingleProcessServerELT) getServerConfig();

        for (int i=0; i < 10; i++) {
            final Object[] rowdata = TestSQLTypesSuite.m_midValues;
            config.addRow("ALLOW_NULLS", convertValsToRow(i, rowdata));

            final Object[] params = convertValsToParams(i, rowdata);
            client.callProcedure("Insert", params);
        }
        quiesceAndVerify(client);
    }

    /**
     * Sends ten tuples to an EL enabled VoltServer and verifies the receipt
     * of those tuples after a quiesce (shutdown). Base case.
     */
    public void testELTRoundTripStreamedTable() throws IOException, ProcCallException, InterruptedException
    {
        final Client client = getClient();

        final LocalSingleProcessServerELT config =
            (LocalSingleProcessServerELT) getServerConfig();

        for (int i=0; i < 10; i++) {
            final Object[] rowdata = TestSQLTypesSuite.m_midValues;
            config.addRow("WITH_DEFAULTS", convertValsToRow(i, rowdata));

            final Object[] params = convertValsToParams(i, rowdata);
            client.callProcedure("Insert", params);
        }
        quiesceAndVerify(client);
    }

    /** Test that a table w/o ELT enabled does not produce ELT content */
    public void testThatTablesOptIn() throws IOException, ProcCallException, InterruptedException
    {
        final Client client = getClient();

        Object params[] = new Object[TestSQLTypesSuite.COLS + 2];
        params[0] = "NO_NULLS";  // this table should not produce ELT output

        // populate the row data
        for (int i=0; i < TestSQLTypesSuite.COLS; ++i) {
            params[i+2] = TestSQLTypesSuite.m_midValues[i];
        }

        for (int i=0; i < 10; i++) {
            params[1] = i; // pkey
            // do NOT add row to TupleVerfier as none should be produced
            client.callProcedure("Insert", params);
        }
        quiesceAndVerify(client);
    }


    /*
     * Sends many tuples to an EL enabled VoltServer and verifies the receipt
     * of each in the EL stream. Some procedures rollback (after a real insert).
     * Tests that streams are correct in the face of rollback.
     */

/* RawProcessor doesn't wait for ACK of data receipt and reports done too early.
 * This races against the test harness which can conclude full quiesce is achieved
 * before having read the final bytes of data. This then fails the verifier (not
 * all rows ELT'd. Comment out this testcase until the raw processor quiesces
 * better.

     public void testELTRollback() throws IOException, ProcCallException, InterruptedException {
        final LocalSingleProcessServerELT config =
            (LocalSingleProcessServerELT) getServerConfig();

        final Client client = getClient();

        double rollbackPerc = 0.15;
        double random = Math.random(); // initializes the generator

        // eltxxx: should pick more random data
        final Object[] rowdata = TestSQLTypesSuite.m_midValues;

        // roughly 10k rows is a full buffer it seems
        for (int pkey=0; pkey < 25000; pkey++) {
            final Object[] params = convertValsToParams(pkey, rowdata);
            // rollback or insert?
            random = Math.random();
            if (random <= rollbackPerc) {
                try {
                    // note - not update the el verifier as this rollsback
                    client.callProcedure("RollbackInsert", params);
                }
                catch (ProcCallException ex) {
                    // make sure the proc failed "on purpose"
                    if (!(ex.getMessage().contains("OK"))) {
                        throw ex;
                    }
                }
            }
            else {
                config.addRow("ALLOW_NULLS", convertValsToRow(pkey, rowdata));
                client.callProcedure("Insert", params);
            }
        }
        quiesceAndVerify(client);
    }
*/
    private VoltTable createLoadTableTable(boolean addToVerifier) {
        final LocalSingleProcessServerELT config =
            (LocalSingleProcessServerELT)getServerConfig();

        VoltTable loadTable = new VoltTable(new ColumnInfo("PKEY", VoltType.INTEGER),
          new ColumnInfo(TestSQLTypesSuite.m_columnNames[0], TestSQLTypesSuite.m_types[0]),
          new ColumnInfo(TestSQLTypesSuite.m_columnNames[1], TestSQLTypesSuite.m_types[1]),
          new ColumnInfo(TestSQLTypesSuite.m_columnNames[2], TestSQLTypesSuite.m_types[2]),
          new ColumnInfo(TestSQLTypesSuite.m_columnNames[3], TestSQLTypesSuite.m_types[3]),
          new ColumnInfo(TestSQLTypesSuite.m_columnNames[4], TestSQLTypesSuite.m_types[4]),
          new ColumnInfo(TestSQLTypesSuite.m_columnNames[5], TestSQLTypesSuite.m_types[5]),
          new ColumnInfo(TestSQLTypesSuite.m_columnNames[6], TestSQLTypesSuite.m_types[6]),
          new ColumnInfo(TestSQLTypesSuite.m_columnNames[7], TestSQLTypesSuite.m_types[7]),
          new ColumnInfo(TestSQLTypesSuite.m_columnNames[8], TestSQLTypesSuite.m_types[8]),
          new ColumnInfo(TestSQLTypesSuite.m_columnNames[9], TestSQLTypesSuite.m_types[9]),
          new ColumnInfo(TestSQLTypesSuite.m_columnNames[10], TestSQLTypesSuite.m_types[10]));

        for (int i=0; i < 100; i++) {
            if (addToVerifier) {
                config.addRow("ALLOW_NULLS", convertValsToRow(i, TestSQLTypesSuite.m_midValues));
            }
            loadTable.addRow(convertValsToRow(i, TestSQLTypesSuite.m_midValues));
        }
        return loadTable;
    }

    /*
     * Verify that allowELT = no is obeyed for @LoadMultipartitionTable
     */
    public void testLoadMultiPartitionTableELTOff() throws IOException, ProcCallException
    {
        // allow ELT is off. no rows added to the verifier
        VoltTable loadTable = createLoadTableTable(false);
        Client client = getClient();
        client.callProcedure("@LoadMultipartitionTable", "ALLOW_NULLS", loadTable, 0);
        quiesceAndVerify(client);
    }

    /*
     * Verify that allowELT = yes is obeyed for @LoadMultipartitionTable
     */
    public void testLoadMultiPartitionTableELTOn() throws IOException, ProcCallException
    {
        // allow ELT is on. rows added to the verifier
        VoltTable loadTable = createLoadTableTable(true);
        Client client = getClient();
        client.callProcedure("@LoadMultipartitionTable", "ALLOW_NULLS", loadTable, 1);
        quiesceAndVerify(client);
    }

    /*
     * Verify that allowELT = yes is obeyed for @LoadMultipartitionTable
     */
    public void testLoadMultiPartitionTableELTOn2() throws IOException, ProcCallException
    {
        // allow ELT is on but table is not opted in to ELT.
        VoltTable loadTable = createLoadTableTable(false);
        Client client = getClient();
        client.callProcedure("@LoadMultipartitionTable", "NO_NULLS", loadTable, 1);
        quiesceAndVerify(client);
    }

    /*
     * Verify that planner rejects updates to append-only tables
     */
    public void testELTUpdateAppendOnly() throws IOException {
        final Client client = getClient();
        boolean passed = false;
        try {
            client.callProcedure("@AdHoc", "Update WITH_DEFAULTS SET A_TINYINT=0 WHERE PKEY=0;");
        }
        catch (ProcCallException e) {
            if (e.getMessage().contains("Illegal to update an export-only table.")) {
                passed = true;
            }
        }
        assertTrue(passed);
    }

    /*
     * Verify that planner rejects reads of append-only tables.
     */
    public void testELTSelectAppendOnly() throws IOException {
        final Client client = getClient();
        boolean passed = false;
        try {
            client.callProcedure("@AdHoc", "Select PKEY from WITH_DEFAULTS WHERE PKEY=0;");
        }
        catch (ProcCallException e) {
            if (e.getMessage().contains("Illegal to read an export-only table.")) {
                passed = true;
            }
        }
        assertTrue(passed);
    }

    /*
     *  Verify that planner rejects deletes of append-only tables
     */
    public void testELTDeleteAppendOnly() throws IOException {
        final Client client = getClient();
        boolean passed = false;
        try {
            client.callProcedure("@AdHoc", "DELETE from WITH_DEFAULTS WHERE PKEY=0;");
        }
        catch (ProcCallException e) {
            if (e.getMessage().contains("Illegal to delete from an export-only table.")) {
                passed = true;
            }
        }
        assertTrue(passed);
    }


    /*
     * Test suite boilerplate
     */
    static final Class<?>[] PROCEDURES = {
        org.voltdb.regressionsuites.sqltypesprocs.Insert.class,
        org.voltdb.regressionsuites.sqltypesprocs.RollbackInsert.class
    };

    public TestELTSuite(final String name) {
        super(name);
    }

    public static void main(final String args[]) {
        org.junit.runner.JUnitCore.runClasses(TestOrderBySuite.class);
    }

    static public junit.framework.Test suite()
    {
        final MultiConfigSuiteBuilder builder =
            new MultiConfigSuiteBuilder(TestELTSuite.class);

        final VoltProjectBuilder project = new VoltProjectBuilder();
        project.addSchema(TestELTSuite.class.getResource("sqltypessuite-ddl.sql"));
        // add the connector/processor (name, host, port)
        // and the exportable tables (name, export-only)
        project.addELT("username", "password",
                       "org.voltdb.elt.processors.RawProcessor",
                       "localhost:5443", true /*enabled*/);
        // project.addELTTable("NO_NULLS", false);   // non-elt'd persistent table
        project.addELTTable("ALLOW_NULLS", false);   // persistent table
        project.addELTTable("WITH_DEFAULTS", true);  // streamed table
        // and then project builder as normal
        project.addPartitionInfo("NO_NULLS", "PKEY");
        project.addPartitionInfo("ALLOW_NULLS", "PKEY");
        project.addPartitionInfo("WITH_DEFAULTS", "PKEY");
        project.addPartitionInfo("WITH_NULL_DEFAULTS", "PKEY");
        project.addPartitionInfo("EXPRESSIONS_WITH_NULLS", "PKEY");
        project.addPartitionInfo("EXPRESSIONS_NO_NULLS", "PKEY");
        project.addProcedures(PROCEDURES);


        // JNI
        final VoltServerConfig config =
            new LocalSingleProcessServerELT("elt-ddl.sql",
                                            1,
                                            BackendTarget.NATIVE_EE_JNI,
                                            ELTSuiteTupleVerifier.class);

        config.compile(project);
        builder.addServerConfig(config);

        return builder;
    }

}
