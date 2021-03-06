-- This schema mutated from TestIndexesSuite.java
CREATE TABLE IP1 (
  ID INTEGER DEFAULT '0' NOT NULL,
  DESC VARCHAR(300),
  NUM INTEGER NOT NULL,
  NUM2 INTEGER NOT NULL,
  RATIO FLOAT NOT NULL,
  PRIMARY KEY (ID)
);
CREATE INDEX P1_IDX_NUM_TREE ON IP1 (NUM);


CREATE TABLE IP1IX ( 
  ID INTEGER DEFAULT '0' NOT NULL, 
  DESC VARCHAR(300), 
  NUM INTEGER NOT NULL, 
  RATIO FLOAT NOT NULL, 
  CONSTRAINT P1IX_PK_TREE PRIMARY KEY (ID) 
); 
CREATE INDEX P1IX_IDX_NUM_TREE ON IP1IX (NUM);
CREATE INDEX P1IX_IDX_RATIO_TREE ON IP1IX (RATIO); 
CREATE INDEX P1IX_IDX_DESC_TREE ON IP1IX (DESC);

CREATE TABLE IR1 (
  ID INTEGER DEFAULT '0' NOT NULL,
  DESC VARCHAR(300),
  NUM INTEGER NOT NULL,
  NUM2 INTEGER NOT NULL,
  RATIO FLOAT NOT NULL,
  PRIMARY KEY (ID)
);
CREATE INDEX R1_IDX_NUM_TREE ON IR1 (NUM);

CREATE TABLE IP2 (
  ID INTEGER DEFAULT '0' NOT NULL,
  DESC VARCHAR(300),
  NUM INTEGER NOT NULL,
  NUM2 INTEGER NOT NULL,
  RATIO FLOAT NOT NULL,
  CONSTRAINT P2_PK_TREE PRIMARY KEY (ID)
);
CREATE INDEX P2_IDX_NUM_TREE ON IP2 (NUM);

CREATE TABLE IR2 (
  ID INTEGER DEFAULT '0' NOT NULL,
  DESC VARCHAR(300),
  NUM INTEGER NOT NULL,
  NUM2 INTEGER NOT NULL,
  RATIO FLOAT NOT NULL,
  CONSTRAINT R2_PK_TREE PRIMARY KEY (ID)
);
CREATE INDEX R2_IDX_NUM_TREE ON IR2 (NUM);

CREATE TABLE IP3 (
  ID INTEGER DEFAULT '0' NOT NULL,
  DESC VARCHAR(300),
  NUM INTEGER NOT NULL,
  NUM2 INTEGER NOT NULL,
  RATIO FLOAT NOT NULL,
  PRIMARY KEY (ID)
);
CREATE INDEX P3_IDX_COMBO ON IP3 (NUM, NUM2);

CREATE TABLE IR3 (
  ID INTEGER DEFAULT '0' NOT NULL,
  DESC VARCHAR(300),
  NUM INTEGER NOT NULL,
  NUM2 INTEGER NOT NULL,
  RATIO FLOAT NOT NULL,
  PRIMARY KEY (ID)
);
CREATE INDEX R3_IDX_COMBO ON IR3 (NUM, NUM2);

-- This schema ripped from TestFunctionsSuite.java
CREATE TABLE P1 (
  ID INTEGER DEFAULT '0' NOT NULL,
  DESC VARCHAR(300),
  NUM INTEGER,
  RATIO FLOAT,
  PAST TIMESTAMP DEFAULT NULL,
  PRIMARY KEY (ID) );

--  Test generalized index on a function of a non-indexed column.
CREATE INDEX P1_ABS_NUM ON P1 ( ABS(NUM) );

--  Test generalized index on an expression of multiple columns.
CREATE INDEX P1_ABS_ID_PLUS_NUM ON P1 ( ABS(ID) + NUM );

--  Test generalized indexes on a string function and various combos.
CREATE INDEX P1_SUBSTRING_DESC ON P1 ( SUBSTRING(DESC FROM 1 FOR 2) );
CREATE INDEX P1_SUBSTRING_WITH_COL_DESC ON P1 ( SUBSTRING(DESC FROM 1 FOR 2), DESC );
CREATE INDEX P1_NUM_EXPR_WITH_STRING_COL ON P1 ( ABS(ID), DESC );
CREATE INDEX P1_MIXED_TYPE_EXPRS1 ON P1 ( ABS(ID+2), SUBSTRING(DESC FROM 1 FOR 2) );
CREATE INDEX P1_MIXED_TYPE_EXPRS2 ON P1 ( SUBSTRING(DESC FROM 1 FOR 2), ABS(ID+2) );

CREATE TABLE R1 (
  ID INTEGER DEFAULT '0' NOT NULL,
  DESC VARCHAR(300),
  NUM INTEGER,
  RATIO FLOAT,
  PAST TIMESTAMP DEFAULT NULL,
  PRIMARY KEY (ID) );

--  Test unique generalized index on a function of an already indexed column.
CREATE UNIQUE INDEX R1_ABS_ID_DESC ON R1 ( ABS(ID), DESC );

--  Test generalized expression index with a constant argument.
CREATE INDEX R1_ABS_ID_SCALED ON R1 ( ID / 3 );

