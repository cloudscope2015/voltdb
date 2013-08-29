/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
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

import org.voltdb.BackendTarget;
import org.voltdb.client.ProcCallException;
import org.voltdb.compiler.VoltProjectBuilder;


public class TestPlansGroupByComplexMaterializedViewSuite extends RegressionSuite {

    public void testTest() throws IOException, ProcCallException {
    }

    //
    // Suite builder boilerplate
    //

    public TestPlansGroupByComplexMaterializedViewSuite(String name) {
        super(name);
    }

    static public junit.framework.Test suite() throws Exception {
        VoltServerConfig config = null;
        MultiConfigSuiteBuilder builder = new MultiConfigSuiteBuilder(
                TestPlansGroupByComplexMaterializedViewSuite.class);
        String literalSchema = null;
        boolean success = true;

//        VoltProjectBuilder project0 = new VoltProjectBuilder();
//        project0.setCompilerDebugPrintStream(capturing);
//        literalSchema =
//                "CREATE TABLE F ( " +
//                "F_PKEY INTEGER NOT NULL, " +
//                "F_D1   INTEGER NOT NULL, " +
//                "F_D2   INTEGER NOT NULL, " +
//                "F_D3   INTEGER NOT NULL, " +
//                "F_VAL1 INTEGER NOT NULL, " +
//                "F_VAL2 INTEGER NOT NULL, " +
//                "F_VAL3 INTEGER NOT NULL, " +
//                "PRIMARY KEY (F_PKEY) ); " +
//
//                "CREATE VIEW V0 (V_D1_PKEY, V_D2_PKEY, V_D3_PKEY, V_F_PKEY, CNT, SUM_V1, SUM_V2, SUM_V3) " +
//                "AS SELECT F_D1, F_D2, F_D3, F_PKEY, COUNT(*), SUM(F_VAL1)+1, SUM(F_VAL2), SUM(F_VAL3) " +
//                "FROM F  GROUP BY F_D1, F_D2, F_D3, F_PKEY;"
//                ;
//        try {
//            project0.addLiteralSchema(literalSchema);
//        } catch (IOException e) {
//            assertFalse(true);
//        }
//
//        config = new LocalCluster("plansgroupby-onesite.jar", 1, 1, 0, BackendTarget.NATIVE_EE_JNI);
//        success = config.compile(project0);
//        assertFalse(success);
//        captured = capturer.toString("UTF-8");
//        lines = captured.split("\n");
//
//        assertTrue(foundLineMatching(lines,
//                ".*V0.*Expressions with aggregate functions are not currently supported in views.*"));
//
//        VoltProjectBuilder project1 = new VoltProjectBuilder();
//        project1.setCompilerDebugPrintStream(capturing);
//        literalSchema =
//                "CREATE TABLE F ( " +
//                "F_PKEY INTEGER NOT NULL, " +
//                "F_D1   INTEGER NOT NULL, " +
//                "F_D2   INTEGER NOT NULL, " +
//                "F_D3   INTEGER NOT NULL, " +
//                "F_VAL1 INTEGER NOT NULL, " +
//                "F_VAL2 INTEGER NOT NULL, " +
//                "F_VAL3 INTEGER NOT NULL, " +
//                "PRIMARY KEY (F_PKEY) ); " +
//
//                "CREATE VIEW V1 (V_D1_PKEY, V_D2_PKEY, V_D3_PKEY, V_F_PKEY, CNT, SUM_V1, SUM_V2, SUM_V3) " +
//                "AS SELECT F_D1, F_D2, F_D3, F_PKEY, COUNT(*) + 1, SUM(F_VAL1), SUM(F_VAL2), SUM(F_VAL3) " +
//                "FROM F  GROUP BY F_D1, F_D2, F_D3, F_PKEY;"
//                ;
//        try {
//            project1.addLiteralSchema(literalSchema);
//        } catch (IOException e) {
//            assertFalse(true);
//        }
//
//        config = new LocalCluster("plansgroupby-onesite.jar", 1, 1, 0, BackendTarget.NATIVE_EE_JNI);
//        success = config.compile(project1);
//        assertFalse(success);
//        captured = capturer.toString("UTF-8");
//        lines = captured.split("\n");


        VoltProjectBuilder project = new VoltProjectBuilder();
        literalSchema =
                "CREATE TABLE R1 ( " +
                "ID INTEGER DEFAULT '0' NOT NULL, " +
                "WAGE INTEGER, " +
                "DEPT INTEGER, " +
                "TM TIMESTAMP DEFAULT NULL, " +
                "PRIMARY KEY (ID) );" +

//                "CREATE TABLE P1 ( " +
//                "ID INTEGER DEFAULT '0' NOT NULL, " +
//                "WAGE INTEGER NOT NULL, " +
//                "DEPT INTEGER NOT NULL, " +
//                "TM TIMESTAMP DEFAULT NULL, " +
//                "PRIMARY KEY (ID) );" +
//                "PARTITION TABLE P1 ON COLUMN ID;" +
//
//                "CREATE TABLE P2 ( " +
//                "ID INTEGER DEFAULT '0' NOT NULL, " +
//                "WAGE INTEGER NOT NULL, " +
//                "DEPT INTEGER NOT NULL, " +
//                "TM TIMESTAMP DEFAULT NULL, " +
//                "PRIMARY KEY (ID) );" +
//                "PARTITION TABLE P2 ON COLUMN DEPT;" +
//
//                "CREATE TABLE P3 ( " +
//                "ID INTEGER DEFAULT '0' NOT NULL, " +
//                "WAGE INTEGER NOT NULL, " +
//                "DEPT INTEGER NOT NULL, " +
//                "TM TIMESTAMP DEFAULT NULL, " +
//                "PRIMARY KEY (ID) );" +
//                "PARTITION TABLE P3 ON COLUMN WAGE;" +

//                "CREATE VIEW V_R1 (V_R1_dept, V_R1_CNT, V_R1_sum_wage) " +
//                "AS SELECT ABS(dept), count(*), SUM(wage) FROM R1 GROUP BY ABS(dept);" +

                "CREATE VIEW V_R1 (V_R1_dept,  V_R1_CNT, V_R1_sum_wage) " +
                "AS SELECT dept, count(*), SUM(wage) FROM R1 GROUP BY dept;" +
                ""
                ;
        try {
            project.addLiteralSchema(literalSchema);
        } catch (IOException e) {
            assertFalse(true);
        }
        config = new LocalCluster("plansgroupby-onesite.jar", 1, 1, 0, BackendTarget.NATIVE_EE_JNI);
        success = config.compile(project);
        assertTrue(success);
        builder.addServerConfig(config);

//        config = new LocalCluster("plansgroupby-hsql.jar", 1, 1, 0, BackendTarget.HSQLDB_BACKEND);
//        success = config.compile(project);
//        assertTrue(success);
//        builder.addServerConfig(config);
//
//        // Cluster
//        config = new LocalCluster("plansgroupby-cluster.jar", 2, 3, 1, BackendTarget.NATIVE_EE_JNI);
//        success = config.compile(project);
//        assertTrue(success);

        return builder;
    }
}
